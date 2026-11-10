package io.embodit.teleopdojo.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Workcell placement from the head: yaw only (D9), 45° to the teleop hand, near edge 0.35 m, base >= 0.8 m (D14). */
class PlacementTest {
    private fun head(yawDeg: Float, pitchDeg: Float = 0f, rollDeg: Float = 0f): HeadPose {
        val q = Quat4.axisAngle(Vec3.UP, Num.rad(yawDeg)) * Quat4.axisAngle(Vec3.RIGHT, Num.rad(pitchDeg)) * Quat4.axisAngle(Vec3(0f, 0f, -1f), Num.rad(rollDeg))
        return HeadPose(Vec3(0.2f, 1.25f, -0.1f), q.normalized())
    }

    @Test
    fun tiltedHeadsGiveAnUprightWorkcellTurnedToTheRight() {
        for ((p, r) in listOf(0f to 0f, -30f to 0f, 30f to 0f, 0f to 20f)) {
            val h = head(10f, p, r)
            val c = Workcell.forHead(h, 0.75f, leftHanded = false)
            // upright: the table's up is the world's up
            assertTrue(c.dirToStage(Vec3.UP).distanceTo(Vec3.UP) < 1e-5f)
            assertEquals(0.75f, c.topY, 1e-6f)
            // 45° to the right of the view (Yaw convention: right is negative)
            assertEquals("pitch $p roll $r", Yaw.wrap(Num.rad(10f - 45f)), c.yaw, 1e-4f)
            // the near edge 0.35 m from the head, the base about 1.03 m away (>= 0.8 m, D14)
            val flat = Vec3(c.origin.x - h.position.x, 0f, c.origin.z - h.position.z).length
            assertEquals(0.75f, flat, 1e-4f)
            val base = Vec3(c.base.x - h.position.x, 0f, c.base.z - h.position.z).length
            assertTrue("base $base", base >= 0.8f && base <= 1.1f)
            // the near edge faces the head
            val nearEdgeDir = c.dirToStage(Vec3(0f, 0f, 1f))
            val toHead = Vec3(h.position.x - c.origin.x, 0f, h.position.z - c.origin.z).normalized()
            assertTrue(nearEdgeDir.dot(toHead) > 0.999f)
        }
    }

    @Test
    fun leftHandedMirrors() {
        val c = Workcell.forHead(head(0f), 0.95f, leftHanded = true)
        assertEquals(Num.rad(45f), c.yaw, 1e-4f)
        assertEquals(-1f, c.mirror)
        assertEquals(-0.3f, c.hand(Vec3(0.3f, 0f, 0f)).x, 1e-6f)
    }

    /** D29: "工作台摆到右前" is pressed looking at the window; the median yaw of the last second lands within 2°. */
    @Test
    fun buttonYawUsesTheMedianOfTheLastSecond() {
        val yaws = listOf(0.4f, 0.2f, -0.3f, 0.1f, 8f, 9f, 0f, -0.2f, 0.3f).map { Num.rad(it) }.sorted()
        val median = yaws[yaws.size / 2]
        val c = Workcell.forHead(head(0f), 0.75f, false, median)
        assertTrue(abs(Num.deg(Yaw.wrap(c.yaw - Num.rad(-45f)))) < 2f)
    }

    @Test
    fun stageAndDhRoundTrip() {
        val c = Workcell(Vec3(0.3f, 0.8f, -0.9f), Num.rad(-37f))
        val p = Vec3(0.5f, 1.0f, -1.1f)
        val back = c.fromDh(c.toDh(p))
        assertTrue(back.distanceTo(p) < 1e-5f)
        // the home TCP is over the table centre
        val tcp = c.tcpOf(Ik.HOME)
        val l = c.toLocal(tcp.p)
        assertEquals(0f, l.x, 0.005f)
        assertEquals(0.20f, l.y, 0.005f)
        assertEquals(0f, l.z, 0.005f)
    }

    @Test
    fun workspaceClampCountsTheTable() {
        val c = Workcell(Vec3(0f, 0.75f, 0f), 0f)
        val q = Tool.down(Vec3(1f, 0f, 0f))
        val r = Workspace.clamp(c, c.toStage(Vec3(0.1f, 0.0f, 0.1f)), q, 0f)
        assertTrue(r.tableHit)
        assertEquals(0.003f + 0.0225f, c.toLocal(r.tcp).y, 1e-4f)
        val inCol = Workspace.clamp(c, c.toStage(Workcell.BASE_LOCAL + Vec3(0.05f, 0.2f, 0f)), q, 0f)
        assertTrue(inCol.selfHit)
        val near = Workspace.clamp(c, c.toStage(Vec3(0f, 0.2f, 0.6f)), q, 0f)
        assertEquals(0.4f, c.toLocal(near.tcp).z, 1e-4f)
    }

    @Test
    fun placerSnapsSquareWithinTenDegrees() {
        val head = Vec3(0f, 1.6f, 0f)
        val center = Vec3(0f, 0f, -1f)
        val facing = Yaw.of(Vec3(0f, 0f, -1f))
        assertEquals(facing, WorkcellPlacer.turn(facing + Num.rad(8f), 0f, 0.016f, head, center), 1e-5f)
        val turned = WorkcellPlacer.turn(facing, 1f, 1f, head, center)
        assertEquals(-60f, Num.deg(Yaw.wrap(turned - facing)), 0.01f)
    }

    /** profile D10 four directions: the user stands E / S / W / N of the S02 marker; the placed table faces them (< 10°). */
    @Test
    fun fourDirectionPlacementFacesTheUser() {
        val marker = Vec3(0.4f, 0f, -1.2f)
        for ((name, at) in listOf("E" to Vec3(1.6f, 1.6f, -1.2f), "S" to Vec3(0.4f, 1.6f, 0.0f), "W" to Vec3(-0.8f, 1.6f, -1.2f), "N" to Vec3(0.4f, 1.6f, -2.4f))) {
            val facing = Yaw.of(Vec3(marker.x - at.x, 0f, marker.z - at.z))
            // the ghost starts a little off and the stick is let go: it snaps square to the user
            val yaw = WorkcellPlacer.turn(facing + Num.rad(7f), 0f, 0.016f, at, marker)
            val c = Workcell.onMarker(marker, yaw, 0.75f, false)
            val near = c.dirToStage(Vec3(0f, 0f, 1f))
            val toUser = Vec3(at.x - marker.x, 0f, at.z - marker.z).normalized()
            val deg = Num.deg(kotlin.math.acos(near.dot(toUser).coerceIn(-1f, 1f)))
            assertTrue("$name off by $deg°", deg < 10f)
            // the top stays level and its height positive (no negative scale anywhere)
            assertTrue(c.dirToStage(Vec3.UP).distanceTo(Vec3.UP) < 1e-5f)
            assertTrue(c.topY > 0f)
        }
    }

    /**
     * D41 / D35: from where you sit or stand at start, the main window's frame (1.75 m ahead, 600 x 620 dp at 0.8 mm, plus
     * the system control strip ~60 dp under it and the TabBar over it) and the workcell (table top, base column, the arm
     * at home, the "打开面板" post and the tip tag) are at least 10° apart.
     */
    @Test
    fun windowAndWorkcellDoNotOverlapFromTheStartPose() {
        for (eye in listOf(1.2f, 1.6f)) for (left in listOf(false, true)) {
            val head = HeadPose(Vec3(0f, eye, 0f), Quat4.IDENTITY)
            val cell = Workcell.forHead(head, 0.75f, left)
            val dp = 0.0008f
            val wc = Vec3(0f, eye, -1.75f)
            val halfW = 300f * dp
            val top = 310f * dp + 60f * dp
            val bottom = -(310f * dp + 60f * dp)
            val rect = ArrayList<Vec3>()
            for (i in 0..20) for (j in 0..20) rect += Vec3(wc.x - halfW + 2 * halfW * i / 20f, wc.y + bottom + (top - bottom) * j / 20f, wc.z)
            val pts = ArrayList<Vec3>()
            for (x in listOf(-0.5f, -0.25f, 0f, 0.25f, 0.5f)) for (z in listOf(-0.4f, 0f, 0.4f)) pts += cell.toStage(Vec3(x, 0f, z))
            for (y in listOf(0f, 0.3f, 0.6f)) pts += cell.toStage(Workcell.BASE_LOCAL + Vec3(0f, y, 0f))
            pts += cell.tcpOf(Ik.HOME).p
            // (the 打开面板 chip is not checked: it only shows while the window is closed)
            pts += cell.toStage(cell.hand(Vec3(0.30f, 0.62f, -0.30f))) // the tip tag
            var worst = 180f
            var at = Vec3.ZERO
            for (p in pts) {
                val d = (p - head.position).normalized()
                for (r in rect) {
                    val a = Num.deg(kotlin.math.acos(d.dot((r - head.position).normalized()).coerceIn(-1f, 1f)))
                    if (a < worst) { worst = a; at = cell.toLocal(p) }
                }
            }
            println("D41 eye=$eye left=$left closest workcell point to the window frame: %.1f° at table-local $at".format(worst))
            assertTrue("eye $eye left $left: $worst°", worst >= 10f)
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // profile 48 (QA #2 q2-1): one window spot function for every open path; each path gets its own case.

    private val size = WindowSpot.Size(600f, 620f, 0.0008f)

    /** A level head at [eye] looking along [yaw] (radians, Yaw convention). */
    private fun level(eye: Float, yaw: Float) = HeadPose(Vec3(0f, eye, 0f), Quat4.axisAngle(Vec3.UP, yaw))

    /** A level head turned to look straight at [p]. */
    private fun lookingAt(eye: Float, p: Vec3) = level(eye, Yaw.of(Vec3(p.x, 0f, p.z)))

    private fun cellFor(eye: Float, left: Boolean) = Workcell.forHead(level(eye, 0f), if (eye < 1.4f) 0.75f else 0.95f, left)

    /** The arm raised as in QA #2 (`tcpl 0 0.55 0`): the TCP 0.55 m over the table centre plus the joints on the way. */
    private fun raisedArm(c: Workcell): List<Vec3> = listOf(
        c.base, c.toStage(Workcell.BASE_LOCAL + Vec3(0f, 0.16f, 0f)), c.toStage(Vec3(0f, 0.75f, -0.15f)), c.toStage(Vec3(0f, 0.55f, 0f)),
    )

    /**
     * What the gate does for one open path: the user starts at [head], keeps the head level and follows the turn hint at
     * about 30°/s, and the window opens when the gate lets it. Returns the head it opened from and the frames it took.
     */
    private fun openFrom(head: HeadPose, keep: List<Vec3>): Pair<HeadPose, Int> {
        val gate = LevelGate()
        gate.ask("test", 0)
        var h = head
        var t = 0L
        var frames = 0
        while (t < 20_000) {
            val turn = WindowSpot.turnDeg(h, keep, size)
            if (gate.step(t, 0f, turn) != null) return h to frames
            if (turn != 0f) h = level(h.position.y, Yaw.wrap(h.yaw(0f) + Num.rad(kotlin.math.sign(turn) * minOf(abs(turn), 0.5f))))
            t += 16
            frames++
        }
        throw AssertionError("the gate never opened from yaw ${Num.deg(head.yaw(0f))}")
    }

    private fun assertClear(path: String, head: HeadPose, keep: List<Vec3>): Float {
        val (opened, frames) = openFrom(head, keep)
        val gap = WindowSpot.gapDeg(opened.position, opened.yaw(0f), keep, size)
        val turned = Num.deg(Yaw.wrap(opened.yaw(0f) - head.yaw(0f)))
        println("profile48 %s: from yaw %.1f° opened at %.1f° (turned %+.1f°, %d frames), gap %.1f°".format(path, Num.deg(head.yaw(0f)), Num.deg(opened.yaw(0f)), turned, frames, gap))
        assertTrue("$path: gap $gap° < ${WindowSpot.MIN_GAP_DEG}°", gap >= WindowSpot.MIN_GAP_DEG)
        return turned
    }

    /** The analytic gap agrees with a brute-force sweep of the frame (0.5° tolerance), so the gate measures the right thing. */
    @Test
    fun gapMatchesBruteForce() {
        for (yawDeg in listOf(0f, -20f, -35f, -44.2f, 15f)) {
            val cell = cellFor(1.6f, false)
            val head = level(1.6f, Num.rad(yawDeg))
            val keep = WindowSpot.keepClear(cell, raisedArm(cell))
            val f = Yaw.dir(head.yaw(0f))
            val r = Vec3(kotlin.math.cos(head.yaw(0f)), 0f, -kotlin.math.sin(head.yaw(0f)))
            val c = head.position + f * Placement.WINDOW_DIST_M
            var brute = 180f
            for (i in 0..60) for (j in 0..60) {
                val q = c + r * (-size.halfW + 2 * size.halfW * i / 60f) + Vec3(0f, size.bottom + (size.top - size.bottom) * j / 60f, 0f)
                val dq = (q - head.position).normalized()
                for (p in keep) brute = minOf(brute, Num.deg(kotlin.math.acos(dq.dot((p - head.position).normalized()).coerceIn(-1f, 1f))))
            }
            val gap = WindowSpot.gapDeg(head.position, head.yaw(0f), keep, size)
            assertEquals("yaw $yawDeg", brute, gap, 0.5f)
        }
    }

    /** Launch: from the start pose the window opens at once (no turn) and stays >= 10° off the workcell, arm raised too. */
    @Test
    fun launchOpensAtOnceAndClear() {
        for (eye in listOf(1.2f, 1.6f)) for (left in listOf(false, true)) {
            val head = level(eye, 0f)
            val cell = cellFor(eye, left)
            for (keep in listOf(WindowSpot.keepClear(cell), WindowSpot.keepClear(cell, raisedArm(cell)))) {
                assertEquals("eye $eye left $left: launch must not wait", 0f, WindowSpot.turnDeg(head, keep, size))
                assertClear("launch eye=$eye left=$left", head, keep)
            }
        }
    }

    /**
     * S25 chip: "打开面板" on the post is pinched with the ray or eye-hand, so the gaze can be anywhere from the post to
     * the middle of the table (QA #2 looked at the table). Every gaze along that sweep opens clear; looking at the
     * table waits and the turn goes away from the arm.
     */
    @Test
    fun chipWhileFacingTheWorkcell() {
        for (eye in listOf(1.2f, 1.6f)) for (left in listOf(false, true)) {
            val cell = cellFor(eye, left)
            val keep = WindowSpot.keepClear(cell, raisedArm(cell))
            val chipYaw = lookingAt(eye, WindowSpot.chip(cell)).yaw(0f)
            val cellYaw = lookingAt(eye, cell.origin).yaw(0f)
            for (i in 0..10) {
                val head = level(eye, chipYaw + Yaw.wrap(cellYaw - chipYaw) * i / 10f)
                val turn = WindowSpot.turnDeg(head, keep, size)
                // away from the arm: left (+) for the right hand, right (-) for the left hand
                if (turn != 0f) assertEquals("chip eye $eye left $left step $i turn $turn", if (left) -1f else 1f, kotlin.math.sign(turn))
                val turned = assertClear("chip eye=$eye left=$left gaze ${i * 10}% post->table", head, keep)
                assertTrue("chip eye $eye left $left: turned $turned°", abs(turned) <= 50f)
            }
            assertTrue("chip eye $eye left $left: facing the table must wait", WindowSpot.turnDeg(level(eye, cellYaw), keep, size) != 0f)
        }
    }

    /** [叫回面板] facing the workcell (QA #2: yaw -44.2°, the window went 1.75 m behind the table): same function, clear. */
    @Test
    fun recallWhileFacingTheWorkcell() {
        for (eye in listOf(1.2f, 1.6f)) for (left in listOf(false, true)) {
            val cell = cellFor(eye, left)
            val keep = WindowSpot.keepClear(cell, raisedArm(cell))
            for (target in listOf(cell.origin, cell.base, cell.toStage(cell.hand(Vec3(0.3f, 0f, 0f))), cell.toStage(cell.hand(Vec3(-0.2f, 0f, 0.3f))))) {
                val head = lookingAt(eye, target)
                assertTrue("recall facing ${cell.toLocal(target)} must wait", WindowSpot.turnDeg(head, keep, size) != 0f)
                assertClear("recall eye=$eye left=$left facing ${cell.toLocal(target)}", head, keep)
            }
            // the QA #2 head exactly: right hand, yaw -44.2°
            if (!left) assertClear("recall eye=$eye QA#2 yaw -44.2", level(eye, Num.rad(-44.2f)), keep)
        }
    }

    /** [叫回面板] / chip while looking where the window was (or anywhere clear): opens at once, no hint. */
    @Test
    fun recallFacingAwayOpensAtOnce() {
        val cell = cellFor(1.6f, false)
        val keep = WindowSpot.keepClear(cell, raisedArm(cell))
        for (deg in listOf(0f, 10f, 30f, 90f, 180f)) assertEquals("yaw $deg", 0f, WindowSpot.turnDeg(level(1.6f, Num.rad(deg)), keep, size))
    }

    /** "工作台摆到右前" then reopen / recall from the new view: the cell goes 45° right of the view, the window stays clear. */
    @Test
    fun asideThenReopen() {
        for (view in listOf(-90f, -30f, 60f, 170f)) {
            val head = level(1.6f, Num.rad(view))
            val cell = Workcell.forHead(head, 0.95f, false)
            val keep = WindowSpot.keepClear(cell, raisedArm(cell))
            assertEquals("view $view", 0f, WindowSpot.turnDeg(head, keep, size))
            assertClear("aside view=$view then reopen", head, keep)
        }
    }

    /** Restore after −: the old spot is kept only while it is still clear (a marker put the table in front of it). */
    @Test
    fun restoreOnlyWhenTheOldSpotIsStillClear() {
        val head = level(1.6f, 0f)
        val cell = Workcell.forHead(head, 0.95f, false)
        assertTrue(WindowSpot.gapDeg(head.position, 0f, WindowSpot.keepClear(cell), size) >= WindowSpot.MIN_GAP_DEG)
        // S02: the table put on a marker 1.2 m straight ahead, right under the old window
        val moved = Workcell.onMarker(Vec3(0f, 0f, -1.2f), 0f, 0.95f, false)
        assertTrue(WindowSpot.gapDeg(head.position, 0f, WindowSpot.keepClear(moved), size) < WindowSpot.MIN_GAP_DEG)
        assertClear("restore after marker", head, WindowSpot.keepClear(moved, raisedArm(moved)))
    }

    /** The gate with a turn still needed never opens, however level and long you wait; the hint asks for the turn. */
    @Test
    fun gateHoldsWhileTheSpotIsBlocked() {
        val g = LevelGate()
        g.ask("chip", 0)
        var t = 0L
        while (t < 30_000) {
            assertEquals(null, g.step(t, 0f, 12f))
            t += 16
        }
        assertTrue(g.turnWanted(0f, 12f))
        assertTrue(!g.hintWanted(0f))
        // level first: a pitched head shows "平视前方", not the turn hint
        assertTrue(!g.turnWanted(25f, 12f))
        assertTrue(g.hintWanted(25f))
    }
}
