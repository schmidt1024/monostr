"""Tests for what keeps the scorer alive under hostile or unlucky input.

Run with the others: python -m unittest -v test_server test_hardening
None of these needs the model; a FakeSession stands in for it.
"""
import io
import json
import struct
import threading
import time
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

import numpy as np
from PIL import Image

import server
from test_server import FakeSession, encode, gradient


def gif_canvas(w: int, h: int, frames: int) -> bytes:
    """A GIF with a w x h canvas whose frames are 1 x 1 pixel: a few bytes per
    frame, but composing each one on the canvas costs w x h."""
    head = b"GIF89a" + struct.pack("<HH", w, h) + bytes([0x80, 0, 0]) + bytes([0, 0, 0, 255, 255, 255])
    frame = bytes([0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0]) + bytes([2, 2, 0x44, 0x01, 0])
    return head + frame * frames + b"\x3b"


class EndlessSession:
    """Answers every run with the same logits."""

    def __init__(self, logits=(-3.0, 3.0)):
        self.logits = logits
        self.inputs = []

    def run(self, outputs, feeds):
        self.inputs.append(feeds["pixel_values"])
        return [np.array([self.logits], dtype=np.float32)]


def serve(session):
    """A scorer on a free port with the given session; returns (base url, stop)."""

    class Handler(server.Handler):
        pass

    Handler.session = session
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()

    def stop():
        httpd.shutdown()
        httpd.server_close()

    return f"http://127.0.0.1:{httpd.server_address[1]}", stop


def post(base: str, body: bytes):
    req = urllib.request.Request(base + "/classify", data=body, method="POST")
    try:
        with urllib.request.urlopen(req) as r:
            return r.status, json.load(r)
    except urllib.error.HTTPError as e:
        with e:
            return e.code, json.load(e)


class AnimationBudget(unittest.TestCase):
    def test_a_small_file_with_a_huge_canvas_and_many_frames_is_refused_at_once(self):
        data = gif_canvas(7000, 7000, 2000)
        self.assertLess(len(data), 64 * 1024)
        session = EndlessSession()
        started = time.monotonic()
        with self.assertRaises(server.Unreadable):
            server.score(session, data)
        self.assertLess(time.monotonic() - started, 5.0)
        self.assertEqual(session.inputs, [])  # refused before a single frame was composed

    def test_an_ordinary_animation_is_scored(self):
        value, looked_at = server.score(EndlessSession(), gif_canvas(800, 600, 300))
        self.assertEqual(looked_at, 8)
        self.assertLess(value, 0.01)

    def test_one_frame_on_a_large_canvas_is_within_the_budget(self):
        _, looked_at = server.score(EndlessSession(), gif_canvas(7000, 7000, 1))
        self.assertEqual(looked_at, 1)

    def test_over_http_it_is_an_unreadable_picture(self):
        base, stop = serve(EndlessSession())
        try:
            status, _ = post(base, gif_canvas(7000, 7000, 2000))
        finally:
            stop()
        self.assertEqual(status, 422)


class OneAtATime(unittest.TestCase):
    def test_pictures_are_scored_one_after_the_other(self):
        class Slow:
            def __init__(self):
                self.lock = threading.Lock()
                self.running = 0
                self.most = 0

            def run(self, outputs, feeds):
                with self.lock:
                    self.running += 1
                    self.most = max(self.most, self.running)
                time.sleep(0.05)
                with self.lock:
                    self.running -= 1
                return [np.array([[-3.0, 3.0]], dtype=np.float32)]

        session = Slow()
        base, stop = serve(session)
        body = encode(gradient((64, 48)), "PNG")
        results = []
        try:
            threads = [threading.Thread(target=lambda: results.append(post(base, body)[0])) for _ in range(6)]
            for t in threads:
                t.start()
            for t in threads:
                t.join()
        finally:
            stop()
        self.assertEqual(results, [200] * 6)
        self.assertEqual(session.most, 1)


class Faults(unittest.TestCase):
    def test_a_fault_of_the_model_is_not_blamed_on_the_picture(self):
        class Broken:
            def run(self, outputs, feeds):
                raise RuntimeError("onnxruntime: out of memory")

        base, stop = serve(Broken())
        try:
            status, body = post(base, encode(gradient((64, 48)), "PNG"))
            garbage, _ = post(base, b"not a picture")
        finally:
            stop()
        self.assertEqual(status, 500)
        self.assertNotIn("onnxruntime", json.dumps(body))  # the reason stays inside
        self.assertEqual(garbage, 422)

    def test_score_raises_unreadable_for_what_is_no_picture(self):
        for data in (b"", b"not a picture", encode(gradient((64, 48)), "PNG")[:40]):
            with self.assertRaises(server.Unreadable):
                server.score(EndlessSession(), data)


class Transparency(unittest.TestCase):
    def translucent(self) -> Image.Image:
        # white pixels whose alpha draws the picture: blank on white, visible on a dark ground
        img = Image.new("RGBA", (64, 48), (255, 255, 255, 0))
        for x in range(64):
            for y in range(48):
                img.putpixel((x, y), (255, 255, 255, (x * 4) % 256))
        return img

    def test_a_translucent_picture_is_scored_over_white_and_over_black(self):
        session = FakeSession([[-3.0, 3.0], [4.0, -4.0]])  # harmless over white, explicit over black
        value, looked_at = server.score(session, encode(self.translucent(), "PNG"))
        self.assertEqual(looked_at, 1)
        self.assertEqual(len(session.shapes), 2)
        self.assertGreater(value, 0.99)  # the higher of the two decides

    def test_the_two_grounds_really_differ(self):
        over_white, over_black = server.inputs(self.translucent())
        self.assertAlmostEqual(float(over_white.min()), 1.0)  # white on white: nothing to see
        self.assertLess(float(over_black.min()), -0.9)
        self.assertGreater(float(over_black.max()), 0.5)

    def test_an_opaque_picture_with_an_alpha_channel_is_scored_once(self):
        opaque = gradient((64, 48)).convert("RGBA")
        session = FakeSession([[0.0, 0.0]])
        server.score(session, encode(opaque, "PNG"))
        self.assertEqual(len(session.shapes), 1)

    def test_a_palette_picture_with_a_transparent_colour_counts_as_translucent(self):
        img = Image.new("P", (64, 48), 0)
        img.putpalette([255, 255, 255, 0, 0, 0] + [0] * 762)
        for x in range(32):
            for y in range(48):
                img.putpixel((x, y), 1)
        data = encode(img, "PNG", transparency=0)
        self.assertEqual(len(server.inputs(Image.open(io.BytesIO(data)))), 2)


class Preprocessing(unittest.TestCase):
    def old_way(self, frame: Image.Image) -> np.ndarray:
        """The preprocessing the threshold was measured with (CALIBRATION.md)."""
        rgba = frame.convert("RGBA")
        flat = Image.new("RGB", rgba.size, (255, 255, 255))
        flat.paste(rgba, mask=rgba.getchannel("A"))
        flat = flat.resize((384, 384), Image.Resampling.BICUBIC)
        arr = (np.asarray(flat, dtype=np.float32) / 255.0 - 0.5) / 0.5
        return arr.transpose(2, 0, 1)[np.newaxis, ...]

    def test_opaque_pictures_are_prepared_exactly_as_when_the_threshold_was_measured(self):
        rgb = gradient((640, 480))
        for name, img in (
            ("rgb", rgb),
            ("grey", rgb.convert("L")),
            ("palette", rgb.convert("P")),
            ("opaque rgba", rgb.convert("RGBA")),
            ("gif", Image.open(io.BytesIO(encode(rgb, "GIF")))),
        ):
            self.assertTrue(np.array_equal(server.to_input(img), self.old_way(img)), name)

    def test_a_large_jpeg_is_decoded_at_a_reduced_size(self):
        data = encode(gradient((3000, 2000)), "JPEG", quality=80)
        img = server.open_image(data)
        self.assertEqual(img.size, (1500, 1000))  # halved: still at least twice the model's input
        small = server.open_image(encode(gradient((640, 480)), "JPEG"))
        self.assertEqual(small.size, (640, 480))  # nothing to gain: left as it is
        png = server.open_image(encode(gradient((3000, 2000)), "PNG"))
        self.assertEqual(png.size, (3000, 2000))  # only JPEG can be decoded reduced


if __name__ == "__main__":
    unittest.main()
