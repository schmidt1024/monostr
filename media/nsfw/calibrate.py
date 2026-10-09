"""Scores a directory of harmless pictures and shows what each threshold would refuse.

    NSFW_MODEL=<path to marqo-nsfw-384.onnx> python calibrate.py <dir> [scores.csv]

The directory is read recursively; the first path component under <dir> is the
group (photos, borderline, graphics). Every picture is assumed harmless, so a
picture at or above a threshold is a false alarm at that threshold. The rule
(spec section 9): take the lowest threshold that refuses at most about 2 % of
the harmless pictures.
"""
import csv
import os
import sys

import server

THRESHOLDS = (0.20, 0.25, 0.30, 0.35, 0.40, 0.45, 0.50, 0.55, 0.60, 0.70)
EXTENSIONS = (".jpg", ".jpeg", ".png", ".gif", ".webp")


def percentile(sorted_values: list[float], p: float) -> float:
    if not sorted_values:
        return 0.0
    i = min(len(sorted_values) - 1, max(0, round(p / 100 * (len(sorted_values) - 1))))
    return sorted_values[i]


def main() -> None:
    if len(sys.argv) not in (2, 3):
        raise SystemExit(__doc__)
    root = sys.argv[1]
    session = server.load_session(os.environ.get("NSFW_MODEL", server.MODEL_PATH))
    rows: list[tuple[str, str, float]] = []
    for directory, _, files in sorted(os.walk(root)):
        for name in sorted(files):
            if not name.lower().endswith(EXTENSIONS):
                continue
            path = os.path.join(directory, name)
            rel = os.path.relpath(path, root)
            group = rel.split(os.sep)[0] if os.sep in rel else "."
            try:
                with open(path, "rb") as f:
                    value, _ = server.score(session, f.read())
            except Exception as e:
                print("unreadable:", rel, e)
                continue
            rows.append((group, rel, value))
    if not rows:
        raise SystemExit("no pictures found")
    if len(sys.argv) == 3:
        with open(sys.argv[2], "w", newline="") as f:
            writer = csv.writer(f)
            writer.writerow(("group", "file", "score"))
            writer.writerows((g, r, f"{v:.4f}") for g, r, v in rows)

    groups = sorted({g for g, _, _ in rows}) + ["all"]
    print(f"{'group':<12}{'n':>5}{'p50':>8}{'p90':>8}{'p95':>8}{'p99':>8}{'max':>8}")
    for group in groups:
        values = sorted(v for g, _, v in rows if group in (g, "all"))
        print(f"{group:<12}{len(values):>5}" + "".join(f"{percentile(values, p):>8.3f}" for p in (50, 90, 95, 99, 100)))
    print()
    print(f"{'threshold':<12}" + "".join(f"{g:>12}" for g in groups))
    for t in THRESHOLDS:
        cells = []
        for group in groups:
            values = [v for g, _, v in rows if group in (g, "all")]
            refused = sum(v >= t for v in values)
            cells.append(f"{refused:>4} {100 * refused / len(values):5.1f}%")
        print(f"{t:<12.2f}" + "".join(f"{c:>12}" for c in cells))
    print()
    print("highest scores:")
    for group, rel, value in sorted(rows, key=lambda r: -r[2])[:25]:
        print(f"  {value:.3f}  {rel}")


if __name__ == "__main__":
    main()
