"""Downloads the pinned model and checks its digest.

    python fetch_model.py <target path>

Model: Marqo/nsfw-image-detection-384 (Apache-2.0, ViT-tiny), as the ONNX
export ICIJ/nsfw-image-detection-384-onnx at a fixed revision. server.py
checks the same digest again every time it starts.
"""
import hashlib
import sys
import urllib.request

from server import MODEL_SHA256

REVISION = "4f63fac119fb24a9d98393e57549b1427530ca52"
URL = f"https://huggingface.co/ICIJ/nsfw-image-detection-384-onnx/resolve/{REVISION}/marqo-nsfw-384.onnx"


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    with urllib.request.urlopen(URL, timeout=300) as r:
        data = r.read()
    digest = hashlib.sha256(data).hexdigest()
    if digest != MODEL_SHA256:
        raise SystemExit(f"downloaded model has digest {digest}, expected {MODEL_SHA256}")
    with open(sys.argv[1], "wb") as f:
        f.write(data)
    print(f"{sys.argv[1]}: {len(data)} bytes, digest ok")


if __name__ == "__main__":
    main()
