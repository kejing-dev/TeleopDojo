package io.embodit.teleopdojo.domain

import kotlin.math.exp
import kotlin.math.sqrt

/** The "oops" events (design rules.scoring metrics.events). */
enum class Oops { TABLE, JOINT, SINGULAR, SLIP }

/**
 * What a finished round measured (design rules.scoring.metrics + realTeleop.extraMetrics). [doneCount] / [targets] =
 * 完成; [timeS] from the first grab to the last target; [steady] 0-100; [pathPct] 0-100; [events] per kind; [clutches]
 * grabs; [idlePct] share of clutched time spent still; [gripperFlips] trigger open / close swaps.
 */
data class Metrics(
    val doneCount: Int,
    val targets: Int,
    val timeS: Float,
    val steady: Int,
    val pathPct: Int,
    val events: Map<Oops, Int>,
    val clutches: Int,
    val idlePct: Int,
    val gripperFlips: Int,
) {
    fun count(o: Oops): Int = events[o] ?: 0

    val oops: Int
        get() = events.values.sum()
}

/** A scored round (score, stars, the demo badge). */
data class RoundResult(
    val level: LevelId,
    val difficulty: Difficulty,
    val mapping: MappingMode,
    val scale: Float,
    val control: ControlMode,
    val metrics: Metrics,
    val score: Int,
    val stars: Int,
    val demoReady: Boolean,
    val assisted: Boolean,
    val round: Int = 0,
)

object Scoring {
    /**
     * 100 to start: -15 per missed target; -5 per table hit; -3 per joint limit / near singular / slip; -2 per 10 s over
     * the reference time (60 / 90 / 120 s); -5 when the steadiness is under 60. Floor 0.
     */
    fun score(m: Metrics, referenceS: Float): Int {
        // our addition: a round with nothing done scores 0 (by the bare formula a 1-target drill ended at once is 80)
        if (m.doneCount == 0) return 0
        var s = 100
        s -= 15 * (m.targets - m.doneCount).coerceAtLeast(0)
        s -= 5 * m.count(Oops.TABLE)
        s -= 3 * (m.count(Oops.JOINT) + m.count(Oops.SINGULAR) + m.count(Oops.SLIP))
        val over = m.timeS - referenceS
        if (over > 0f) s -= 2 * kotlin.math.ceil(over / 10f).toInt()
        if (m.steady < 60) s -= 5
        return s.coerceAtLeast(0)
    }

    /** >= 90 three stars, >= 75 two, >= 50 one. */
    fun stars(score: Int): Int = when {
        score >= 90 -> 3
        score >= 75 -> 2
        score >= 50 -> 1
        else -> 0
    }

    /**
     * "这一把能当示范" (design rules.realTeleop.demoReady): all targets done, no table hit, no slip, no joint limit,
     * steadiness >= 70, idle <= 15 %, gripper flips <= targets x 2.
     */
    fun demoReady(m: Metrics): Boolean =
        m.doneCount == m.targets && m.count(Oops.TABLE) == 0 && m.count(Oops.SLIP) == 0 && m.count(Oops.JOINT) == 0 &&
            m.steady >= 70 && m.idlePct <= 15 && m.gripperFlips <= m.targets * 2

    /** The scorecard title from the average stars of the three drills' bests. */
    enum class Title { STEADY, SKILLED, GETTING_IT, NEW }

    fun title(avgStars: Float): Title = when {
        avgStars >= 2.5f -> Title.STEADY
        avgStars >= 1.75f -> Title.SKILLED
        avgStars >= 1f -> Title.GETTING_IT
        else -> Title.NEW
    }
}

/**
 * Per-frame measurements while a round runs (all Stage metres / seconds). Feed [frame] every frame; [segmentDone] when
 * a target completes; [metrics] at the end.
 */
class MetricsMeter {
    private var clutchedS = 0f
    private var idleS = 0f
    private var stillS = 0f
    private var lastTip: Vec3? = null
    private var lastV = Vec3.ZERO
    private var lp = Vec3.ZERO
    private var jitterSq = 0.0
    private var jitterN = 0
    private var path = 0f
    private var straight = 0f
    private var segStart: Vec3? = null
    private var segPath = 0f
    var firstGrabS = -1f
        private set
    var lastDoneS = -1f
        private set
    var clutches = 0
        private set
    var flips = 0
        private set
    private var wasClosed = false
    val events = HashMap<Oops, Int>()

    fun event(o: Oops) {
        events[o] = (events[o] ?: 0) + 1
    }

    fun grabbed(t: Float) {
        clutches++
        if (firstGrabS < 0f) firstGrabS = t
    }

    /** One frame: the TCP [tip], whether the clutch is held, the commanded opening fraction (0 open .. 1 closed). */
    fun frame(t: Float, dt: Float, tip: Vec3, clutched: Boolean, closedFrac: Float) {
        val closed = if (wasClosed) closedFrac > 0.4f else closedFrac > 0.6f
        if (closed != wasClosed) {
            if (closed) flips++
            wasClosed = closed
        }
        val prev = lastTip
        lastTip = tip
        if (prev == null || dt <= 0f) return
        val d = tip.distanceTo(prev)
        if (segStart == null) segStart = prev
        if (!clutched) return
        clutchedS += dt
        path += d
        segPath += d
        val v = (tip - prev) / dt
        // 4 Hz first-order low-pass of the tip velocity; the residual is the jitter (design steadiness)
        val a = (dt / (dt + 1f / (2f * Math.PI.toFloat() * 4f))).coerceIn(0f, 1f)
        lp = lp + (v - lp) * a
        val r = v - lp
        jitterSq += r.dot(r).toDouble()
        jitterN++
        lastV = v
        if (v.length < 0.01f) {
            stillS += dt
        } else {
            if (stillS > 1f) idleS += stillS
            stillS = 0f
        }
    }

    fun segmentDone(t: Float, tip: Vec3) {
        lastDoneS = t
        val s = segStart
        if (s != null) straight += minOf(s.distanceTo(tip), segPath.coerceAtLeast(s.distanceTo(tip)))
        segStart = tip
        segPath = 0f
    }

    fun metrics(done: Int, targets: Int, endS: Float): Metrics {
        val idleTotal = idleS + if (stillS > 1f) stillS else 0f
        val rms = if (jitterN == 0) 0.0 else sqrt(jitterSq / jitterN)
        val steady = (100.0 * exp(-rms / STEADY_SCALE)).toInt().coerceIn(0, 100)
        val pathPct = if (path < 1e-3f) 0 else (100f * straight / path).toInt().coerceIn(0, 100)
        val start = if (firstGrabS < 0f) 0f else firstGrabS
        val end = if (done >= targets && lastDoneS >= 0f) lastDoneS else endS
        return Metrics(
            doneCount = done,
            targets = targets,
            timeS = (end - start).coerceAtLeast(0f),
            steady = if (clutchedS <= 0f) 0 else steady,
            pathPct = pathPct,
            events = HashMap(events),
            clutches = clutches,
            idlePct = if (clutchedS <= 0f) 0 else (100f * idleTotal / clutchedS).toInt().coerceIn(0, 100),
            gripperFlips = flips,
        )
    }

    companion object {
        /** Jitter RMS (m/s) that maps to steadiness 37; 0.015 m/s gives ~60 (initial value, calibrate on the headset). */
        const val STEADY_SCALE = 0.03
    }
}
