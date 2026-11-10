package app.resound.transcribe

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.resound.data.*
import app.resound.search.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class OriginalAudio062Test {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    private lateinit var db: CaptureDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var repo: LocalOriginalAudio
    private lateinit var directory: File
    private val day = 86_400_000L
    private val now = 20 * day
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).allowMainThreadQueries()
            .addCallback(SearchIndex.callback).build()
        directory = File(context.cacheDir, "original062").apply { mkdirs() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val prefs = context.getSharedPreferences("original062", 0)
        prefs.edit().clear().commit()
        repo = LocalOriginalAudio(db.transcribe(), prefs, scope, playback = PlaybackCaptureHold(scope, {}), now = { now })
    }
    @After fun close() { scope.cancel(); db.close(); directory.deleteRecursively() }
    private suspend fun segment(id: Long, age: Int, status: String = "Done", processed: Boolean = true, source: String = "mic"): File {
        val file = File(directory, "$id.ogg").apply { writeBytes(ByteArray(10)) }
        db.capture().segment(SegmentEntity(id, now - age * day, now - age * day + 1000, source, file.path, 10,
            status, completedAt = if (status == "Pending") null else now - age * day, voiceprintProcessedAt = if (processed) now else null))
        return file
    }
    private suspend fun event(id: Long = 1, age: Int = 4, starred: Boolean = false): Long = db.memory().insertEvent(
        MemoryEventEntity(id, now - age * day, now - age * day + 1000, "会议", "旧标题", "旧摘要", "[\"旧要点\"]", "[]",
            null, null, null, false, createdAt = now, model = "test", starred = starred))

    @Test fun retentionProtectsRecentAndSavedButExpiresPendingAudio() = runBlocking {
        val recent = segment(1, 2); val expired = segment(2, 4)
        val starred = segment(3, 5); event(age = 5, starred = true)
        val pending = segment(4, 6, "Pending"); val voice = segment(5, 7, processed = false)
        db.transcribe().insertLines(listOf(TranscriptLineEntity(segmentId = 5,
            startAt = now - 7 * day, endAt = now - 7 * day + 1000,
            text = "虚构的已转写内容", speakerTag = "speaker0")))
        assertEquals(3, repo.retentionDays.value)
        assertEquals(3, repo.cleanup().segments)
        listOf(expired, pending, voice).forEach { assertFalse(it.exists()) }
        listOf(recent, starred).forEach { assertTrue(it.exists()) }
        val abandoned = db.transcribe().segment(4)
        assertEquals("Abandoned", abandoned.status)
        assertNull(abandoned.nextRetryAt)
        assertTrue(abandoned.error!!.contains("超过 3 天"))
        assertEquals("Done", db.transcribe().segment(5).status)
        assertNotNull(db.transcribe().segment(5).voiceprintProcessedAt)
        assertEquals("虚构的已转写内容", db.transcribe().linesForRedo(5).single().text)
    }
    @Test fun lateTranscriptionDoesNotExtendRecordedAudioAndReadersRemainProtected() = runBlocking {
        val old = segment(1, 5)
        db.transcribe().update(db.transcribe().segment(1).copy(completedAt = now))
        val reader = segment(2, 5)
        val running = segment(3, 5, "Running")
        val guarded = LocalOriginalAudio(db.transcribe(), context.getSharedPreferences("original062", 0), scope,
            guard = { it(setOf(2L)) }, playback = PlaybackCaptureHold(scope, {}), now = { now })
        assertEquals(1, guarded.cleanup().segments)
        assertFalse(old.exists()); assertTrue(reader.exists()); assertTrue(running.exists())
    }
    @Test fun importedAudioAgeStartsWhenItsLocalFileWasGenerated() = runBlocking {
        val fresh = segment(1, 100, source = "import").apply { setLastModified(now - day) }
        val old = segment(2, 100, source = "import").apply { setLastModified(now - 4 * day) }
        repo.cleanup()
        assertTrue(fresh.exists()); assertFalse(old.exists())
    }
    @Test fun savingASegmentPersistsAndProtectsPendingAudioUntilExplicitlyUnsaved() = runBlocking {
        val pending = segment(1, 6, "Pending")
        val completed = segment(2, 6)
        repo.setSaved(1, true); repo.setSaved(2, true)
        val prefs = context.getSharedPreferences("original062", 0)
        prefs.edit().putBoolean("originalKeepStarred", false).commit()
        val reopened = LocalOriginalAudio(db.transcribe(), prefs, scope,
            playback = PlaybackCaptureHold(scope, {}), now = { now })
        assertEquals(setOf(1L, 2L), reopened.savedSegments.value)
        assertEquals(0, reopened.cleanup().segments)
        assertTrue(pending.exists()); assertTrue(completed.exists())
        assertEquals(0, reopened.clear(false).segments)
        assertEquals(1, reopened.clear(true).segments)
        assertFalse(completed.exists()); assertTrue(pending.exists())
        reopened.setSaved(1, false)
        assertEquals(1, reopened.cleanup().segments)
        assertFalse(pending.exists())
    }
    @Test fun savingDayPersistsAndProtectsLaterArrivalsWithoutSavingOtherDays() = runBlocking {
        val first = segment(1, 6)
        val savedDate = java.time.Instant.ofEpochMilli(now - 6 * day).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        repo.saveDay(savedDate)
        val later = segment(2, 6, "Pending")
        val otherDay = segment(3, 5)
        val legacy = segment(4, 7)
        repo.setSaved(4, true)
        val reopened = LocalOriginalAudio(db.transcribe(), context.getSharedPreferences("original062", 0), scope,
            playback = PlaybackCaptureHold(scope, {}), now = { now })
        assertEquals(setOf(savedDate), reopened.savedDays.value)
        assertEquals(setOf(4L), reopened.savedSegments.value)
        assertEquals(1, reopened.previewTrim(3).segments)
        assertEquals(1, reopened.cleanup().segments)
        assertFalse(otherDay.exists())
        listOf(first, later, legacy).forEach { assertTrue(it.exists()) }
        assertEquals(30L, reopened.usage().first().starredBytes)
        assertEquals(0, reopened.clear(false).segments)
        // 主动选择包含已保存的原音时才可删除；未完成片段仍受保护。
        assertEquals(2, reopened.clear(true).segments)
        assertTrue(later.exists())
    }

    @Test fun shorteningCleansImmediatelyAndLengtheningDoesNot() = runBlocking {
        val file = segment(1, 2)
        repo.setRetentionDays(7)
        assertTrue(file.exists())
        repo.setRetentionDays(1)
        scope.coroutineContext[Job]!!.children.toList().joinAll()
        assertFalse(file.exists())
        assertEquals(1, context.getSharedPreferences("original062", 0).getInt("originalRetentionDays", 0))
    }
    @Test fun previewAndClearAgreeAndKeepStarredAndPending() = runBlocking {
        segment(1, 4); val star = segment(2, 5); event(age = 5, starred = true)
        val pending = segment(3, 6, "Pending")
        val preview = repo.previewClear(false)
        assertEquals(setOf(1L, 2L, 3L), repo.available(setOf(1, 2, 3)))
        assertEquals(preview, repo.clear(false))
        assertTrue(star.exists()); assertTrue(pending.exists())
        assertEquals("Done", db.transcribe().segment(1).status)
        assertNotNull(db.memory().event(1)); assertNull(repo.locate(1))
        assertEquals(1, repo.clear(true).segments)
    }
    @Test fun clearRangeOnlyRemovesAudioInsideRange() = runBlocking {
        val old = segment(1, 6); val mid = segment(2, 4); val fresh = segment(3, 2)
        val from = now - 5 * day; val until = now - 3 * day
        assertEquals(repo.previewClearRange(from, until, false), repo.clearRange(from, until, false))
        assertTrue(old.exists()); assertFalse(mid.exists()); assertTrue(fresh.exists())
    }
    @Test fun usageLocateAndTrimSupportImportedAudioWithoutChangingItsRetention() = runBlocking {
        val imported = segment(1, 8, source = "import"); event(age = 8, starred = true)
        segment(2, 4)
        assertEquals(AudioUsage(20, 10, 2, 0), repo.usage().first())
        assertEquals(imported, repo.locate(1)!!.file)
        assertEquals(1, repo.previewTrim(1).segments)
        repo.cleanup(); assertTrue(imported.exists())
        assertEquals(10L, repo.usage().first().bytes)
    }
    @Test fun playbackIsIdempotentAndScopeCancellationReleases() = runBlocking {
        val calls = mutableListOf<Boolean>()
        val hold = PlaybackCaptureHold(scope, calls::add)
        hold.begin(); hold.begin(); hold.end(); hold.end()
        assertEquals(listOf(true, false), calls)
        hold.begin(); scope.cancel()
        assertEquals(listOf(true, false, true, false), calls)
    }
    @Test fun playbackTimeoutReleasesCapture() = runBlocking {
        val calls = mutableListOf<Boolean>()
        val hold = PlaybackCaptureHold(this, calls::add, 10)
        hold.begin(); delay(30); hold.end()
        assertEquals(listOf(true, false), calls)
    }
    @Test fun editsPreserveFirstOriginalRestoreAndRefreshSearchAndEmbedding() = runBlocking {
        event()
        val store = EmbeddingStore(db)
        store.save(store.pending("fake").single(), "fake", floatArrayOf(1f, 0f))
        db.memory().editEvent(1, "新标题", "新摘要", "[\"新要点\"]", now)
        assertEquals("旧标题", db.memory().event(1)!!.originalTitle)
        assertEquals("新标题\n新摘要\n[\"新要点\"]", store.pending("fake").single().text)
        db.openHelper.readableDatabase.query("SELECT rowid FROM events_fts WHERE events_fts MATCH '新标'").use {
            assertTrue(it.moveToFirst()); assertEquals(1L, it.getLong(0))
        }
        db.memory().editEvent(1, "再次修改", "摘要二", "[]", now + 1)
        assertEquals("旧标题", db.memory().event(1)!!.originalTitle)
        db.memory().restoreEvent(1)
        val restored = db.memory().event(1)!!
        assertEquals("旧标题", restored.title); assertEquals("旧摘要", restored.summary)
        assertEquals("[\"旧要点\"]", restored.pointsJson)
        assertNull(restored.editedAt); assertNull(restored.originalTitle)
        assertNull(restored.originalSummary); assertNull(restored.originalPointsJson)
    }
    @Test fun version19MigratesKeepingExistingEvents() = runBlocking {
        val name = "migration062.db"; context.deleteDatabase(name)
        val schema = JSONObject(File("schemas/app.resound.data.CaptureDatabase/19.json").readText()).getJSONObject("database")
        context.openOrCreateDatabase(name, 0, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                fun sql(s: String) = s.replace("\u0024{TABLE_NAME}", entity.getString("tableName"))
                old.execSQL(sql(entity.getString("createSql")))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (j in 0 until indices.length()) old.execSQL(sql(indices.getJSONObject(j).getString("createSql")))
            }
            val queries = schema.getJSONArray("setupQueries")
            for (i in 0 until queries.length()) old.execSQL(queries.getString(i))
            old.execSQL("INSERT INTO memory_events (id,startAt,endAt,scene,title,summary,pointsJson,peopleJson,important,createdAt,model) VALUES (1,1,2,'会议','旧标题','旧摘要','[]','[]',0,1,'test')")
            old.version = 19
        }
        val migrated = Room.databaseBuilder(context, CaptureDatabase::class.java, name).allowMainThreadQueries()
            .addMigrations(*CaptureDatabase.MIGRATIONS).build()
        try {
            assertEquals(CaptureDatabase.VERSION, migrated.openHelper.writableDatabase.version)
            val row = migrated.memory().event(1)!!
            assertEquals("旧标题", row.title); assertNull(row.editedAt); assertNull(row.originalTitle)
            migrated.memory().editEvent(1, "新标题", "新摘要", "[]", now)
            assertEquals("旧标题", migrated.memory().event(1)!!.originalTitle)
        } finally { migrated.close(); context.deleteDatabase(name) }
    }
}
