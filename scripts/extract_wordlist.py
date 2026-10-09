#!/usr/bin/env python3
"""Extract Monero's English mnemonic word list from src/mnemonics/english.h."""
import re
import sys
import urllib.request

URL = "https://raw.githubusercontent.com/monero-project/monero/master/src/mnemonics/english.h"
OUT = "android/monero/src/main/resources/mnemonic/english.txt"

src = urllib.request.urlopen(URL).read().decode()
start = src.index('"abbey"')  # the array literal starts with the first word
words = re.findall(r'"([a-z]+)"', src[start:])
assert len(words) == 1626, f"expected 1626 words, got {len(words)}"
assert words[0] == "abbey" and words[-1] == "zoom", (words[0], words[-1])
with open(OUT, "w") as f:
    f.write("\n".join(words) + "\n")
print(f"wrote {len(words)} words to {OUT}")
