package app.resound.ui.transcript

import app.resound.capture.QuietRule
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class TranscriptPeriodsTest {
    @Test fun everyMinuteBelongsToExactlyOneOfTwelveFixedPeriods() {
        val zone = ZoneId.of("Asia/Shanghai")
        val start = LocalDate.of(2026, 11, 10).atStartOfDay(zone)
        val counts = (0 until 1440).groupingBy { transcriptPeriod(start.plusMinutes(it.toLong()).toInstant().toEpochMilli(), zone) }.eachCount()
        assertEquals((0 until 12).associateWith { 120 }, counts)
        assertEquals("00–02", periodLabel(0))
        assertEquals("22–24", periodLabel(11))
    }
    @Test fun sampleRecordingsUseTheirStartPeriodWithoutSplittingAcrossBoundary() {
        val zone = ZoneId.of("Asia/Shanghai")
        val date = LocalDate.of(2026, 11, 10)
        fun at(hour: Int, minute: Int) = date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
        assertEquals("08–10", periodLabel(transcriptPeriod(at(8, 10), zone)))
        assertEquals("02–04", periodLabel(transcriptPeriod(at(2, 30), zone)))
        assertEquals(3, transcriptPeriod(at(7, 59), zone))
        assertEquals(4, transcriptPeriod(at(8, 0), zone))
    }
    @Test fun sleepCrossingMidnightUsesStartWeekdayAndDisabledRulesDoNotAppear() {
        val monday = LocalDate.of(2026, 11, 9)
        val sleep = QuietRule("sleep", "睡眠", 23 * 60, 6 * 60, setOf(monday.dayOfWeek))
        assertEquals(listOf("23:00–24:00 睡眠"), quietPeriodLabels(monday, listOf(sleep)))
        assertEquals(listOf("00:00–06:00 睡眠"), quietPeriodLabels(monday.plusDays(1), listOf(sleep)))
        assertTrue(quietPeriodLabels(monday.plusDays(2), listOf(sleep)).isEmpty())
        assertTrue(quietPeriodLabels(monday, listOf(sleep.copy(enabled = false))).isEmpty())
    }
}
