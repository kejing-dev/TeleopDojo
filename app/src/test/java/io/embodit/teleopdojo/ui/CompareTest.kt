package io.embodit.teleopdojo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** S15 comparison (QA #1 q1-2): completion first, time only between rounds with equal completion, never praise an empty round. */
class CompareTest {
    private fun side(map: String, done: Int, total: Int = 5, time: Float = 60f, table: Int = 0) = CmpSide(map, done, total, time, table)

    @Test
    fun emptyEarlyEndIsNotFaster() {
        // the QA case: Follow ended early, 0/5 in 21 s; Clutch 1.0x did 5/5 in 22 s
        val l = Compare.of(side("follow", 0, time = 21f), side("clutch1.0", 5, time = 22f))!!
        assertEquals(CmpLine.Kind.FEWER, l.kind)
        assertEquals("0/5", l.myDone)
        assertEquals("5/5", l.otherDone)
    }

    @Test
    fun moreTargetsWinsEvenWhenSlower() {
        val l = Compare.of(side("clutch1.0", 5, time = 90f), side("follow", 3, time = 40f))!!
        assertEquals(CmpLine.Kind.MORE, l.kind)
    }

    @Test
    fun twoEmptyRoundsAreReportedEmpty() {
        val l = Compare.of(side("follow", 0, time = 5f), side("clutch1.0", 0, time = 30f))!!
        assertEquals(CmpLine.Kind.NONE_DONE, l.kind)
        assertEquals(0, l.dt)
    }

    @Test
    fun equalCompletionComparesTime() {
        val f = Compare.of(side("clutch1.0", 5, time = 48f, table = 1), side("follow", 5, time = 60f, table = 4))!!
        assertEquals(CmpLine.Kind.FASTER, f.kind)
        assertEquals(12, f.dt)
        assertEquals(3, f.dTable)
        val s = Compare.of(side("follow", 4, time = 70f), side("clutch1.0", 4, time = 61f))!!
        assertEquals(CmpLine.Kind.SLOWER, s.kind)
        assertEquals(9, s.dt)
    }

    @Test
    fun subSecondGapIsSameTimeNeverZeroSecondsFaster() {
        for (gap in listOf(0f, 0.2f, 0.49f, -0.3f)) {
            val l = Compare.of(side("follow", 5, time = 30f), side("clutch1.0", 5, time = 30f + gap))!!
            assertEquals("gap $gap", CmpLine.Kind.SAME_TIME, l.kind)
        }
        // whenever FASTER / SLOWER is said, the seconds are at least 1
        for (t in 0..40) {
            val l = Compare.of(side("follow", 3, time = 20f + t * 0.25f), side("clutch1.0", 3, time = 25f))!!
            if (l.kind == CmpLine.Kind.FASTER || l.kind == CmpLine.Kind.SLOWER) assertNotEquals(0, l.dt)
        }
    }

    @Test
    fun completionIsComparedAsAShareWhenTotalsDiffer() {
        assertEquals(CmpLine.Kind.FASTER, Compare.of(side("follow", 3, 5, 30f), side("clutch1.0", 6, 10, 40f))!!.kind)
        assertEquals(CmpLine.Kind.MORE, Compare.of(side("follow", 4, 5), side("clutch1.0", 6, 10))!!.kind)
    }

    @Test
    fun oldEntriesWithoutCompletionAreNotCompared() {
        assertNull(Compare.of(side("follow", 3), side("clutch1.0", -1, -1)))
        assertNull(Compare.sentence(side("follow", 3), side("clutch1.0", 2, 0)))
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val l = Compare.of(side("clutch1.5", 5, time = 40f, table = 0), side("follow", 5, time = 52f, table = 2))!!
        assertEquals(l, Compare.decode(Compare.encode(l)))
        assertNull(Compare.decode("follow|clutch1.0|1|0"))
    }
}
