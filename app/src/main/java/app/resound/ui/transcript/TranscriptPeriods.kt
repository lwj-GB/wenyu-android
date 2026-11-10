package app.resound.ui.transcript

import app.resound.capture.QuietRule
import java.time.LocalDate
import java.time.ZoneId

/** 固定当地时间两小时一组；跨边界的录音完整归入开始时段，不拆音频或原话。 */
internal fun transcriptPeriod(startAt: Long, zone: ZoneId = ZoneId.systemDefault()): Int =
    transcriptHour(startAt, zone) / 2

internal fun periodLabel(period: Int): String = "%02d–%02d".format(period * 2, (period + 1) * 2)

/** 仅展示当前启用的停录计划，不能用录音空白推断实际睡眠。跨午夜规则按开始日计算。 */
internal fun quietPeriodLabels(date: LocalDate, rules: List<QuietRule>): List<String> =
    rules.filter { it.enabled }.flatMap { rule ->
        listOf(date.minusDays(1), date).mapNotNull { startDate ->
            if (startDate.dayOfWeek !in rule.days) return@mapNotNull null
            val offset = if (startDate == date) 0 else -1440
            val start = offset + rule.startMinute
            val end = offset + rule.endMinute + if (rule.endMinute <= rule.startMinute) 1440 else 0
            val from = maxOf(0, start)
            val until = minOf(1440, end)
            if (from >= until) null else {
                fun clock(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)
                "${clock(from)}–${clock(until)} ${rule.name}"
            }
        }
    }.distinct()
