package app.resound.backup

import android.app.Application
import android.content.Context
import app.resound.data.CaptureDatabase
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class GlobalAnalysisBackupTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var db: CaptureDatabase
    private val audioName = "00000000-0000-0000-0000-000000000001.m4a"
    private fun live(path: String) = File(context.applicationInfo.dataDir, path).apply { parentFile!!.mkdirs() }
    private fun audio() = live("files/analysis-audio/$audioName")
    private fun work() = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        root = File(context.cacheDir, "global-analysis-backup").apply { deleteRecursively(); mkdirs() }
        context.deleteDatabase("capture.db"); db = CaptureDatabase.open(context); db.openHelper.writableDatabase
        (BackupSettings.prefs + BackupSettings.stores.keys + "analysis-recording-drafts").forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
        AnalysisBackupAssets.livePaths.forEach { live(it).deleteRecursively() }
        listOf("backup-restore", "backup-restore-done", "backup-restore-failed", "restore-journal").forEach { File(context.noBackupFilesDir,it).deleteRecursively() }
    }
    @After fun cleanup() {
        db.close(); root.deleteRecursively()
        AnalysisBackupAssets.livePaths.forEach { live(it).deleteRecursively() }
        listOf("backup-restore", "backup-restore-done", "backup-restore-failed", "restore-journal").forEach { File(context.noBackupFilesDir,it).deleteRecursively() }
    }
    private fun originals() {
        audio().writeBytes(byteArrayOf(1,2,3,4))
        live("files/analysis-mode.json").writeText(JSONObject().put("people", org.json.JSONArray().put(JSONObject().put("audioPath", audio().absolutePath))).toString())
        live("files/analysis-ask.json").writeText("[{\"text\":\"虚构聊天\"}]")
        live("files/analysis-skills.json").writeText("{\"runs\":[\"虚构技能记录\"]}")
        context.getSharedPreferences("analysis-recording-drafts",0).edit().putString("person",audio().absolutePath).commit()
    }
    @Test fun fullAndDayVolumeBackupsRestoreAnalysisTextAudioSkillsAndDrafts() {
        originals()
        val full = File(root,"full.zip")
        BackupArchive.create(context, db, work(), full, 1L)
        val legacyVerified = File(root,"legacy-verified")
        BackupArchive.verify(full, legacyVerified, CaptureDatabase.VERSION)
        assertArrayEquals(audio().readBytes(), File(legacyVerified,"files/analysis-audio/$audioName").readBytes())
        assertTrue(File(legacyVerified,"files/analysis-skills.json").readText().contains("虚构技能记录"))
        val remote = DirectoryVolumeStore(File(root,"remote").apply{mkdirs()})
        DayVolumes.sync(context,db,remote,work(),null,2L)
        val verified=File(root,"verified")
        DayVolumes.restore(context,remote,work(),verified,null,null)
        assertEquals("[{\"text\":\"虚构聊天\"}]",File(verified,"files/analysis-ask.json").readText())
        // Simulate a different device's private data root; restore must rebase the safe filename.
        val mode=File(verified,"files/analysis-mode.json")
        val foreignMode = JSONObject(mode.readText())
        foreignMode.getJSONArray("people").getJSONObject(0).put("audioPath", "/data/user/99/old.app/files/analysis-audio/$audioName")
        mode.writeText(foreignMode.toString())
        val expectedSkills=File(verified,"files/analysis-skills.json").readText()
        live("files/analysis-mode.json").writeText("stale")
        live("files/analysis-audio/00000000-0000-0000-0000-000000000002.wav").writeBytes(byteArrayOf(9))
        db.close(); BackupRestore.stage(context,verified); BackupRestore.applyPending(context)
        assertEquals(audio().absolutePath, JSONObject(live("files/analysis-mode.json").readText()).getJSONArray("people").getJSONObject(0).getString("audioPath"))
        assertEquals(expectedSkills,live("files/analysis-skills.json").readText())
        assertArrayEquals(byteArrayOf(1,2,3,4),audio().readBytes())
        assertFalse(live("files/analysis-audio/00000000-0000-0000-0000-000000000002.wav").exists())
        assertEquals(audio().absolutePath,context.getSharedPreferences("analysis-recording-drafts",0).getString("person",null))
    }
    @Test fun oldBackupWithoutAnalysisScopePreservesCurrentAnalysis() {
        originals(); val before=live("files/analysis-mode.json").readText()
        val stage=work(); File(stage,BackupArchive.DATABASE).apply{parentFile!!.mkdirs();writeText("old-database")}
        db.close(); BackupRestore.stage(context,stage); BackupRestore.applyPending(context)
        assertEquals(before,live("files/analysis-mode.json").readText())
        assertTrue(audio().isFile)
        assertTrue(live("files/analysis-skills.json").readText().contains("虚构技能记录"))
    }
    @Test fun scopeClearsAbsentFilesAndFailureRollsBackEveryAnalysisFileAndDraft() {
        originals(); val before=live("files/analysis-mode.json").readText()
        val stage=work(); File(stage,AnalysisBackupAssets.MARKER).apply{parentFile!!.mkdirs();writeText("{\"format\":1}")}
        File(stage,BackupArchive.DATABASE).apply{parentFile!!.mkdirs();writeText("restored-database")}
        db.close(); BackupRestore.stage(context,stage)
        BackupRestore.applyPending(context) { if(it==BackupArchive.DATABASE) throw IOException("simulated write failure") }
        assertEquals(before,live("files/analysis-mode.json").readText())
        assertTrue(live("files/analysis-ask.json").isFile)
        assertTrue(live("files/analysis-skills.json").isFile)
        assertArrayEquals(byteArrayOf(1,2,3,4),audio().readBytes())
        assertEquals(audio().absolutePath,context.getSharedPreferences("analysis-recording-drafts",0).getString("person",null))
        val empty=work();File(empty,AnalysisBackupAssets.MARKER).apply{parentFile!!.mkdirs();writeText("{\"format\":1}")}
        File(empty,BackupArchive.DATABASE).apply{parentFile!!.mkdirs();writeText("restored-database")}
        BackupRestore.stage(context,empty); BackupRestore.applyPending(context)
        assertFalse(live("files/analysis-mode.json").exists());assertFalse(audio().exists())
        assertFalse(live("files/analysis-ask.json").exists());assertFalse(live("files/analysis-skills.json").exists())
        assertTrue(context.getSharedPreferences("analysis-recording-drafts",0).all.isEmpty())
        assertFalse(BackupArchive.allowed("files/analysis-audio/../../secret"))
    }
}
