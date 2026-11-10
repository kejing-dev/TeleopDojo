#!/usr/bin/env bash
# Whitespace + overflow sweep (factory build.md: 空白的量法, D13 overflow): every page and state in zh and en; logcat is
# streamed to a file while it runs (not read from the ring buffer). Needs `controller on` support (debug build).
#   tools/sweep.sh OUT.log
OUT=${1:-.scratch/sweep.log}
D="$(dirname "$0")/dev.sh"
c() { "$D" cmd "$1" >/dev/null; }
adb -s ${DEV:-emulator-5558} logcat -c
adb -s ${DEV:-emulator-5558} logcat -v time -s TeleopDojo:V > "$OUT" &
LP=$!
c 'fit reset'; c 'controller on'
for lang in zh en; do
  c "lang $lang"; sleep 2
  m() { sleep ${2:-3}; c "bounds $1"; }
  c 'goto S03'; m home
  c 'coach'; m coach1; c 'goto S03'
  for lv in reach pick stack; do for d in easy standard picky; do c "level $lv $d"; m "intro_${lv}_$d" 2; done; done
  c 'setup follow'; m intro_follow 2; c 'setup clutch 1.0'
  c 'goto S07'; m live 7
  c 'pause'; m paused 2
  c 'restart'; m confirm_restart 2
  c 'goto S13'; m result 9
  c 'goto S14'; m card
  c 'goto S16'; m free_intro
  c 'goto S03'; c 'tab free'; m free_home
  c 'goto S18'; m settings
  c 'goto S19'; m settings_exit
  c 'goto S20'; m settings_reset
  c 'goto S18'; c 'about'; m settings_about
  c 'about'; c 'goto S02'; m placing
  c 'goto S03'
done
c 'lang auto'
sleep 3; c 'fit'; sleep 2
kill $LP
grep -E "BOUNDS|OVERFLOW |FIT checked" "$OUT"
