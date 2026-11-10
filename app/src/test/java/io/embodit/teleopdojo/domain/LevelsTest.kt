package io.embodit.teleopdojo.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/** design content.levelLayout + freeMode.solvability + rules.scoring. */
class LevelsTest {
    private val fixed = listOf(LevelId.REACH, LevelId.PICK, LevelId.STACK).flatMap { l -> Difficulty.values().map { Levels.layout(l, it) } }

    @Test
    fun nineFixedLayoutsAreInTheRegionAndReachable() {
        assertEquals(9, fixed.size)
        for (lay in fixed) {
            val name = "${lay.level}/${lay.difficulty}"
            val points = ArrayList<Pair<Float, Float>>()
            for (l in lay.lights) {
                assertTrue("$name light ${l.c} region", Reachability.inRegion(l.c.x, l.c.z))
                assertTrue("$name light ${l.c} base", Reachability.clearOfBase(l.c.x, l.c.z, 0.05f))
                assertTrue("$name light ${l.c} reach", Reachability.reachable(l.c, l.arrow ?: Vec3(0f, -1f, 0f)))
                points += l.c.x to l.c.z
            }
            for (b in lay.blocks) {
                assertTrue("$name block $b region", Reachability.inRegion(b.x, b.z))
                assertTrue("$name block $b base", Reachability.clearOfBase(b.x, b.z, 0.05f))
                assertTrue("$name block $b reach", Reachability.reachable(Vec3(b.x, b.size / 2f, b.z), closingYawDeg = b.yawDeg))
                points += b.x to b.z
            }
            for (t in lay.trays) {
                assertTrue("$name tray $t region", Reachability.inRegion(t.x, t.z))
                assertTrue("$name tray $t reach", Reachability.reachable(Vec3(t.x, 0.06f, t.z)))
            }
            lay.stackBase?.let { s ->
                assertTrue("$name base region", Reachability.inRegion(s.x, s.z))
                // the top of the tower is reachable too
                assertTrue("$name tower top", Reachability.reachable(Vec3(s.x, lay.blocks.sumOf { it.size.toDouble() }.toFloat(), s.z)))
            }
            // objects at least a block apart (lights 8 cm)
            for (i in points.indices) for (j in i + 1 until points.size) {
                val d = hypot(points[i].first - points[j].first, points[i].second - points[j].second)
                val need = if (lay.lights.isNotEmpty()) 0.08f else lay.blocks.maxOf { it.size } * 2f
                if (lay.lights.isNotEmpty()) {
                    val a = lay.lights[i].c
                    val b = lay.lights[j].c
                    assertTrue("$name lights $a $b", a.distanceTo(b) >= need)
                } else assertTrue("$name objects $i $j d=$d", d >= need)
            }
        }
    }

    @Test
    fun freePlayFirstRoundsAreSolvable() {
        // design: 1000 seeds solvable. Every target the generator keeps passed Reachability (same IK as the drills);
        // here a spread of seeds and rounds is generated and rechecked independently.
        var n = 0
        for (seed in 0L until 1000L) {
            val round = 1 + (seed % 10).toInt()
            val lay = FreePlay.generate(round, seed)
            assertTrue(lay.targets > 0)
            for (l in lay.lights) assertTrue("seed $seed light ${l.c}", Reachability.inRegion(l.c.x, l.c.z) && Reachability.clearOfBase(l.c.x, l.c.z, 0.05f))
            for (b in lay.blocks) assertTrue("seed $seed block $b", Reachability.inRegion(b.x, b.z) && Reachability.clearOfBase(b.x, b.z, 0.05f))
            if (seed % 50 == 0L) {
                for (l in lay.lights) assertTrue("seed $seed reach ${l.c}", Reachability.reachable(l.c, l.arrow ?: Vec3(0f, -1f, 0f)))
                for (b in lay.blocks) assertTrue("seed $seed reach $b", Reachability.reachable(Vec3(b.x, b.size / 2f, b.z)))
            }
            n++
        }
        assertEquals(1000, n)
    }

    @Test
    fun freePlayCurve() {
        assertEquals(0.0235f, FreePlay.tolerance(1), 1e-6f)
        assertEquals(0.010f, FreePlay.tolerance(20), 1e-6f)
        assertEquals(4, FreePlay.targets(1))
        assertEquals(8, FreePlay.targets(12))
    }

    private fun m(done: Int = 5, targets: Int = 5, time: Float = 40f, steady: Int = 80, table: Int = 0, joint: Int = 0, sing: Int = 0, slip: Int = 0, idle: Int = 5, flips: Int = 2) =
        Metrics(done, targets, time, steady, 80, mapOf(Oops.TABLE to table, Oops.JOINT to joint, Oops.SINGULAR to sing, Oops.SLIP to slip), 3, idle, flips)

    @Test
    fun scoreTable() {
        assertEquals(100, Scoring.score(m(), 60f))
        assertEquals(70, Scoring.score(m(done = 3), 60f))
        assertEquals(90, Scoring.score(m(table = 2), 60f))
        assertEquals(91, Scoring.score(m(joint = 1, sing = 1, slip = 1), 60f))
        // 25 s over the reference: three started 10 s blocks
        assertEquals(94, Scoring.score(m(time = 85f), 60f))
        assertEquals(95, Scoring.score(m(steady = 55), 60f))
        assertEquals(0, Scoring.score(m(done = 0, table = 10), 60f))
        // nothing done: 0, even on a one-target drill (the bare formula would give 80)
        assertEquals(0, Scoring.score(m(done = 0, targets = 1), 60f))
        assertEquals(3, Scoring.stars(90))
        assertEquals(2, Scoring.stars(75))
        assertEquals(1, Scoring.stars(50))
        assertEquals(0, Scoring.stars(49))
        assertEquals(Scoring.Title.STEADY, Scoring.title(2.7f))
        assertEquals(Scoring.Title.NEW, Scoring.title(0.3f))
    }

    @Test
    fun demoBadgeNeedsAllSixThresholds() {
        assertTrue(Scoring.demoReady(m()))
        assertFalse(Scoring.demoReady(m(table = 1)))
        assertFalse(Scoring.demoReady(m(slip = 1)))
        assertFalse(Scoring.demoReady(m(joint = 1)))
        assertFalse(Scoring.demoReady(m(steady = 69)))
        assertFalse(Scoring.demoReady(m(idle = 16)))
        assertFalse(Scoring.demoReady(m(flips = 11)))
        assertFalse(Scoring.demoReady(m(done = 4)))
    }
}
