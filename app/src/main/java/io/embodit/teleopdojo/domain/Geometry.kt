package io.embodit.teleopdojo.domain

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Head pose in Stage space. */
data class HeadPose(val position: Vec3, val rotation: Quat4) {
    val forward: Vec3
        get() = rotation.rotate(Vec3.AHEAD)

    val up: Vec3
        get() = rotation.rotate(Vec3.UP)

    val right: Vec3
        get() = rotation.rotate(Vec3.RIGHT)

    /** Yaw of the view direction (see [Yaw]); [fallback] when looking straight up or down. */
    fun yaw(fallback: Float = 0f): Float {
        val f = forward
        return if (hypot(f.x, f.z) < 1e-3f) fallback else Yaw.of(f)
    }

    /** Pitch of the view direction, radians (+ up). */
    val pitch: Float
        get() {
            val f = forward
            return atan2(f.y, hypot(f.x, f.z))
        }
}

/**
 * Yaw angles about the vertical, radians: 0 is Stage -Z (ahead at Stage start), positive turns left
 * (counter-clockwise seen from above, the right-hand rule about +Y).
 */
object Yaw {
    fun dir(yaw: Float): Vec3 = Vec3(-sin(yaw), 0f, -cos(yaw))

    fun of(v: Vec3): Float = atan2(-v.x, -v.z)

    /** Wraps to -pi..pi. */
    fun wrap(a: Float): Float {
        var d = a
        while (d > Math.PI) d -= (2 * Math.PI).toFloat()
        while (d < -Math.PI) d += (2 * Math.PI).toFloat()
        return d
    }
}

/** Turn of a panel towards the viewer: yaw about the vertical, then pitch, never roll. */
data class PanelFacing(val yaw: Float, val pitch: Float) {
    val rotation: Quat4
        get() = Quat4.axisAngle(Vec3.UP, yaw) * Quat4.axisAngle(Vec3.RIGHT, -pitch)

    /** Unit vector out of the panel's face, towards the viewer. */
    val normal: Vec3
        get() = Vec3(sin(yaw) * cos(pitch), sin(pitch), cos(yaw) * cos(pitch))

    val right: Vec3
        get() = Vec3(cos(yaw), 0f, -sin(yaw))

    val up: Vec3
        get() = normal.cross(right)
}

/**
 * The panel faces the viewer: yaw from the horizontal direction, pitch up to [maxPitch] (labels high in
 * the sky tilt down a long way). Within 10 cm horizontally the last yaw is kept.
 */
object FacePanel {
    private const val KEEP_YAW_WITHIN_M = 0.1f

    fun facing(panel: Vec3, head: Vec3, lastYaw: Float?, maxPitch: Float = 1.0471976f): PanelFacing {
        val to = head - panel
        val flat = hypot(to.x, to.z)
        val yaw = if (flat < KEEP_YAW_WITHIN_M && lastYaw != null) lastYaw else if (flat < 1e-4f) 0f else atan2(to.x, to.z)
        val pitch = atan2(to.y, flat).coerceIn(-maxPitch, maxPitch)
        return PanelFacing(yaw, pitch)
    }
}
