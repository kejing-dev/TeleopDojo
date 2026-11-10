package io.embodit.teleopdojo.domain

import kotlin.math.cos
import kotlin.math.sin

/**
 * Where the course and the panels go (design rules.courseGeometry, rules.panelPlacement, spatialLayout).
 *
 * Course-local space: the pad centre C is the origin ON THE FLOOR, +Y up (so a local y is a height above the floor),
 * local -Z is the course's "ahead" (your view direction when it was placed), +X its right. The two poles stand on the
 * X axis. Stage = origin + yaw rotation of course-local ([CourseFrame]).
 *
 * Every pose given to a panel goes through [placeUpright]: only the HORIZONTAL heading of the head is used (pitch and
 * roll forced to 0), so a head tilted down / up / sideways never tilts a panel (profile D9 hard gate).
 */
data class CourseFrame(val origin: Vec3, val yaw: Float) {
    private val q: Quat4
        get() = Quat4.axisAngle(Vec3.UP, yaw)

    fun toStage(local: Vec3): Vec3 = origin + q.rotate(local)

    fun toLocal(stage: Vec3): Vec3 = q.conjugate().rotate(stage - origin)

    fun dirToLocal(stage: Vec3): Vec3 = q.conjugate().rotate(stage)

    fun dirToStage(local: Vec3): Vec3 = q.rotate(local)

    /** A heading in course-local (Yaw convention) as a Stage rotation about the vertical. */
    fun rotToStage(localYaw: Float): Quat4 = Quat4.axisAngle(Vec3.UP, yaw + localYaw)

    fun turned(deltaYaw: Float): CourseFrame = copy(yaw = Yaw.wrap(yaw + deltaYaw))

    fun movedTo(stageOrigin: Vec3): CourseFrame = copy(origin = stageOrigin)
}

/** A pose with no pitch and no roll: a position and a heading about the vertical (Yaw convention, + = left). */
data class Upright(val position: Vec3, val yaw: Float) {
    val rotation: Quat4
        get() = Quat4.axisAngle(Vec3.UP, yaw)
}

/**
 * The only way content is posed from the head (D9): [relYawDeg] around you (+ = left, the [Yaw] convention), [distM]
 * along the line of sight, [downDeg] below eye level. The face (+Z) of the result turns back to the head, about the
 * vertical only.
 */
fun placeUpright(head: HeadPose, relYawDeg: Float, distM: Float, downDeg: Float): Upright {
    val yaw0 = head.yaw(0f)
    val a = yaw0 + Math.toRadians(relYawDeg.toDouble()).toFloat()
    val down = Math.toRadians(downDeg.toDouble()).toFloat()
    val flat = distM * cos(down)
    val dir = Yaw.dir(a)
    val p = head.position + dir * flat - Vec3(0f, distM * sin(down), 0f)
    return Upright(p, Yaw.wrap(a))
}

object Placement {
    /** Control panel: 38° left of the view, 0.9 m, 15° below the eye (design rules.panelPlacement). */
    const val PANEL_YAW_DEG = 38f
    const val PANEL_DIST_M = 0.9f
    const val PANEL_DOWN_DEG = 15f

    /** Blocked dialog: straight ahead, 0.6 m, 5° down. */
    const val DIALOG_DIST_M = 0.6f
    const val DIALOG_DOWN_DEG = 5f

    /** Course centre: 3.0 m ahead, turned 16° to the right, never closer than 2.0 m to your feet. */
    const val COURSE_DIST_M = 3.0f
    const val COURSE_RIGHT_DEG = 16f
    const val COURSE_MIN_M = 2.0f

    /** Eye heights over the floor for the estimated floor (design input.dataSources). */
    const val STAND_EYE_M = 1.6f
    const val SIT_EYE_M = 1.2f

    /** Default course frame for a head pose and a floor height (Stage y). */
    fun courseAhead(head: HeadPose, floorY: Float): CourseFrame {
        val up = placeUpright(head, -COURSE_RIGHT_DEG, COURSE_DIST_M, 0f)
        val c = Vec3(up.position.x, floorY, up.position.z)
        return CourseFrame(c, head.yaw(0f))
    }

    /**
     * Where a ray hit on the floor puts the course centre: the hit itself, pushed out to [COURSE_MIN_M] from your feet
     * when it lands closer (design entities3d.courseGhost).
     */
    fun courseAt(hit: Vec3, head: HeadPose, floorY: Float): Vec3 {
        val feet = Vec3(head.position.x, floorY, head.position.z)
        val flat = Vec3(hit.x - feet.x, 0f, hit.z - feet.z)
        val d = flat.length
        if (d >= COURSE_MIN_M) return Vec3(hit.x, floorY, hit.z)
        val dir = if (d < 1e-3f) Yaw.dir(head.yaw(0f)) else flat / d
        return feet + dir * COURSE_MIN_M
    }

    /**
     * Ray against the horizontal floor plane at [floorY]: null when it points level or up, or hits further than [maxM].
     */
    fun rayFloor(origin: Vec3, dir: Vec3, floorY: Float, maxM: Float = 12f): Vec3? {
        if (dir.y > -0.02f) return null
        val t = (floorY - origin.y) / dir.y
        if (t <= 0f || t > maxM) return null
        return origin + dir * t
    }

    /** The control panel's pose for the current head (first show and "bring panel back", D9 / D21). */
    fun panel(head: HeadPose): Upright = placeUpright(head, PANEL_YAW_DEG, PANEL_DIST_M, PANEL_DOWN_DEG)

    fun dialog(head: HeadPose): Upright = placeUpright(head, 0f, DIALOG_DIST_M, DIALOG_DOWN_DEG)

    /**
     * v0.1.2: where the system opens the main window (native-window-move §4, seen on the emulator): straight ahead of
     * the head at about 1.75 m, centred on the line of sight. Only an estimate (the app cannot read or set a window's
     * pose, and the user may drag it): used to keep waypoint rings out of it and to fade the drone in front of it.
     */
    const val WINDOW_DIST_M = 1.75f

    fun window(head: HeadPose): Upright = placeUpright(head, 0f, WINDOW_DIST_M, 0f)

    /** The "open panel" chip on the Stage when the window is closed: 20° right, 0.9 m, 25° down (beside the course). */
    fun openChip(head: HeadPose): Upright = placeUpright(head, -20f, 0.9f, 25f)

    /**
     * "平视前方" while the main window waits for a level head (q4-3): straight ahead on the line of sight (the head's
     * own pitch, clamped to ±40°) so it is in view looking down or up, 0.75 m; still upright, yaw only (D9).
     */
    fun levelHint(head: HeadPose): Upright {
        val pitchDeg = Math.toDegrees(head.pitch.toDouble()).toFloat().coerceIn(-40f, 40f)
        return placeUpright(head, 0f, 0.75f, -pitchDeg)
    }
}

/** Panel facing helper: the yaw (Yaw convention) of a panel at [panel] whose face (+Z) turns to [viewer]. */
fun faceYaw(panel: Vec3, viewer: Vec3): Float {
    val to = viewer - panel
    return kotlin.math.atan2(to.x, to.z)
}
