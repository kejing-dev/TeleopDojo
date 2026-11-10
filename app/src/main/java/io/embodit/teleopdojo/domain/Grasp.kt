package io.embodit.teleopdojo.domain

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/*
 * Rule-based grasping (design rules.grasp; gate B Q5 "先规则"): not physics and not "touch and it sticks". Both pads must
 * really sit on two opposite faces of a block. All positions here are table-local (see [Workcell]).
 */

enum class BlockState { RESTING, HELD, FALLING, SLIDING }

/** A cube of edge [size] at table-local [c] (its centre), turned [yaw] about the vertical. */
class Block(val id: Int, val size: Float, var c: Vec3, var yaw: Float, val color: Int) {
    var state = BlockState.RESTING
    var vy = 0f
    var fallT = 0f
    /** Held: the block in the tool frame (offset of the centre along tool x / y / z, and yaw relative to the closing axis). */
    var holdOffset = Vec3.ZERO
    var holdYaw = 0f
    var slideFrom = Vec3.ZERO
    var slideTo = Vec3.ZERO
    var slideT = 0f
    /** Two "almost" grasps in a row on this block loosen the rules one step (rules.grasp.lenient). */
    var nearMisses = 0
    var loosened = 0

    val half: Float
        get() = size / 2f

    val bottom: Float
        get() = c.y - half

    val top: Float
        get() = c.y + half

    override fun toString(): String = "B$id(${"%.3f".format(c.x)},${"%.3f".format(c.y)},${"%.3f".format(c.z)} yaw=${"%.0f".format(Num.deg(yaw))} $state)"
}

/** Grasp thresholds for a difficulty (and the auto-loosening steps). */
data class GraspRules(val overlap: Float, val angleDeg: Float, val centerTol: Float) {
    fun loosen(steps: Int): GraspRules = if (steps <= 0) this else GraspRules(maxOf(0.25f, overlap - 0.1f * steps), angleDeg + 7f * steps, centerTol + 0.004f * steps)

    companion object {
        /** Easy: pads overlap >= 40 %, closing axis within 30° of the face normal. */
        val EASY = GraspRules(0.40f, 30f, 0.016f)
        /** Standard / picky: >= 60 %, within 20°. */
        val STANDARD = GraspRules(0.60f, 20f, 0.012f)
    }
}

/** Which of the three conditions failed (the hint text: 往里一点 / 再转一点 / 再扣紧点). */
enum class Miss { OVERLAP, ANGLE, SQUEEZE }

/** The verdict of one grasp check. */
data class GraspCheck(val ok: Boolean, val overlap: Float, val angleDeg: Float, val squeezeOk: Boolean, val misses: List<Miss>) {
    /** Two of the three conditions held: "差一点". */
    val almost: Boolean
        get() = !ok && misses.size == 1
}

object Grasp {
    /** Commanded opening must be < block - 3 mm to hold; > block + 2 mm lets go. */
    const val SQUEEZE_M = 0.003f
    const val RELEASE_M = 0.002f

    /** Slip: TCP acceleration over 15 m/s² for 50 ms while holding drops the block (calibrate in the spike). */
    const val SLIP_ACC = 15f
    const val SLIP_MS = 50L

    const val G = 9.81f
    const val MAX_FALL_S = 0.4f
    const val SLIDE_S = 0.6f

    /**
     * Checks a grasp of [b] by a gripper at table-local TCP [tcp] with table-local orientation [q] and commanded
     * opening [opening].
     */
    fun check(b: Block, tcp: Vec3, q: Quat4, opening: Float, rules: GraspRules): GraspCheck {
        // tool axes into the block frame (block local: rotate by -yaw about Y)
        fun toBlock(v: Vec3): Vec3 = Num.rotY(v, -b.yaw)
        val x = toBlock(Tool.closing(q))
        val y = toBlock(Tool.across(q))
        val z = toBlock(Tool.approach(q))
        val d = toBlock(tcp - b.c)
        // the face pair whose normal is closest to the closing axis
        val comps = floatArrayOf(abs(x.x), abs(x.y), abs(x.z))
        val axis = if (comps[0] >= comps[1] && comps[0] >= comps[2]) 0 else if (comps[2] >= comps[1]) 2 else 1
        val angle = Num.deg(kotlin.math.acos(comps[axis].coerceIn(0f, 1f)))
        fun comp(v: Vec3, i: Int) = when (i) { 0 -> v.x; 1 -> v.y; else -> v.z }
        val (u, w) = when (axis) { 0 -> 1 to 2; 1 -> 0 to 2; else -> 0 to 1 }
        // pad rectangle projected on the face plane: half extents along the face axes u, w
        val hu = Gripper.PAD_D / 2f * abs(comp(y, u)) + Gripper.PAD_H / 2f * abs(comp(z, u))
        val hw = Gripper.PAD_D / 2f * abs(comp(y, w)) + Gripper.PAD_H / 2f * abs(comp(z, w))
        val h = b.half
        fun overlap1(center: Float, half: Float): Float = maxOf(0f, minOf(center + half, h) - maxOf(center - half, -h))
        val area = (2 * hu) * (2 * hw)
        val ov = if (area < 1e-8f) 0f else overlap1(comp(d, u), hu) * overlap1(comp(d, w), hw) / area
        // the TCP must sit between the two faces (both pads touch): centred along the face normal
        val centred = abs(comp(d, axis)) <= rules.centerTol
        val squeeze = opening < b.size - SQUEEZE_M
        val misses = ArrayList<Miss>(3)
        if (ov < rules.overlap || !centred) misses += Miss.OVERLAP
        if (angle > rules.angleDeg) misses += Miss.ANGLE
        if (!squeeze) misses += Miss.SQUEEZE
        return GraspCheck(misses.isEmpty(), ov, angle, squeeze, misses)
    }

    /** Records where the block sits in the tool frame at the moment it is grabbed. */
    fun attach(b: Block, tcp: Vec3, q: Quat4) {
        val inv = q.conjugate()
        b.holdOffset = inv.rotate(b.c - tcp)
        b.holdYaw = Yaw.wrap(b.yaw - Tool.closingYaw(q).let { -it })
        b.state = BlockState.HELD
        b.vy = 0f
    }

    /** A held block follows the tool. */
    fun follow(b: Block, tcp: Vec3, q: Quat4) {
        b.c = tcp + q.rotate(b.holdOffset)
        b.yaw = Yaw.wrap(-Tool.closingYaw(q) + b.holdYaw)
    }

    /** Visual opening of the pads: they stop on the block faces instead of passing into it. */
    fun visualOpening(opening: Float, held: Block?): Float = if (held != null) maxOf(opening, held.size) else opening

    /** Support height (table-local y of the surface) under [b]: the table (0), or the top of a block below it. */
    fun supportUnder(b: Block, others: List<Block>): Pair<Float, Block?> {
        var best = 0f
        var on: Block? = null
        for (o in others) {
            if (o === b || o.state == BlockState.HELD) continue
            if (o.top > b.bottom + 0.002f) continue
            val dx = b.c.x - o.c.x
            val dz = b.c.z - o.c.z
            // footprint test: the falling block's centre over the lower block's square (yaw-aware)
            val lx = dx * cos(-o.yaw) + dz * sin(-o.yaw)
            val lz = -dx * sin(-o.yaw) + dz * cos(-o.yaw)
            if (abs(lx) <= o.half && abs(lz) <= o.half && o.top > best) {
                best = o.top
                on = o
            }
        }
        return best to on
    }

    /**
     * Steps a released block: free fall at g (at most 0.4 s, then it snaps) onto the surface below; on a block it stays
     * if the centres are within [stackTol], else it slides off to the table (0.6 s scripted) or drops straight down.
     * Returns true on the frame it comes to rest (the "放稳" sound).
     */
    fun stepFree(b: Block, others: List<Block>, dt: Float, stackTol: Float): Boolean {
        when (b.state) {
            BlockState.FALLING -> {
                val (surface, on) = supportUnder(b, others)
                b.fallT += dt
                b.vy -= G * dt
                var y = b.c.y + b.vy * dt
                val rest = surface + b.half
                if (y <= rest || b.fallT >= MAX_FALL_S) {
                    y = rest
                    b.c = Vec3(b.c.x, y, b.c.z)
                    b.vy = 0f
                    if (on != null) {
                        val off = hypot(b.c.x - on.c.x, b.c.z - on.c.z)
                        if (off > stackTol) {
                            // tilts over the edge and slides down beside the block below
                            val dir = Vec3(b.c.x - on.c.x, 0f, b.c.z - on.c.z).let { if (it.length < 1e-4f) Vec3(1f, 0f, 0f) else it.normalized() }
                            b.slideFrom = b.c
                            val target = on.c + dir * (on.half + b.half + 0.01f)
                            b.slideTo = Vec3(target.x, b.half, target.z)
                            b.slideT = 0f
                            b.state = BlockState.SLIDING
                            return false
                        }
                    }
                    b.state = BlockState.RESTING
                    return true
                }
                b.c = Vec3(b.c.x, y, b.c.z)
            }
            BlockState.SLIDING -> {
                b.slideT += dt
                val t = (b.slideT / SLIDE_S).coerceIn(0f, 1f)
                val e = t * t * (3 - 2 * t)
                val p = b.slideFrom.lerp(b.slideTo, e)
                b.c = Vec3(p.x, b.slideFrom.y + (b.slideTo.y - b.slideFrom.y) * e, p.z)
                if (t >= 1f) {
                    b.state = BlockState.RESTING
                    return true
                }
            }
            else -> Unit
        }
        return false
    }

    fun release(b: Block) {
        b.state = BlockState.FALLING
        b.vy = 0f
        b.fallT = 0f
    }
}
