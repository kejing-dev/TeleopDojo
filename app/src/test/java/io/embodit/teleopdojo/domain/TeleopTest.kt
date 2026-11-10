package io.embodit.teleopdojo.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/** The XRoboToolkit port: geometry.py, the clutch of base_teleop_controller._update_ik, the parallel gripper. */
class TeleopTest {
    private fun q(axis: Vec3, deg: Float) = Quat4.axisAngle(axis.normalized(), Num.rad(deg))

    @Test
    fun quaternionToAngleAxisMatchesGeometryPy() {
        // (input, expected angle deg, expected axis): same cases as geometry.py's docstring semantics
        val cases = listOf(
            Triple(Quat4.IDENTITY, 0f, Vec3(1f, 0f, 0f)),
            Triple(q(Vec3(0f, 0f, 1f), 90f), 90f, Vec3(0f, 0f, 1f)),
            Triple(q(Vec3(1f, 0f, 0f), 45f), 45f, Vec3(1f, 0f, 0f)),
            Triple(q(Vec3(0f, 1f, 0f), 170f), 170f, Vec3(0f, 1f, 0f)),
            Triple(q(Vec3(1f, 1f, 0f), 30f), 30f, Vec3(1f, 1f, 0f).normalized()),
            Triple(q(Vec3(0f, 0f, 1f), -60f), 60f, Vec3(0f, 0f, -1f)),
            // w < 0 is flipped first: the long way round becomes the short one
            Triple(q(Vec3(0f, 1f, 0f), 270f), 90f, Vec3(0f, -1f, 0f)),
            Triple(Quat4(0f, 0f, 0f, -1f), 0f, Vec3(1f, 0f, 0f)),
            Triple(q(Vec3(1f, 2f, 3f), 120f), 120f, Vec3(1f, 2f, 3f).normalized()),
            Triple(q(Vec3(0f, 0f, 1f), 1e-5f), 0f, Vec3(1f, 0f, 0f)),
        )
        for ((input, angle, axis) in cases) {
            val aa = Geo.quaternionToAngleAxis(input)
            assertEquals("angle of $input", angle, Num.deg(aa.angle), 0.05f)
            if (angle > 0.01f) assertTrue("axis of $input: ${aa.axis}", aa.axis.distanceTo(axis) < 1e-3f)
        }
    }

    @Test
    fun quatDiffIsQ2TimesQ1Inverse() {
        val q1 = q(Vec3(0f, 1f, 0f), 20f)
        val q2 = q(Vec3(0f, 1f, 0f), 50f)
        val d = Geo.quatDiffAsAngleAxis(q1, q2)
        assertEquals(30f, Num.deg(d.angle), 0.01f)
        assertTrue(d.axis.distanceTo(Vec3(0f, 1f, 0f)) < 1e-4f)
    }

    @Test
    fun gripLatchHasHysteresis() {
        val l = GripLatch()
        assertFalse(l.update(0.85f))
        assertFalse(l.engaged)
        assertTrue(l.update(0.95f))
        assertTrue(l.engaged)
        // a half-pressed grip between 0.8 and 0.9 does not flicker
        assertFalse(l.update(0.85f))
        assertTrue(l.engaged)
        assertFalse(l.update(0.81f))
        assertTrue(l.update(0.79f))
        assertFalse(l.engaged)
    }

    @Test
    fun clutchScalesTheHandMotion() {
        for (scale in listOf(0.5f, 1.0f, 1.5f)) {
            val m = ClutchMapper()
            val ee = Pose(Vec3(0.1f, 0.9f, -0.6f), Quat4.IDENTITY)
            val c0 = Pose(Vec3(0.3f, 1.0f, -0.3f), Quat4.IDENTITY)
            m.engage(ee, c0)
            val t = m.target(Pose(c0.p + Vec3(0.1f, -0.05f, 0.02f), c0.q), TeleopConfig(scaleFactor = scale))!!
            assertTrue((t.p - ee.p).distanceTo(Vec3(0.1f, -0.05f, 0.02f) * scale) < 1e-5f)
        }
    }

    @Test
    fun releaseAndRegrabNeverJumps() {
        val m = ClutchMapper()
        val cfg = TeleopConfig()
        var ee = Pose(Vec3(0f, 0.9f, -0.6f), q(Vec3(1f, 0f, 0f), 180f))
        m.engage(ee, Pose(Vec3(0.2f, 1f, -0.3f), Quat4.IDENTITY))
        // move and turn the hand, then let go
        val hand1 = Pose(Vec3(0.3f, 1.05f, -0.35f), q(Vec3(0f, 1f, 0f), 25f))
        ee = m.target(hand1, cfg)!!
        m.release()
        assertFalse(m.active)
        // bring the hand back somewhere else, turned differently, and grab again: the target starts where the arm is
        val hand2 = Pose(Vec3(0.1f, 0.95f, -0.2f), q(Vec3(1f, 0f, 1f), -40f))
        m.engage(ee, hand2)
        val t = m.target(hand2, cfg)!!
        assertTrue(t.p.distanceTo(ee.p) < 1e-5f)
        assertTrue(Num.deg(Geo.angleBetween(t.q, ee.q)) < 0.05f)
        // then follows the hand's delta rotation
        val t2 = m.target(Pose(hand2.p, (q(Vec3(0f, 1f, 0f), 10f) * hand2.q).normalized()), cfg)!!
        assertEquals(10f, Num.deg(Geo.angleBetween(t2.q, ee.q)), 0.05f)
    }

    @Test
    fun positionOnlyKeepsTheGripperDown() {
        val m = ClutchMapper()
        val ee = Pose(Vec3(0f, 0.9f, -0.6f), Tool.down(Vec3(1f, 0f, 0f)))
        val c0 = Pose(Vec3(0.2f, 1f, -0.3f), Quat4.IDENTITY)
        m.engage(ee, c0)
        val t = m.target(Pose(c0.p, q(Vec3(1f, 0.3f, 0.2f), 50f)), TeleopConfig(controlMode = ControlMode.POSITION))!!
        assertTrue(Tool.approach(t.q).y < -0.9999f)
    }

    @Test
    fun gripperOpeningIsLinearInTheTrigger() {
        assertEquals(0.085f, Gripper.opening(0f), 1e-6f)
        assertEquals(0.0425f, Gripper.opening(0.5f), 1e-6f)
        assertEquals(0f, Gripper.opening(1f), 1e-6f)
        assertEquals(0f, Gripper.opening(1.4f), 1e-6f)
    }

    @Test
    fun followPushesTowardsTheBase() {
        val t = FollowMapper.target(Pose(Vec3(0f, 1f, 0f), Quat4.IDENTITY), Vec3(0f, -0.3f, -2f), ControlMode.POSE, Quat4.IDENTITY)
        assertEquals(-0.45f, t.p.z, 1e-5f)
        assertEquals(1f, t.p.y, 1e-5f)
        @Suppress("UNUSED_VARIABLE") val pi = PI
    }
}
