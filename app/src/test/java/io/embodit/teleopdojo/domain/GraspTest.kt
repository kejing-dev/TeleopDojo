package io.embodit.teleopdojo.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** design rules.grasp: four base cases, >= 10 sloppy cases on easy (D27), release / fall / stack stability. */
class GraspTest {
    private fun block(yawDeg: Float = 0f) = Block(0, 0.05f, Vec3(0f, 0.025f, 0f), Num.rad(yawDeg), 0)

    /** Gripper pointing down, closing axis turned [yawDeg] about the vertical, approach tilted [tiltDeg] about [tiltAxis]. */
    private fun tool(yawDeg: Float = 0f, tiltDeg: Float = 0f, tiltAxis: Vec3 = Vec3(0f, 0f, 1f)): Quat4 {
        val down = Tool.down(Num.rotY(Vec3(1f, 0f, 0f), Num.rad(yawDeg)))
        return (Quat4.axisAngle(tiltAxis.normalized(), Num.rad(tiltDeg)) * down).normalized()
    }

    private val closed = 0.02f

    @Test
    fun alignedGrasps() {
        val c = Grasp.check(block(), Vec3(0f, 0.025f, 0f), tool(), closed, GraspRules.STANDARD)
        assertTrue(c.misses.toString(), c.ok)
    }

    @Test
    fun thirtyDegreesOffFailsOnStandard() {
        val c = Grasp.check(block(), Vec3(0f, 0.025f, 0f), tool(yawDeg = 30f), closed, GraspRules.STANDARD)
        assertFalse(c.ok)
        assertEquals(listOf(Miss.ANGLE), c.misses)
        assertTrue(c.almost)
    }

    @Test
    fun touchingOnlyOneFaceFails() {
        val c = Grasp.check(block(), Vec3(0.03f, 0.025f, 0f), tool(), closed, GraspRules.STANDARD)
        assertFalse(c.ok)
        assertTrue(Miss.OVERLAP in c.misses)
    }

    @Test
    fun notSqueezedEnoughFails() {
        val c = Grasp.check(block(), Vec3(0f, 0.025f, 0f), tool(), 0.049f, GraspRules.STANDARD)
        assertFalse(c.ok)
        assertEquals(listOf(Miss.SQUEEZE), c.misses)
    }

    @Test
    fun aGripperAboveTheBlockDoesNotGrab() {
        val c = Grasp.check(block(), Vec3(0f, 0.09f, 0f), tool(), closed, GraspRules.EASY)
        assertFalse(c.ok)
    }

    /** Ten sloppy grasps (15-20° off, half-turned 25-30°, 1 cm off, barely squeezed): all pass on easy. */
    @Test
    fun sloppyGraspsPassOnEasy() {
        data class Case(val name: String, val tcp: Vec3, val q: Quat4, val opening: Float, val blockYaw: Float = 0f)
        val cases = listOf(
            Case("yaw 15", Vec3(0f, 0.025f, 0f), tool(yawDeg = 15f), closed),
            Case("yaw -20", Vec3(0f, 0.025f, 0f), tool(yawDeg = -20f), closed),
            Case("half turn 28", Vec3(0f, 0.025f, 0f), tool(yawDeg = 28f), closed),
            Case("tilt 20 about across", Vec3(0f, 0.025f, 0f), tool(tiltDeg = 20f, tiltAxis = Vec3(0f, 0f, 1f)), closed),
            Case("tilt 18 about travel", Vec3(0f, 0.025f, 0f), tool(tiltDeg = 18f, tiltAxis = Vec3(1f, 0f, 0f)), closed),
            Case("1 cm across", Vec3(0f, 0.025f, 0.01f), tool(), closed),
            Case("1 cm low", Vec3(0f, 0.015f, 0f), tool(), closed),
            Case("1 cm high", Vec3(0f, 0.035f, 0f), tool(), closed),
            Case("1 cm along the travel", Vec3(0.01f, 0.025f, 0f), tool(), closed),
            Case("barely squeezed (4 mm under)", Vec3(0f, 0.025f, 0f), tool(), 0.046f),
            Case("block turned 17 + 1 cm", Vec3(0.005f, 0.025f, 0.01f), tool(), closed, 17f),
            Case("yaw 15 + tilt 15 + 8 mm", Vec3(0f, 0.017f, 0.008f), tool(yawDeg = 15f, tiltDeg = 15f), closed),
        )
        for (c in cases) {
            val r = Grasp.check(block(c.blockYaw), c.tcp, c.q, c.opening, GraspRules.EASY)
            assertTrue("${c.name}: ${r.misses} overlap=${r.overlap} angle=${r.angleDeg}", r.ok)
        }
    }

    @Test
    fun twoNearMissesInARowLoosenTheRules() {
        val rules = GraspRules.STANDARD
        val c = Grasp.check(block(), Vec3(0f, 0.025f, 0f), tool(yawDeg = 24f), closed, rules)
        assertFalse(c.ok)
        assertTrue(Grasp.check(block(), Vec3(0f, 0.025f, 0f), tool(yawDeg = 24f), closed, rules.loosen(1)).ok)
    }

    @Test
    fun releasedBlockFallsOntoTheTableAndStops() {
        val b = block()
        b.c = Vec3(0f, 0.20f, 0f)
        Grasp.release(b)
        var settled = false
        repeat(60) { if (Grasp.stepFree(b, listOf(b), 1f / 72f, 0.015f)) settled = true }
        assertTrue(settled)
        assertEquals(BlockState.RESTING, b.state)
        assertEquals(0.025f, b.c.y, 1e-5f)
    }

    @Test
    fun stackWithinToleranceStandsAndOffItSlides() {
        val lower = block()
        val upper = Block(1, 0.05f, Vec3(0.01f, 0.20f, 0f), 0f, 1)
        Grasp.release(upper)
        repeat(60) { Grasp.stepFree(upper, listOf(lower, upper), 1f / 72f, 0.015f) }
        assertEquals(0.075f, upper.c.y, 1e-4f)
        val off = Block(2, 0.05f, Vec3(0.02f, 0.20f, 0f), 0f, 2)
        val lower2 = block()
        Grasp.release(off)
        repeat(120) { Grasp.stepFree(off, listOf(lower2, off), 1f / 72f, 0.015f) }
        assertEquals(BlockState.RESTING, off.state)
        assertEquals(0.025f, off.c.y, 1e-4f)
    }

    @Test
    fun padsStopOnTheBlockFaces() {
        assertEquals(0.05f, Grasp.visualOpening(0.0f, block()), 1e-6f)
        assertEquals(0.03f, Grasp.visualOpening(0.03f, null), 1e-6f)
    }
}
