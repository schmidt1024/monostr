#!/usr/bin/env python3
"""One-off migration (spec docs/superpowers/specs/2026-11-10-spacing-shapes-tokens-design.md §4):
rewrite numeric dp literals inside spacing calls to Spacing.* tokens, snapping off-grid values.

usage: scripts/spacing-tokens.py <kotlin source root>
Same call contexts as TokenGuardTest: padding(, PaddingValues(, spacedBy(, Spacer(Modifier.width|height|size(.
Prints a per-file count and every site whose value changed.
"""
import pathlib
import re
import sys

# nearest scale value, ties up (spec §4): 18 is nearer 16 than 24; 20 and 22 tie/lean to 24
TOKEN = {2: "xxs", 4: "xs", 6: "s", 8: "s", 10: "m", 12: "m", 14: "l", 16: "l", 18: "l",
         20: "xl", 22: "xl", 24: "xl", 28: "xxl", 32: "xxl", 40: "xxxl", 48: "xxxl"}
VALUE = {"xxs": 2, "xs": 4, "s": 8, "m": 12, "l": 16, "xl": 24, "xxl": 32, "xxxl": 48}
CALLS = [re.compile(p) for p in (
    r"(?<![\w])padding\(",
    r"\bPaddingValues\(",
    r"\bspacedBy\(",
    r"\bSpacer\(\s*(?:modifier\s*=\s*)?Modifier\s*\.\s*(?:width|height|size)\(",
)]
LIT = re.compile(r"(?<![\w.])(\d+)\.dp\b")
IMPORT = "import com.monostr.app.ui.theme.Spacing\n"


def matching(s, i):
    depth = 0
    for j in range(i, len(s)):
        if s[j] == "(":
            depth += 1
        elif s[j] == ")":
            depth -= 1
            if depth == 0:
                return j
    return None


def rewrite(path, src):
    spans = {}
    for call in CALLS:
        for m in call.finditer(src):
            o = m.end() - 1
            c = matching(src, o)
            if c is None:
                continue
            for lit in LIT.finditer(src, o + 1, c):
                n = int(lit.group(1))
                if n == 0:
                    continue
                if n not in TOKEN:
                    raise SystemExit(f"{path}: no token for {n}.dp at line {src.count(chr(10), 0, lit.start()) + 1}")
                spans[lit.start()] = (lit.end(), n)
    if not spans:
        return src, 0, []
    out, last, snapped = [], 0, []
    for start in sorted(spans):
        end, n = spans[start]
        tok = TOKEN[n]
        out.append(src[last:start] + f"Spacing.{tok}")
        if VALUE[tok] != n:
            snapped.append(f"{path}:{src.count(chr(10), 0, start) + 1}: {n} -> {VALUE[tok]}")
        last = end
    out.append(src[last:])
    return add_import("".join(out)), len(spans), snapped


def add_import(src):
    """Insert IMPORT next to its closest relative (longest shared package prefix), or after the package line."""
    if IMPORT in src or "import com.monostr.app.ui.theme.*\n" in src:
        return src
    lines = src.split("\n")
    name = IMPORT[len("import "):].strip()
    imps = [i for i, l in enumerate(lines) if l.startswith("import ")]
    if not imps:
        pkg = next(i for i, l in enumerate(lines) if l.startswith("package "))
        lines[pkg + 1:pkg + 1] = ["", IMPORT.strip()]
        return "\n".join(lines)

    def shared(other):
        n = 0
        for a, b in zip(name.split("."), other.split(".")):
            if a != b:
                break
            n += 1
        return n

    best = max(shared(lines[i][7:].strip()) for i in imps)
    cands = [i for i in imps if shared(lines[i][7:].strip()) == best]
    before = [i for i in cands if lines[i][7:].strip() < name]
    lines.insert(before[-1] + 1 if before else cands[0], IMPORT.strip())
    return "\n".join(lines)


def main():
    root = pathlib.Path(sys.argv[1])
    total, all_snapped = 0, []
    for p in sorted(root.rglob("*.kt")):
        if "/ui/theme/" in p.as_posix():
            continue
        src = p.read_text()
        new, n, snapped = rewrite(p.relative_to(root).as_posix(), src)
        if n:
            p.write_text(new)
            total += n
            all_snapped += snapped
            print(f"{n:4d} {p.relative_to(root)}")
    print(f"{total} literals rewritten; {len(all_snapped)} snapped:")
    print("\n".join(all_snapped))


if __name__ == "__main__":
    main()
