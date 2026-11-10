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

    /** "往左 / 往右看一点" while the window waits for a clear spot (profile 48): 20° to the side to turn to, 0.75 m, 5° down. */
    fun turnHint(head: HeadPose, side: Int): Upright = placeUpright(head, 20f * side, 0.75f, 5f)
}

/**
 * profile 48 (QA #2 q2-1): the ONE place that says where the main window lands and whether that spot keeps the
 * workcell clear. The system opens the window square to the line of sight [Placement.WINDOW_DIST_M] ahead and SDK 6.1
 * cannot move it, so the only lever is when to open: every open (start, the "打开面板" chip, [叫回面板], restore
 * after −) waits in [LevelGate] until [turnDeg] says 0, i.e. the window frame, with the TabBar over it and the
 * Toolbar / system strip under it, is at least [MIN_GAP_DEG] from every point that matters: the table, the base
 * column, the gripper's whole workspace over the practice region (targets on the top, the gripper up to
 * [Workcell.MAX_UP]), the tip tag, and the arm as it stands now. Page switches stay inside the same window.
 */
object WindowSpot {
    const val MIN_GAP_DEG = 10f

    /** The TabBar capsule over the window and the Toolbar + system strip (dot, ×, −) under it, in window dp. */
    const val OVER_DP = 88f
    const val UNDER_DP = 72f

    /** The tip tag over the far side of the table (right hand, table-local). */
    val TIP_LOCAL = Vec3(0.30f, 0.62f, -0.30f)

    /** The post with "打开面板" at the left near corner (right hand, table-local) and its height; the chip sits on top. */
    val CHIP_CORNER = Vec3(-0.44f, 0f, 0.34f)
    const val CHIP_POST_H = 0.35f

    fun chip(cell: Workcell): Vec3 = cell.toStage(cell.hand(CHIP_CORNER) + Vec3(0f, CHIP_POST_H + 0.07f, 0f))

    /** Window size in dp (PanelSizes) and metres per dp (the window reports it; 0.8 mm until it does). */
    data class Size(val wDp: Float, val hDp: Float, val dpM: Float) {
        val halfW: Float get() = wDp * dpM / 2f
        val top: Float get() = (hDp / 2f + OVER_DP) * dpM
        val bottom: Float get() = -(hDp / 2f + UNDER_DP) * dpM
    }

    /** Points of the workcell the window must keep clear of (Stage); [live] adds the arm as it stands now. */
    fun keepClear(cell: Workcell, live: List<Vec3> = emptyList()): List<Vec3> {
        val pts = ArrayList<Vec3>(64 + live.size)
        for (x in floatArrayOf(-0.5f, -0.25f, 0f, 0.25f, 0.5f)) for (z in floatArrayOf(-0.4f, 0f, 0.4f)) pts += cell.toStage(Vec3(x, 0f, z))
        for (y in floatArrayOf(0f, 0.3f, 0.6f, 0.9f)) pts += cell.toStage(Workcell.BASE_LOCAL + Vec3(0f, y, 0f))
        val xs = floatArrayOf(Workcell.REGION_X0, (Workcell.REGION_X0 + Workcell.REGION_X1) / 2f, Workcell.REGION_X1)
        val zs = floatArrayOf(Workcell.REGION_Z0, (Workcell.REGION_Z0 + Workcell.REGION_Z1) / 2f, Workcell.REGION_Z1)
        val ys = floatArrayOf(0f, Workcell.MAX_UP / 2f, Workcell.MAX_UP)
        for (x in xs) for (z in zs) for (y in ys) pts += cell.toStage(cell.hand(Vec3(x, y, z)))
        pts += cell.toStage(cell.hand(TIP_LOCAL))
        pts += live
        return pts
    }

    /** Smallest angle (degrees) from the eye between any [keep] point and the window opened looking along [yaw]. */
    fun gapDeg(eye: Vec3, yaw: Float, keep: List<Vec3>, size: Size): Float {
        val f = Yaw.dir(yaw)
        val r = Vec3(cos(yaw), 0f, -sin(yaw))
        val d = Placement.WINDOW_DIST_M
        var worst = 180f
        for (p in keep) {
            val v = p - eye
            val len = v.length
            if (len < 1e-4f) continue
            val ahead = v.dot(f)
            val x = v.dot(r)
            // the closest point of the frame: the projection on the window plane, clamped into the frame
            val cx: Float
            val cy: Float
            if (ahead > 1e-3f) {
                cx = (x * d / ahead).coerceIn(-size.halfW, size.halfW)
                cy = (v.y * d / ahead).coerceIn(size.bottom, size.top)
            } else {
                cx = if (x >= 0f) size.halfW else -size.halfW
                cy = v.y.coerceIn(size.bottom, size.top)
            }
            val q = r * cx + Vec3(0f, cy, 0f) + f * d
            val cosA = (v.dot(q) / (len * q.length)).coerceIn(-1f, 1f)
            val a = Math.toDegrees(kotlin.math.acos(cosA).toDouble()).toFloat()
            if (a < worst) worst = a
        }
        return worst
    }

    /**
     * How far to turn (degrees, + = left, the [Yaw] convention) before the window may open; 0 = clear now. The shorter
     * way round, away from the workcell on a tie. 0 also when nothing clears (no workcell points), so it never blocks.
     */
    fun turnDeg(head: HeadPose, keep: List<Vec3>, size: Size): Float {
        if (keep.isEmpty()) return 0f
        val eye = head.position
        val yaw = head.yaw(0f)
        if (gapDeg(eye, yaw, keep, size) >= MIN_GAP_DEG) return 0f
        // away from the workcell first: the side its centre is not on
        var sx = 0f
        val r = Vec3(cos(yaw), 0f, -sin(yaw))
        for (p in keep) sx += (p - eye).dot(r)
        val away = if (sx > 0f) 1f else -1f
        for (deg in 1..180) for (s in floatArrayOf(away, -away)) {
            val a = Yaw.wrap(yaw + Math.toRadians((s * deg).toDouble()).toFloat())
            if (gapDeg(eye, a, keep, size) >= MIN_GAP_DEG) return s * deg
        }
        return 0f
    }
}

/** Panel facing helper: the yaw (Yaw convention) of a panel at [panel] whose face (+Z) turns to [viewer]. */
fun faceYaw(panel: Vec3, viewer: Vec3): Float {
    val to = viewer - panel
    return kotlin.math.atan2(to.x, to.z)
}
