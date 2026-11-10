package io.embodit.teleopdojo.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The arm loop: grip latch -> clutch -> clamp -> IK, release holds still, tracking loss stops, a drill judges. */
class ArmTest {
    private val cell = Workcell(Vec3(0f, 0.75f, -0.75f), 0f)
    private val dt = 1f / 72f

    private fun run(arm: Arm, frames: Int, hand: HandInput, config: TeleopConfig = TeleopConfig()): ArmFrame {
        var f: ArmFrame? = null
        repeat(frames) { f = arm.step(dt, hand, cell, config, 0f, true) }
        return f!!
    }

    @Test
    fun handMotionMovesTheTipByTheScaledDelta() {
        for (scale in listOf(0.5f, 1.0f, 1.5f)) {
            val arm = Arm()
            val start = arm.tcp(cell).p
            val h0 = Pose(Vec3(0.3f, 1.0f, -0.3f), Quat4.IDENTITY)
            run(arm, 2, HandInput(h0, 1f, 0f), TeleopConfig(scaleFactor = scale))
            val move = Vec3(0.06f, 0.04f, -0.05f)
            val f = run(arm, 120, HandInput(Pose(h0.p + move, h0.q), 1f, 0f), TeleopConfig(scaleFactor = scale))
            assertTrue(f.clutched)
            assertTrue("scale $scale moved ${f.tcp.p - start}", (f.tcp.p - start).distanceTo(move * scale) < 0.004f)
        }
    }

    @Test
    fun letGoAndTheArmStaysPut() {
        val arm = Arm()
        val h0 = Pose(Vec3(0.3f, 1.0f, -0.3f), Quat4.IDENTITY)
        run(arm, 2, HandInput(h0, 1f, 0f))
        run(arm, 60, HandInput(Pose(h0.p + Vec3(0.05f, 0f, 0f), h0.q), 1f, 0f))
        val held = run(arm, 2, HandInput(Pose(h0.p + Vec3(0.05f, 0f, 0f), h0.q), 0.5f, 0f)).tcp.p
        val after = run(arm, 60, HandInput(Pose(h0.p + Vec3(-0.2f, 0.1f, 0f), h0.q), 0.5f, 0f))
        assertFalse(after.clutched)
        assertTrue(after.tcp.p.distanceTo(held) < 1e-4f)
        // grab again from a different hand position: no jump on the first clutched frame
        val f = arm.step(dt, HandInput(Pose(h0.p + Vec3(-0.2f, 0.1f, 0f), h0.q), 1f, 0f), cell, TeleopConfig(), 0f, true)
        assertTrue(f.grabbedNow)
        assertTrue(f.tcp.p.distanceTo(held) < 0.002f)
    }

    @Test
    fun trackingLossStopsAndNeedsANewGrab() {
        val arm = Arm()
        val h0 = Pose(Vec3(0.3f, 1.0f, -0.3f), Quat4.IDENTITY)
        run(arm, 2, HandInput(h0, 1f, 0f))
        val lost = run(arm, 3, HandInput(null, 1f, 0f))
        assertTrue(lost.trackingLost)
        assertFalse(lost.clutched)
        val p = lost.tcp.p
        // pose back, grip still held: nothing moves until the grip is pressed again
        val back = run(arm, 30, HandInput(Pose(h0.p + Vec3(0.1f, 0f, 0f), h0.q), 1f, 0f))
        assertFalse(back.clutched)
        assertTrue(back.tcp.p.distanceTo(p) < 1e-4f)
    }

    @Test
    fun pushingIntoTheTableCountsOneHitAndStopsAbove() {
        val arm = Arm()
        val h0 = Pose(Vec3(0.3f, 1.0f, -0.3f), Quat4.IDENTITY)
        run(arm, 2, HandInput(h0, 1f, 0f))
        var hits = 0
        var f: ArmFrame? = null
        repeat(200) {
            f = arm.step(dt, HandInput(Pose(h0.p + Vec3(0f, -0.4f, 0f), h0.q), 1f, 0f), cell, TeleopConfig(), 0f, true)
            if (f!!.tableHit) hits++
        }
        assertEquals(1, hits)
        val low = cell.toLocal(f!!.tcp.p).y + Gripper.lowestBelowTcp(f!!.tcp.q)
        assertTrue("lowest point $low", low >= 0.002f)
    }

    @Test
    fun homeEasesBackInPointEightSeconds() {
        val arm = Arm()
        val h0 = Pose(Vec3(0.3f, 1.0f, -0.3f), Quat4.IDENTITY)
        run(arm, 2, HandInput(h0, 1f, 0f))
        run(arm, 60, HandInput(Pose(h0.p + Vec3(0.1f, 0.1f, 0.05f), h0.q), 1f, 0f))
        arm.goHome()
        run(arm, (0.8f / dt).toInt() + 2, HandInput(h0, 0f, 0f))
        assertTrue(Ur5eKinematics.maxJointDelta(arm.q, Ik.HOME) < 1e-3)
    }

    @Test
    fun reachDrillLightsALightAndFinishes() {
        val layout = Layout(LevelId.REACH, Difficulty.EASY, lights = listOf(LightSpec(Vec3(0.05f, 0.18f, 0.02f), 0.025f)), tolerance = 0.025f, dwellS = 0.2f, referenceS = 60f)
        val drill = Drill(layout, cell)
        val arm = Arm()
        arm.debugTarget = cell.toDh(cell.toStage(Vec3(0.05f, 0.18f, 0.02f)))
        val evs = ArrayList<DrillEv>()
        repeat(200) {
            val f = arm.step(dt, HandInput(Pose(Vec3.ZERO, Quat4.IDENTITY), 0f, 0f), cell, TeleopConfig(), 0f, true)
            evs += drill.step(dt, f, Gripper.OPEN_M)
        }
        assertTrue(evs.any { it is DrillEv.Lit })
        assertTrue(drill.done)
        val r = drill.result(MappingMode.CLUTCH, 1f, ControlMode.POSITION)
        assertEquals(1, r.metrics.doneCount)
        assertTrue(r.assisted)
    }

    @Test
    fun pickDrillGrabsCarriesAndPlaces() {
        val layout = Levels.layout(LevelId.PICK, Difficulty.EASY)
        val drill = Drill(layout, cell)
        val arm = Arm()
        val b = layout.blocks[0]
        val tray = layout.trays[0]
        fun go(x: Float, y: Float, z: Float, opening: Float, frames: Int): List<DrillEv> {
            arm.debugTarget = cell.toDh(cell.toStage(Vec3(x, y, z)))
            val out = ArrayList<DrillEv>()
            repeat(frames) {
                val f = arm.step(dt, HandInput(Pose(Vec3.ZERO, Quat4.IDENTITY), 0f, 0f), cell, TeleopConfig(), drill.held?.half ?: 0f, true)
                out += drill.step(dt, f, opening)
            }
            return out
        }
        go(b.x, 0.15f, b.z, Gripper.OPEN_M, 150)
        go(b.x, b.size / 2f, b.z, Gripper.OPEN_M, 150)
        val grab = go(b.x, b.size / 2f, b.z, 0f, 10)
        assertTrue("grab $grab", grab.any { it is DrillEv.Grabbed })
        go(b.x, 0.15f, b.z, 0f, 150)
        go(tray.x, 0.15f, tray.z, 0f, 200)
        go(tray.x, b.size / 2f + 0.02f, tray.z, 0f, 150)
        val drop = go(tray.x, b.size / 2f + 0.02f, tray.z, Gripper.OPEN_M, 80)
        assertTrue("drop $drop", drop.any { it is DrillEv.Settled && it.placed })
        assertTrue(drill.done)
    }
}
