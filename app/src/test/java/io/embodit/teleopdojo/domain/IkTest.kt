package io.embodit.teleopdojo.domain

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** design verification: 200 random targets in the workspace converge >= 98 %, <= 1.5 ms a frame, no wrist flip. */
class IkTest {
    private val cell = Workcell(Vec3(0f, 0.75f, 0f), 0f)

    @Test
    fun randomTargetsInThePracticeRegionConverge() {
        val rnd = Random(42)
        val ik = Ik()
        var ok = 0
        val n = 200
        for (i in 0 until n) {
            val l = Vec3(
                Workcell.REGION_X0 + rnd.nextFloat() * (Workcell.REGION_X1 - Workcell.REGION_X0),
                0.03f + rnd.nextFloat() * 0.35f,
                Workcell.REGION_Z0 + rnd.nextFloat() * (Workcell.REGION_Z1 - Workcell.REGION_Z0),
            )
            val q = Tool.down(Num.rotY(Vec3(1f, 0f, 0f), rnd.nextFloat() * 1.5f - 0.75f))
            val s = ik.converge(Ik.HOME, cell.toDh(cell.toStage(l)), cell.rotToDh(cell.rotToStage(q)), 60)
            if (s.reached) ok++
        }
        println("IK converged $ok / $n")
        assertTrue("converged $ok / $n", ok >= n * 0.98)
    }

    @Test
    fun oneFrameIsFast() {
        val ik = Ik()
        val target = cell.toDh(cell.toStage(Vec3(0.2f, 0.15f, 0.1f)))
        val r = cell.rotToDh(cell.rotToStage(Tool.down(Vec3(1f, 0f, 0f))))
        var q = Ik.HOME
        repeat(200) { q = ik.solve(q, target, r).q } // warm up the JIT
        val t0 = System.nanoTime()
        val n = 500
        var qq = Ik.HOME
        repeat(n) { qq = ik.solve(Ik.HOME, target, r).q }
        val ms = (System.nanoTime() - t0) / 1e6 / n
        println("IK frame %.3f ms (JVM; the headset is slower, the per-frame budget is 1.5 ms)".format(ms))
        assertTrue("frame $ms ms", ms < 1.5)
    }

    @Test
    fun nearTheWristSingularityTheWristDoesNotFlip() {
        // walk the target straight up over the base axis region where the wrist goes straight: joints change smoothly
        val ik = Ik()
        val r = cell.rotToDh(cell.rotToStage(Tool.down(Vec3(1f, 0f, 0f))))
        var q = Ik.HOME
        var worst = 0.0
        var y = 0.10f
        while (y < 0.62f) {
            val s = ik.solve(q, cell.toDh(cell.toStage(Vec3(0.0f, y, -0.05f))), r)
            worst = maxOf(worst, Ur5eKinematics.maxJointDelta(q, s.q))
            q = s.q
            y += 0.01f
        }
        println("IK worst joint step %.1f°".format(Math.toDegrees(worst)))
        assertTrue("worst step ${Math.toDegrees(worst)}°", worst < Math.toRadians(20.0))
    }

    @Test
    fun anOutOfReachTargetStopsAtTheClosestPoint() {
        val ik = Ik()
        val r = cell.rotToDh(cell.rotToStage(Tool.down(Vec3(1f, 0f, 0f))))
        val far = cell.toDh(cell.toStage(Vec3(1.5f, 0.2f, 0.3f)))
        var q = Ik.HOME
        var last = q
        repeat(80) {
            last = q
            q = ik.solve(q, far, r).q
        }
        // it settles (no flailing) and the joints stay inside their limits
        assertTrue(Ur5eKinematics.maxJointDelta(last, q) < Math.toRadians(2.0))
        assertTrue(Ik.limitMargin(q).first >= 0.0)
    }
}
