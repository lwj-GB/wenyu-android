package app.resound.backup

import android.app.Application
import app.resound.data.CaptureDatabase
import app.resound.location.LocationPoint
import app.resound.location.PlaceRow
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class LocationBackupTest {
    @Test fun dayVolumeRoundTripPreservesLocationPlacesAndExistingConversation() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val root = File(context.cacheDir, "footprint-backup").apply { mkdirs() }
        val database = CaptureDatabase.open(context, File(root, "source.db").absolutePath)
        try {
            database.location().insert(LocationPoint(at = 1_794_297_600_000, lat = 31.23, lng = 121.47, accuracy = 12f, provider = "test"))
            database.places().put(PlaceRow("manual-home", "家", "虚构地址", "手动", 1, 31.23, 121.47, true))
            database.openHelper.writableDatabase.execSQL("INSERT INTO ask_conversations(id, createdAt, title) VALUES(9, 1794297600000, '原有问答')")
            val snapshot = File(root, "snapshot.db")
            BackupArchive.snapshot(database, snapshot)
            val (version, rows) = DayVolumeRows.write(snapshot, File(root, "rows"))
            val restored = File(root, "restored.db")
            DayVolumeRows.rebuild(context, restored, version, rows.getValue("state"), rows.filterKeys { it != "state" }.values.toList())
            val read = CaptureDatabase.open(context, restored.absolutePath)
            try {
                assertEquals("家", read.places().get("manual-home")?.name)
                assertTrue(read.places().get("manual-home")!!.home)
                assertEquals(31.23, read.location().latest()!!.lat, 0.0)
                read.openHelper.writableDatabase.query("SELECT title FROM ask_conversations WHERE id = 9").use {
                    assertTrue(it.moveToFirst()); assertEquals("原有问答", it.getString(0))
                }
            } finally { read.close() }
        } finally { database.close(); root.deleteRecursively() }
    }

    @Test fun locationPreferencesAndGeocodingSecretUseExistingPortableBackupPath() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("location", 0).edit().putBoolean("enabled", true).putBoolean("lookup", false).commit()
        context.getSharedPreferences("location-secrets", 0).edit().putString("amap", "encrypted-placeholder").commit()
        val directory = File(context.cacheDir, "footprint-settings").apply { mkdirs() }
        try {
            BackupSettings.capture(context, directory) { store, key -> if (store == "location-secrets" && key == "amap") "fictional-key" else "" }
            val settings = JSONObject(File(directory, "settings.json").readText())
            val secrets = JSONObject(File(directory, "secrets.json").readText())
            BackupSettings.validate(settings, secrets)
            assertTrue(settings.getJSONObject("location").getJSONObject("enabled").getBoolean("value"))
            assertFalse(settings.getJSONObject("location").getJSONObject("lookup").getBoolean("value"))
            assertEquals("fictional-key", secrets.getJSONObject("location-secrets").getString("amap"))
            val written = mutableMapOf<Pair<String, String>, String>()
            BackupSettings.apply(context, directory) { store, key, value -> written[store to key] = value }
            assertEquals("fictional-key", written["location-secrets" to "amap"])
        } finally { directory.deleteRecursively() }
    }
}
