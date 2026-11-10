#!/usr/bin/env bash
# PrintWindow capture of the emulator window (native-window-move §7.5: adb screencap tears after window drags).
#   tools/cap.sh OUT.png      (AVD PICO_6_1_kj-alienware_3 unless AVD is set)
AVD=${AVD:-PICO_6_1_kj-alienware_3}
H="$(dirname "$0")/harness"
hwnd=$(powershell -ExecutionPolicy Bypass -File "$H/emu.ps1" info -Avd $AVD | grep -o 'main hwnd=0x[0-9A-F]*' | cut -d= -f2)
powershell -ExecutionPolicy Bypass -File "$H/emu.ps1" fast on -Avd $AVD >/dev/null
sleep 3
powershell -ExecutionPolicy Bypass -File "$H/emu.ps1" capwin $hwnd "$(cygpath -w "$1")" -Avd $AVD | tail -1
