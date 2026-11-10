package io.embodit.teleopdojo.domain

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Teleop mapping ported from XRoboToolkit-Teleop-Sample-Python (MIT, © 2025 XR Robotics):
 *  - xrobotoolkit_teleop/utils/geometry.py: quat_diff_as_angle_axis, quaternion_to_angle_axis, apply_delta_pose
 *  - xrobotoolkit_teleop/common/base_teleop_controller.py: _update_ik (grip > 0.9 activates, refs recorded on that frame,
 *    delta position x scale_factor, delta rotation left-multiplied)
 *  - xrobotoolkit_teleop/utils/parallel_gripper_utils.py: calc_parallel_gripper_position (open + (close - open) * t)
 * Field names follow the manipulator_config of teleop_dual_ur5e_mujoco.py. Everything here works in Stage metres.
 */

/** Angle-axis pair (radians, unit axis). */
data class AngleAxis(val angle: Float, val axis: Vec3)

/** Port of geometry.py. */
object Geo {
    /** quaternion_to_angle_axis: w < 0 is flipped first (shortest arc); angles under 1e-6 count as zero. */
    fun quaternionToAngleAxis(q: Quat4): AngleAxis {
        var qq = q.normalized()
        if (qq.w < 0f) qq = Quat4(-qq.x, -qq.y, -qq.z, -qq.w)
        val w = qq.w.coerceIn(-1f, 1f)
        val angle = 2f * acos(w)
        val s = sqrt(maxOf(0f, 1f - w * w))
        if (angle < 1e-6f || s < 1e-6f) return AngleAxis(0f, Vec3(1f, 0f, 0f))
        return AngleAxis(angle, Vec3(qq.x / s, qq.y / s, qq.z / s))
    }

    /** quat_diff_as_angle_axis(q1, q2): the rotation that takes q1 to q2, i.e. q2 * q1^-1. */
    fun quatDiffAsAngleAxis(q1: Quat4, q2: Quat4): AngleAxis = quaternionToAngleAxis(q2 * q1.conjugate())

    /** apply_delta_pose: position + delta, orientation = delta * orientation. */
    fun applyDeltaPose(p: Vec3, q: Quat4, dp: Vec3, dRot: AngleAxis): Pair<Vec3, Quat4> =
        (p + dp) to (Quat4.axisAngle(dRot.axis, dRot.angle) * q).normalized()

    /** Angle between two orientations, radians. */
    fun angleBetween(a: Quat4, b: Quat4): Float = quatDiffAsAngleAxis(a, b).angle
}

/** XRoboToolkit control_mode. "position" here is OUR change: gripper kept pointing down, yaw follows the hand. */
enum class ControlMode { POSE, POSITION }

/** Clutch (relative, XRoboToolkit) or follow (absolute, for comparison). */
enum class MappingMode { CLUTCH, FOLLOW }

/**
 * The manipulator_config fields we keep (pose_source right_controller, control_trigger right_grip, gripper_trigger
 * right_trigger, control_mode, scale_factor, gripper_config parallel open_pos / close_pos in metres).
 */
data class TeleopConfig(
    val linkName: String = "tool0",
    val poseSource: String = "right_controller",
    val controlTrigger: String = "right_grip",
    val gripperTrigger: String = "right_trigger",
    val controlMode: ControlMode = ControlMode.POSE,
    val scaleFactor: Float = 1.0f,
    val mapping: MappingMode = MappingMode.CLUTCH,
    val gripperOpenPos: Float = Gripper.OPEN_M,
    val gripperClosePos: Float = 0f,
)

/** Pose in Stage space. */
data class Pose(val p: Vec3, val q: Quat4)

/**
 * grip > [ON] engages, < [OFF] releases (XRoboToolkit has the single 0.9 threshold; the 0.8 hysteresis is ours, so a
 * half-pressed grip on a real controller does not flicker).
 */
class GripLatch {
    var engaged = false
        private set

    /** Returns true on the frame the state changes. */
    fun update(value: Float): Boolean {
        val next = if (engaged) value >= OFF else value > ON
        if (next == engaged) return false
        engaged = next
        return true
    }

    fun reset() {
        engaged = false
    }

    companion object {
        const val ON = 0.9f
        const val OFF = 0.8f
    }
}

/**
 * Clutch mapping (port of base_teleop_controller._update_ik). On activation the end-effector reference ref_ee and the
 * controller reference ref_controller are recorded; every frame target = (p_ee_ref + (p - p_ref) * scale,
 * (q * q_ref^-1) * q_ee_ref). Release clears the WHOLE reference (the original clears only the xyz refs and gates the
 * quaternion ones through them), so re-grabbing never jumps, position or orientation.
 */
class ClutchMapper {
    private var refEe: Pose? = null
    private var refController: Pose? = null

    val active: Boolean
        get() = refEe != null

    fun engage(ee: Pose, controller: Pose) {
        refEe = ee
        refController = controller
    }

    fun release() {
        refEe = null
        refController = null
    }

    /** Target end-effector pose for the controller pose [c]; null when not engaged. */
    fun target(c: Pose, config: TeleopConfig): Pose? {
        val ee = refEe ?: return null
        val rc = refController ?: return null
        val dp = (c.p - rc.p) * config.scaleFactor
        val dRot = Geo.quatDiffAsAngleAxis(rc.q, c.q)
        val (p, q) = Geo.applyDeltaPose(ee.p, ee.q, dp, dRot)
        return Pose(p, if (config.controlMode == ControlMode.POSITION) Tool.downWithYawOf(q) else q)
    }
}

/**
 * Follow mapping (absolute, for the comparison): the TCP = controller position + a fixed 0.45 m horizontal push
 * towards the robot base, scale 1.0; orientation as the controller.
 */
object FollowMapper {
    const val PUSH_M = 0.45f

    fun target(c: Pose, towardsBase: Vec3, controlMode: ControlMode, grabOffset: Quat4): Pose {
        val flat = Vec3(towardsBase.x, 0f, towardsBase.z).normalized()
        val q = (c.q * grabOffset).normalized()
        return Pose(c.p + flat * PUSH_M, if (controlMode == ControlMode.POSITION) Tool.downWithYawOf(q) else q)
    }
}

/**
 * The tool frame of the gripper (Stage orientation [q]): local +Z is the approach (flange -> fingertips), local +X the
 * finger travel, +Y across the pads.
 */
object Tool {
    fun approach(q: Quat4): Vec3 = q.rotate(Vec3(0f, 0f, 1f))

    fun closing(q: Quat4): Vec3 = q.rotate(Vec3(1f, 0f, 0f))

    fun across(q: Quat4): Vec3 = q.rotate(Vec3(0f, 1f, 0f))

    /** Approach straight down, finger travel along the horizontal direction [closingFlat]. */
    fun down(closingFlat: Vec3): Quat4 {
        val z = Vec3(0f, -1f, 0f)
        var x = Vec3(closingFlat.x, 0f, closingFlat.z)
        if (x.length < 1e-4f) x = Vec3(1f, 0f, 0f)
        x = x.normalized()
        val y = z.cross(x).normalized()
        return Quat4.fromBasis(x, y, z)
    }

    /** "Position only": keep the gripper pointing down, keep the yaw of the closing axis of [q] (4 DoF). */
    fun downWithYawOf(q: Quat4): Quat4 {
        val x = closing(q)
        val flat = Vec3(x.x, 0f, x.z)
        return if (flat.length > 0.2f) down(flat) else down(across(q).cross(Vec3.UP))
    }

    /** Yaw of the closing axis about the vertical (atan2 on the horizontal projection), radians. */
    fun closingYaw(q: Quat4): Float {
        val x = closing(q)
        return atan2(x.z, x.x)
    }
}

/** The parallel gripper (design entities3d.gripper: 2F-85 class, 0-85 mm, TCP at the pad centres, 0.15 m from flange). */
object Gripper {
    const val OPEN_M = 0.085f
    const val TCP_M = 0.15f
    const val PAD_W = 0.010f // along the finger travel
    const val PAD_D = 0.020f // across
    const val PAD_H = 0.045f // along the approach
    const val FLANGE_R = 0.0375f
    const val FLANGE_H = 0.070f

    /** calc_parallel_gripper_position(open, close, t) with the trigger value t: 0 = open, 1 = closed. */
    fun opening(trigger: Float, config: TeleopConfig = TeleopConfig()): Float {
        val t = trigger.coerceIn(0f, 1f)
        return config.gripperOpenPos + (config.gripperClosePos - config.gripperOpenPos) * t
    }

    /** Lowest point of the pads under the TCP for a tool orientation [q] (Stage y offset, <= 0). */
    fun lowestBelowTcp(q: Quat4): Float {
        val z = Tool.approach(q)
        val y = Tool.across(q)
        return -(PAD_H / 2f * abs(z.y) + PAD_D / 2f * abs(y.y) + PAD_W / 2f * abs(Tool.closing(q).y))
    }
}

/** Small numeric helpers. */
object Num {
    fun deg(rad: Float): Float = Math.toDegrees(rad.toDouble()).toFloat()

    fun rad(deg: Float): Float = Math.toRadians(deg.toDouble()).toFloat()

    fun yawQuat(rad: Float): Quat4 = Quat4.axisAngle(Vec3.UP, rad)

    fun rotY(v: Vec3, rad: Float): Vec3 {
        val c = cos(rad)
        val s = sin(rad)
        return Vec3(v.x * c + v.z * s, v.y, -v.x * s + v.z * c)
    }
}
