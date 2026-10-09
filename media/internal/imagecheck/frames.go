package imagecheck

import "encoding/binary"

// frames counts the frames of a picture without decoding one: the image
// descriptors of a GIF, the ANMF chunks of a WebP, the announced frames of an
// APNG. A still picture, or one whose structure cannot be followed, counts 1.
//
// The count is deliberately generous: where decoders skip over odd bytes, so
// does this, because a frame that is not counted here is not paid for by the
// budget, but still worked on by the scorer.
func frames(data []byte, mediaType string) int {
	n := 1
	switch mediaType {
	case "image/gif":
		n = gifFrames(data)
	case "image/webp":
		n = webpFrames(data)
	case "image/png":
		n = apngFrames(data)
	}
	if n < 1 {
		return 1
	}
	return n
}

// gifFrames walks the blocks of a GIF: header, logical screen, optional global
// colour table, then extensions (0x21), images (0x2C) and the trailer (0x3B).
func gifFrames(d []byte) int {
	if len(d) < 13 {
		return 0
	}
	p := 13
	if d[10]&0x80 != 0 {
		p += 3 << ((d[10] & 7) + 1)
	}
	// subBlocks skips a chain of length-prefixed data blocks up to its terminator
	subBlocks := func() {
		for p < len(d) {
			size := int(d[p])
			p++
			if size == 0 {
				return
			}
			p += size
		}
	}
	n := 0
	for p < len(d) {
		switch d[p] {
		case 0x3B: // trailer
			return n
		case 0x21: // extension: introducer, label, data
			p += 2
			subBlocks()
		case 0x2C: // image descriptor (10 bytes), optional local colour table, LZW code size, data
			if p+10 > len(d) {
				return n + 1
			}
			flags := d[p+9]
			p += 10
			if flags&0x80 != 0 {
				p += 3 << ((flags & 7) + 1)
			}
			p++
			subBlocks()
			n++
		default: // decoders skip a byte they do not know
			p++
		}
	}
	return n
}

// webpFrames counts the ANMF chunks of a RIFF/WEBP container.
func webpFrames(d []byte) int {
	n := 0
	p := 12 // "RIFF", size, "WEBP"
	for p+8 <= len(d) {
		size := int(binary.LittleEndian.Uint32(d[p+4:]))
		if string(d[p:p+4]) == "ANMF" {
			n++
		}
		if size < 0 {
			break
		}
		p += 8 + size + size%2
	}
	return n
}

// apngFrames reads the frame count an APNG announces in its acTL chunk, which
// must stand before the first IDAT; a plain PNG has none.
func apngFrames(d []byte) int {
	p := 8 // signature
	for p+8 <= len(d) {
		size := int(binary.BigEndian.Uint32(d[p:]))
		kind := string(d[p+4 : p+8])
		if kind == "IDAT" || size < 0 {
			break
		}
		if kind == "acTL" && p+12 <= len(d) {
			count := binary.BigEndian.Uint32(d[p+8:])
			if count > 1<<30 {
				return 1 << 30
			}
			return int(count)
		}
		p += 12 + size // length, type, data, crc
	}
	return 0
}
