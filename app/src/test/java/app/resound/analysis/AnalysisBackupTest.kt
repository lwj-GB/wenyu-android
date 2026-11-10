package app.resound.analysis

import android.app.Application
import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],manifest=Config.NONE,application=Application::class)
class AnalysisBackupTest {
    private lateinit var context:Context
    private lateinit var store:AnalysisStore
    @Before fun setup() {
        context=RuntimeEnvironment.getApplication()
        context.deleteFile("analysis-mode.json");context.deleteFile("analysis-mode.json.bak")
        store=AnalysisStore(context)
    }
    @Test fun `organized memory backup namespaces device ids and preserves deduplication`() {
        val id=store.createPerson(mapOf("q1" to "虚构甲"))
        assertTrue(store.importPersonalTranscript(id,7,100,200,"整理后的记忆", "personal-memory"))
        assertFalse(store.importPersonalTranscript(id,7,100,200,"整理后的记忆", "personal-memory"))
        val json=JSONObject(store.exportBackup())
        val key=json.getJSONObject("data").getJSONArray("people").getJSONObject(0).getJSONArray("materials").getJSONObject(0).getString("sourceKey")
        assertTrue(key.startsWith("restored:"))
        assertTrue(key.endsWith(":personal-memory:7"))
        assertFalse(store.importPersonalTranscript(id,7,100,200,"整理后的记忆", "personal-memory"))
    }
    @Test fun `editing preserves originals reports and default kind persists with validation`() {
        val id=store.createPerson(mapOf("q1" to "虚构甲","q2" to "同学"))
        store.addMaterial(id,"原话","虚构内容","/private/audio.wav")
        val before=store.person(id)
        store.append(id,AnalysisReport("r","兴趣偏好","资料不足",emptyList(),before.revision,1))
        store.updatePerson(id,mapOf("q1" to "虚构乙"))
        assertEquals("同学",store.person(id).answers["q2"])
        assertEquals(before.materials,store.person(id).materials)
        assertEquals(1,store.person(id).reports.size)
        assertTrue(store.reportMarkdown(id,"r").contains("不保证与生成时版本相同"))
        assertEquals(before.revision+1,store.person(id).revision)
        store.setDefaultReportKind("兴趣偏好")
        assertEquals("兴趣偏好",AnalysisStore(context).state.value.defaultReportKind)
        try { store.setDefaultReportKind("bad");fail() } catch(_:IllegalArgumentException) {}
    }
    @Test fun `backup roundtrip merges text only and keeps local audio on duplicate`() {
        val id=store.createPerson(mapOf("q1" to "虚构甲"))
        store.addMaterial(id,"原话","虚构文字","/private/audio.wav")
        store.importPersonalTranscript(id,7,100,200,"虚构转写")
        val backup=store.exportBackup()
        assertFalse(backup.contains("/private/audio.wav"))
        assertEquals(1,store.previewBackup(backup).people)
        try { store.restoreBackup(backup,false);fail() } catch(_:IllegalArgumentException) {}
        store.restoreBackup(backup,true)
        assertEquals("/private/audio.wav",store.person(id).materials.first().audioPath)
        context.deleteFile("analysis-mode.json")
        val clean=AnalysisStore(context)
        clean.restoreBackup(backup,true)
        assertNull(clean.person(id).materials.first().audioPath)
        assertTrue(clean.person(id).materials.last().sourceKey!!.startsWith("restored:"))
        assertEquals(1,clean.state.value.people.size)
        clean.restoreBackup(backup,true)
        assertEquals(1,clean.state.value.people.size)
    }
    @Test fun `conflict or corrupt restore leaves every existing record untouched`() {
        val id=store.createPerson(mapOf("q1" to "虚构甲"))
        val original=store.state.value
        val json=JSONObject(store.exportBackup())
        val person=json.getJSONObject("data").getJSONArray("people").getJSONObject(0)
        person.put("name","冲突");person.getJSONObject("answers").put("q1","冲突")
        try { store.restoreBackup(json.toString(),true);fail() } catch(_:IllegalArgumentException) {}
        assertEquals(original,store.state.value)
        try { store.restoreBackup("{bad",true);fail() } catch(_:IllegalArgumentException) {}
        assertEquals(original,store.state.value)
        assertEquals("虚构甲",store.person(id).name)
    }
}
