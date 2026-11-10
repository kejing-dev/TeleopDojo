package io.embodit.teleopdojo.domain

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/** The three drills (design content.levels) and free play. */
enum class LevelId { REACH, PICK, STACK, FREE }

enum class Difficulty { EASY, STANDARD, PICKY }

/** A light (reach drill): table-local centre (right hand), radius, and for picky an approach direction to match. */
data class LightSpec(val c: Vec3, val radius: Float, val arrow: Vec3? = null)

/** A block to start with: table-local centre on the table (x, z), yaw, edge. */
data class BlockSpec(val x: Float, val z: Float, val size: Float, val yawDeg: Float = 0f)

/** A target square on the table: a tray (raised rim) or a dashed grid cell; [alignDeg] > 0 asks the block edges to match. */
data class TraySpec(val x: Float, val z: Float, val inner: Float, val grid: Boolean, val alignDeg: Float = 0f)

/** One playable layout (all table-local, right hand: mirrored for the left hand by [Workcell.hand]). */
data class Layout(
    val level: LevelId,
    val difficulty: Difficulty,
    val lights: List<LightSpec> = emptyList(),
    val tolerance: Float = 0.015f,
    val dwellS: Float = 0.3f,
    val angleDeg: Float = 0f,
    val blocks: List<BlockSpec> = emptyList(),
    val trays: List<TraySpec> = emptyList(),
    val stackBase: Vec3? = null,
    val stackTol: Float = 0.015f,
    val holdS: Float = 2f,
    val referenceS: Float = 90f,
    val round: Int = 0,
) {
    val targets: Int
        get() = when (level) {
            LevelId.REACH -> lights.size
            LevelId.PICK -> blocks.size
            LevelId.STACK -> 1
            LevelId.FREE -> if (lights.isNotEmpty()) lights.size else if (stackBase != null) 1 else blocks.size
        }

    /** The kind of drill this layout plays (free play draws one of the three). */
    val kind: LevelId
        get() = if (level != LevelId.FREE) level else if (lights.isNotEmpty()) LevelId.REACH else if (stackBase != null) LevelId.STACK else LevelId.PICK

    val graspRules: GraspRules
        get() = if (difficulty == Difficulty.EASY) GraspRules.EASY else GraspRules.STANDARD
}

/**
 * Fixed layouts (design content.levels + levelLayout): inside the practice region x in [-0.20, +0.45], z in [-0.10, +0.30]
 * (table centre = origin), clear of the base column by >= 5 cm, objects at least a block apart. LevelsTest checks every
 * target with the IK from home.
 */
object Levels {
    private val DOWN = Vec3(0f, -1f, 0f)
    private fun tilt(dx: Float, dz: Float): Vec3 = Vec3(dx, -1f, dz).normalized()

    fun layout(level: LevelId, d: Difficulty): Layout =
        when (level) {
            LevelId.REACH -> when (d) {
                Difficulty.EASY -> Layout(level, d, lights = listOf(
                    LightSpec(Vec3(0.00f, 0.15f, 0.05f), 0.025f), LightSpec(Vec3(0.20f, 0.10f, 0.12f), 0.025f), LightSpec(Vec3(0.35f, 0.20f, 0.00f), 0.025f),
                    LightSpec(Vec3(0.12f, 0.25f, 0.24f), 0.025f), LightSpec(Vec3(-0.12f, 0.12f, 0.18f), 0.025f),
                ), tolerance = 0.025f, dwellS = 0.2f, referenceS = 60f)
                Difficulty.STANDARD -> Layout(level, d, lights = STD_LIGHTS.map { LightSpec(it, 0.02f) }, tolerance = 0.015f, dwellS = 0.3f, referenceS = 90f)
                Difficulty.PICKY -> Layout(level, d, lights = STD_LIGHTS.zip(PICKY_ARROWS).map { (c, a) -> LightSpec(c, 0.02f, a) }, tolerance = 0.015f, dwellS = 0.3f, angleDeg = 20f, referenceS = 120f)
            }
            LevelId.PICK -> when (d) {
                Difficulty.EASY -> Layout(level, d, blocks = listOf(BlockSpec(0.30f, 0.10f, 0.06f)), trays = listOf(TraySpec(-0.05f, 0.16f, 0.16f, grid = false)), referenceS = 60f)
                Difficulty.STANDARD -> Layout(level, d, blocks = listOf(BlockSpec(0.36f, 0.00f, 0.05f), BlockSpec(0.24f, 0.22f, 0.05f), BlockSpec(0.42f, 0.16f, 0.05f)), trays = listOf(TraySpec(-0.06f, 0.15f, 0.12f, grid = false)), referenceS = 90f)
                Difficulty.PICKY -> Layout(level, d, blocks = listOf(BlockSpec(0.36f, 0.00f, 0.05f, 20f), BlockSpec(0.24f, 0.22f, 0.05f, 35f), BlockSpec(0.42f, 0.16f, 0.05f, -25f)),
                    trays = listOf(TraySpec(-0.10f, 0.02f, 0.08f, true, 15f), TraySpec(-0.10f, 0.14f, 0.08f, true, 15f), TraySpec(-0.10f, 0.26f, 0.08f, true, 15f)), referenceS = 120f)
            }
            LevelId.STACK -> when (d) {
                Difficulty.EASY -> Layout(level, d, blocks = listOf(BlockSpec(0.30f, 0.00f, 0.06f), BlockSpec(0.38f, 0.18f, 0.06f), BlockSpec(0.16f, 0.25f, 0.06f)), stackBase = Vec3(0.00f, 0f, 0.12f), stackTol = 0.025f, referenceS = 60f)
                Difficulty.STANDARD -> Layout(level, d, blocks = listOf(BlockSpec(0.30f, 0.00f, 0.05f), BlockSpec(0.38f, 0.18f, 0.05f), BlockSpec(0.16f, 0.25f, 0.05f)), stackBase = Vec3(0.00f, 0f, 0.12f), stackTol = 0.015f, referenceS = 90f)
                Difficulty.PICKY -> Layout(level, d, blocks = listOf(BlockSpec(0.30f, 0.00f, 0.04f), BlockSpec(0.40f, 0.15f, 0.04f), BlockSpec(0.22f, 0.26f, 0.04f), BlockSpec(0.14f, 0.10f, 0.04f)), stackBase = Vec3(-0.04f, 0f, 0.14f), stackTol = 0.010f, referenceS = 120f)
            }
            LevelId.FREE -> FreePlay.generate(1, 1L)
        }

    /** Standard reach: six lights, two of them 30 cm or more over the table. */
    private val STD_LIGHTS = listOf(
        Vec3(0.05f, 0.12f, 0.00f), Vec3(0.30f, 0.15f, 0.08f), Vec3(0.38f, 0.32f, -0.04f),
        Vec3(0.15f, 0.34f, 0.20f), Vec3(-0.15f, 0.10f, 0.16f), Vec3(0.25f, 0.08f, 0.26f),
    )

    /** Picky reach: the approach to match at each light (30° tilts around straight down). */
    private val PICKY_ARROWS = listOf(DOWN, tilt(0.58f, 0f), tilt(0f, -0.58f), tilt(-0.4f, 0.4f), tilt(-0.58f, 0f), tilt(0f, 0.4f))

    /** Reference times for the score (design rules.scoring.score). */
    fun referenceS(d: Difficulty): Float = when (d) { Difficulty.EASY -> 60f; Difficulty.STANDARD -> 90f; Difficulty.PICKY -> 120f }
}

/**
 * Solvability of a target (design freeMode.solvability): inside the practice region, >= 6 cm from the table edges, out of
 * the base column, and reachable by the IK from home with every joint >= 15° from its limit and manipulability >= 30 %
 * of home. The fixed layouts go through the same check (LevelsTest).
 */
object Reachability {
    private val ik = Ik()
    private val cell = Workcell(Vec3(0f, 0.75f, 0f), 0f)

    fun inRegion(x: Float, z: Float): Boolean =
        x in Workcell.REGION_X0..Workcell.REGION_X1 && z in Workcell.REGION_Z0..Workcell.REGION_Z1 &&
            x > -Workcell.WIDTH / 2 + 0.06f && x < Workcell.WIDTH / 2 - 0.06f && z > -Workcell.DEPTH / 2 + 0.06f && z < Workcell.DEPTH / 2 - 0.06f

    fun clearOfBase(x: Float, z: Float, margin: Float): Boolean = hypot(x - Workcell.BASE_LOCAL.x, z - Workcell.BASE_LOCAL.z) >= Workcell.EXCL_R + margin

    /** IK check of a table-local TCP with approach [approach] (default straight down). */
    fun reachable(tcpLocal: Vec3, approach: Vec3 = Vec3(0f, -1f, 0f), closingYawDeg: Float = 0f): Boolean {
        val q = approachQuat(approach, closingYawDeg)
        val stage = cell.toStage(tcpLocal)
        val p = cell.toDh(stage)
        val r = cell.rotToDh(cell.rotToStage(q))
        val s = ik.converge(Ik.HOME, p, r, 60)
        if (!s.reached) return false
        val (margin, _) = Ik.limitMargin(s.q)
        if (margin < Math.toRadians(15.0)) return false
        return s.manip >= 0.3 * Ik.manipulability(Ik.HOME)
    }

    /** Tool orientation (table-local) whose approach is [approach] and whose closing axis is as close to yaw [deg] as it can be. */
    fun approachQuat(approach: Vec3, closingYawDeg: Float): Quat4 {
        val z = approach.normalized()
        val want = Num.rotY(Vec3(1f, 0f, 0f), Num.rad(closingYawDeg))
        var x = want - z * want.dot(z)
        if (x.length < 1e-3f) x = Vec3(0f, 0f, 1f) - z * z.z
        x = x.normalized()
        val y = z.cross(x).normalized()
        return Quat4.fromBasis(x, y, z)
    }
}

/**
 * Free play (design content.freeMode): seed = timestamp; each round draws one of the three drills; positions sampled
 * uniformly in the practice region and kept only when [Reachability] passes (50 tries, then one step easier). Round n:
 * tolerance max(1.0, 2.5 - 0.15 n) cm, targets min(8, 4 + n / 2), orientation from n >= 4, stacking from n >= 6.
 */
object FreePlay {
    fun tolerance(n: Int): Float = maxOf(0.010f, 0.025f - 0.0015f * n)

    fun targets(n: Int): Int = minOf(8, 4 + n / 2)

    fun generate(n: Int, seed: Long): Layout = generate(n, seed, Random(seed * 31 + n))

    private fun generate(n: Int, seed: Long, rnd: Random, easier: Int = 0): Layout {
        val nn = maxOf(1, n - easier * 2)
        val kinds = if (nn >= 6) listOf(LevelId.REACH, LevelId.PICK, LevelId.STACK) else listOf(LevelId.REACH, LevelId.PICK)
        val kind = kinds[rnd.nextInt(kinds.size)]
        val tol = tolerance(nn)
        val diff = if (nn <= 2) Difficulty.EASY else if (nn < 6) Difficulty.STANDARD else Difficulty.PICKY
        val ref = Levels.referenceS(diff)
        return when (kind) {
            LevelId.REACH -> {
                val want = targets(nn)
                val lights = ArrayList<LightSpec>()
                var tries = 0
                while (lights.size < want && tries < 50 * want) {
                    tries++
                    val x = Workcell.REGION_X0 + rnd.nextFloat() * (Workcell.REGION_X1 - Workcell.REGION_X0)
                    val z = Workcell.REGION_Z0 + rnd.nextFloat() * (Workcell.REGION_Z1 - Workcell.REGION_Z0)
                    val y = 0.06f + rnd.nextFloat() * 0.30f
                    val c = Vec3(x, y, z)
                    if (!Reachability.inRegion(x, z) || !Reachability.clearOfBase(x, z, 0.05f)) continue
                    if (lights.any { it.c.distanceTo(c) < 0.08f }) continue
                    val arrow = if (nn >= 4) {
                        val a = rnd.nextFloat() * 2 * Math.PI.toFloat()
                        Vec3(0.5f * cos(a), -1f, 0.5f * sin(a)).normalized()
                    } else null
                    if (!Reachability.reachable(c, arrow ?: Vec3(0f, -1f, 0f))) continue
                    lights += LightSpec(c, 0.02f, arrow)
                }
                if (lights.size < want) return generate(n, seed, rnd, easier + 1)
                Layout(LevelId.FREE, diff, lights = lights, tolerance = tol, dwellS = 0.3f, angleDeg = if (nn >= 4) 20f else 0f, referenceS = ref, round = n)
            }
            else -> {
                val size = if (nn <= 2) 0.06f else 0.05f
                val count = if (kind == LevelId.STACK) 3 else minOf(3, 1 + nn / 3)
                val placed = ArrayList<Pair<Float, Float>>()
                fun free(x: Float, z: Float, gap: Float) = placed.none { (px, pz) -> hypot(px - x, pz - z) < gap }
                fun sample(gap: Float, check: (Float, Float) -> Boolean): Pair<Float, Float>? {
                    repeat(50) {
                        val x = Workcell.REGION_X0 + rnd.nextFloat() * (Workcell.REGION_X1 - Workcell.REGION_X0)
                        val z = Workcell.REGION_Z0 + rnd.nextFloat() * (Workcell.REGION_Z1 - Workcell.REGION_Z0)
                        if (Reachability.inRegion(x, z) && Reachability.clearOfBase(x, z, 0.05f) && free(x, z, gap) && check(x, z)) return x to z
                    }
                    return null
                }
                val target = sample(0.20f) { x, z -> Reachability.reachable(Vec3(x, 0.10f, z)) } ?: return generate(n, seed, rnd, easier + 1)
                placed += target
                val blocks = ArrayList<BlockSpec>()
                repeat(count) {
                    val p = sample(size * 2 + 0.04f) { x, z -> Reachability.reachable(Vec3(x, size / 2, z)) } ?: return generate(n, seed, rnd, easier + 1)
                    placed += p
                    val yaw = if (nn >= 4) rnd.nextFloat() * 60f - 30f else 0f
                    blocks += BlockSpec(p.first, p.second, size, yaw)
                }
                if (kind == LevelId.STACK) Layout(LevelId.FREE, diff, blocks = blocks, stackBase = Vec3(target.first, 0f, target.second), stackTol = tol, referenceS = ref, round = n)
                else Layout(LevelId.FREE, diff, blocks = blocks, trays = listOf(TraySpec(target.first, target.second, maxOf(0.12f, 0.18f - 0.01f * nn), grid = false, alignDeg = if (nn >= 4) 15f else 0f)), referenceS = ref, round = n)
            }
        }
    }
}
