package app.resound.ui

import app.resound.diary.NotificationHour
import app.resound.ui.items.notificationChartBars
import app.resound.ui.items.rankingFractions
import app.resound.ui.transcript.transcriptHour
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class DashboardChartsTest {
    @Test fun rankingUsesActualValuesAndPreservesObservedZero() {
        assertEquals(listOf(1f, 0.5f, 0f), rankingFractions(listOf(120, 60, 0)))
        assertEquals(listOf(0f, 0f), rankingFractions(listOf(0, 0)))
        assertTrue(rankingFractions(emptyList()).isEmpty())
    }

    @Test fun hourChartDoesNotInventMissingHoursOrPullInAnotherDate() {
        val date = LocalDate.of(2026, 11, 10)
        val zone = ZoneId.of("Asia/Shanghai")
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val bars = notificationChartBars(listOf(NotificationHour(start + 8 * 3_600_000L, 6),
            NotificationHour(start + 10 * 3_600_000L, 3), NotificationHour(start - 3_600_000L, 100)), date, zone)
        assertEquals(2, bars.size)
        assertEquals(listOf(6, 3), bars.map { it.count })
        assertEquals(8f / 24f, bars.first().from, 0.0001f)
        assertEquals(1f, bars.first().height, 0.0001f)
        assertEquals(0.5f, bars.last().height, 0.0001f)
    }

    @Test fun rawSpeechHourUsesLocalTimeWithoutAlteringSourceTimestamps() {
        val at = Instant.parse("2026-11-10T16:10:00Z").toEpochMilli()
        assertEquals(0, transcriptHour(at, ZoneId.of("Asia/Shanghai")))
        assertEquals(16, transcriptHour(at, ZoneId.of("UTC")))
    }
}
