"""Tests for server.py. Run: NSFW_MODEL=<path to marqo-nsfw-384.onnx> python -m unittest -v test_server"""
import io
import json
import os
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

import numpy as np
from PIL import Image

import server


def encode(img: Image.Image, fmt: str, **kw) -> bytes:
    buf = io.BytesIO()
    img.save(buf, fmt, **kw)
    return buf.getvalue()


def gradient(size=(640, 480)) -> Image.Image:
    img = Image.new("RGB", size)
    img.putdata([(x % 256, y % 256, (x + y) % 256) for y in range(size[1]) for x in range(size[0])])
    return img


class FrameIndices(unittest.TestCase):
    def test_few_frames_are_all_looked_at(self):
        self.assertEqual(server.frame_indices(1), [0])
        self.assertEqual(server.frame_indices(8), list(range(8)))

    def test_many_frames_are_sampled_evenly_with_first_and_last(self):
        got = server.frame_indices(100)
        self.assertEqual(len(got), 8)
        self.assertEqual(got[0], 0)
        self.assertEqual(got[-1], 99)
        self.assertEqual(got, sorted(set(got)))


class FakeSession:
    """Answers each run with the next logits pair [nsfw, sfw] from a list."""

    def __init__(self, logits):
        self.logits = list(logits)
        self.shapes = []

    def run(self, outputs, feeds):
        self.shapes.append(feeds["pixel_values"].shape)
        return [np.array([self.logits.pop(0)], dtype=np.float32)]


class FrameChoice(unittest.TestCase):
    def test_the_highest_frame_decides(self):
        frames = [Image.new("RGB", (64, 48), (i * 50, 0, 0)) for i in range(4)]
        data = encode(frames[0], "GIF", save_all=True, append_images=frames[1:], duration=40, loop=0)
        # frame 2 is the explicit one: logits strongly towards index 0 (NSFW)
        session = FakeSession([[-3.0, 3.0], [-3.0, 3.0], [4.0, -4.0], [-3.0, 3.0]])
        value, looked_at = server.score(session, data)
        self.assertEqual(looked_at, 4)
        self.assertGreater(value, 0.99)
        self.assertEqual(session.shapes, [(1, 3, 384, 384)] * 4)

    def test_a_still_picture_is_scored_once(self):
        session = FakeSession([[0.0, 0.0]])
        value, looked_at = server.score(session, encode(gradient(), "PNG"))
        self.assertEqual(looked_at, 1)
        self.assertAlmostEqual(value, 0.5)

    def test_input_is_scaled_to_minus_one_to_one(self):
        white = server.to_input(Image.new("RGB", (10, 10), (255, 255, 255)))
        black = server.to_input(Image.new("RGB", (10, 10), (0, 0, 0)))
        clear = server.to_input(Image.new("RGBA", (10, 10), (0, 0, 0, 0)))
        self.assertEqual(white.shape, (1, 3, 384, 384))
        self.assertEqual(white.dtype, np.float32)
        self.assertAlmostEqual(float(white.max()), 1.0)
        self.assertAlmostEqual(float(black.min()), -1.0)
        # full transparency is laid over white, not read as black
        self.assertAlmostEqual(float(clear.min()), 1.0)


class Scoring(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.session = server.load_session(os.environ.get("NSFW_MODEL", server.MODEL_PATH))

    def test_harmless_pictures_score_low_in_every_format(self):
        for fmt, kw in (("JPEG", {}), ("PNG", {}), ("WEBP", {}), ("GIF", {})):
            value, frames = server.score(self.session, encode(gradient(), fmt, **kw))
            self.assertLess(value, 0.2, fmt)
            self.assertEqual(frames, 1, fmt)

    def test_transparent_png_is_read(self):
        img = Image.new("RGBA", (300, 200), (0, 0, 0, 0))
        value, frames = server.score(self.session, encode(img, "PNG"))
        self.assertTrue(0.0 <= value <= 1.0)
        self.assertEqual(frames, 1)

    def test_animated_gif_is_sampled(self):
        frames = [Image.new("RGB", (120, 90), (i * 8 % 256, 40, 200)) for i in range(30)]
        data = encode(frames[0], "GIF", save_all=True, append_images=frames[1:], duration=40, loop=0)
        value, looked_at = server.score(self.session, data)
        self.assertEqual(looked_at, 8)
        self.assertTrue(0.0 <= value <= 1.0)

    def test_animated_webp_is_sampled(self):
        frames = [Image.new("RGB", (120, 90), (10, i * 20 % 256, 90)) for i in range(12)]
        data = encode(frames[0], "WEBP", save_all=True, append_images=frames[1:], duration=40)
        _, looked_at = server.score(self.session, data)
        self.assertEqual(looked_at, 8)

    def test_garbage_raises(self):
        with self.assertRaises(Exception):
            server.score(self.session, b"not an image at all")

    def test_a_wrong_model_digest_stops_the_start(self):
        path = os.path.join(os.path.dirname(__file__), "wrong.onnx")
        with open(path, "wb") as f:
            f.write(b"x")
        try:
            with self.assertRaises(SystemExit):
                server.load_session(path)
        finally:
            os.remove(path)


class Http(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        server.Handler.session = server.load_session(os.environ.get("NSFW_MODEL", server.MODEL_PATH))
        cls.httpd = ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
        cls.base = f"http://127.0.0.1:{cls.httpd.server_address[1]}"
        threading.Thread(target=cls.httpd.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()
        cls.httpd.server_close()

    def post(self, body: bytes):
        req = urllib.request.Request(self.base + "/classify", data=body, method="POST")
        try:
            with urllib.request.urlopen(req) as r:
                return r.status, json.load(r)
        except urllib.error.HTTPError as e:
            with e:
                return e.code, json.load(e)

    def test_healthz(self):
        with urllib.request.urlopen(self.base + "/healthz") as r:
            self.assertEqual(r.status, 200)

    def test_classify_answers_score_and_frames(self):
        status, body = self.post(encode(gradient(), "JPEG"))
        self.assertEqual(status, 200)
        self.assertEqual(body["frames"], 1)
        self.assertTrue(0.0 <= body["score"] <= 1.0)

    def test_unreadable_image_is_422(self):
        status, _ = self.post(b"not an image")
        self.assertEqual(status, 422)

    def test_empty_body_is_413(self):
        status, _ = self.post(b"")
        self.assertEqual(status, 413)


if __name__ == "__main__":
    unittest.main()
