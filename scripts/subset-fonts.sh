#!/usr/bin/env bash
# Downloads Inter, JetBrains Mono and Newsreader (article reader, 2026-10-30), subsets them to the app's scripts and writes them to res/font (Plan 6, spec 4.2).
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
NEWSREADER="https://raw.githubusercontent.com/google/fonts/8b0a1d0f5983c89bc2b93f1b5fb55f9e252744b5/ofl/newsreader"
[ -f Newsreader.ttf ] || curl -fsSL -o Newsreader.ttf "$NEWSREADER/Newsreader%5Bopsz,wght%5D.ttf"
[ -f Newsreader-Italic.ttf ] || curl -fsSL -o Newsreader-Italic.ttf "$NEWSREADER/Newsreader-Italic%5Bopsz,wght%5D.ttf"
[ -f OFL-Newsreader.txt ] || curl -fsSL -o OFL-Newsreader.txt "$NEWSREADER/OFL.txt"
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
# Newsreader ships as a variable font; the reader needs three static text instances (optical size 16)
newsreader() { # src wght dst
  python3 -m fontTools.varLib.instancer "$1" wght="$2" opsz=16 --static --output=newsreader-tmp.ttf
  subset newsreader-tmp.ttf "$3"
}
newsreader Newsreader.ttf 400 "$FONT_DIR/newsreader_regular.ttf"
newsreader Newsreader.ttf 500 "$FONT_DIR/newsreader_medium.ttf"
newsreader Newsreader-Italic.ttf 400 "$FONT_DIR/newsreader_italic.ttf"
cp "$(find inter -iname 'LICENSE*' | head -1)" "$LIC_DIR/OFL-Inter.txt"
cp "$(find mono -iname 'OFL*' | head -1)" "$LIC_DIR/OFL-JetBrainsMono.txt"
cp OFL-Newsreader.txt "$LIC_DIR/OFL-Newsreader.txt"
du -ch "$FONT_DIR"/*.ttf | tail -1
