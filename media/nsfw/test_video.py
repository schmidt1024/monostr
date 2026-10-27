"""Tests for the video path of server.py (spec 2026-10-27 video §7).
Run: NSFW_MODEL=<model> PATH=<dir with ffmpeg and ffprobe>:$PATH python -m unittest -v test_video"""
import json
import os
import subprocess
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

import numpy as np

import server


def clip(path: str, seconds: float = 2, size: str = "320x180", audio: bool = True, rotate: int = 0, codec: str = "libx264"):
    """A tiny test clip made with ffmpeg: no binary fixtures in git."""
    cmd = ["ffmpeg", "-v", "error", "-y"]
    if rotate:
        cmd += ["-display_rotation", str(rotate)]
    cmd += ["-f", "lavfi", "-i", f"testsrc=size={size}:rate=10:duration={seconds}"]
    if audio:
        cmd += ["-f", "lavfi", "-i", f"sine=duration={seconds}", "-c:a", "aac"]
    cmd += ["-c:v", codec, "-pix_fmt", "yuv420p", "-shortest", path]
    subprocess.run(cmd, check=True)
    with open(path, "rb") as f:
        return f.read()


class FakeSession:
    """Answers each frame with the next logits pair [nsfw, sfw]."""

    def __init__(self, logits):
        self.logits = list(logits)

    def run(self, outputs, feeds):
        return [np.array([self.logits.pop(0)], dtype=np.float32)]


class FrameTimes(unittest.TestCase):
    def test_frame_times_short_and_long(self):
        self.assertEqual(len(server.frame_times(5)), 3)
        self.assertEqual(len(server.frame_times(35)), 4)
        self.assertEqual(len(server.frame_times(120)), 12)
        self.assertEqual(len(server.frame_times(600)), 12)

    def test_frame_times_are_inside_and_ordered(self):
        times = server.frame_times(30)
        self.assertEqual(times, sorted(times))
        self.assertTrue(all(0 < t < 30 for t in times))


class Probe(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()

    def test_probe_reports_size_codecs_duration(self):
        path = os.path.join(self.dir, "a.mp4")
        clip(path)
        info = server.probe(path)
        self.assertEqual((info["width"], info["height"]), (320, 180))
        self.assertEqual(info["video_codec"], "h264")
        self.assertEqual(info["audio_codec"], "aac")
        self.assertEqual(info["container"], "mp4")
        self.assertAlmostEqual(info["duration"], 2, delta=0.2)

    def test_no_audio_is_none(self):
        path = os.path.join(self.dir, "b.mp4")
        clip(path, audio=False)
        self.assertIsNone(server.probe(path)["audio_codec"])

    def test_rotated_portrait_reports_displayed_size(self):
        path = os.path.join(self.dir, "c.mp4")
        clip(path, size="320x180", rotate=90)
        info = server.probe(path)
        self.assertEqual((info["width"], info["height"]), (180, 320))

    def test_a_phone_video_with_a_rotation_flag_is_read(self):
        # a phone stores landscape pixels plus a display matrix; Media3 puts the sound first (stream 0)
        land = os.path.join(self.dir, "land.mp4")
        clip(land, size="320x180")
        flagged = os.path.join(self.dir, "flagged.mp4")
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-display_rotation", "90", "-i", land, "-map", "0:a", "-map", "0:v",
                        "-c", "copy", flagged], check=True)
        info = server.probe(flagged)
        self.assertEqual((info["width"], info["height"]), (180, 320))
        self.assertAlmostEqual(info["duration"], 2, delta=0.3)

    def test_not_a_video_is_unreadable(self):
        path = os.path.join(self.dir, "d.mp4")
        with open(path, "wb") as f:
            f.write(b"no video at all")
        with self.assertRaises(server.Unreadable):
            server.probe(path)


class Hostile(unittest.TestCase):
    """Security review of 2026-10-27: the input is hostile."""

    def setUp(self):
        self.dir = tempfile.mkdtemp()

    def test_a_playlist_is_never_opened(self):
        # an HLS playlist naming a local file: ffmpeg's own format detection would follow it
        path = os.path.join(self.dir, "p.mp4")
        with open(path, "w") as f:
            f.write("#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:10.0,\nfile:///etc/passwd\n#EXT-X-ENDLIST\n")
        with self.assertRaises(server.Unreadable):
            server.probe(path)

    def test_two_video_streams_are_refused(self):
        a, b = os.path.join(self.dir, "a.mp4"), os.path.join(self.dir, "b.mp4")
        clip(a, audio=False)
        clip(b, audio=False)
        both = os.path.join(self.dir, "both.mp4")
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", a, "-i", b, "-map", "0:v", "-map", "1:v", "-c", "copy", both], check=True)
        with self.assertRaises(server.Unreadable):
            server.probe(both)

    def test_a_cover_picture_stream_is_refused(self):
        # an attached picture is a second video stream: what is scored must be what plays
        a = os.path.join(self.dir, "a.mp4")
        clip(a, audio=False)
        cover = os.path.join(self.dir, "cover.png")
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "color=red:size=64x64", "-frames:v", "1", cover], check=True)
        both = os.path.join(self.dir, "both.mp4")
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", a, "-i", cover, "-map", "0:v", "-map", "1", "-c:v:0", "copy",
                        "-c:v:1", "png", "-disposition:v:1", "attached_pic", both], check=True)
        with self.assertRaises(server.Unreadable):
            server.probe(both)

    def test_too_many_packets_are_refused_before_they_are_listed(self):
        path = os.path.join(self.dir, "a.mp4")
        clip(path, seconds=5)  # 50 packets at 10 fps
        real = server.MAX_VIDEO_PACKETS
        try:
            server.MAX_VIDEO_PACKETS = 20
            with self.assertRaises(server.Unreadable):
                server.probe(path)
        finally:
            server.MAX_VIDEO_PACKETS = real

    def test_duration_comes_from_the_packets(self):
        path = os.path.join(self.dir, "a.mp4")
        clip(path, seconds=4)
        info = server.probe(path)
        self.assertAlmostEqual(info["duration"], 4, delta=0.3)

    def test_a_frame_that_cannot_be_taken_fails_the_check(self):
        path = os.path.join(self.dir, "a.mp4")
        clip(path, seconds=5)
        real = server.frame_times
        try:
            server.frame_times = lambda d: [1.0, 999.0]  # the second lies past the end
            with self.assertRaises(server.Unreadable):
                server.score_video(FakeSession([[-3.0, 3.0]] * 3), path)
        finally:
            server.frame_times = real

    def test_the_whole_check_has_one_budget(self):
        path = os.path.join(self.dir, "a.mp4")
        clip(path, seconds=5)
        real = server.VIDEO_BUDGET
        try:
            server.VIDEO_BUDGET = 0.001
            with self.assertRaises(server.Unreadable):
                server.score_video(FakeSession([[-3.0, 3.0]] * 3), path)
        finally:
            server.VIDEO_BUDGET = real


class LimitsBeforeDecoding(unittest.TestCase):
    """Final review I3/I4: what the server refuses anyway is never decoded, and sound counts toward the length."""

    def setUp(self):
        self.dir = tempfile.mkdtemp()

    def test_long_audio_under_a_short_video_counts(self):
        v = os.path.join(self.dir, "v.mp4")
        clip(v, seconds=2, audio=False)
        both = os.path.join(self.dir, "both.mp4")
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", v, "-f", "lavfi", "-i", "sine=duration=150", "-map", "0:v", "-map", "1:a",
                        "-c:v", "copy", "-c:a", "aac", both], check=True)
        self.assertGreater(server.probe(both)["duration"], 140)  # over 125 s: refused as too long

    def test_two_audio_streams_are_refused(self):
        v = os.path.join(self.dir, "v.mp4")
        clip(v, seconds=2)
        two = os.path.join(self.dir, "two.mp4")
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", v, "-map", "0:v", "-map", "0:a", "-map", "0:a", "-c", "copy", two], check=True)
        with self.assertRaises(server.Unreadable):
            server.probe(two)

    def _never_decoded(self, path):
        session = FakeSession([])  # any frame would pop from an empty list
        info = server.score_video(session, path)
        self.assertEqual(info["frames"], 0)
        return info

    def test_another_codec_is_not_decoded(self):
        path = os.path.join(self.dir, "m.mp4")
        clip(path, codec="mpeg4")
        self.assertEqual(self._never_decoded(path)["video_codec"], "mpeg4")

    def test_a_large_picture_is_not_decoded(self):
        path = os.path.join(self.dir, "big.mp4")
        clip(path, size="1280x1280", audio=False)
        self._never_decoded(path)

    def test_a_long_video_is_not_decoded(self):
        path = os.path.join(self.dir, "long.mp4")
        clip(path, seconds=130, size="160x90", audio=False)
        self.assertGreater(self._never_decoded(path)["duration"], 125)


class Scoring(unittest.TestCase):
    def test_score_is_max_of_frames(self):
        d = tempfile.mkdtemp()
        path = os.path.join(d, "a.mp4")
        clip(path, seconds=5)
        # three frames; the second is the explicit one
        info = server.score_video(FakeSession([[-3.0, 3.0], [4.0, -4.0], [-3.0, 3.0]]), path)
        self.assertEqual(info["frames"], 3)
        self.assertGreater(info["score"], 0.99)


class Endpoint(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        server.TEMP_ROOT = self.tmp
        server.Handler.session = FakeSession([[-3.0, 3.0]] * 20)
        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()
        self.url = f"http://127.0.0.1:{self.httpd.server_address[1]}/video"

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()

    def post(self, data: bytes):
        req = urllib.request.Request(self.url, data=data, method="POST", headers={"Content-Type": "video/mp4"})
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                return r.status, json.loads(r.read())
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read())

    def test_a_video_is_probed_and_scored(self):
        data = clip(os.path.join(tempfile.mkdtemp(), "a.mp4"))
        status, body = self.post(data)
        self.assertEqual(status, 200, body)
        self.assertEqual((body["width"], body["height"], body["video_codec"], body["container"]), (320, 180, "h264", "mp4"))
        self.assertLess(body["score"], 0.01)

    def test_not_a_video_is_422(self):
        status, body = self.post(b"plain text, no video")
        self.assertEqual(status, 422, body)

    def test_temp_files_are_removed(self):
        self.post(clip(os.path.join(tempfile.mkdtemp(), "a.mp4")))
        self.post(b"plain text, no video")
        self.assertEqual(os.listdir(self.tmp), [])

    def test_too_large_is_413(self):
        req = urllib.request.Request(self.url, data=b"x", method="POST", headers={"Content-Length": str(server.MAX_VIDEO_BODY + 1)})
        try:
            urllib.request.urlopen(req, timeout=10)
            self.fail("accepted")
        except urllib.error.HTTPError as e:
            self.assertEqual(e.code, 413)
        except (ConnectionError, urllib.error.URLError):
            pass  # the server answered 413 and closed before reading the rest


if __name__ == "__main__":
    unittest.main()
