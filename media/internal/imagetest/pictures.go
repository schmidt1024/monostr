// Package imagetest makes small pictures in every accepted format for tests.
package imagetest

import (
	"bytes"
	"encoding/base64"
	"encoding/binary"
	"hash/crc32"
	"image"
	"image/color"
	"image/color/palette"
	"image/gif"
	"image/jpeg"
	"image/png"
)

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func fill(w, h int, c color.RGBA) *image.RGBA {
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	for i := 0; i < len(img.Pix); i += 4 {
		img.Pix[i], img.Pix[i+1], img.Pix[i+2], img.Pix[i+3] = c.R, c.G, c.B, c.A
	}
	return img
}

// tint is a colour that differs clearly from one seed to the next, so that
// even a lossy encoder gives different bytes, and so a different hash.
func tint(seed uint8) color.RGBA {
	return color.RGBA{seed * 37, seed*91 + 50, seed*53 + 100, 255}
}

// JPEG is a w x h JPEG; pictures with different seeds have different bytes.
func JPEG(w, h int, seed uint8) []byte {
	var buf bytes.Buffer
	must(jpeg.Encode(&buf, fill(w, h, tint(seed)), &jpeg.Options{Quality: 80}))
	return buf.Bytes()
}

// PNG is a w x h PNG; pictures with different seeds have different bytes.
func PNG(w, h int, seed uint8) []byte {
	var buf bytes.Buffer
	must(png.Encode(&buf, fill(w, h, tint(seed))))
	return buf.Bytes()
}

// GIF is a w x h GIF with the given number of frames.
func GIF(w, h, frames int) []byte {
	g := &gif.GIF{}
	for i := 0; i < frames; i++ {
		p := image.NewPaletted(image.Rect(0, 0, w, h), palette.Plan9)
		for j := range p.Pix {
			p.Pix[j] = uint8(i + 1)
		}
		g.Image = append(g.Image, p)
		g.Delay = append(g.Delay, 4)
	}
	var buf bytes.Buffer
	must(gif.EncodeAll(&buf, g))
	return buf.Bytes()
}

func decode(s string) []byte {
	b, err := base64.StdEncoding.DecodeString(s)
	must(err)
	return b
}

// WebP is a 40 x 30 lossy WebP (Go has no WebP encoder, so the bytes are fixed).
func WebP() []byte {
	return decode("UklGRkoAAABXRUJQVlA4ID4AAACQAwCdASooAB4APm02l0ikIyIhJWgAgA2JZwDQvoB+AAAr98NwAP6H1//NgVzXP0t//yU1/jbj47yncIAAAA==")
}

// WebPLossless is a 40 x 30 lossless WebP with transparency.
func WebPLossless() []byte {
	return decode("UklGRh4AAABXRUJQVlA4TBEAAAAvJ0AHEAdQvCIXpYCBiOh/AAA=")
}

// WebPAnimated is a 40 x 30 animated WebP with four frames.
func WebPAnimated() []byte {
	return decode("UklGRrIBAABXRUJQVlA4WAoAAAACAAAAJwAAHQAAQU5JTQYAAAAAAAAAAABBTk1GYAAAAAAAAAAAACcAAB0AACgAAAJWUDggSAAAAJADAJ0BKigAHgA+bTaXSKQjIiElaACADYlnAND6gAKnDIMXDAAA/va+B//aWf/Wh/+tD/mz//gN18W0y/5dNdX/6/CHFAAAAEFOTUZWAAAAAAAAAAAAJwAAHQAAKAAAAFZQOCA+AAAA1AIAnQEqKAAeAD5tLpFIglYAANiWcAy6TsVd2y/BAAD+8ut//64D//nAf/84D+l41/+z8292f+z2Ro3UAABBTk1GWgAAAAAAAAAAACcAAB0AACgAAABWUDggQgAAABQDAJ0BKigAHgA+bS6RSIJWAADYlnAND6gAKnDH71+4AAD+795f/9Js/5Bn/kGfn7/8kj1cygn+hdcM/8Ee3PQAAEFOTUZeAAAAAAAAAAAAJwAAHQAAKAAAAFZQOCBGAAAAVAMAnQEqKAAeAD5tLpFIglYAANiWcAy1qAfgACtFx+9fuAAA/uveL/84ljqd3pB//OJY6nd6Qf6A0lCi//0BoOWoiQAAAA==")
}

// PNGHeader is a PNG whose header claims w x h pixels without carrying them:
// enough for a size check, useless to a decoder.
func PNGHeader(w, h int) []byte {
	chunk := func(tag string, data []byte) []byte {
		var out bytes.Buffer
		must(binary.Write(&out, binary.BigEndian, uint32(len(data))))
		out.WriteString(tag)
		out.Write(data)
		must(binary.Write(&out, binary.BigEndian, crc32.ChecksumIEEE(append([]byte(tag), data...))))
		return out.Bytes()
	}
	ihdr := make([]byte, 13)
	binary.BigEndian.PutUint32(ihdr[0:], uint32(w))
	binary.BigEndian.PutUint32(ihdr[4:], uint32(h))
	ihdr[8], ihdr[9] = 8, 2 // 8 bit, truecolour
	var out bytes.Buffer
	out.WriteString("\x89PNG\r\n\x1a\n")
	out.Write(chunk("IHDR", ihdr))
	out.Write(chunk("IEND", nil))
	return out.Bytes()
}
