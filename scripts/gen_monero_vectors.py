#!/usr/bin/env python3
"""Generate Monero reference vectors with monero-python (independent implementation).

Usage: python3 -m venv .venv && .venv/bin/pip install monero==1.1.1 && .venv/bin/python scripts/gen_monero_vectors.py
"""
import json
import os
import sys

from monero import const
from monero.seed import Seed

L = 2**252 + 27742317777372353535851937790883648493
OUT = "android/monero/src/test/resources/vectors.json"


def reduced_random_hex() -> str:
    n = int.from_bytes(os.urandom(32), "little") % L
    return n.to_bytes(32, "little").hex()


def vector(spend_hex: str) -> dict:
    s = Seed(spend_hex)
    assert s.secret_spend_key() == spend_hex, "seed was not canonical"
    main = s.public_address(net=const.NET_MAIN)
    stage = s.public_address(net=const.NET_STAGE)
    integrated = []
    for pid in ["0123456789abcdef", os.urandom(8).hex(), os.urandom(8).hex()]:
        integrated.append({
            "pid": pid,
            "mainnet": str(main.with_payment_id(pid)),
            "stagenet": str(stage.with_payment_id(pid)),
        })
    return {
        "spend_secret": spend_hex,
        "view_secret": s.secret_view_key(),
        "spend_public": s.public_spend_key(),
        "view_public": s.public_view_key(),
        "mainnet": str(main),
        "stagenet": str(stage),
        "mnemonic": s.phrase.split(),
        "integrated": integrated,
    }


vectors = [vector("0000000000000000000000000000000000000000000000000000000000000001")]
vectors += [vector(reduced_random_hex()) for _ in range(9)]

with open(OUT, "w") as f:
    json.dump({"generator": "monero-python 1.1.1", "vectors": vectors}, f, indent=2)
print(f"wrote {len(vectors)} vectors to {OUT}", file=sys.stderr)
