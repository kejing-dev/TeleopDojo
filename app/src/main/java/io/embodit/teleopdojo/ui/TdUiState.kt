package io.embodit.teleopdojo.ui

import io.embodit.teleopdojo.domain.ControlMode
import io.embodit.teleopdojo.domain.Difficulty
import io.embodit.teleopdojo.domain.LevelId
import io.embodit.teleopdojo.domain.MappingMode
import io.embodit.teleopdojo.domain.Miss
import io.embodit.teleopdojo.domain.Oops
import io.embodit.teleopdojo.domain.RoundResult

/**
 * Screens of the main window (design states). The base screen; overlays (S09-S12, S21-S24) are flags in [Live].
 * S04 no controller and S25 window closed are flags too ([TdUiState.controller], WindowCtl).
 */
enum class Screen {
    STARTING, // S01
    COACH, // S05
    HOME, // S03 (关卡 page list)
    INTRO, // S06
    COUNTDOWN, // 3-2-1 before S07
    LIVE, // S07 / S08
    PAUSED, // S17
    RESULT, // S13 / S15
    PLACING, // S02
}

enum class Lang { SYSTEM, ZH, EN }

enum class Tab { LEVELS, FREE, CARD, SETTINGS }

/** S04 copy by the provider's support state. */
enum class ControllerState { OK, PENDING, NOT_SUPPORTED, NO_PERMISSION, NOT_FULL_SPACE }

data class Prefs(
    val lang: Lang = Lang.SYSTEM,
    val leftHanded: Boolean = false,
    val standing: Boolean = false,
    val tableAdjustCm: Int = 0,
    val controlStandard: ControlMode = ControlMode.POSE,
    val vr: Boolean = false,
    val volume: Int = 90,
    val muted: Boolean = false,
    val coachSeen: Boolean = false,
) {
    val tableHeight: Float
        get() = ((if (standing) 0.95f else 0.75f) + tableAdjustCm / 100f).coerceIn(0.65f, 1.05f)
}

/** The choices on the intro page (only this round: D30). */
data class RoundSetup(
    val level: LevelId = LevelId.REACH,
    val difficulty: Difficulty = Difficulty.EASY,
    val mapping: MappingMode = MappingMode.CLUTCH,
    val scale: Float = 1.0f,
) {
    /** Easy uses "position only" (our assist); standard / picky use the setting (default full pose); picky forces pose. */
    fun control(p: Prefs): ControlMode = when (difficulty) {
        Difficulty.EASY -> ControlMode.POSITION
        Difficulty.STANDARD -> p.controlStandard
        Difficulty.PICKY -> ControlMode.POSE
    }
}

/** Hint line keys (both the window and the tag on the workcell). */
enum class Hint { NONE, HOLD_GRIP, REGRAB, GOT_IT, TABLE, JOINT, SINGULAR, SLIPPED, LOST, ALMOST_IN, ALMOST_TURN, ALMOST_SQUEEZE, LOOSENED, PRESS_GRIP_AGAIN, TOWER, PLACED, DROPPED }

/** What the live page shows (changes a few times a second at most). */
data class Live(
    val clutched: Boolean = false,
    val openingMm: Int = 85,
    val done: Int = 0,
    val targets: Int = 0,
    val timeS: Int = 0,
    val oops: Map<Oops, Int> = emptyMap(),
    val holding: Boolean = false,
    val jointLimit: Int = -1,
    val singular: Boolean = false,
    val unreachable: Boolean = false,
    val trackingLost: Boolean = false,
    val tooClose: Boolean = false,
    val inWindowCone: Boolean = false,
    val towerHeight: Int = 0,
    val miss: Miss? = null,
)

/** Best of a level / difficulty (persisted). */
/** [done] / [total]: targets of the round (comparison entries only; -1 = saved before v0.1.0 rework, not comparable). */
data class Best(val score: Int, val stars: Int, val timeS: Float, val steady: Int, val oops: Int, val demo: Int, val done: Int = -1, val total: Int = -1)

/** Mapping comparison (scorecard): key "clutch0.5" / "clutch1.0" / "clutch1.5" / "follow". */
data class MapStat(val best: Int, val sumTime: Float, val n: Int) {
    val avgTime: Float
        get() = if (n == 0) 0f else sumTime / n
}

data class TdUiState(
    val screen: Screen = Screen.STARTING,
    val tab: Tab = Tab.LEVELS,
    val prefs: Prefs = Prefs(),
    val setup: RoundSetup = RoundSetup(),
    val countdown: Int = 0,
    val live: Live = Live(),
    val hint: Hint = Hint.NONE,
    val result: RoundResult? = null,
    val compare: String? = null,
    val best: Map<String, Best> = emptyMap(),
    val mapStats: Map<String, MapStat> = emptyMap(),
    val levelPlays: Map<LevelId, Set<MappingMode>> = emptyMap(),
    val freeRound: Int = 1,
    val coachStep: Int = 0,
    val controller: ControllerState = ControllerState.PENDING,
    val headWaiting: Boolean = false,
    val exitConfirm: Boolean = false,
    /** "restart" / "home" while a round is running: the round would be lost, so ask first (D44). */
    val roundConfirm: String? = null,
    val resetConfirm: Boolean = false,
    val aboutOpen: Boolean = false,
    val placingFrom: Screen = Screen.HOME,
    val markerSet: Boolean = false,
    val panelEpoch: Int = 0,
    val fromFree: Boolean = false,
    val seed: Long = 0L,
) {
    val inRound: Boolean
        get() = screen == Screen.LIVE || screen == Screen.COUNTDOWN || screen == Screen.PAUSED

    /** The design state id for logs and QA (S01 ... S25, overlays joined with +). */
    val designState: String
        get() {
            val base = when (screen) {
                Screen.STARTING -> "S01"
                Screen.PLACING -> "S02"
                Screen.HOME -> if (tab == Tab.CARD) "S14" else if (tab == Tab.FREE) "S16" else if (tab == Tab.SETTINGS) (if (exitConfirm) "S19" else if (resetConfirm) "S20" else "S18") else "S03"
                Screen.COACH -> "S05"
                Screen.INTRO -> if (tab == Tab.FREE) "S16" else "S06"
                Screen.COUNTDOWN -> "S07"
                Screen.LIVE -> if (live.clutched) "S08" else "S07"
                Screen.PAUSED -> "S17"
                Screen.RESULT -> if (compare != null) "S15" else "S13"
            }
            val o = ArrayList<String>()
            if (screen == Screen.LIVE) {
                if (live.holding) o += "S09"
                if ((live.oops[Oops.TABLE] ?: 0) > 0 && hint == Hint.TABLE) o += "S10"
                if (live.jointLimit >= 0) o += "S11"
                if (live.singular) o += "S12"
                if (live.trackingLost) o += "S22"
            }
            if (live.tooClose) o += "S21"
            if (prefs.vr) o += "S23"
            if (live.inWindowCone) o += "S24"
            if (controller != ControllerState.OK) o += "S04"
            return (listOf(base) + o).joinToString("+")
        }

    companion object {
        fun mapKey(m: MappingMode, scale: Float): String = if (m == MappingMode.FOLLOW) "follow" else "clutch" + String.format(java.util.Locale.US, "%.1f", scale)

        fun bestKey(l: LevelId, d: Difficulty): String = "${l.name}_${d.name}"
    }
}
