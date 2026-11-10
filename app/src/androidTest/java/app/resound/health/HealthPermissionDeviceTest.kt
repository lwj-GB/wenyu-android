package app.resound.health

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.resound.MainActivity
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** 只验证普通应用身份能打开健康权限页；不自动点击授权、不读取健康记录。 */
@RunWith(AndroidJUnit4::class)
class HealthPermissionDeviceTest {
    @Test fun appCanOpenHealthPermissionPage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.runOnMainSync {
            val intent = HealthMovementReader(activity).permissionIntent()
            assertNotNull("Health authorization entry should be available", intent)
            activity.startActivity(intent)
        }
    }
}
