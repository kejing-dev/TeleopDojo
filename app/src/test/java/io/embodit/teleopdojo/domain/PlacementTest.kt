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
}
