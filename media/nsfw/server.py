"""NSFW scorer beside the media server.

POST /classify with the raw image bytes answers {"score": 0..1, "frames": n};
POST /video with an MP4 answers what ffprobe sees (size as displayed, codecs,
container, duration) and the highest score of up to MAX_VIDEO_FRAMES frames
ffmpeg takes from it (spec 2026-10-27 video §7), or 422 when it is no video;
GET /healthz answers 200 once the model is loaded. The score is the model's
NSFW probability; for an animated GIF, WebP or PNG it is the highest score of
up to MAX_FRAMES evenly spaced frames, and a picture with transparency is
scored over a white and over a black ground. Nothing is stored and nothing is
logged about a request.

The input is hostile by assumption: one picture is scored at a time, an
animation may not cost more than MAX_ANIMATION_PIXELS to compose, and a fault
of the scorer itself (500) is kept apart from an unreadable picture (422).
"""
import hashlib
import io
import json
import math
import os
import shutil
import subprocess
import tempfile
import threading
import time
import warnings
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import numpy as np
import onnxruntime as ort
from PIL import Image

MODEL_PATH = os.environ.get("NSFW_MODEL", "/app/marqo-nsfw-384.onnx")
MODEL_SHA256 = "50256dde930a4ceeb8953246ba7345158e02cc0a201a173402ab3e6e3a76f40a"
LISTEN = os.environ.get("NSFW_LISTEN", "0.0.0.0:8081")
MAX_BODY = 11 * 1024 * 1024
MAX_FRAMES = 8
# videos (spec 2026-10-27 video §6, §7): the media server caps them at 50 MiB
MAX_VIDEO_BODY = 50 * 1024 * 1024
MIN_VIDEO_FRAMES = 3
MAX_VIDEO_FRAMES = 12
VIDEO_FRAME_EVERY = 10.0  # seconds
FFPROBE = os.environ.get("NSFW_FFPROBE", "ffprobe")
FFMPEG = os.environ.get("NSFW_FFMPEG", "ffmpeg")
TEMP_ROOT = os.environ.get("NSFW_TEMP", tempfile.gettempdir())
# the whole check of one video, every ffprobe and ffmpeg run included
VIDEO_BUDGET = 120.0  # seconds
# 125 s at 60 frames a second: more packets (of a stream) are refused before they are listed
MAX_VIDEO_PACKETS = 7500
# what the media server takes (spec 2026-10-27 video §6): anything else is answered without decoding a frame,
# the server refuses it with its own reason (final review I3)
VIDEO_CODEC = "h264"
AUDIO_CODECS = (None, "aac")
MAX_VIDEO_SECONDS = 125.0
MAX_SHORT_EDGE = 720
# ffmpeg reads exactly this file as MP4/MOV and nothing else: no format guessing
# (a playlist could name other files or URLs) and no protocol but the file itself
FF_INPUT = ["-protocol_whitelist", "file", "-f", "mov", "-enable_drefs", "0"]
SIZE = 384
NSFW_INDEX = 0  # labels are ["NSFW", "SFW"]

# the media server refuses anything above 50 megapixels before it gets here;
# this is the second line of defence against a decompression bomb
Image.MAX_IMAGE_PIXELS = 50_000_000
warnings.simplefilter("error", Image.DecompressionBombWarning)

# Reaching a frame means composing every frame before it on the full canvas,
# so frames x width x height is what an animation costs. The media server
# enforces the same budget (imagecheck.MaxAnimationPixels); this is the second
# line of defence.
MAX_ANIMATION_PIXELS = 250_000_000

# One picture at a time: a 50 megapixel picture takes several hundred megabytes
# while it is decoded, and two of them would not fit into the container.
SCORING = threading.BoundedSemaphore(1)
# ffmpeg is expensive: two videos at a time, a third waits (spec §10)
VIDEOS = threading.BoundedSemaphore(2)


class Unreadable(Exception):
    """The bytes are no picture this scorer can read, or one it refuses to work on."""


def load_session(path: str) -> ort.InferenceSession:
    with open(path, "rb") as f:
        digest = hashlib.sha256(f.read()).hexdigest()
    if digest != MODEL_SHA256:
        raise SystemExit(f"model digest {digest} does not match the pinned one")
    opts = ort.SessionOptions()
    opts.intra_op_num_threads = int(os.environ.get("NSFW_THREADS", "2"))
    return ort.InferenceSession(path, sess_options=opts, providers=["CPUExecutionProvider"])


def frame_indices(count: int) -> list[int]:
    """Up to MAX_FRAMES indices spread evenly over count frames, first and last included."""
    if count <= MAX_FRAMES:
        return list(range(count))
    return sorted({round(i * (count - 1) / (MAX_FRAMES - 1)) for i in range(MAX_FRAMES)})


def open_image(data: bytes) -> Image.Image:
    """Opens the picture without decoding it; a large JPEG is set up to decode at
    a reduced size (still at least twice the model's input), which saves most
    of the memory and time a 50 megapixel photo would take."""
    img = Image.open(io.BytesIO(data))
    if img.format == "JPEG":
        img.draft("RGB", (2 * SIZE, 2 * SIZE))
    return img


def has_alpha(frame: Image.Image) -> bool:
    return frame.mode in ("RGBA", "LA", "PA") or "transparency" in frame.info


def _array(flat: Image.Image) -> np.ndarray:
    """An RGB picture as float32 [1, 3, 384, 384] in [-1, 1]."""
    flat = flat.resize((SIZE, SIZE), Image.Resampling.BICUBIC)
    arr = np.asarray(flat, dtype=np.float32) / 255.0
    arr = (arr - 0.5) / 0.5
    return arr.transpose(2, 0, 1)[np.newaxis, ...]


def inputs(frame: Image.Image) -> list[np.ndarray]:
    """The model inputs for one frame: one for an opaque picture; for a picture
    that is really translucent two, laid over white and over black - white
    pixels with a varying alpha are blank on white and a picture on a dark
    theme."""
    if not has_alpha(frame):
        return [_array(frame if frame.mode == "RGB" else frame.convert("RGB"))]
    rgba = frame.convert("RGBA")
    alpha = rgba.getchannel("A")
    grounds = [(255, 255, 255)] if alpha.getextrema()[0] == 255 else [(255, 255, 255), (0, 0, 0)]
    out = []
    for ground in grounds:
        flat = Image.new("RGB", rgba.size, ground)
        flat.paste(rgba, mask=alpha)
        out.append(_array(flat))
    return out


def to_input(frame: Image.Image) -> np.ndarray:
    """One frame over a white ground, as the threshold was measured (CALIBRATION.md)."""
    return inputs(frame)[0]


def score(session, data: bytes) -> tuple[float, int]:
    """(highest NSFW probability, number of frames looked at). Raises Unreadable
    for what is no readable picture or exceeds the animation budget; anything
    else that goes wrong is the scorer's own fault and propagates."""
    try:
        img = open_image(data)
        count = getattr(img, "n_frames", 1)
        if count * img.width * img.height > MAX_ANIMATION_PIXELS:
            raise Unreadable("animation over the frame pixel budget")
        indices = frame_indices(count)
    except (Unreadable, MemoryError):
        raise
    except Exception as e:
        raise Unreadable(str(e)) from e
    best = 0.0
    for i in indices:
        try:
            img.seek(i)
            arrays = inputs(img)
        except MemoryError:
            raise
        except Exception as e:  # Pillow could not decode this frame
            raise Unreadable(str(e)) from e
        for pixels in arrays:
            logits = session.run(["logits"], {"pixel_values": pixels})[0][0]
            e = np.exp(logits - logits.max())
            best = max(best, float(e[NSFW_INDEX] / e.sum()))
    return best, len(indices)


def frame_times(duration: float) -> list[float]:
    """One frame per VIDEO_FRAME_EVERY seconds, at least MIN and at most MAX,
    each in the middle of its equal share of the video."""
    n = min(MAX_VIDEO_FRAMES, max(MIN_VIDEO_FRAMES, math.ceil(duration / VIDEO_FRAME_EVERY)))
    return [duration * (i + 0.5) / n for i in range(n)]


def _rotation(stream: dict) -> int:
    for side in stream.get("side_data_list") or []:
        if "rotation" in side:
            return int(float(side["rotation"]))
    tag = (stream.get("tags") or {}).get("rotate")
    return int(float(tag)) if tag else 0


class _Budget:
    def __init__(self):
        self.end = time.monotonic() + VIDEO_BUDGET

    def left(self) -> float:
        left = self.end - time.monotonic()
        if left <= 0:
            raise Unreadable("the video check ran out of time")
        return left


def _run(cmd: list, budget: _Budget) -> bytes:
    try:
        return subprocess.run(cmd, capture_output=True, timeout=budget.left(), check=True).stdout
    except subprocess.SubprocessError as e:
        raise Unreadable(str(e)) from e


def _is_mp4(path: str) -> bool:
    """An ISO media file starts with a box whose type is ftyp."""
    with open(path, "rb") as f:
        head = f.read(12)
    return len(head) == 12 and head[4:8] == b"ftyp"


def probe(path: str, budget: "_Budget | None" = None) -> dict:
    """What ffprobe sees: width and height as displayed (rotation applied),
    codecs, container, and the duration of the video stream's packets (not the
    header's word for it). Raises Unreadable for what is no MP4 with exactly one
    video stream."""
    budget = budget or _Budget()
    if not _is_mp4(path):
        raise Unreadable("not an MP4 file")
    try:
        data = json.loads(_run([FFPROBE, "-v", "error", *FF_INPUT, "-show_streams", "-show_format", "-of", "json", path], budget))
    except ValueError as e:
        raise Unreadable(str(e)) from e
    streams = data.get("streams") or []
    # a cover picture is a video stream too: exactly one, so what is scored is what plays; one sound at most and
    # nothing else (final review I4: a second stream could carry what nobody checks)
    videos = [s for s in streams if s.get("codec_type") == "video"]
    audios = [s for s in streams if s.get("codec_type") == "audio"]
    if len(videos) != 1:
        raise Unreadable(f"{len(videos)} video streams")
    if len(audios) > 1 or len(videos) + len(audios) != len(streams):
        raise Unreadable("streams other than one video and one sound")
    video = videos[0]
    audio = audios[0] if audios else None
    fmt = data.get("format") or {}
    if not video.get("width") or not video.get("height"):
        raise Unreadable("no picture size")
    width, height = int(video["width"]), int(video["height"])
    if _rotation(video) % 180 != 0:
        width, height = height, width
    names = (fmt.get("format_name") or "").split(",")
    index = str(int(video["index"]))
    # the length is where the last packet of any stream ends: sound longer than the picture counts (final review I4)
    duration = _stream_end(path, index, budget)
    if audio is not None:
        duration = max(duration, _stream_end(path, str(int(audio["index"])), budget, need_packets=False))
    return {
        "index": int(index),
        "width": width,
        "height": height,
        "duration": duration,
        "video_codec": video.get("codec_name"),
        "audio_codec": audio.get("codec_name") if audio else None,
        "container": "mp4" if "mp4" in names else (names[0] if names[0] else None),
    }


def _stream_end(path: str, index: str, budget: "_Budget", need_packets: bool = True) -> float:
    """Where the last packet of stream [index] ends. The packets are counted first (one number; a rotated video adds
    an empty side-data field: "2699,") and more than MAX_VIDEO_PACKETS are refused before any listing reaches memory."""
    try:
        packets = int(_run([FFPROBE, "-v", "error", *FF_INPUT, "-select_streams", index, "-count_packets",
                            "-show_entries", "stream=nb_read_packets", "-of", "csv=p=0", path], budget).split()[0].split(b",")[0])
    except (ValueError, IndexError) as e:
        raise Unreadable("packets not countable") from e
    if packets > MAX_VIDEO_PACKETS or (need_packets and packets < 1):
        raise Unreadable(f"{packets} packets in stream {index}")
    ends = _run([FFPROBE, "-v", "error", *FF_INPUT, "-select_streams", index, "-show_entries", "packet=pts_time,duration_time",
                 "-of", "csv=p=0", path], budget).decode(errors="replace").split()
    end = 0.0
    for line in ends:
        parts = line.split(",")
        try:
            end = max(end, float(parts[0]) + (float(parts[1]) if len(parts) > 1 and parts[1] not in ("", "N/A") else 0.0))
        except ValueError:
            continue
    if need_packets and end <= 0:
        raise Unreadable("no video packets")
    return end


def acceptable(info: dict) -> bool:
    """What the media server would store at all; only such a video is decoded."""
    return (info["video_codec"] == VIDEO_CODEC and info["audio_codec"] in AUDIO_CODECS
            and min(info["width"], info["height"]) <= MAX_SHORT_EDGE and info["duration"] <= MAX_VIDEO_SECONDS)


def score_video(session, path: str) -> dict:
    """probe() plus the highest score of the frames at frame_times(). Every
    frame must be taken: one that cannot be fails the check (no part of a
    video goes unscored). The whole check shares one time budget."""
    budget = _Budget()
    info = probe(path, budget)
    if not acceptable(info):
        # refused anyway: no frame is decoded, the server answers with its own reason
        info["score"], info["frames"] = 0.0, 0
        del info["index"]
        return info
    best, looked_at = 0.0, 0
    for t in frame_times(info["duration"]):
        frame = _run([FFMPEG, "-v", "error", *FF_INPUT, "-c:v", VIDEO_CODEC, "-ss", f"{t:.3f}", "-i", path, "-map", f"0:{info['index']}", "-frames:v", "1",
                      "-f", "image2pipe", "-vcodec", "mjpeg", "-"], budget)
        if not frame:
            raise Unreadable(f"no frame at {t:.3f} s")
        with SCORING:
            value, _ = score(session, frame)
        best, looked_at = max(best, value), looked_at + 1
    info["score"], info["frames"] = round(best, 6), looked_at
    del info["index"]
    return info


class Handler(BaseHTTPRequestHandler):
    session: ort.InferenceSession

    def log_message(self, *args):  # no request log
        pass

    def reply(self, status: int, body: dict):
        raw = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        if self.path == "/healthz":
            self.reply(200, {"ok": True})
        else:
            self.reply(404, {"error": "not found"})

    def do_POST(self):
        if self.path == "/video":
            self.video()
            return
        if self.path != "/classify":
            self.reply(404, {"error": "not found"})
            return
        try:
            length = int(self.headers.get("Content-Length", ""))
        except ValueError:
            self.reply(411, {"error": "length required"})
            return
        if length <= 0 or length > MAX_BODY:
            self.reply(413, {"error": "body too large"})
            return
        data = self.rfile.read(length)
        try:
            with SCORING:
                value, frames = score(self.session, data)
        except Unreadable:
            self.reply(422, {"error": "unreadable image"})
            return
        except Exception:
            # the model or the memory, not the picture: the media server answers 503, not 415
            self.reply(500, {"error": "scorer fault"})
            return
        self.reply(200, {"score": round(value, 6), "frames": frames})


    def video(self):
        try:
            length = int(self.headers.get("Content-Length", ""))
        except ValueError:
            self.reply(411, {"error": "length required"})
            return
        if length <= 0 or length > MAX_VIDEO_BODY:
            self.reply(413, {"error": "body too large"})
            return
        work = tempfile.mkdtemp(dir=TEMP_ROOT)
        try:
            status, body = self._video_in(work, length)
        finally:
            # gone before the answer: the caller never sees a temp file outlive its request
            shutil.rmtree(work, ignore_errors=True)
        self.reply(status, body)

    def _video_in(self, work: str, length: int) -> tuple[int, dict]:
        path = os.path.join(work, "in.mp4")
        with open(path, "wb") as f:
            left = length
            while left > 0:
                chunk = self.rfile.read(min(left, 1 << 16))
                if not chunk:
                    return 400, {"error": "short body"}
                f.write(chunk)
                left -= len(chunk)
        try:
            with VIDEOS:
                return 200, score_video(self.session, path)
        except Unreadable:
            return 422, {"error": "unreadable video"}
        except Exception:
            return 500, {"error": "scorer fault"}


def main():
    Handler.session = load_session(MODEL_PATH)
    host, port = LISTEN.rsplit(":", 1)
    ThreadingHTTPServer((host, int(port)), Handler).serve_forever()


if __name__ == "__main__":
    main()
