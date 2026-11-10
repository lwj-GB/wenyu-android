package app.resound.health

import android.app.Application
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.ActivityInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class HealthMovementReaderTest {
    @Test @Config(sdk = [34])
    fun managementUsesThePublicHealthSettingsEntry() {
        val context = RuntimeEnvironment.getApplication()
        val action = "android.health.connect.action.HEALTH_HOME_SETTINGS"
        shadowOf(context.packageManager).addResolveInfoForIntent(Intent(action), ResolveInfo().apply {
            isDefault = true
            activityInfo = ActivityInfo().apply {
                packageName = "example.system.health"
                name = "example.system.health.Settings"
                exported = true
            }
        })
        val intent = HealthMovementReader(context).permissionIntent()
        assertNotNull("Public health settings must be reachable without system-only management privileges", intent)
        assertEquals(action, intent!!.action)
    }

    @Test fun unavailableDataIsDifferentFromAnActualZero() {
        val absent = movementValue<Long>(null)
        val zero = movementValue(0L, listOf("example.synthetic.pedometer"))
        assertEquals(MovementStatus.NoData, absent.status)
        assertNull(absent.value)
        assertEquals(MovementStatus.Available, zero.status)
        assertEquals(0L, zero.value!!.toLong())
        assertEquals(listOf("example.synthetic.pedometer"), zero.sourcePackages)
    }

    @Test fun localDayUsesCivilMidnightAcrossDaylightSavingChanges() {
        val zone = ZoneId.of("America/New_York")
        val spring = movementDayBounds(LocalDate.of(2026, 3, 8), zone)
        val autumn = movementDayBounds(LocalDate.of(2026, 11, 1), zone)
        assertEquals(23L, Duration.between(spring.first, spring.second).toHours())
        assertEquals(25L, Duration.between(autumn.first, autumn.second).toHours())
    }

    @Test fun oldAndroidDoesNotRequestPermissionsOrReadRecords() {
        val reader = HealthMovementReader(RuntimeEnvironment.getApplication())
        val access = reader.access()
        assertEquals(MovementAvailability.Unsupported, access.availability)
        assertTrue(access.grantedPermissions.isEmpty())
        assertEquals(HealthMovementReader.PERMISSIONS, access.missingPermissions)
        assertNull(reader.permissionIntent())
    }
}
