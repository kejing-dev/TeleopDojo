package io.embodit.teleopdojo.domain

import kotlin.math.abs

/** One frame of the teleop hand (Stage space). [pose] null = tracking lost (S22). */
data class HandInput(val pose: Pose?, val grip: Float, val trigger: Float)

/** What the arm did this frame. */
data class ArmFrame(
    val tcp: Pose,
    val commanded: Pose?,
    val clutched: Boolean,
    val grabbedNow: Boolean,
    val releasedNow: Boolean,
    val tableHit: Boolean,
    val selfHit: Boolean,
    val contact: Vec3?,
    val unreachable: Boolean,
    val limitJoint: Int,
    val limitWarn: DoubleArray,
    val singular: Boolean,
    val manipRatio: Double,
    val trackingLost: Boolean,
)

/**
 * The teleoperated UR5e (design rules.input / rules.mapping / rules.ik): grip latch -> clutch mapping (XRoboToolkit) or
 * follow mapping -> workspace clamp -> damped least-squares IK in the DH frame. The commanded target is held while the
 * clutch is open, so the arm stays put. Home = joint-space ease over 0.8 s. Pure JVM (ArmTest).
 */
class Arm {
    var q: DoubleArray = Ik.HOME.copyOf()
        private set
    private val ik = Ik()
    private val latch = GripLatch()
    private val clutch = ClutchMapper()
    private var followOffset: Quat4 = Quat4.IDENTITY
    private var followBlend = 1f
    private var followFrom: Vec3? = null
    private var homeFrom: DoubleArray? = null
    private var homeT = 0f
    private var lostWhileClutched = false
    /** After a tracking loss, pause or home the grip must be let go once before it can engage again. */
    private var needRelease = false
    private var singularOn = false
    private var tableWasHit = false
    private var selfWasHit = false

    /** Debug `tcp x y z`: a fixed target (DH base frame metres) the arm goes to, as if a hand held it. */
    var debugTarget: DoubleArray? = null

    val clutched: Boolean
        get() = latch.engaged

    fun tcp(cell: Workcell): Pose {
        val t = Ur5eKinematics.fkTcp(q, Gripper.TCP_M.toDouble())
        val p = cell.fromDh(t.position)
        val r = cell.rotFromDh(t.axis(0), t.axis(1), t.axis(2))
        return Pose(p, r)
    }

    /** Toolbar 回起始姿势: joint-space ease to home in 0.8 s; the clutch reference is dropped. */
    fun goHome() {
        homeFrom = q.copyOf()
        homeT = 0f
        clutch.release()
        latch.reset()
        followFrom = null
        needRelease = true
    }

    /** Drops the clutch reference (pause, settings, end of a round): the user has to grab again. */
    fun dropClutch() {
        clutch.release()
        latch.reset()
        followFrom = null
        needRelease = true
        lostWhileClutched = false
    }

    fun resetPose() {
        q = Ik.HOME.copyOf()
        homeFrom = null
        dropClutch()
    }

    fun step(dt: Float, hand: HandInput, cell: Workcell, config: TeleopConfig, heldHalf: Float, allowTeleop: Boolean): ArmFrame {
        val before = tcp(cell)
        var grabbedNow = false
        var releasedNow = false
        val trackingLost = hand.pose == null
        // ---- home ease
        homeFrom?.let { from ->
            homeT += dt
            val t = (homeT / HOME_S).coerceIn(0f, 1f)
            val e = if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).let { it * it * it } / 2f
            q = DoubleArray(6) { from[it] + Ur5eKinematics.wrapPi(Ik.HOME[it] - from[it]) * e }
            if (t >= 1f) homeFrom = null
            return frame(cell, before, null, false, false, false, null, false, trackingLost)
        }
        // ---- clutch (grip latch with hysteresis)
        if (needRelease && hand.grip < GripLatch.OFF) needRelease = false
        val gripValue = if (allowTeleop && !needRelease) hand.grip else 0f
        if (latch.update(gripValue)) {
            if (latch.engaged) {
                val pose = hand.pose
                if (pose != null) {
                    if (config.mapping == MappingMode.CLUTCH) clutch.engage(before, pose)
                    else {
                        followOffset = (pose.q.conjugate() * before.q).normalized()
                        followFrom = before.p
                        followBlend = 0f
                    }
                    grabbedNow = true
                    lostWhileClutched = false
                } else {
                    // grip pressed while the controller is not tracked: same as losing it while held (S22)
                    latch.reset()
                    lostWhileClutched = true
                    needRelease = true
                }
            } else {
                clutch.release()
                followFrom = null
                releasedNow = true
            }
        }
        if (latch.engaged && trackingLost) {
            // S22: the arm stops; after the pose is back the user has to grab again (no old reference)
            lostWhileClutched = true
            clutch.release()
            followFrom = null
            latch.reset()
            needRelease = true
            releasedNow = true
        }
        // ---- commanded target
        val dbg = debugTarget
        var commanded: Pose? = null
        if (dbg != null) {
            val p = cell.fromDh(dbg)
            commanded = Pose(p, Tool.down(cell.dirToStage(Vec3(1f, 0f, 0f))))
        } else if (latch.engaged && hand.pose != null) {
            commanded = if (config.mapping == MappingMode.CLUTCH) clutch.target(hand.pose, config)
            else {
                val towards = cell.base - hand.pose.p
                val t = FollowMapper.target(hand.pose, towards, config.controlMode, followOffset)
                val from = followFrom
                // grabbing with the gripper > 5 cm from the target: catch up over 0.4 s
                if (from != null && followBlend < 1f) {
                    val far = from.distanceTo(t.p) > 0.05f
                    followBlend = if (far) (followBlend + dt / 0.4f).coerceAtMost(1f) else 1f
                    Pose(from.lerp(t.p, followBlend), t.q)
                } else t
            }
        }
        if (commanded == null) return frame(cell, before, null, grabbedNow, releasedNow, false, null, false, trackingLost)
        // ---- workspace clamp and IK
        val c = Workspace.clamp(cell, commanded.p, commanded.q, heldHalf)
        val step = ik.solve(q, cell.toDh(c.tcp), cell.rotToDh(commanded.q))
        q = step.q
        val tableHit = c.tableHit && !tableWasHit
        tableWasHit = c.tableHit
        val selfHit = c.selfHit && !selfWasHit
        selfWasHit = c.selfHit
        return frame(cell, before, Pose(c.tcp, commanded.q), grabbedNow, releasedNow, tableHit, c.contact, selfHit, trackingLost, !step.reached && step.posErr > 0.02)
    }

    private fun frame(cell: Workcell, before: Pose, commanded: Pose?, grabbed: Boolean, released: Boolean, tableHit: Boolean, contact: Vec3?, selfHit: Boolean, lost: Boolean, unreachable: Boolean = false): ArmFrame {
        val margins = Ik.margins(q)
        var limitJoint = -1
        for (i in 0..5) if (margins[i] < Ik.LIMIT_MARGIN && (limitJoint < 0 || margins[i] < margins[limitJoint])) limitJoint = i
        val ratio = Ik.manipulability(q) / Ik.manipulability(Ik.HOME).coerceAtLeast(1e-9)
        singularOn = if (singularOn) ratio < SINGULAR_RATIO * 1.5 else ratio < SINGULAR_RATIO
        return ArmFrame(
            tcp = tcp(cell),
            commanded = commanded,
            clutched = latch.engaged,
            grabbedNow = grabbed,
            releasedNow = released,
            tableHit = tableHit,
            selfHit = selfHit,
            contact = contact,
            unreachable = unreachable,
            limitJoint = limitJoint,
            limitWarn = margins,
            singular = singularOn,
            manipRatio = ratio,
            trackingLost = lost && lostWhileClutched,
        )
    }

    companion object {
        const val HOME_S = 0.8f
        /** "快奇异" under 8 % of home manipulability (calibrate on the headset); clears at 1.5x. */
        const val SINGULAR_RATIO = 0.08

        fun anyNear(margins: DoubleArray, margin: Double): Boolean = margins.any { abs(it) < margin }
    }
}
