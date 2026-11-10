package io.embodit.teleopdojo.debug

import android.os.Handler
import android.os.Looper
import io.embodit.teleopdojo.domain.Difficulty
import io.embodit.teleopdojo.domain.LevelId
import io.embodit.teleopdojo.domain.MappingMode
import io.embodit.teleopdojo.domain.Vec3
import io.embodit.teleopdojo.platform.TdLog
import io.embodit.teleopdojo.ui.Lang
import io.embodit.teleopdojo.ui.Screen
import io.embodit.teleopdojo.ui.Tab
import io.embodit.teleopdojo.ui.TdController
import io.embodit.teleopdojo.ui.Ui
import io.embodit.teleopdojo.ui.components.TextFit
import io.embodit.teleopdojo.ui.debug.Probe
import io.embodit.teleopdojo.ui.window.PANEL_ID
import io.embodit.teleopdojo.ui.window.PANEL_TAG
import io.embodit.teleopdojo.ui.window.WindowCtl

/**
 * Debug builds only (QA hooks, design qaHooks). Commands go through the same events as the window keys
 * (TdController.onEvent) or feed the same inputs as the controller (grip / trigger values, a held TCP target), never an
 * app state directly (factory rule 2026-10-29). Usage and the screenshot list: AGENTS.md.
 */
object DebugCommands {
    private val main = Handler(Looper.getMainLooper())

    private fun ev(e: Ui) = TdController.onEvent(e)

    private fun state() = TdController.state.value

    private fun scene() = Probe.host?.scene()

    private fun log(msg: String) = TdLog.i("CMD $msg state=${state().designState}")

    /** Back to 首页 (S03) with the user's own keys. */
    private fun home() {
        val s = state()
        if (s.exitConfirm) ev(Ui.QuitCancel)
        if (s.resetConfirm) ev(Ui.ResetCancel)
        if (s.aboutOpen) ev(Ui.About)
        if (s.screen == Screen.PLACING) ev(Ui.PlaceCancel)
        if (s.screen == Screen.COACH) ev(Ui.CoachSkip)
        if (s.inRound || s.screen == Screen.RESULT || s.screen == Screen.INTRO) ev(Ui.ToHome)
        // a running round asks first (D44): answer like the user would
        if (state().roundConfirm != null) ev(Ui.ConfirmYes)
        ev(Ui.Tab(Tab.LEVELS))
    }

    private fun level(id: String?): LevelId = when (id?.lowercase()) { "pick" -> LevelId.PICK; "stack" -> LevelId.STACK; "free" -> LevelId.FREE; else -> LevelId.REACH }

    private fun diff(d: String?): Difficulty = when (d?.lowercase()) { "standard" -> Difficulty.STANDARD; "picky" -> Difficulty.PICKY; else -> Difficulty.EASY }

    private fun later(ms: Long, f: () -> Unit) = main.postDelayed({ runCatching(f).onFailure { TdLog.w("CMD later failed: $it") } }, ms)

    /** Opens a drill and starts it; [then] runs once the countdown is over (S07). */
    private fun live(l: LevelId, d: Difficulty, then: () -> Unit = {}) {
        // the emulator has no controller provider: rounds need `controller on` (logged, debug builds only)
        if (scene()?.controllers?.debugPresent == false) {
            scene()?.controllers?.debugPresent = true
            log("controller on (emulator: provider DEVICE_NOT_SUPPORTED)")
        }
        home()
        ev(Ui.OpenLevel(l))
        ev(Ui.Setup("debug") { it.copy(difficulty = d) })
        // one frame for the controller state to reach the app
        later(1500) { ev(Ui.Start) }
        later(5200) {
            then()
            log("live ready")
        }
    }

    fun run(cmd: String, a: List<String>) {
        val sc = scene()
        when (cmd) {
            "goto" -> goto(a.getOrNull(0)?.uppercase() ?: "S03")
            "level" -> {
                home()
                ev(Ui.OpenLevel(level(a.getOrNull(0))))
                ev(Ui.Setup("debug") { it.copy(difficulty = diff(a.getOrNull(1))) })
            }
            "setup" -> {
                // setup clutch|follow [scale]
                val m = if (a.getOrNull(0) == "follow") MappingMode.FOLLOW else MappingMode.CLUTCH
                val k = a.getOrNull(1)?.toFloatOrNull()
                ev(Ui.Setup("debug") { it.copy(mapping = m, scale = k ?: it.scale) })
            }
            "start" -> ev(Ui.Start)
            "end" -> ev(Ui.End)
            "void" -> ev(Ui.Void)
            "pause" -> ev(Ui.Pause)
            "resume" -> ev(Ui.Resume)
            "restart" -> ev(Ui.Restart)
            "b" -> ev(Ui.ButtonB)
            "a" -> ev(Ui.ButtonA)
            "stick" -> ev(Ui.StickPress)
            "homepose" -> ev(Ui.GoHomePose)
            "again" -> ev(Ui.Again)
            "next" -> ev(Ui.NextLevel)
            "switch" -> ev(Ui.SwitchMapping)
            "free" -> {
                home()
                ev(Ui.Tab(Tab.FREE))
                ev(Ui.FreeNext)
            }
            "seed" -> TdController.debugSeed(a.getOrNull(0)?.toLongOrNull() ?: 1L)
            "tab" -> ev(Ui.Tab(when (a.getOrNull(0)) { "free" -> Tab.FREE; "card" -> Tab.CARD; "settings" -> Tab.SETTINGS; else -> Tab.LEVELS }))
            // the teleop hand's analog values (same path as the controller); "grip off" hands them back
            "grip" -> sc?.controllers?.debugGrip = a.getOrNull(0)?.toFloatOrNull()
            "trigger" -> sc?.controllers?.debugTrigger = a.getOrNull(0)?.toFloatOrNull()
            "controller" -> sc?.controllers?.debugPresent = a.getOrNull(0) != "off"
            "lost" -> sc?.controllers?.debugLost = a.getOrNull(0) != "off"
            // a TCP target held as if by a hand: DH base frame metres (design qaHooks) or table-local (tcpl)
            "tcp" -> TdController.arm.debugTarget = if (a.isEmpty() || a[0] == "off") null else doubleArrayOf(a[0].toDouble(), a[1].toDouble(), a[2].toDouble())
            "tcpl" -> {
                val c = sc?.cell ?: return log("tcpl: no workcell")
                TdController.arm.debugTarget = if (a.isEmpty() || a[0] == "off") null else c.toDh(c.toStage(c.hand(Vec3(a[0].toFloat(), a[1].toFloat(), a[2].toFloat()))))
            }
            "lang" -> ev(Ui.SetPrefs("lang") { it.copy(lang = when (a.getOrNull(0)) { "zh" -> Lang.ZH; "en" -> Lang.EN; else -> Lang.SYSTEM }) })
            "hand" -> ev(Ui.SetPrefs("hand") { it.copy(leftHanded = a.getOrNull(0) == "left") })
            "vr" -> ev(Ui.SetPrefs("vr") { it.copy(vr = a.getOrNull(0) != "off") })
            "mute" -> ev(Ui.SetPrefs("mute") { it.copy(muted = a.getOrNull(0) != "off") })
            "volume" -> ev(Ui.SetPrefs("volume") { it.copy(volume = a.getOrNull(0)?.toIntOrNull() ?: 60) })
            "recall" -> ev(Ui.RecallPanel)
            "aside" -> ev(Ui.WorkcellAside)
            "place" -> when (a.getOrNull(0)) {
                "confirm" -> ev(Ui.PlaceConfirm)
                "cancel" -> ev(Ui.PlaceCancel)
                "marker" -> TdController.onMarker(true)
                else -> {
                    home()
                    ev(Ui.PlaceStart)
                }
            }
            "close" -> runCatching { WindowCtl.navigator?.closeWindowContainer(PANEL_ID, PANEL_TAG) }
            "open" -> WindowCtl.reopen("debug")
            "view" -> sc?.viewYawDeg = a.getOrNull(0)?.toFloatOrNull() ?: 0f
            "coach" -> {
                home()
                ev(Ui.SetPrefs("coach") { it.copy(coachSeen = false) })
                TdController.debugCoach()
            }
            "exit" -> {
                ev(Ui.Tab(Tab.SETTINGS))
                ev(Ui.QuitAsk)
                ev(Ui.QuitYes)
            }
            "head" -> {
                sc?.debugHeadShift = Vec3(a.getOrNull(0)?.toFloatOrNull() ?: 0f, a.getOrNull(1)?.toFloatOrNull() ?: 0f, a.getOrNull(2)?.toFloatOrNull() ?: 0f)
                sc?.debugHeadPitchDeg = a.getOrNull(3)?.toFloatOrNull() ?: 0f
            }
            "bounds" -> bounds(a.getOrNull(0) ?: "")
            "about" -> ev(Ui.About)
            "table" -> ev(Ui.SetPrefs("table") { it.copy(standing = a.getOrNull(0) == "stand", tableAdjustCm = 0) })
            "where" -> TdLog.i("WHERE ${sc?.where()}")
            "fit" -> if (a.getOrNull(0) == "reset") TextFit.reset() else TdLog.i(TextFit.summary())
            else -> return log("unknown $cmd")
        }
        log("$cmd $a done")
    }

    /**
     * `bounds [label]`: the whitespace of the window page as shown (factory build.md 空白的量法): the content block is the
     * first to the last visible control; blank = (block top to window top + block bottom to window bottom) / window height;
     * the largest internal gap between merged control rows. One `BOUNDS` line per call.
     */
    private fun bounds(label: String) {
        val size = Probe.sizeOf("window") ?: return log("bounds: no window controls")
        val h = size.second.toFloat()
        val rows = Probe.controlsOf("window").values.filter { it.h > 0f && it.cy - it.h / 2f >= -1f && it.cy + it.h / 2f <= h + 1f }.map { (it.cy - it.h / 2f) to (it.cy + it.h / 2f) }.sortedBy { it.first }
        if (rows.isEmpty()) return log("bounds: empty")
        var gap = 0f
        var end = rows.first().second
        for ((t, b) in rows.drop(1)) {
            if (t > end) gap = maxOf(gap, t - end)
            end = maxOf(end, b)
        }
        val top = rows.first().first
        val blank = (top + (h - end)) / h * 100f
        TdLog.i(String.format(java.util.Locale.US, "BOUNDS label=%s state=%s lang=%s h=%.0f top=%.0f bottom=%.0f blank=%.1f%% maxGap=%.1f%% controls=%d", label, state().designState, state().prefs.lang, h, top, end, blank, gap / h * 100f, rows.size))
    }

    /** `goto Sxx`: walks to a design state with the keys / inputs a user would use. */
    private fun goto(s: String) {
        val sc = scene()
        val c = sc?.cell
        fun tl(x: Float, y: Float, z: Float) {
            if (c != null) TdController.arm.debugTarget = c.toDh(c.toStage(c.hand(Vec3(x, y, z))))
        }
        TdController.arm.debugTarget = null
        sc?.controllers?.debugGrip = null
        sc?.controllers?.debugTrigger = null
        sc?.controllers?.debugLost = false
        when (s) {
            "S02" -> {
                home()
                ev(Ui.PlaceStart)
            }
            "S03" -> home()
            "S04" -> log("S04 follows the controller provider (turn the emulator controllers off)")
            "S05" -> run("coach", emptyList())
            "S06" -> {
                home()
                ev(Ui.OpenLevel(LevelId.PICK))
            }
            "S07" -> live(LevelId.PICK, Difficulty.EASY)
            "S08" -> live(LevelId.REACH, Difficulty.EASY) { sc?.controllers?.debugGrip = 1f }
            "S09" -> live(LevelId.PICK, Difficulty.EASY) {
                // over the block with the gripper open, down onto it, close, lift
                sc?.controllers?.debugTrigger = 0f
                tl(0.30f, 0.12f, 0.10f)
                later(3000) { tl(0.30f, 0.03f, 0.10f) }
                later(7000) { sc?.controllers?.debugTrigger = 1f }
                later(9000) { tl(0.30f, 0.15f, 0.10f) }
            }
            "S10" -> live(LevelId.REACH, Difficulty.EASY) {
                tl(0.10f, 0.10f, 0.10f)
                later(1500) { tl(0.10f, -0.05f, 0.10f) }
            }
            "S11" -> live(LevelId.REACH, Difficulty.EASY) { tl(0.45f, 0.62f, -0.45f) }
            "S12" -> live(LevelId.REACH, Difficulty.EASY) { tl(0.0f, 0.62f, -0.20f) }
            "S13" -> live(LevelId.REACH, Difficulty.EASY) {
                tl(0.00f, 0.15f, 0.05f)
                later(1500) { ev(Ui.End) }
            }
            "S14" -> {
                home()
                ev(Ui.Tab(Tab.CARD))
            }
            "S15" -> live(LevelId.REACH, Difficulty.EASY) {
                tl(0.0f, 0.15f, 0.05f)
                later(1200) { ev(Ui.End) }
                later(1800) {
                    ev(Ui.SwitchMapping)
                    ev(Ui.Start)
                }
                later(5600) { tl(0.2f, 0.10f, 0.12f) }
                later(7000) { ev(Ui.End) }
            }
            "S16" -> {
                home()
                ev(Ui.Tab(Tab.FREE))
                ev(Ui.FreeNext)
            }
            "S17" -> live(LevelId.STACK, Difficulty.EASY) { ev(Ui.Pause) }
            "S18" -> {
                home()
                ev(Ui.Tab(Tab.SETTINGS))
            }
            "S19" -> {
                home()
                ev(Ui.Tab(Tab.SETTINGS))
                ev(Ui.QuitAsk)
            }
            "S20" -> {
                home()
                ev(Ui.Tab(Tab.SETTINGS))
                ev(Ui.ResetAsk)
            }
            "S21" -> log("S21 needs your head within 25 cm of the arm (move the emulator camera into it)")
            "S22" -> live(LevelId.REACH, Difficulty.EASY) {
                sc?.controllers?.debugGrip = 1f
                later(800) { sc?.controllers?.debugLost = true }
            }
            "S23" -> {
                home()
                ev(Ui.SetPrefs("vr") { it.copy(vr = true) })
            }
            "S24" -> live(LevelId.REACH, Difficulty.EASY) { tl(-0.40f, 0.60f, 0.35f) }
            "S25" -> {
                home()
                runCatching { WindowCtl.navigator?.closeWindowContainer(PANEL_ID, PANEL_TAG) }
            }
            else -> log("goto $s: not a reachable state")
        }
    }
}
