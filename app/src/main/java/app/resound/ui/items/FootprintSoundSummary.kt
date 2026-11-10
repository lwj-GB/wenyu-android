package app.resound.ui.items

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import app.resound.AppGraph
import app.resound.capture.DayStats
import app.resound.location.Track
import app.resound.ui.ShellNav
import app.resound.ui.collectWhenPageActive
import app.resound.ui.settings.SettingsPage
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.Pill
import java.time.LocalDate

/** 复用采集和足迹记录，不用健康步数推断行踪或环境。 */
@Composable
internal fun FootprintSoundSummary(date: LocalDate, speech: DayStats?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("足迹、声音与环境", style = RsType.headline, color = Rs.colors.text)
        if (AppGraph.isLocationReady) {
            val repo = AppGraph.location
            val enabled by repo.settings.enabled.collectWhenPageActive(initial = repo.settings.enabled.value)
            val points by remember(date, repo) { repo.day(date) }.collectWhenPageActive(initial = null)
            val track = remember(points) { points?.let(Track::day) }
            val message = when {
                !enabled && points.isNullOrEmpty() -> "足迹未开启"
                points == null -> "正在读取足迹…"
                track!!.points.isEmpty() -> "当天没有定位记录"
                else -> "${track.points.size} 个定位点 · ${track.stays.size} 段停留"
            }
            SummaryText(message)
            if (enabled && !repo.settings.hasForeground()) SummaryText("尚未授权定位，不会记录新位置")
            SummaryText("未记录的时段保留为空白。")
            Pill("查看足迹", filled = false) { ShellNav.openSettings(SettingsPage.Location, date) }
        } else SummaryText("足迹尚未就绪")

        Text("声音", style = RsType.headline, color = Rs.colors.text)
        SummaryText(speech?.let { "${it.segmentCount} 段人声 · ${it.speechMillis / 60_000} 分钟" } ?: "录音统计尚未就绪")
        Pill("打开原话页", filled = false) { ShellNav.go(5) }

        Text("环境", style = RsType.headline, color = Rs.colors.text)
        if (AppGraph.isEnvSoundReady) {
            val repo = AppGraph.envSound
            val settings by repo.settings.collectWhenPageActive(initial = repo.settings.value)
            val hours by remember(date, repo) { repo.hourly(date) }.collectWhenPageActive(initial = null)
            SummaryText(when {
                hours == null && settings.enabled -> "正在读取环境记录…"
                hours.isNullOrEmpty() -> if (settings.enabled) "当天暂无环境识别结果" else "环境音识别未开启"
                else -> hours.orEmpty().sortedBy { it.hour }.joinToString("；") { hour ->
                    val labels = (listOfNotNull(hour.tag?.title) + hour.sounds.map { it.title }).distinct()
                    "${hour.hour}时：${labels.joinToString("、").ifEmpty { "未识别到环境标签" }}"
                }
            })
            Pill("环境音设置", filled = false) { ShellNav.openSettings(SettingsPage.EnvSound) }
        } else SummaryText("环境音识别尚未就绪")
    }
}

@Composable
private fun SummaryText(text: String) = Text(text, style = RsType.caption, color = Rs.colors.textSecondary)
