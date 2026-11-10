#!/usr/bin/env bash
# tools/cap.sh, then the title bar cut off and the client scaled to the 2560 x 2558 framebuffer space of pico-cli shots,
# so the contrast boxes fit both (PrintWindow always shows the current frame; pico-cli screencap can return a stale one).
set -e
"$(dirname "$0")/cap.sh" "$1.raw.png" >/dev/null
python - "$1.raw.png" "$1" <<'PY'
import sys
from PIL import Image
im = Image.open(sys.argv[1]).convert("RGB")
im.crop((0, 48, im.width, im.height)).resize((2560, 2558), Image.BICUBIC).save(sys.argv[2])
PY
rm -f "$1.raw.png"; echo "$1"
