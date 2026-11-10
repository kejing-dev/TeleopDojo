package io.embodit.teleopdojo.domain

import kotlin.math.hypot

/**
 * The virtual workcell (design frame): table top 1.00 x 0.80 m, 4 cm thick, at [topY] over the floor (Stage y = 0 is the
 * floor under the headset). Table-local space: origin at the centre of the table TOP, +Y up, +Z towards the user (the
 * near edge at z = +0.40), -Z the far edge. The UR5e base stands on the top, 0.12 m in from the middle of the far edge,
 * and at J1 = 0 its arm reaches towards the user.
 *
 * Stage = [origin] + yaw rotation of table-local; [yaw] uses the [Yaw] convention (0 = table far edge towards Stage -Z).
 * Right-handed mode: practice items sit at x in [-0.20, +0.45]; left-handed mode mirrors x ([mirror] = -1).
 */
data class Workcell(val origin: Vec3, val yaw: Float, val mirror: Float = 1f) {
    private val q: Quat4
        get() = Quat4.axisAngle(Vec3.UP, yaw)

    val rotation: Quat4
        get() = q

    val topY: Float
        get() = origin.y

    fun toStage(local: Vec3): Vec3 = origin + q.rotate(local)

    fun toLocal(stage: Vec3): Vec3 = q.conjugate().rotate(stage - origin)

    fun dirToStage(local: Vec3): Vec3 = q.rotate(local)

    fun dirToLocal(stage: Vec3): Vec3 = q.conjugate().rotate(stage)

    fun rotToStage(local: Quat4): Quat4 = (q * local).normalized()

    fun rotToLocal(stage: Quat4): Quat4 = (q.conjugate() * stage).normalized()

    /** A practice-region point written for the right hand (x to the right), mirrored for the left hand. */
    fun hand(local: Vec3): Vec3 = Vec3(local.x * mirror, local.y, local.z)

    /** Base mount point (Stage). */
    val base: Vec3
        get() = toStage(BASE_LOCAL)

    /**
     * Robot-local (Y up, what [Ur5e.frames] draws) -> table-local: the robot root sits on [BASE_LOCAL] turned +90° about
     * Y, so its zero-pose reach (local -X) points at the user (+Z).
     */
    val robotYawInTable: Float
        get() = Num.rad(90f)

    /** Robot root pose in Stage (what the view uses). */
    val robotRotation: Quat4
        get() = (q * Num.yawQuat(robotYawInTable)).normalized()

    /** Stage point -> UR DH base frame (Z up, metres). */
    fun toDh(stage: Vec3): DoubleArray {
        val robotLocal = robotRotation.conjugate().rotate(stage - base)
        return Ur5eKinematics.toDh(robotLocal)
    }

    /** UR DH base frame -> Stage. */
    fun fromDh(dh: DoubleArray): Vec3 = base + robotRotation.rotate(Ur5eKinematics.fromDh(dh))

    /** Stage direction -> DH. */
    fun dirToDh(stage: Vec3): DoubleArray = Ur5eKinematics.toDh(robotRotation.conjugate().rotate(stage))

    fun dirFromDh(dh: DoubleArray): Vec3 = robotRotation.rotate(Ur5eKinematics.fromDh(dh))

    /** Stage orientation of a tool -> a DH rotation matrix (columns = tool x / y / z in DH). */
    fun rotToDh(stage: Quat4): Array<DoubleArray> {
        val x = dirToDh(Tool.closing(stage))
        val y = dirToDh(Tool.across(stage))
        val z = dirToDh(Tool.approach(stage))
        return arrayOf(x, y, z)
    }

    /** DH rotation (columns x, y, z) -> Stage quaternion. */
    fun rotFromDh(x: DoubleArray, y: DoubleArray, z: DoubleArray): Quat4 = Quat4.fromBasis(dirFromDh(x).normalized(), dirFromDh(y).normalized(), dirFromDh(z).normalized())

    companion object {
        const val WIDTH = 1.00f
        const val DEPTH = 0.80f
        const val THICK = 0.04f
        const val SIT_H = 0.75f
        const val STAND_H = 0.95f
        const val MIN_H = 0.65f
        const val MAX_H = 1.05f
        val BASE_LOCAL = Vec3(0f, 0f, -DEPTH / 2f + 0.12f)

        /** Practice region (table-local, right hand): x in [-0.20, +0.45], z in [-0.10, +0.30]. */
        const val REGION_X0 = -0.20f
        const val REGION_X1 = 0.45f
        const val REGION_Z0 = -0.10f
        const val REGION_Z1 = 0.30f

        /** Base exclusion column: radius 0.15 m around the base axis, up to 0.35 m over the top. */
        const val EXCL_R = 0.15f
        const val EXCL_H = 0.35f

        /** Workspace box for the gripper (rules.ik.workspace). */
        const val CLEAR_M = 0.003f
        const val HIT_MARGIN = 0.005f
        const val MAX_UP = 0.65f
        const val SIDE_OUT = 0.05f

        /** Head to near edge 0.35 m: the table centre sits 0.75 m out along the workcell direction. */
        const val NEAR_EDGE_M = 0.35f

        /** Right hand: the workcell turns 45° to the right of your view (the window keeps the straight-ahead). */
        const val TURN_DEG = 45f

        /** Default workcell for a head pose: yaw only (D9), turned [TURN_DEG] towards the teleop hand. */
        fun forHead(head: HeadPose, heightM: Float, leftHanded: Boolean, headYaw: Float = head.yaw(0f)): Workcell {
            val yaw = Yaw.wrap(headYaw + Num.rad(if (leftHanded) TURN_DEG else -TURN_DEG))
            return at(Vec3(head.position.x, 0f, head.position.z), yaw, heightM, leftHanded)
        }

        /** Workcell whose near edge is [NEAR_EDGE_M] out from [feet] towards [yaw]. */
        fun at(feet: Vec3, yaw: Float, heightM: Float, leftHanded: Boolean): Workcell {
            val c = feet + Yaw.dir(yaw) * (NEAR_EDGE_M + DEPTH / 2f)
            return Workcell(Vec3(c.x, heightM, c.z), yaw, if (leftHanded) -1f else 1f)
        }

        /** Workcell centred on a floor marker [marker] (S02), facing [yaw]. */
        fun onMarker(marker: Vec3, yaw: Float, heightM: Float, leftHanded: Boolean): Workcell =
            Workcell(Vec3(marker.x, heightM, marker.z), yaw, if (leftHanded) -1f else 1f)
    }
}

/** Result of clamping a commanded TCP into the workspace (rules.ik.workspace). */
data class Clamp(val tcp: Vec3, val tableHit: Boolean, val selfHit: Boolean, val contact: Vec3?)

/**
 * The workspace box: the lowest point of gripper + held block stays >= 3 mm over the top; the TCP within 0.65 m over the
 * top, 5 cm outside the side / far edges, not past the near edge; outside the base exclusion column.
 */
object Workspace {
    fun clamp(cell: Workcell, tcpStage: Vec3, q: Quat4, heldBlockHalf: Float): Clamp {
        var l = cell.toLocal(tcpStage)
        val lowBelow = if (heldBlockHalf > 0f) maxOf(-Gripper.lowestBelowTcp(q), heldBlockHalf) else -Gripper.lowestBelowTcp(q)
        var tableHit = false
        var selfHit = false
        var contact: Vec3? = null
        val minY = Workcell.CLEAR_M + lowBelow
        if (l.y < minY) {
            // counted when the command goes 5 mm past the clearance (taking a block off the table is not a hit)
            tableHit = l.y < minY - Workcell.HIT_MARGIN
            contact = cell.toStage(Vec3(l.x, 0f, l.z))
            l = Vec3(l.x, minY, l.z)
        }
        l = Vec3(
            l.x.coerceIn(-Workcell.WIDTH / 2f - Workcell.SIDE_OUT, Workcell.WIDTH / 2f + Workcell.SIDE_OUT),
            l.y.coerceAtMost(Workcell.MAX_UP),
            l.z.coerceIn(-Workcell.DEPTH / 2f - Workcell.SIDE_OUT, Workcell.DEPTH / 2f),
        )
        val b = Workcell.BASE_LOCAL
        val dx = l.x - b.x
        val dz = l.z - b.z
        val r = hypot(dx, dz)
        if (l.y < Workcell.EXCL_H && r < Workcell.EXCL_R) {
            selfHit = true
            val k = if (r < 1e-4f) 1f else Workcell.EXCL_R / r
            l = if (r < 1e-4f) Vec3(b.x, l.y, b.z + Workcell.EXCL_R) else Vec3(b.x + dx * k, l.y, b.z + dz * k)
        }
        return Clamp(cell.toStage(l), tableHit, selfHit, contact)
    }
}

/** TCP pose (Stage) of joint angles [q] on this workcell. */
fun Workcell.tcpOf(q: DoubleArray): Pose {
    val t = Ur5eKinematics.fkTcp(q, Gripper.TCP_M.toDouble())
    return Pose(fromDh(t.position), rotFromDh(t.axis(0), t.axis(1), t.axis(2)))
}
