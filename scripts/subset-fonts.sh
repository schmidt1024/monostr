#!/usr/bin/env bash
# Downloads Inter and JetBrains Mono, subsets them to the app's scripts and writes them to res/font (Plan 6, spec 4.2).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FONT_DIR="$ROOT/android/app/src/main/res/font"
LIC_DIR="$ROOT/android/app/src/main/assets/licenses"
WORK="${TMPDIR:-/tmp}/monostr-fonts"
UNICODES="U+0000-00FF,U+0100-024F,U+0300-036F,U+0400-04FF,U+1E00-1EFF,U+2000-206F,U+20A0-20CF,U+2190-21FF,U+2200-22FF,U+2713-2714,U+2764"
mkdir -p "$FONT_DIR" "$LIC_DIR" "$WORK"
cd "$WORK"
[ -f Inter-4.1.zip ] || curl -fsSL -o Inter-4.1.zip https://github.com/rsms/inter/releases/download/v4.1/Inter-4.1.zip
[ -f JetBrainsMono-2.304.zip ] || curl -fsSL -o JetBrainsMono-2.304.zip https://github.com/JetBrains/JetBrainsMono/releases/download/v2.304/JetBrainsMono-2.304.zip
rm -rf inter mono && mkdir inter mono
unzip -q -o Inter-4.1.zip -d inter
unzip -q -o JetBrainsMono-2.304.zip -d mono
subset() { # src dst
  python3 -m fontTools.subset "$1" --output-file="$2" --unicodes="$UNICODES" --layout-features='*' --name-IDs='*' --drop-tables+=DSIG --no-hinting
}
subset "$(find inter -name 'Inter-Regular.ttf' | head -1)"  "$FONT_DIR/inter_regular.ttf"
subset "$(find inter -name 'Inter-Medium.ttf' | head -1)"   "$FONT_DIR/inter_medium.ttf"
subset "$(find inter -name 'Inter-SemiBold.ttf' | head -1)" "$FONT_DIR/inter_semibold.ttf"
subset "$(find mono -name 'JetBrainsMono-Regular.ttf' | head -1)" "$FONT_DIR/jetbrains_mono_regular.ttf"
cp "$(find inter -iname 'LICENSE*' | head -1)" "$LIC_DIR/OFL-Inter.txt"
cp "$(find mono -iname 'OFL*' | head -1)" "$LIC_DIR/OFL-JetBrainsMono.txt"
du -ch "$FONT_DIR"/*.ttf | tail -1
