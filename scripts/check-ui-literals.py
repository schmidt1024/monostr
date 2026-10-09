#!/usr/bin/env python3
"""Fails when a user-visible string literal is left in the :app UI/controller/session/work sources (Plan 6)."""
import pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parents[1] / "android/app/src/main/kotlin/com/monostr/app"
SCAN_DIRS = ["ui", "session", "work", "nav"]
LITERAL = re.compile(r'"((?:[^"\\]|\\.)*)"')
# ${...} and bare $identifier interpolations never carry natural-language text themselves;
# strip them before judging a literal so e.g. "${row * 3 + col + 1}. $w" (pure numbering/code,
# no words) is not mistaken for prose just because a Kotlin identifier contains letters.
INTERPOLATION = re.compile(r"\$\{[^}]*\}|\$[A-Za-z_][A-Za-z0-9_]*")
# literals that are not user-facing text
ALLOWED = re.compile(
    r"^(wss?://|https?://|monero:|nostrsigner|content://|nostr-|compose\?replyTo|media/\{noteId\}|media-urls\?u=|"
    r"[a-z0-9_.:\-/?={}$+]*$|[0-9. ]*$|%|\+$|<redacted|Monostr$|"
    r"watcher pubkey|Existing\(|[A-Z_]+$|replyTo$|intro$|pubkey$|id$|noteId$|InlinedApi$|"
    # third-party wallet brand names (proper nouns, like Monostr itself; never translated)
    r"Monerujo$|Cake Wallet$|"
    # blurhash base83 alphabet (spec-fixed data literal, not prose; the source has a
    # backslash-escaped $ for Kotlin string-interpolation, kept literally here)
    r"0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz\#\\\$%\*\+,\-\.:;=\?@\[\]\^_\{\|\}\~$)"
)
problems = []
for sub in SCAN_DIRS:
    for path in sorted((ROOT / sub).rglob("*.kt")):
        for lineno, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            stripped = line.strip()
            if stripped.startswith("//") or stripped.startswith("*") or stripped.startswith("/**"):
                continue
            for m in LITERAL.finditer(line):
                s = m.group(1)
                bare = INTERPOLATION.sub("", s)
                if not re.search(r"[A-Za-zÄÖÜäöüß]{2}", bare):
                    continue
                if ALLOWED.match(s) or ALLOWED.match(bare):
                    continue
                if f'testTag("{s}' in line or f'testTag("{s.split("$")[0]}' in line:
                    continue
                if "Log." in line or "require(" in line or "error(" in line or "check(" in line or "Exception(" in line:
                    continue
                # regex source lines (a `Regex(` call, or a continuation line marked `// regex`)
                # are pattern code, not user-facing text
                if "Regex(" in line or line.rstrip().endswith("// regex"):
                    continue
                # SQL source lines (a query or statement string marked `// sql`) are database
                # code, not user-facing text
                if line.rstrip().endswith("// sql"):
                    continue
                problems.append(f"{path.relative_to(ROOT)}:{lineno}: \"{s}\"")
if problems:
    print("User-visible literals left (move them to res/values/strings.xml):")
    print("\n".join(problems))
    sys.exit(1)
print("no UI literals left")
