package app.resound.diary

import android.app.Application
import android.content.Context
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class DeviceStatisticsTest {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    private val start = 6_000_000L
    @Before fun reset() {
        context.getSharedPreferences("notification-metadata", 0).edit().clear().commit()
        NotificationMetadataStore.listenerConnected = false
    }
    @After fun disconnect() { NotificationMetadataStore.listenerConnected = false }

    @Test fun usageClipsCrossMidnightAndIgnoresOldActivityPauses() {
        val events = listOf(
            UsageTransition(90, "app.fictional.video", UsageTransitionKind.Foreground),
            UsageTransition(130, "app.fictional.video", UsageTransitionKind.Background),
            UsageTransition(150, "app.fictional.chat", UsageTransitionKind.Foreground),
            UsageTransition(160, "app.fictional.old", UsageTransitionKind.Background),
            UsageTransition(180, "", UsageTransitionKind.Reset),
            UsageTransition(190, "app.fictional.notes", UsageTransitionKind.Foreground))
        val slices = usageSlices(events, 100, 200)
        assertEquals(listOf(UsageSlice("app.fictional.video", 100, 130),
            UsageSlice("app.fictional.chat", 150, 180), UsageSlice("app.fictional.notes", 190, 200)), slices)
        assertEquals(70L, slices.sumOf { it.endAt - it.startAt })
    }

    @Test fun notificationUpdatesDeduplicateAndRemovalDoesNotEraseReceivedCount() {
        val store = NotificationMetadataStore(context)
        val rawIdentity = "fictional-id-with-a-private-tag"
        val identity = notificationIdentityHash(rawIdentity)
        store.connected(start + 500)
        store.posted("app.fictional.chat", identity, start + 1_000, start + 1_000)
        store.posted("app.fictional.chat", identity, start + 2_000, start + 2_000)
        store.removed(identity, start + 3_000)
        store.posted("app.fictional.chat", identity, start + 4_000, start + 4_000)
        // 授权之前已存在的通知不补算为新通知。
        store.posted("app.fictional.old", notificationIdentityHash("old"), start - 1_000, start + 5_000)
        val result = store.read(start, start + 60_000, start + 10_000)
        assertEquals(mapOf("app.fictional.chat" to 2), result.counts)
        assertEquals(9_500L, result.coveredMillis)
        assertEquals(2, result.hours.values.sum())
        val raw = context.getSharedPreferences("notification-metadata", 0).getString("state", "")!!
        assertFalse(raw.contains(rawIdentity))
        assertFalse(raw.contains("title")); assertFalse(raw.contains("text"))
    }

    @Test fun processGapIsNotReportedAsContinuousObservation() {
        val store = NotificationMetadataStore(context)
        store.connected(start + 1_000)
        store.posted("app.fictional.chat", notificationIdentityHash("one"), start + 2_000, start + 2_000)
        NotificationMetadataStore.listenerConnected = false
        assertEquals(1_000L, store.read(start, start + 60_000, start + 20_000).coveredMillis)
        val restored = NotificationMetadataStore(context)
        restored.connected(start + 30_000)
        val day = restored.read(start, start + 60_000, start + 40_000)
        assertEquals(11_000L, day.coveredMillis)
        assertEquals(start + 1_000, day.from)
        assertEquals(start + 40_000, day.until)
        assertEquals(0L, restored.read(start - 86_400_000L, start - 1, start + 40_000).coveredMillis)
    }

    @Test fun dailyTotalsRemainAfterMinuteTimelineExpires() {
        val store = NotificationMetadataStore(context)
        store.connected(start + 500)
        store.posted("app.fictional.chat", notificationIdentityHash("kept-day"), start + 1_000, start + 1_000)
        store.disconnected(start + 2_000)
        store.connected(start + 40 * 86_400_000L)
        val oldDay = store.read(start, start + 60_000, start + 40 * 86_400_000L)
        assertEquals(mapOf("app.fictional.chat" to 1), oldDay.counts)
        assertEquals(1_500L, oldDay.coveredMillis)
        assertTrue(oldDay.hours.isEmpty())
    }
}
