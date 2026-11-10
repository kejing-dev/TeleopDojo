package io.embodit.teleopdojo.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import io.embodit.teleopdojo.domain.Arm
import io.embodit.teleopdojo.domain.Difficulty
import io.embodit.teleopdojo.domain.Drill
import io.embodit.teleopdojo.domain.DrillEv
import io.embodit.teleopdojo.domain.FreePlay
import io.embodit.teleopdojo.domain.LevelId
import io.embodit.teleopdojo.domain.Levels
import io.embodit.teleopdojo.domain.MappingMode
import io.embodit.teleopdojo.domain.Miss
import io.embodit.teleopdojo.domain.Oops
import io.embodit.teleopdojo.domain.RoundResult
import io.embodit.teleopdojo.domain.TeleopConfig
import io.embodit.teleopdojo.domain.Workcell
import io.embodit.teleopdojo.platform.Sound
import io.embodit.teleopdojo.platform.Store
import io.embodit.teleopdojo.platform.TdLog
import io.embodit.teleopdojo.ui.window.WindowCtl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Everything that changes the app state: panel keys, controller buttons, the debug receiver (one path, profile D18). */
sealed class Ui {
    data class Tab(val tab: io.embodit.teleopdojo.ui.Tab) : Ui()
    object CoachNext : Ui()
    object CoachSkip : Ui()
    data class OpenLevel(val level: LevelId) : Ui()
    data class Setup(val why: String, val f: (RoundSetup) -> RoundSetup) : Ui()
    object Back : Ui()
    object Start : Ui()
    object End : Ui()
    object Void : Ui()
    object Pause : Ui()
    object Resume : Ui()
    object Restart : Ui()
    object ConfirmYes : Ui()
    object ConfirmNo : Ui()
    object GoHomePose : Ui()
    object ToHome : Ui()
    object Again : Ui()
    object SwitchMapping : Ui()
    object NextLevel : Ui()
    object ShowCard : Ui()
    object FreeNext : Ui()
    data class SetPrefs(val why: String, val f: (Prefs) -> Prefs) : Ui()
    object PlaceStart : Ui()
    object PlaceConfirm : Ui()
    object PlaceCancel : Ui()
    object WorkcellAside : Ui()
    object RecallPanel : Ui()
    object ResetAsk : Ui()
    object ResetCancel : Ui()
    object ResetYes : Ui()
    object QuitAsk : Ui()
    object QuitCancel : Ui()
    object QuitYes : Ui()
    object About : Ui()
    data class Background(val on: Boolean) : Ui()
    /** Controller buttons of the teleop hand (XRoboToolkit layout: B start / end, A pause, stick press void; left hand Y / X). */
    object ButtonB : Ui()
    object ButtonA : Ui()
    object StickPress : Ui()
}

/**
 * The state machine (design states S01-S25, interactions I1-I20). The scene's frame system steps [arm] and [drill] and
 * reports back through the `on*` callbacks; everything the user does arrives in [onEvent]. Main thread only.
 */
object TdController {
    private val _state = MutableStateFlow(TdUiState())
    val state: StateFlow<TdUiState> = _state

    /** The teleoperated arm (lives as long as the process; the scene steps it). */
    val arm = Arm()

    /** The round being played (COUNTDOWN .. RESULT). */
    var drill: Drill? = null
        private set

    var activity: Activity? = null
    private var store: Store? = null
    private var sound: Sound? = null
    private val main = Handler(Looper.getMainLooper())
    private var started = false
    private var hintUntil = 0L

    /** The scene: the current workcell (needed to lay out a round) and hooks for haptics / placement. */
    var cellProvider: (() -> Workcell?)? = null
    var quitHook: (() -> Unit)? = null
    var placementHook: ((String) -> Unit)? = null
    var haptic: ((String) -> Unit)? = null

    val soundOrNull: Sound?
        get() = sound

    fun start(context: Context) {
        activity = findActivity(context)
        if (started) return
        started = true
        val st = Store(context.applicationContext)
        store = st
        val prefs = st.prefs()
        sound = Sound(context.applicationContext).also { it.setVolume(prefs.volume, prefs.muted); it.start() }
        _state.value = TdUiState(prefs = prefs, best = st.bests(), mapStats = st.mapStats(), levelPlays = st.levelPlays(), freeRound = st.freeRound)
        TdLog.i("CONTROLLER start state=${_state.value.designState} prefs=$prefs")
    }

    private fun findActivity(c: Context): Activity? {
        var x: Context? = c
        while (x != null) {
            if (x is Activity) return x
            x = (x as? ContextWrapper)?.baseContext
        }
        return null
    }

    private fun set(why: String, f: (TdUiState) -> TdUiState) {
        val before = _state.value
        val after = f(before)
        if (after == before) return
        _state.value = after
        if (after.designState != before.designState || after.screen != before.screen || after.tab != before.tab) {
            TdLog.i("STATE ${after.designState} screen=${after.screen} tab=${after.tab} setup=${after.setup} why=$why")
        }
    }

    /** The teleop config of the current round. */
    fun config(s: TdUiState = _state.value): TeleopConfig {
        val hand = if (s.prefs.leftHanded) "left" else "right"
        return TeleopConfig(
            poseSource = "${hand}_controller",
            controlTrigger = "${hand}_grip",
            gripperTrigger = "${hand}_trigger",
            controlMode = s.setup.control(s.prefs),
            scaleFactor = if (s.setup.mapping == MappingMode.FOLLOW) 1.0f else s.setup.scale,
            mapping = s.setup.mapping,
        )
    }

    // ============================================================================================== scene callbacks

    /** S01 -> S05 (first run) / S03 once the head pose and the scene are ready. */
    fun onSceneReady() {
        if (_state.value.screen != Screen.STARTING) return
        set("ready") { it.copy(screen = if (it.prefs.coachSeen) Screen.HOME else Screen.COACH, headWaiting = false) }
    }

    fun onHeadWaiting(waiting: Boolean) = set("headWaiting") { it.copy(headWaiting = waiting) }

    fun onControllerState(c: ControllerState) {
        if (c == _state.value.controller) return
        set("controller $c") { it.copy(controller = c) }
    }

    /** Live snapshot from the frame system (only sent when it changes). */
    fun onLive(live: Live) = set("live") { s ->
        val hint = if (System.currentTimeMillis() < hintUntil) s.hint else liveHint(s, live)
        s.copy(live = live, hint = hint)
    }

    private fun liveHint(s: TdUiState, l: Live): Hint = when {
        l.trackingLost -> Hint.LOST
        l.jointLimit >= 0 -> Hint.JOINT
        l.singular -> Hint.SINGULAR
        s.screen == Screen.LIVE && l.towerHeight > 0 && l.towerHeight >= (drill?.blocks?.size ?: 99) -> Hint.TOWER
        l.holding -> Hint.GOT_IT
        s.screen == Screen.LIVE && !l.clutched -> Hint.HOLD_GRIP
        else -> Hint.NONE
    }

    private fun flash(h: Hint, ms: Long = 1800L) {
        hintUntil = System.currentTimeMillis() + ms
        set("hint $h") { it.copy(hint = h) }
    }

    fun onDrillEvents(evs: List<DrillEv>) {
        for (e in evs) {
            when (e) {
                is DrillEv.Lit -> { sound?.lit(); haptic?.invoke("press") }
                is DrillEv.Grabbed -> { sound?.grab(); haptic?.invoke("press"); flash(Hint.GOT_IT) }
                is DrillEv.Almost -> flash(if (e.loosened) Hint.LOOSENED else when (e.miss) { Miss.OVERLAP -> Hint.ALMOST_IN; Miss.ANGLE -> Hint.ALMOST_TURN; Miss.SQUEEZE -> Hint.ALMOST_SQUEEZE })
                is DrillEv.Dropped -> Unit
                is DrillEv.Settled -> { sound?.settle(); if (e.placed) flash(Hint.PLACED, 1200) }
                is DrillEv.Slipped -> flash(Hint.SLIPPED)
                is DrillEv.Oopsed -> when (e.kind) {
                    Oops.TABLE -> { sound?.bump(); haptic?.invoke("table"); flash(Hint.TABLE, 1500) }
                    Oops.JOINT -> haptic?.invoke("joint")
                    Oops.SINGULAR -> Unit
                    Oops.SLIP -> Unit
                }
                DrillEv.Done -> main.post { finishRound("done") }
            }
        }
    }

    /** The window was closed (S25) or the app went to the background: a running round pauses. */
    fun onWindowClosed() {
        if (_state.value.screen == Screen.LIVE) onEvent(Ui.Pause)
    }

    // ============================================================================================== events

    fun onEvent(e: Ui) {
        val s = _state.value
        TdLog.i("UI $e state=${s.designState}")
        when (e) {
            is Ui.Tab -> {
                if (s.screen == Screen.LIVE || s.screen == Screen.COUNTDOWN) pause("tab")
                if (s.screen == Screen.PLACING) return
                set("tab") { it.copy(tab = e.tab, exitConfirm = false, resetConfirm = false) }
            }
            Ui.CoachNext -> if (s.screen == Screen.COACH) {
                if (s.coachStep >= 2) endCoach() else set("coach") { it.copy(coachStep = it.coachStep + 1) }
            }
            Ui.CoachSkip -> if (s.screen == Screen.COACH) endCoach()
            is Ui.OpenLevel -> if (!s.inRound && s.screen != Screen.PLACING) set("open ${e.level}") {
                it.copy(screen = Screen.INTRO, tab = Tab.LEVELS, fromFree = false, setup = it.setup.copy(level = e.level), result = null, compare = null)
            }
            is Ui.Setup -> if (s.screen == Screen.INTRO) set("setup ${e.why}") { it.copy(setup = e.f(it.setup)) }
            Ui.Back -> if (s.screen == Screen.INTRO || s.screen == Screen.RESULT) set("back") { it.copy(screen = Screen.HOME) }
            Ui.Start, Ui.Again -> if (s.screen == Screen.INTRO || s.screen == Screen.RESULT) startCountdown()
            Ui.End -> if (s.screen == Screen.LIVE || s.screen == Screen.PAUSED) finishRound("end")
            Ui.Void -> if (s.inRound) voidRound()
            Ui.Pause -> if (s.screen == Screen.LIVE || s.screen == Screen.COUNTDOWN) pause("pause")
            Ui.Resume -> if (s.screen == Screen.PAUSED) {
                // S17 -> S07: the clutch reference was dropped; grab again (rules: no old reference)
                arm.dropClutch()
                set("resume") { it.copy(screen = Screen.LIVE, tab = if (it.fromFree) Tab.FREE else Tab.LEVELS, roundConfirm = null) }
                flash(Hint.PRESS_GRIP_AGAIN, 2500)
            }
            // restarting or leaving a running round loses it: pause and ask first (D44)
            Ui.Restart -> when {
                s.screen == Screen.RESULT -> startCountdown()
                s.inRound -> {
                    if (s.screen != Screen.PAUSED) pause("restart?")
                    set("confirm restart") { it.copy(roundConfirm = "restart") }
                }
            }
            Ui.ConfirmYes -> when (s.roundConfirm) {
                "restart" -> {
                    set("restart yes") { it.copy(roundConfirm = null) }
                    startCountdown()
                }
                "home" -> {
                    set("home yes") { it.copy(roundConfirm = null) }
                    leaveRound()
                }
                else -> Unit
            }
            Ui.ConfirmNo -> set("confirm no") { it.copy(roundConfirm = null) }
            Ui.GoHomePose -> if (s.screen != Screen.PLACING) arm.goHome()
            Ui.ToHome -> if (s.inRound) {
                if (s.screen != Screen.PAUSED) pause("home?")
                set("confirm home") { it.copy(roundConfirm = "home") }
            } else leaveRound()
            Ui.SwitchMapping -> if (s.screen == Screen.RESULT) set("switchMapping") {
                it.copy(screen = Screen.INTRO, setup = it.setup.copy(mapping = if (it.setup.mapping == MappingMode.CLUTCH) MappingMode.FOLLOW else MappingMode.CLUTCH), result = null, compare = null)
            }
            Ui.NextLevel -> if (s.screen == Screen.RESULT) {
                val next = when (s.setup.level) { LevelId.REACH -> LevelId.PICK; LevelId.PICK -> LevelId.STACK; else -> null }
                if (next == null) onEvent(Ui.ShowCard) else set("next") { it.copy(screen = Screen.INTRO, setup = it.setup.copy(level = next), result = null, compare = null) }
            }
            Ui.ShowCard -> set("card") { it.copy(screen = Screen.HOME, tab = Tab.CARD, result = null, compare = null) }
            Ui.FreeNext -> if (!s.inRound) set("free") {
                it.copy(screen = Screen.INTRO, tab = Tab.FREE, fromFree = true, setup = it.setup.copy(level = LevelId.FREE), result = null, compare = null)
            }
            is Ui.SetPrefs -> {
                val p = e.f(s.prefs)
                if (p == s.prefs) return
                store?.savePrefs(p)
                sound?.setVolume(p.volume, p.muted)
                set("prefs ${e.why}") { it.copy(prefs = p) }
                if (p.leftHanded != s.prefs.leftHanded || p.tableHeight != s.prefs.tableHeight) placementHook?.invoke("prefs ${e.why}")
            }
            Ui.PlaceStart -> if (!s.inRound && s.screen != Screen.PLACING) {
                set("placeStart") { it.copy(screen = Screen.PLACING, placingFrom = it.screen, markerSet = false) }
                placementHook?.invoke("start")
            }
            Ui.PlaceConfirm -> if (s.screen == Screen.PLACING && s.markerSet) {
                placementHook?.invoke("confirm")
                set("placeConfirm") { it.copy(screen = it.placingFrom, markerSet = false) }
            }
            Ui.PlaceCancel -> if (s.screen == Screen.PLACING) {
                placementHook?.invoke("cancel")
                set("placeCancel") { it.copy(screen = it.placingFrom, markerSet = false) }
            }
            Ui.WorkcellAside -> if (!s.inRound && s.screen != Screen.PLACING) placementHook?.invoke("aside")
            Ui.RecallPanel -> set("recall") { it.copy(panelEpoch = it.panelEpoch + 1) }
            Ui.ResetAsk -> if (!s.inRound) set("resetAsk") { it.copy(resetConfirm = true, exitConfirm = false) }
            Ui.ResetCancel -> set("resetCancel") { it.copy(resetConfirm = false) }
            Ui.ResetYes -> {
                store?.clearProgress()
                set("resetYes") { it.copy(resetConfirm = false, best = emptyMap(), mapStats = emptyMap(), levelPlays = emptyMap(), freeRound = 1) }
            }
            Ui.QuitAsk -> set("quitAsk") { it.copy(exitConfirm = true, resetConfirm = false) }
            Ui.QuitCancel -> set("quitCancel") { it.copy(exitConfirm = false) }
            Ui.QuitYes -> quit()
            Ui.About -> set("about") { it.copy(aboutOpen = !it.aboutOpen) }
            is Ui.Background -> if (e.on && (s.screen == Screen.LIVE || s.screen == Screen.COUNTDOWN)) pause("background")
            Ui.ButtonB -> when (s.screen) {
                Screen.INTRO, Screen.RESULT -> if (s.controller == ControllerState.OK) startCountdown()
                Screen.LIVE, Screen.PAUSED -> finishRound("B")
                else -> Unit
            }
            Ui.ButtonA -> when (s.screen) {
                Screen.LIVE, Screen.COUNTDOWN -> pause("A")
                Screen.PAUSED -> onEvent(Ui.Resume)
                else -> Unit
            }
            Ui.StickPress -> if (s.inRound) voidRound()
        }
    }

    private fun leaveRound() {
        countdownJob?.let { main.removeCallbacks(it) }
        drill = null
        arm.dropClutch()
        set("toHome") { it.copy(screen = Screen.HOME, tab = if (it.fromFree) Tab.FREE else Tab.LEVELS, result = null, compare = null, roundConfirm = null) }
    }

    private fun endCoach() {
        val p = _state.value.prefs.copy(coachSeen = true)
        store?.savePrefs(p)
        set("coachDone") { it.copy(screen = Screen.HOME, prefs = p, coachStep = 0) }
    }

    // ============================================================================================== rounds

    private var countdownJob: Runnable? = null

    private fun startCountdown() {
        val s = _state.value
        if (s.controller != ControllerState.OK && s.controller != ControllerState.PENDING) {
            TdLog.i("ROUND start refused: controller ${s.controller}")
            return
        }
        val cell = cellProvider?.invoke() ?: return TdLog.w("ROUND start: no workcell yet")
        countdownJob?.let { main.removeCallbacks(it) }
        arm.dropClutch()
        val layout = if (s.setup.level == LevelId.FREE) FreePlay.generate(s.freeRound, if (s.seed != 0L) s.seed else System.currentTimeMillis()) else Levels.layout(s.setup.level, s.setup.difficulty)
        val d = Drill(layout, cell)
        drill = d
        TdLog.i("ROUND layout level=${layout.level} kind=${layout.kind} diff=${layout.difficulty} targets=${layout.targets} round=${layout.round} lights=${d.lights.size} blocks=${d.blocks}")
        set("countdown") { it.copy(screen = Screen.COUNTDOWN, countdown = 3, roundConfirm = null, result = null, compare = null, live = Live(targets = layout.targets), tab = if (it.fromFree) Tab.FREE else Tab.LEVELS) }
        val job = object : Runnable {
            override fun run() {
                val st = _state.value
                if (st.screen != Screen.COUNTDOWN) return
                if (st.countdown <= 1) {
                    set("go") { it.copy(screen = Screen.LIVE, countdown = 0) }
                    soundOrNull?.lit()
                } else {
                    set("count") { it.copy(countdown = it.countdown - 1) }
                    main.postDelayed(this, 1000)
                }
            }
        }
        countdownJob = job
        main.postDelayed(job, 1000)
    }

    private fun pause(why: String) {
        countdownJob?.let { main.removeCallbacks(it) }
        arm.dropClutch()
        set("pause $why") { it.copy(screen = Screen.PAUSED) }
    }

    private fun voidRound() {
        countdownJob?.let { main.removeCallbacks(it) }
        drill = null
        arm.dropClutch()
        TdLog.i("ROUND void")
        set("void") { it.copy(screen = Screen.INTRO, result = null, compare = null, roundConfirm = null) }
    }

    private fun finishRound(why: String) {
        val s = _state.value
        val d = drill ?: return
        if (s.screen != Screen.LIVE && s.screen != Screen.PAUSED) return
        arm.dropClutch()
        val r = d.result(s.setup.mapping, if (s.setup.mapping == MappingMode.FOLLOW) 1f else s.setup.scale, s.setup.control(s.prefs))
        TdLog.i("RESULT why=$why level=${r.level} diff=${r.difficulty} map=${TdUiState.mapKey(r.mapping, r.scale)} score=${r.score} stars=${r.stars} demo=${r.demoReady} metrics=${r.metrics}")
        record(r)
        if (r.doneCount() == r.metrics.targets) sound?.settle()
    }

    private fun RoundResult.doneCount() = metrics.doneCount

    /** Bests, comparison, plays; then S13 / S15 with the comparison sentence. */
    private fun record(r: RoundResult) {
        val s = _state.value
        val best = HashMap(s.best)
        val key = TdUiState.bestKey(r.level, r.difficulty)
        val old = best[key]
        val demo = (old?.demo ?: 0) + if (r.demoReady) 1 else 0
        if (old == null || r.score > old.score || (r.score == old.score && r.metrics.timeS < old.timeS)) best[key] = Best(r.score, r.stars, r.metrics.timeS, r.metrics.steady, r.metrics.oops, demo)
        else best[key] = old.copy(demo = demo)
        // comparison per level x mapping (time and table hits of the last round)
        val mk = TdUiState.mapKey(r.mapping, r.scale)
        val cmpKey = "cmp_${r.level.name}_$mk"
        val prevOther = best.filterKeys { it.startsWith("cmp_${r.level.name}_") && it != cmpKey }
        best[cmpKey] = Best(r.score, r.stars, r.metrics.timeS, r.metrics.steady, r.metrics.count(Oops.TABLE), 0, r.metrics.doneCount, r.metrics.targets)
        val maps = HashMap(s.mapStats)
        val ms = maps[mk]
        maps[mk] = io.embodit.teleopdojo.ui.MapStat(maxOf(ms?.best ?: 0, r.score), (ms?.sumTime ?: 0f) + r.metrics.timeS, (ms?.n ?: 0) + 1)
        val plays = HashMap(s.levelPlays)
        plays[r.level] = (plays[r.level] ?: emptySet()) + r.mapping
        val compare = prevOther.entries.firstOrNull { (k, _) -> (k.contains("follow")) != (r.mapping == MappingMode.FOLLOW) }?.let { (k, o) ->
            Compare.sentence(
                CmpSide(mk, r.metrics.doneCount, r.metrics.targets, r.metrics.timeS, r.metrics.count(Oops.TABLE)),
                CmpSide(k.removePrefix("cmp_${r.level.name}_"), o.done, o.total, o.timeS, o.oops),
            )
        }
        val free = if (r.level == LevelId.FREE) s.freeRound + 1 else s.freeRound
        store?.saveBests(best)
        store?.saveMapStats(maps)
        store?.saveLevelPlays(plays)
        if (r.level == LevelId.FREE) store?.freeRound = free
        set("result") { it.copy(screen = Screen.RESULT, result = r, compare = compare, best = best, mapStats = maps, levelPlays = plays, freeRound = free) }
    }

    /** Debug: fixed free-play seed (`seed n`). */
    fun debugSeed(n: Long) = set("seed") { it.copy(seed = n) }

    /** Debug: the placement marker was set by the scene. */
    fun onMarker(set: Boolean) {
        if (_state.value.markerSet != set) set("marker") { it.copy(markerSet = set) }
    }

    // ============================================================================================== quit

    /** D26 exit, same order as the factory template: release, close the window and the Stage, remove the tasks, end. */
    private fun quit() {
        TdLog.i("EXIT confirmed: release + close window + closeStage + finishAndRemoveTask + exit")
        countdownJob?.let { main.removeCallbacks(it) }
        drill = null
        quitHook?.invoke()
        runCatching { sound?.release() }
        sound = null
        WindowCtl.closeForQuit()
        val nav = WindowCtl.navigator
        val a = activity
        kotlinx.coroutines.MainScope().launch {
            runCatching { nav?.closeStage() }.onFailure { TdLog.w("EXIT closeStage: $it") }
            runCatching {
                val am = a?.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                am?.appTasks?.forEach { runCatching { it.finishAndRemoveTask() } }
                a?.finishAndRemoveTask()
            }.onFailure { TdLog.w("EXIT finishAndRemoveTask: $it") }
            main.postDelayed({ kotlin.system.exitProcess(0) }, 400)
        }
    }

    /** Debug `coach`: shows the first-time tips again (only reachable on a first start otherwise). */
    fun debugCoach() {
        if (_state.value.inRound) return
        set("debugCoach") { it.copy(screen = Screen.COACH, tab = Tab.LEVELS, coachStep = 0) }
    }
}

/** One side of the S15 comparison: mapping key, targets done of total, time and table hits of that round. */
data class CmpSide(val map: String, val done: Int, val total: Int, val timeS: Float, val table: Int)

/** What S15 says, decided here so the window only words it (QA #1 q1-2: an empty round was praised as faster). */
data class CmpLine(val kind: Kind, val mine: String, val other: String, val dt: Int = 0, val dTable: Int = 0, val myDone: String = "", val otherDone: String = "") {
    enum class Kind { MORE, FEWER, NONE_DONE, FASTER, SLOWER, SAME_TIME }
}

/**
 * The comparison sentence of S15 ("离合 1.0× 比跟手快 12 秒，撞桌少 3 次"). Completion first: the round that finished more
 * of its targets is the better one whatever the clock says. Time (and table hits) only between rounds with equal
 * completion; two empty rounds are reported as empty, never as faster; a gap under 1 s is "about the same time".
 */
object Compare {
    fun of(mine: CmpSide, other: CmpSide): CmpLine? {
        if (mine.total <= 0 || other.total <= 0 || mine.done < 0 || other.done < 0) return null
        val myDone = "${mine.done}/${mine.total}"
        val otherDone = "${other.done}/${other.total}"
        // cross-multiplied so 3/5 vs 6/10 counts as equal
        val c = (mine.done * other.total).compareTo(other.done * mine.total)
        if (c != 0) return CmpLine(if (c > 0) CmpLine.Kind.MORE else CmpLine.Kind.FEWER, mine.map, other.map, myDone = myDone, otherDone = otherDone)
        if (mine.done == 0) return CmpLine(CmpLine.Kind.NONE_DONE, mine.map, other.map, myDone = myDone, otherDone = otherDone)
        val dt = Math.round(other.timeS - mine.timeS)
        val kind = when {
            dt > 0 -> CmpLine.Kind.FASTER
            dt < 0 -> CmpLine.Kind.SLOWER
            else -> CmpLine.Kind.SAME_TIME
        }
        return CmpLine(kind, mine.map, other.map, kotlin.math.abs(dt), other.table - mine.table, myDone, otherDone)
    }

    /** "kind|mine|other|dt|dTable|myDone|otherDone", the form kept in [TdUiState.compare]. */
    fun encode(l: CmpLine): String = listOf(l.kind.name, l.mine, l.other, l.dt, l.dTable, l.myDone, l.otherDone).joinToString("|")

    fun decode(raw: String): CmpLine? {
        val f = raw.split('|')
        if (f.size < 7) return null
        val kind = runCatching { CmpLine.Kind.valueOf(f[0]) }.getOrNull() ?: return null
        return CmpLine(kind, f[1], f[2], f[3].toIntOrNull() ?: 0, f[4].toIntOrNull() ?: 0, f[5], f[6])
    }

    fun sentence(mine: CmpSide, other: CmpSide): String? = of(mine, other)?.let(::encode)
}

/** Difficulties in order (for the intro segments). */
val DIFFS = listOf(Difficulty.EASY, Difficulty.STANDARD, Difficulty.PICKY)
