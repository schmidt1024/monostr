"""NSFW scorer beside the media server.

POST /classify with the raw image bytes answers {"score": 0..1, "frames": n};
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
import os
import threading
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


def main():
    Handler.session = load_session(MODEL_PATH)
    host, port = LISTEN.rsplit(":", 1)
    ThreadingHTTPServer((host, int(port)), Handler).serve_forever()


if __name__ == "__main__":
    main()
