package app.resound.ui.settings

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.room.Room
import app.resound.data.CaptureDatabase
import app.resound.location.LocationRepository
import app.resound.ui.ShellNav
import app.resound.ui.preview.FakeCaptureController
import app.resound.ui.preview.FakeDigestSettings
import app.resound.ui.preview.FakeTranscribeSettings
import app.resound.ui.theme.ResoundTheme
import app.resound.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35], qualifiers = "w320dp-h720dp-xhdpi")
class LocationPageUiTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var database: CaptureDatabase? = null

    @After fun close() {
        ShellNav.settingsPage.value = null
        ShellNav.takeLocationDate()
        scope.cancel()
        database?.close()
    }

    @Test fun settingsRequestOpensFootprintAndDoesNotStartRecordingOnEmptyDay() {
        val context = compose.activity
        val db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).allowMainThreadQueries().build()
        database = db
        val repo = LocationRepository.create(context, db, scope)
        repo.settings.setEnabled(false)
        val selectedDate = java.time.LocalDate.now().minusDays(3)
        ShellNav.openSettings(SettingsPage.Location, selectedDate)
        compose.setContent { ResoundTheme(ThemeMode.Light) {
            SettingsScreen(FakeCaptureController(), FakeTranscribeSettings(), FakeDigestSettings(), ThemeMode.Light, {}, 0.dp) { page, bottom ->
                if (page == SettingsPage.Location) {
                    val date = androidx.compose.runtime.remember { ShellNav.takeLocationDate() ?: java.time.LocalDate.now() }
                    app.resound.ui.location.LocationPage(repo, bottom, date)
                }
            }
        } }
        compose.onNodeWithText("记录全天位置").assertExists()
        val dateTitle = selectedDate.format(java.time.format.DateTimeFormatter.ofPattern("M月d日 EEE", java.util.Locale.CHINA))
        compose.onNodeWithText(dateTitle).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("这一天没有位置记录").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("没有记录的时段不推定为在家、外出或零距离。").performScrollTo().assertExists()
        compose.onNodeWithText("0.0 km").assertDoesNotExist()
        org.junit.Assert.assertFalse(repo.settings.enabled.value)
    }
}
