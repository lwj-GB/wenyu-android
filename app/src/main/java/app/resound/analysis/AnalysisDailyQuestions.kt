package app.resound.analysis

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import app.resound.MainActivity
import app.resound.R
import java.time.*
import java.util.concurrent.TimeUnit

const val DAILY_QUESTIONS_SKILL = "analysis_daily_questions"
const val OPEN_ANALYSIS_QUESTIONS = "analysis_questions"
const val EXTRA_ANALYSIS_PERSON = "analysis_person_id"
enum class DailyQuestionStatus { Pending, Answered, Unknown, Skipped }
data class AnalysisDailyQuestion(
    val id: Long, val personId: String, val date: LocalDate, val text: String,
    val reason: String, val status: DailyQuestionStatus = DailyQuestionStatus.Pending,
    val answer: String = "", val occurredAt: Long? = null, val answeredAt: Long? = null,
)

/** Fixed wall-clock target with WorkManager: the OS may delay work while idle. */
internal object AnalysisQuestionSchedule {
    const val WORK = "analysis-daily-questions"
    fun schedule(context: Context, trigger: app.resound.skills.SkillTrigger.Daily?) {
        val manager = WorkManager.getInstance(context)
        if (trigger == null) { manager.cancelUniqueWork(WORK); return }
        val now = ZonedDateTime.now(app.resound.skills.SKILL_ZONE)
        val next = nextRun(now, trigger)
        manager.enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<AnalysisDailyQuestionsWorker>()
                .setInitialDelay(Duration.between(now, next).toMillis().coerceAtLeast(0), TimeUnit.MILLISECONDS).build())
    }
    internal fun nextRun(now: ZonedDateTime, trigger: app.resound.skills.SkillTrigger.Daily): ZonedDateTime {
        var next = now.toLocalDate().atTime(trigger.minutes / 60, trigger.minutes % 60).atZone(now.zone)
        if (!next.isAfter(now)) next = next.plusDays(1)
        while (trigger.weekdays.isNotEmpty() && next.dayOfWeek.value !in trigger.weekdays) next = next.plusDays(1)
        return next
    }
}
class AnalysisDailyQuestionsWorker(context: Context, params: WorkerParameters): CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val repo = AnalysisStore(applicationContext).intelligence
        try { repo.runDue() } finally { repo.scheduleDailyQuestions() }
        Result.success()
    } catch (_: Exception) { Result.retry() }
}
internal object AnalysisQuestionNotifications {
    private const val CHANNEL = "analysis-questions"
    fun cancel(context: Context, personId: String) { context.getSystemService(NotificationManager::class.java).cancel(personId.hashCode()) }
    fun post(context: Context, personId: String, count: Int) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "人物资料待核对", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(context, MainActivity::class.java).setAction("$OPEN_ANALYSIS_QUESTIONS:$personId")
            .putExtra("open", OPEN_ANALYSIS_QUESTIONS).putExtra(EXTRA_ANALYSIS_PERSON, personId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, personId.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(personId.hashCode(), NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify).setContentTitle("有资料待核对")
            .setContentText("有${count}个问题待补充，点击查看")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setContentIntent(pending).setAutoCancel(true).build())
    }
}
