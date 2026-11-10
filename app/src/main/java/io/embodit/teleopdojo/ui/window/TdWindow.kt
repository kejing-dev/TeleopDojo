package io.embodit.teleopdojo.ui.window

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.abs
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.platform.LengthUnit
import com.pico.spatial.ui.platform.LocalPhysicalLengthConverter
import com.pico.spatial.ui.platform.containers.SpatialNavigator
import io.embodit.teleopdojo.R
import io.embodit.teleopdojo.domain.LevelGate
import io.embodit.teleopdojo.platform.TdLog
import io.embodit.teleopdojo.ui.Screen
import io.embodit.teleopdojo.ui.Tab
import io.embodit.teleopdojo.ui.TdController
import io.embodit.teleopdojo.ui.Ui
import io.embodit.teleopdojo.ui.components.CardPage
import io.embodit.teleopdojo.ui.components.FreePage
import io.embodit.teleopdojo.ui.components.Haptics
import io.embodit.teleopdojo.ui.components.LevelsPage
import io.embodit.teleopdojo.ui.components.LiveToolbar
import io.embodit.teleopdojo.ui.components.LocalTdResources
import io.embodit.teleopdojo.ui.components.PanelSizes
import io.embodit.teleopdojo.ui.components.SettingsPage
import io.embodit.teleopdojo.ui.components.localizedResources
import io.embodit.teleopdojo.ui.components.rs
import io.embodit.teleopdojo.ui.debug.LocalProbePanel
import io.embodit.teleopdojo.ui.theme.TdTheme

/** Id and tag of the main window (tag != null: "reuse" mode, one instance). */
const val PANEL_ID = "ControlPanel"
const val PANEL_TAG = "main"

/**
 * The main window's state shared by the Stage and the window (one process, native-window-move §3.4): the window
 * reports its lifecycle, the Stage opens / recalls it with its navigator. Main thread only.
 */
object WindowCtl {
    /** Last lifecycle event of the window ("ON_CREATE" … "ON_DESTROY"), "never" before the first open. */
    var panelState by mutableStateOf("never")
        private set

    /** Bumped on every ON_CREATE: the scene re-estimates where the system put the window. */
    var createdEpoch by mutableIntStateOf(0)
        private set

    /** Metres per window dp, from the window's LocalPhysicalLengthConverter (the Stage's 0.8 mm until it reports). */
    @Volatile var dpM = 0.0008f

    /** The Stage's navigator while the Stage is composed. */
    var navigator: SpatialNavigator? = null

    private val main = Handler(Looper.getMainLooper())
    private var recallPending = false
    private var askedAtMs = 0L

    /**
     * D9 (QA #4 q4-3): the system opens the window square to the line of sight, head pitch included, and nothing can
     * re-level it afterwards; so every open (first, chip, recall) waits here for a level head. The Stage steps it each
     * frame ([stepGate]) and shows "平视前方" while [levelHint] is on.
     */
    private val gate = LevelGate()
    private var loggedTurn = 0f
    private var lastStepMs = 0L

    /** The Stage's "平视前方 / Look straight ahead" hint: an open waits and the head is not level. */
    var levelHint by mutableStateOf(false)
        private set

    /**
     * profile 48: the Stage's "往左看一点 / 往右看一点" hint, +1 left, -1 right, 0 off: an open waits, the head is level,
     * but the window would land on the workcell ([io.embodit.teleopdojo.domain.WindowSpot]).
     */
    var turnHint by mutableIntStateOf(0)
        private set

    /** Something waits for the gate (the Stage then works out [stepGate]'s turn). */
    val waiting: Boolean
        get() = gate.waiting

    /** The Stage's check that the spot the window was opened at is still clear (restore after −, profile 48). */
    var spotClear: () -> Boolean = { true }

    val open: Boolean
        get() = panelState != "never" && panelState != "ON_DESTROY" && panelState != "ON_STOP"

    /** The Stage shows "打开面板" while the window is closed / minimised, or never came up within 8 s of asking. */
    fun chipWanted(now: Long): Boolean =
        if (gate.waiting) false
        else when (panelState) {
            "ON_DESTROY", "ON_STOP" -> !recallPending
            "never" -> askedAtMs > 0 && now - askedAtMs > 8000
            else -> false
        }

    /** Ask for the window: it opens once the head is level (or at once when the Stage sends no frames, [watchdog]). */
    fun openWindow(why: String) {
        val now = SystemClock.elapsedRealtime()
        TdLog.i("WINDOW ask why=$why state=$panelState (opens when the head is level)")
        gate.ask(why, now)
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, WATCHDOG_MS)
    }

    /**
     * One Stage frame: [pitchDeg] = head pitch (+ up), null while the head pose is not valid yet; [turnDeg] = how far
     * the head still has to turn for a clear window spot (+ left, 0 = clear, profile 48).
     */
    fun stepGate(now: Long, pitchDeg: Float?, turnDeg: Float = 0f) {
        lastStepMs = now
        if (!gate.waiting) {
            if (levelHint) levelHint = false
            if (turnHint != 0) turnHint = 0
            return
        }
        val turn = if (gate.turnWanted(pitchDeg, turnDeg)) (if (turnDeg > 0f) 1 else -1) else 0
        if (turn != turnHint || (turn != 0 && abs(turnDeg - loggedTurn) >= 5f)) {
            turnHint = turn
            loggedTurn = turnDeg
            TdLog.i(String.format(java.util.Locale.US, "TURN hint=%d need=%.0f pitch=%.1f waited=%d ms", turn, turnDeg, pitchDeg ?: Float.NaN, gate.waitedMs(now)))
        }
        val hint = gate.hintWanted(pitchDeg)
        if (hint != levelHint) {
            levelHint = hint
            TdLog.i(String.format(java.util.Locale.US, "LEVEL hint=%s pitch=%.1f waited=%d ms", hint, pitchDeg ?: Float.NaN, gate.waitedMs(now)))
        }
        val why = gate.step(now, pitchDeg, turnDeg) ?: return
        levelHint = false
        turnHint = 0
        TdLog.i(String.format(java.util.Locale.US, "LEVEL ok pitch=%.1f spot clear -> open", pitchDeg ?: Float.NaN))
        openNow(why)
    }

    /**
     * No Stage frames for a while: do not hold the window back. Before the first frame that is [FIRST_FRAME_MS] after
     * the ask (headset cold start: the Stage's frames start ~6 s after launch); after it, [WATCHDOG_MS] without one.
     */
    private val watchdog =
        object : Runnable {
            override fun run() {
                if (!gate.waiting) return
                val now = SystemClock.elapsedRealtime()
                val stalled = if (lastStepMs == 0L) gate.waitedMs(now) > FIRST_FRAME_MS else now - lastStepMs > WATCHDOG_MS
                if (stalled) {
                    val why = gate.pending ?: return
                    gate.cancel()
                    levelHint = false
                    turnHint = 0
                    openNow("$why/noframes")
                } else main.postDelayed(this, WATCHDOG_MS)
            }
        }

    private fun openNow(why: String) {
        val nav = navigator ?: return TdLog.w("WINDOW open why=$why: no navigator")
        askedAtMs = SystemClock.elapsedRealtime()
        TdLog.i("WINDOW open why=$why state=$panelState")
        runCatching { nav.openWindowContainer(PANEL_ID, PANEL_TAG) }.onFailure { TdLog.w("WINDOW open failed: $it") }
    }

    /**
     * The chip: a minimised window is restored where it was while that spot is still clear of the workcell; otherwise
     * (closed, or the table moved in front of it) it opens through the gate like every other open (profile 48).
     */
    fun reopen(why: String) {
        if (panelState == "ON_STOP") {
            if (!spotClear()) return recall("$why/blocked")
            if (navigator?.let { runCatching { it.restoreWindowContainer(PANEL_ID, PANEL_TAG) }.getOrDefault(false) } == true) {
                TdLog.i("WINDOW restore why=$why")
                return
            }
        }
        openWindow(why)
    }

    /**
     * "叫回面板": the system has no call to move a window, and a second open of an open window only focuses it, so a
     * recall closes it and opens it again once it reported ON_DESTROY (an open right after close is swallowed, §3.5).
     */
    fun recall(why: String) {
        if (panelState == "never" || panelState == "ON_DESTROY") return openWindow(why)
        val nav = navigator ?: return
        recallPending = true
        TdLog.i("WINDOW recall why=$why: close, then open after ON_DESTROY")
        runCatching { nav.closeWindowContainer(PANEL_ID, PANEL_TAG) }.onFailure { TdLog.w("WINDOW close failed: $it"); recallPending = false }
    }

    fun onLifecycle(event: String) {
        panelState = event
        TdLog.i("WINDOW lifecycle $event")
        if (event == "ON_CREATE") createdEpoch++
        if (event == "ON_DESTROY" && recallPending) {
            recallPending = false
            main.postDelayed({ openWindow("recall") }, 300)
        }
    }

    /** Quit: close the window first (it would otherwise outlive the Stage in the Shared Space, §7.7). */
    fun closeForQuit() {
        recallPending = false
        gate.cancel()
        levelHint = false
        turnHint = 0
        runCatching { navigator?.closeWindowContainer(PANEL_ID, PANEL_TAG) }
    }

    private const val WATCHDOG_MS = 3000L
    private const val FIRST_FRAME_MS = 15000L
}

/**
 * The content of the main window (design windowModel single_system_window): TabBar 关卡 / 随便练 / 成绩卡 / 设置; the round
 * quick keys on the system Toolbar while a round runs; confirmations in place. Page labels come from the app's own
 * language table, not the system locale (factory gate tabbar-lang).
 */
@Composable
fun TdWindow() {
    val context = LocalContext.current
    val state by TdController.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> WindowCtl.onLifecycle(e.name) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val converter = LocalPhysicalLengthConverter.current
    LaunchedEffect(converter) {
        runCatching { converter.dpToLength(1000.dp, LengthUnit.Meters) / 1000f }.getOrNull()?.takeIf { it in 0.0002f..0.005f }?.let {
            WindowCtl.dpM = it
            TdLog.i(String.format(java.util.Locale.US, "WINDOW dp=%.5f m (%.2f x %.2f m)", it, PanelSizes.WINDOW_W * it, PanelSizes.WINDOW_H * it))
        }
    }
    val resources = remember(state.prefs.lang, context) { localizedResources(context, state.prefs.lang) }
    val onEvent: (Ui) -> Unit = TdController::onEvent
    CompositionLocalProvider(LocalTdResources provides resources, LocalProbePanel provides "window") {
        TdTheme {
            Haptics.Bind()
            val roundTab = if (state.fromFree) "free" else "levels"
            val toolbar: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? =
                if (state.screen == Screen.LIVE || state.screen == Screen.COUNTDOWN) ({ LiveToolbar(state, onEvent) }) else null
            MainWindow(
                pages =
                    listOf(
                        MainWindowPage("levels", rs(R.string.tab_levels), R.drawable.ic_format_list_numbered, if (roundTab == "levels") toolbar else null) { LevelsPage(state, onEvent) },
                        MainWindowPage("free", rs(R.string.tab_free), R.drawable.ic_shuffle, if (roundTab == "free") toolbar else null) { FreePage(state, onEvent) },
                        MainWindowPage("card", rs(R.string.tab_card), R.drawable.ic_star) { CardPage(state, onEvent) },
                    ),
                settings = MainWindowSettings(rs(R.string.tab_settings), R.drawable.ic_settings) { SettingsPage(state, onEvent) },
                current = when (state.tab) { Tab.LEVELS -> "levels"; Tab.FREE -> "free"; Tab.CARD -> "card"; Tab.SETTINGS -> SETTINGS_PAGE },
                onSelect = { id -> onEvent(Ui.Tab(when (id) { "free" -> Tab.FREE; "card" -> Tab.CARD; SETTINGS_PAGE -> Tab.SETTINGS; else -> Tab.LEVELS })) },
            )
        }
    }
}
