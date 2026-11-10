package app.resound.digest

import app.resound.data.TranscriptProgressRow

/** 原话句按起始日期归属；非空白 Unicode 字符计数，含标点，不含角色名或时间标签。 */
data class DayTextStats(val transcribedCharacters: Long, val digestedCharacters: Long,
    val pendingCharacters: Long, val events: Int)

internal fun transcriptCharacters(text: String): Long = text.codePoints()
    .filter { !Character.isWhitespace(it) && !Character.isSpaceChar(it) }.count()

internal fun calculateDayTextStats(rows: List<TranscriptProgressRow>, events: Int): DayTextStats {
    var transcribed = 0L; var digested = 0L
    rows.forEach { row ->
        val count = transcriptCharacters(row.text)
        transcribed += count
        if (row.digestedAt != null) digested += count
    }
    return DayTextStats(transcribed, digested, transcribed - digested, events)
}
