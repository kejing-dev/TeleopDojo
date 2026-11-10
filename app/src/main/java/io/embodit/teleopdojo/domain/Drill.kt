package io.embodit.teleopdojo.domain

import kotlin.math.abs
import kotlin.math.hypot

/** Something the drill reports for sound, haptics and the hint line. */
sealed class DrillEv {
    data class Lit(val index: Int) : DrillEv()
    data class Grabbed(val block: Int) : DrillEv()
    data class Almost(val block: Int, val miss: Miss, val loosened: Boolean) : DrillEv()
    data class Dropped(val block: Int) : DrillEv()
    data class Settled(val block: Int, val placed: Boolean) : DrillEv()
    data class Slipped(val block: Int) : DrillEv()
    data class Oopsed(val kind: Oops) : DrillEv()
    object Done : DrillEv()
}

/** A light of the reach drill at run time. */
class Light(val spec: LightSpec) {
    var lit = false
    var dwell = 0f
}

/**
 * One round of a drill (design content.levels, rules.grasp, rules.scoring): owns the lights / blocks, judges the arm
 * frames and measures the metrics. The B-to-B timing lives in the controller; this class only runs while live. All
 * object positions are table-local; the [cell] converts. Pure JVM (DrillTest, GraspTest, SloppyTest).
 */
class Drill(val layout: Layout, val cell: Workcell) {
    val lights: List<Light> = layout.lights.map { Light(it.copy(c = cell.hand(it.c), arrow = it.arrow?.let { a -> cell.hand(a) })) }
    val blocks: List<Block> = layout.blocks.mapIndexed { i, s ->
        val p = cell.hand(Vec3(s.x, s.size / 2f, s.z))
        Block(i, s.size, p, Num.rad(s.yawDeg * cell.mirror), i % 3)
    }
    val trays: List<TraySpec> = layout.trays.map { val p = cell.hand(Vec3(it.x, 0f, it.z)); it.copy(x = p.x, z = p.z) }
    val stackBase: Vec3? = layout.stackBase?.let { cell.hand(it) }
    val meter = MetricsMeter()
    var t = 0f
        private set
    var done = false
        private set
    var held: Block? = null
        private set
    private var lastOpening = Gripper.OPEN_M
    private var closingChecked = false
    private var slipMs = 0f
    private var lastTipV: Vec3? = null
    private var lastTip: Vec3? = null
    private var towerSince = -1f
    private var limitWas = false
    private var singularWas = false
    /** Per-block placed state (pick drill). */
    val placed = BooleanArray(blocks.size)

    val doneCount: Int
        get() = when (layout.kind) {
            LevelId.REACH -> lights.count { it.lit }
            LevelId.PICK -> placed.count { it }
            else -> if (done) 1 else 0
        }

    /** Stack drill: how many blocks currently stand in a column on the base target. */
    var towerHeight = 0
        private set

    /** One frame: [arm] from [Arm.step], commanded opening [opening] (metres). */
    fun step(dt: Float, arm: ArmFrame, opening: Float): List<DrillEv> {
        val evs = ArrayList<DrillEv>()
        if (done) return evs
        t += dt
        val tcpL = cell.toLocal(arm.tcp.p)
        val qL = cell.rotToLocal(arm.tcp.q)
        meter.frame(t, dt, arm.tcp.p, arm.clutched, 1f - opening / Gripper.OPEN_M)
        if (arm.grabbedNow) meter.grabbed(t)
        // ---- events (counted once per entry)
        if (arm.tableHit) { meter.event(Oops.TABLE); evs += DrillEv.Oopsed(Oops.TABLE) }
        val limitNow = arm.limitJoint >= 0
        if (limitNow && !limitWas) { meter.event(Oops.JOINT); evs += DrillEv.Oopsed(Oops.JOINT) }
        limitWas = limitNow
        if (arm.singular && !singularWas) { meter.event(Oops.SINGULAR); evs += DrillEv.Oopsed(Oops.SINGULAR) }
        singularWas = arm.singular
        when (layout.kind) {
            LevelId.REACH -> stepReach(dt, tcpL, qL, evs)
            else -> stepBlocks(dt, tcpL, qL, opening, evs)
        }
        if (!done && doneCount >= layout.targets) {
            done = true
            evs += DrillEv.Done
        }
        return evs
    }

    private fun stepReach(dt: Float, tcpL: Vec3, qL: Quat4, evs: MutableList<DrillEv>) {
        for ((i, l) in lights.withIndex()) {
            if (l.lit) continue
            val near = tcpL.distanceTo(l.spec.c) <= layout.tolerance
            val aligned = l.spec.arrow == null || angleDeg(Tool.approach(qL), l.spec.arrow) <= layout.angleDeg
            if (near && aligned) {
                l.dwell += dt
                if (l.dwell >= layout.dwellS) {
                    l.lit = true
                    meter.segmentDone(t, cell.toStage(tcpL))
                    evs += DrillEv.Lit(i)
                }
            } else l.dwell = 0f
        }
    }

    private fun stepBlocks(dt: Float, tcpL: Vec3, qL: Quat4, opening: Float, evs: MutableList<DrillEv>) {
        val h = held
        if (h != null) {
            // slip: TCP acceleration over 15 m/s² for 50 ms
            val prev = lastTip
            val v = if (prev != null && dt > 0f) (tcpL - prev) / dt else Vec3.ZERO
            val pv = lastTipV
            val acc = if (pv != null && dt > 0f) ((v - pv) / dt).length else 0f
            slipMs = if (acc > Grasp.SLIP_ACC) slipMs + dt * 1000f else 0f
            lastTipV = v
            if (opening > h.size + Grasp.RELEASE_M) {
                Grasp.release(h)
                held = null
                evs += DrillEv.Dropped(h.id)
            } else if (slipMs >= Grasp.SLIP_MS) {
                Grasp.release(h)
                held = null
                meter.event(Oops.SLIP)
                evs += DrillEv.Slipped(h.id)
                evs += DrillEv.Oopsed(Oops.SLIP)
                slipMs = 0f
            } else Grasp.follow(h, tcpL, qL)
        } else {
            lastTipV = null
            slipMs = 0f
            // a closing gripper: judge when the commanded opening passes under the nearest block's width
            if (opening < lastOpening - 1e-4f) {
                val b = blocks.filter { it.state == BlockState.RESTING }.minByOrNull { it.c.distanceTo(tcpL) }
                if (b != null && b.c.distanceTo(tcpL) < b.size + 0.03f && opening < b.size && !closingChecked) {
                    val rules = layout.graspRules.loosen(b.loosened)
                    val c = Grasp.check(b, tcpL, qL, opening, rules)
                    if (c.ok) {
                        Grasp.attach(b, tcpL, qL)
                        held = b
                        b.nearMisses = 0
                        closingChecked = true
                        placed[b.id] = false
                        evs += DrillEv.Grabbed(b.id)
                    } else if (opening < b.size - Grasp.SQUEEZE_M) {
                        closingChecked = true
                        if (c.almost) {
                            b.nearMisses++
                            val loosen = b.nearMisses >= 2
                            if (loosen) {
                                b.loosened++
                                b.nearMisses = 0
                            }
                            evs += DrillEv.Almost(b.id, c.misses.first(), loosen)
                        } else b.nearMisses = 0
                    }
                }
            }
            if (opening > lastOpening + 1e-4f && opening > 0.5f * Gripper.OPEN_M) closingChecked = false
        }
        lastTip = tcpL
        lastOpening = opening
        // falling / sliding blocks
        for (b in blocks) {
            if (b.state == BlockState.FALLING || b.state == BlockState.SLIDING) {
                if (Grasp.stepFree(b, blocks, dt, layout.stackTol)) {
                    val ok = judgePlaced(b)
                    if (layout.kind == LevelId.PICK) {
                        if (ok && !placed[b.id]) meter.segmentDone(t, cell.toStage(b.c))
                        placed[b.id] = ok
                    }
                    evs += DrillEv.Settled(b.id, ok)
                }
            }
        }
        if (layout.kind == LevelId.STACK) stepTower(evs)
    }

    /** Pick drill: the block rests inside a tray (and is aligned within [TraySpec.alignDeg] when asked). */
    private fun judgePlaced(b: Block): Boolean {
        if (layout.kind != LevelId.PICK) return false
        for (tr in trays) {
            val dx = abs(b.c.x - tr.x)
            val dz = abs(b.c.z - tr.z)
            val room = tr.inner / 2f - if (tr.grid) 0f else b.half * 0.6f
            if (dx <= room && dz <= room && b.bottom < 0.03f) {
                if (tr.alignDeg > 0f) {
                    val a = abs(Num.deg(b.yaw)) % 90f
                    if (minOf(a, 90f - a) > tr.alignDeg) return false
                }
                return true
            }
        }
        return false
    }

    private fun stepTower(evs: MutableList<DrillEv>) {
        val base = stackBase ?: return
        val resting = blocks.filter { it.state == BlockState.RESTING }
        // the column: a block on the target, then each next one on the previous within the tolerance
        val bottom = resting.filter { it.bottom < 0.005f && hypot(it.c.x - base.x, it.c.z - base.z) <= 0.035f }.minByOrNull { hypot(it.c.x - base.x, it.c.z - base.z) }
        var height = 0
        if (bottom != null) {
            height = 1
            var cur: Block = bottom
            while (true) {
                val next = resting.firstOrNull { it !== cur && abs(it.bottom - cur.top) < 0.006f && hypot(it.c.x - cur.c.x, it.c.z - cur.c.z) <= layout.stackTol } ?: break
                height++
                cur = next
            }
        }
        towerHeight = height
        val need = blocks.size
        if (height >= need) {
            if (towerSince < 0f) towerSince = t
            if (t - towerSince >= layout.holdS) {
                meter.segmentDone(t, cell.toStage(blocks.maxByOrNull { it.c.y }!!.c))
                done = true
                evs += DrillEv.Done
            }
        } else towerSince = -1f
    }

    /** Seconds the tower has stood (stack drill), for the hint line. */
    fun towerHeld(): Float = if (towerSince < 0f) 0f else t - towerSince

    fun result(mapping: MappingMode, scale: Float, control: ControlMode): RoundResult {
        val m = meter.metrics(doneCount, layout.targets, t)
        val score = Scoring.score(m, layout.referenceS)
        return RoundResult(layout.level, layout.difficulty, mapping, scale, control, m, score, Scoring.stars(score), Scoring.demoReady(m), control == ControlMode.POSITION, layout.round)
    }

    companion object {
        fun angleDeg(a: Vec3, b: Vec3): Float = Num.deg(kotlin.math.acos(a.normalized().dot(b.normalized()).coerceIn(-1f, 1f)))
    }
}
