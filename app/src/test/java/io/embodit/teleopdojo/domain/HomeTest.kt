package io.embodit.teleopdojo.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** design frame.home: TCP 0.20 m over the table centre, gripper down, fingers along the table width. */
class HomeTest {
    private val cell = Workcell(Vec3(0f, 0.75f, 0f), 0f)

    @Test
    fun homeIsTheDesignedPose() {
        val tcp = cell.tcpOf(Ik.HOME)
        val l = cell.toLocal(tcp.p)
        println("HOME tcp local=$l approach=${cell.dirToLocal(Tool.approach(tcp.q))} closing=${cell.dirToLocal(Tool.closing(tcp.q))}")
        assertEquals(0f, l.x, 0.01f)
        assertEquals(0.20f, l.y, 0.01f)
        assertEquals(0f, l.z, 0.01f)
        assertTrue(cell.dirToLocal(Tool.approach(tcp.q)).y < -0.999f)
        assertTrue(kotlin.math.abs(cell.dirToLocal(Tool.closing(tcp.q)).x) > 0.999f)
    }

    @Test
    fun homeIsTheClosedFormElbowUpWristDownSolution() {
        val t = Ur5eKinematics.Mat4.of(doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, -1.0), doubleArrayOf(-0.28, 0.0, 0.35))
        val best = Ur5eKinematics.ik(t).solutions.minByOrNull { Ur5eKinematics.maxJointDelta(Ur5eKinematics.nearestEquivalent(it, Ik.HOME), Ik.HOME) }!!
        assertTrue(Ur5eKinematics.maxJointDelta(Ur5eKinematics.nearestEquivalent(best, Ik.HOME), Ik.HOME) < 1e-3)
        // every joint well inside its limit at home
        assertTrue(Ik.limitMargin(Ik.HOME).first > Math.toRadians(25.0))
    }
}
