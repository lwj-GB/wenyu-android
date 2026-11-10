package app.resound.ui.items

import app.resound.diary.NotificationHour
import java.time.LocalDate
import java.time.ZoneId

internal fun rankingFractions(values: List<Long>): List<Float> {
    require(values.all { it >= 0 })
    val maximum = values.maxOrNull() ?: return emptyList()
    return values.map { if (maximum == 0L) 0f else (it.toDouble() / maximum).toFloat() }
}

internal data class NotificationChartBar(val from: Float, val until: Float, val height: Float, val count: Int)

/** 只画实际记录的小时，不为未观测小时补零。 */
internal fun notificationChartBars(hours: List<NotificationHour>, date: LocalDate, zone: ZoneId): List<NotificationChartBar> {
    require(hours.all { it.count >= 0 })
    val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val relevant = hours.filter { it.hourStartMillis in start until end }.sortedBy { it.hourStartMillis }
    val maximum = relevant.maxOfOrNull { it.count } ?: return emptyList()
    val span = (end - start).toDouble()
    return relevant.map { hour -> NotificationChartBar(
        ((hour.hourStartMillis - start) / span).toFloat(),
        ((minOf(hour.hourStartMillis + 3_600_000L, end) - start) / span).toFloat(),
        if (maximum == 0) 0f else hour.count.toFloat() / maximum,
        hour.count,
    ) }
}
