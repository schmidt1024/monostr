package imagetest

import (
	"bytes"
	"encoding/binary"
	"hash/crc32"
)

// GIFCanvas is a GIF whose canvas is w x h (at most 65535 a side) and whose
// frames are 1 x 1 pixel each: a few bytes per frame, but a decoder that
// composes frames on the canvas pays for w x h on every one of them.
func GIFCanvas(w, h, frames int) []byte {
	var out bytes.Buffer
	out.WriteString("GIF89a")
	must(binary.Write(&out, binary.LittleEndian, uint16(w)))
	must(binary.Write(&out, binary.LittleEndian, uint16(h)))
	out.Write([]byte{0x80, 0, 0})             // global colour table with two entries
	out.Write([]byte{0, 0, 0, 255, 255, 255}) // black, white
	for i := 0; i < frames; i++ {
		out.Write([]byte{0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0}) // image descriptor: 1 x 1 at 0,0
		out.Write([]byte{2, 2, 0x44, 0x01, 0})             // LZW: one pixel of colour 0
	}
	out.WriteByte(0x3B)
	return out.Bytes()
}

// WebPCanvas is the container of an animated WebP with a w x h canvas and the
// given number of (empty) frames: enough for a size and frame check, useless
// to a decoder.
func WebPCanvas(w, h, frames int) []byte {
	chunk := func(tag string, data []byte) []byte {
		var c bytes.Buffer
		c.WriteString(tag)
		must(binary.Write(&c, binary.LittleEndian, uint32(len(data))))
		c.Write(data)
		if len(data)%2 == 1 {
			c.WriteByte(0)
		}
		return c.Bytes()
	}
	vp8x := make([]byte, 10)
	vp8x[0] = 0x02 // animation
	vp8x[4], vp8x[5], vp8x[6] = byte(w-1), byte((w-1)>>8), byte((w-1)>>16)
	vp8x[7], vp8x[8], vp8x[9] = byte(h-1), byte((h-1)>>8), byte((h-1)>>16)
	var body bytes.Buffer
	body.WriteString("WEBP")
	body.Write(chunk("VP8X", vp8x))
	body.Write(chunk("ANIM", make([]byte, 6)))
	for i := 0; i < frames; i++ {
		body.Write(chunk("ANMF", make([]byte, 16)))
	}
	var out bytes.Buffer
	out.WriteString("RIFF")
	must(binary.Write(&out, binary.LittleEndian, uint32(body.Len())))
	out.Write(body.Bytes())
	return out.Bytes()
}

// APNGHeader is PNGHeader with an acTL chunk that announces frames.
func APNGHeader(w, h, frames int) []byte {
	plain := PNGHeader(w, h)
	actl := make([]byte, 8)
	binary.BigEndian.PutUint32(actl, uint32(frames))
	var chunk bytes.Buffer
	must(binary.Write(&chunk, binary.BigEndian, uint32(len(actl))))
	chunk.WriteString("acTL")
	chunk.Write(actl)
	must(binary.Write(&chunk, binary.BigEndian, crc32.ChecksumIEEE(append([]byte("acTL"), actl...))))
	// signature (8) + IHDR chunk (4 + 4 + 13 + 4), then acTL, then the rest
	const afterIHDR = 8 + 25
	var out bytes.Buffer
	out.Write(plain[:afterIHDR])
	out.Write(chunk.Bytes())
	out.Write(plain[afterIHDR:])
	return out.Bytes()
}
