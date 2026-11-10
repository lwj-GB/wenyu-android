package app.resound.digest

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.resound.data.*
import app.resound.search.SearchIndex
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class DayTextStatsTest {
    @Test fun countUsesUnicodeCharactersAndNeverAddsProcessedToTotalAgain() {
        val rows = listOf(TranscriptProgressRow("你 好 A1，😀\n", 10), TranscriptProgressRow("未整理 123", null))
        assertEquals(DayTextStats(12, 6, 6, 2), calculateDayTextStats(rows, 2))
        assertEquals(0L, transcriptCharacters(" \n\t\u00A0"))
    }

    @Test fun databaseCountsOnlyStableTranscriptsAndNonDeletedEventsInSelectedDay() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        val db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).allowMainThreadQueries()
            .addCallback(SearchIndex.callback).build()
        try {
            db.capture().segment(SegmentEntity(1, 1000, 5000, "mic", "/fictional/one.ogg", 1, "Done"))
            db.capture().segment(SegmentEntity(2, 1000, 5000, "mic", "/fictional/two.ogg", 1, "Failed"))
            db.transcribe().insertLines(listOf(
                TranscriptLineEntity(1, 1, 1000, 2000, "中文😀", null, digestedAt = 9000),
                TranscriptLineEntity(2, 1, 2000, 3000, "待整理", null),
                TranscriptLineEntity(3, 1, 3000, 4000, "未稳定不算", null, voiceInputPendingUntil = 9000),
                TranscriptLineEntity(4, 2, 3000, 4000, "失败段不算", null),
                TranscriptLineEntity(5, 1, 5000, 6000, "下一日不算", null)))
            fun event(id: Long, deleted: Boolean, at: Long) = MemoryEventEntity(id, at, at + 100, "虚构会议", "虚构事件", "", "[]", "[]",
                null, null, null, false, deleted = deleted, createdAt = 9000, model = "fixture")
            db.memory().insertEvent(event(1, false, 1000)); db.memory().insertEvent(event(2, true, 2000)); db.memory().insertEvent(event(3, false, 5000))
            val rows = db.memory().dayTextRows(1000, 5000).first()
            val events = db.memory().dayEventCount(1000, 5000).first()
            assertEquals(DayTextStats(6, 3, 3, 1), calculateDayTextStats(rows, events))
            db.memory().markDigested(listOf(2), 10000)
            assertEquals(DayTextStats(6, 6, 0, 1), calculateDayTextStats(db.memory().dayTextRows(1000, 5000).first(), events))
        } finally { db.close() }
    }
}
