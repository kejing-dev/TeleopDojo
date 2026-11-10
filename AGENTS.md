# Teleop Dojo (遥操练习场) · io.embodit.teleopdojo

Factory app `teleop-dojo`, design package `spatial-app-factory/apps/teleop-dojo/design-package.json` (skeleton D,
STAGE_MIXED, one system window). A 1:1 UR5e on a virtual table; hold grip and the gripper follows your hand
(XRoboToolkit clutch mapping), squeeze the trigger to close it; three drills × three levels + free play; a scorecard.

## Build / run (kj-alienware, Git Bash)

```
tools/dev.sh build | test | install | launch | relaunch | log REGEX | crash     # DEV=emulator-5558 AVD=PICO_6_1_kj-alienware_3 by default
tools/cap.sh OUT.png          # PrintWindow capture of the emulator window (adb screencap tears)
python -I tools/strings.py    # copy table -> values / values-zh / values-en
PYTHONPATH=<miniaudio> python tools/sfx.py SRC   # cues from the freesound CC0 previews
python -I tools/loudness.py OUT.wav               # loudness evidence (rendered at default gain)
python -I tools/make_icon.py OUT.png              # 3D icon layers + 512 export
```

Emulator facts: PICO Emulator 6.1.0 reports the controller provider as `DEVICE_NOT_SUPPORTED` even in Right
Controller Mode (`tools/harness/emu_mode.ps1 -Mode right`), so rounds are driven with the debug hooks below
(`controller on` + `tcp` / `tcpl` / `grip` / `trigger`). The emulator throttles to 2-4 fps unless
`tools/harness/emu.ps1 fast on` (cap.sh does it).

## Code map

- `domain/` (pure JVM, unit-tested): `Teleop.kt` (XRoboToolkit port: geometry.py, ClutchMapper, GripLatch 0.9/0.8,
  gripper opening, follow mapping), `Ik.kt` (DLS IK, placo weights), `Arm.kt` (latch → clutch → workspace clamp → IK),
  `Workcell.kt` (table frame, Stage ↔ UR DH frame, workspace box), `Grasp.kt` (rule grasp, fall, stack), `Drill.kt`
  (one round), `Levels.kt` (9 fixed layouts, free-play generator, reachability), `Scoring.kt`, `LevelGate.kt`.
- `ui/scene/TdScene.kt`: the one ECS frame system: head + controllers → Arm → Drill → transforms.
- `ui/window/`: the system Planar window (MainWindow template, TabBar 关卡 / 随便练 / 成绩卡 / 设置, Toolbar while live).
- `ui/TdController.kt`: the state machine (design S01-S25); every key, controller button and debug command goes
  through `onEvent`.

## Debug broadcast (debug builds only)

```
adb -s emulator-5558 shell "am broadcast -a io.embodit.teleopdojo.DEBUG -p io.embodit.teleopdojo --es cmd 'goto S09'"
adb -s emulator-5558 shell "am broadcast -a io.embodit.teleopdojo.DEBUG -p io.embodit.teleopdojo --es cmd goto --es s S09"
tools/dev.sh cmd 'goto S09'
```

| cmd | what |
|---|---|
| `goto Sxx` | walk to a design state with the user's keys (S02 S03 S05-S20 S22-S25; S01 = cold start; S04 / S21 log why not) |
| `level reach\|pick\|stack easy\|standard\|picky` | open a drill's intro (S06) |
| `setup clutch\|follow [0.5\|1.0\|1.5]` | intro choices |
| `start` `end` `void` `pause` `resume` `restart` `again` `next` `switch` `homepose` | the window / Toolbar keys |
| `b` `a` `stick` | controller B / A / right stick press (XRoboToolkit layout) |
| `controller on\|off` | provider counts as SUPPORTED (emulator) |
| `grip v` / `trigger v` / `grip off` | teleop hand analog values (same input path) |
| `lost [off]` | teleop hand pose reads null (S22) |
| `tcp x y z` / `tcpl x y z` / `tcp off` | hold a TCP target: UR base frame (DH, metres, design qaHooks) / table-local (right hand) |
| `seed n` `free` | fixed free-play seed / open free play |
| `tab levels\|free\|card\|settings` `lang zh\|en\|auto` `hand left\|right` `vr on\|off` `mute [off]` `volume n` | settings |
| `place` / `place marker` / `place confirm\|cancel` | S02 |
| `aside` `recall` `close` `open` | workcell to front-right (D29 median yaw) / recall window / close window (S25) / reopen |
| `view DEG` | placements use the head turned DEG (+ left); `view 45` + `aside` brings the workcell in front of the camera |
| `coach` `exit` `where` `fit [reset]` | first-time tips / quit through the keys / pose dump / text overflow summary |

## Window spot (profile 48, QA #2 q2-1)

The system opens the main window 1.75 m straight ahead of the head and SDK 6.1 cannot place or move it, so every open
(start, the 打开面板 chip, [叫回面板], restore after −) waits in `WindowCtl`'s gate until the head is level AND
`WindowSpot.turnDeg` (domain/Layout.kt) is 0: the window frame + TabBar + Toolbar strip >= 10° from the table, base,
gripper workspace, targets, tip tag and the live arm. Meanwhile the Stage shows 往左看一点 / 往右看一点. Logs:
`TURN hint=1 need=29` (re-logged every 5°), `LEVEL ok ... spot clear -> open`, `WINDOW estimate why=first|reopen ... gap=13.2`.
To test on the emulator: face the workcell (camera yaw about -44, pitch about -3), `close` then tap the chip (or `recall`),
check the hint, turn left until it opens (about yaw -15 or more), `resume`. `view DEG` does not move this gate (it reads the real head).
Per-path unit tests: `PlacementTest` (launch / chip / recall / aside / restore / gate).

## Screenshot list for QA (per language: `lang zh`, `lang en`)

Cold start (S01 → S05), S03, S06 (each drill), S07 / S08 (`grip 1`), S09, S10, S11, S12, S13, S15, S14, S16, S17 +
restart confirm, S18, S19, S20, S02, S22, S23, S24, S25 (+ 打开面板 on the post), level hint (cold start with the
emulator camera pitched ±30°). Use `view 45` + `aside` to put the workcell in front of the camera.
