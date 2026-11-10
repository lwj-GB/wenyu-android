package app.resound.ui.settings

import android.content.Intent
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import app.resound.AppGraph
import app.resound.ui.verify.Pill
import kotlinx.coroutines.launch
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.resound.R
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.RsCard

const val HOMEPAGE_URL = "https://github.com/lwj-GB/lingxi-bridge"
const val UPSTREAM_URL = "https://github.com/0xCoach/TaoSheng"

/** 与 docs/A08 的使用目的及 A19 的手机实际能力保持一致。 */
private val HIGHLIGHTS = listOf(
    "记录与回看" to "保存主动录下的人声，按日期查看原话、记忆和待办，找到当时说过什么。",
    "人物与事件" to "围绕一个人添加档案、录音和文字，把相关经历放到同一处。",
    "分析与核对" to "整理沟通方式、兴趣偏好和关系变化。每个判断都可以回看依据、修正或忽略。",
    "问答与归档" to "带着问题查资料，保存有出处的回答；需要时主动把选中的日记录发到电脑。",
)

private val USE_CASES = listOf(
    "回顾一天，找回遗漏" to "晚上打开记忆，回看当天的事件和原话，把答应的事、没想明白的问题再确认一遍。",
    "记住重要的人的细节" to "为亲友建立档案，加入对方明确说过的偏好和共同经历。下次聊天或准备礼物时，先回到这些材料。",
    "沟通前，理清事情经过" to "把相关原话和事件放在一起，请 AI 帮忙区分已知、推测和未知，再决定哪些问题值得当面问清楚。",
    "会谈后，整理需要跟进的事" to "在参与者知情的情况下录音，保存后用本地转写形成文字，再按需要分析和核对。原音与报告分别保留。",
)

/** 设置 → 关于 → 关于文鱼 */
@Composable
fun AboutPage(version: String?, bottom: Dp) {
    val c = Rs.colors
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("ui", Context.MODE_PRIVATE) }
    var devUnlocked by remember { mutableStateOf(prefs.getBoolean(DEV_UNLOCKED, false)) }
    var taps by remember { mutableIntStateOf(0) }
    Column(
        Modifier.fillMaxWidth().testTag("about-page").verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = bottom + 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(68.dp).shadow(12.dp, RoundedCornerShape(20.dp), ambientColor = c.shadow, spotColor = c.shadow)
                .clip(RoundedCornerShape(20.dp))) {
                Image(painterResource(R.drawable.wenyu_avatar), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Spacer(Modifier.height(14.dp))
            Text(context.getString(R.string.app_name), style = RsType.title, color = c.text)
            Spacer(Modifier.height(4.dp))
            Text("记录日常，带着依据理解关系。", style = RsType.body.copy(lineHeight = 24.sp), color = c.textSecondary, textAlign = TextAlign.Center)
            if (version != null) {
                Spacer(Modifier.height(4.dp))
                // 连点 7 下打开开发者选项（预览版开关）；普通用户看不到
                Text("版本 ${displayVersion(version)}", style = RsType.caption, color = c.textTertiary,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(interactionSource = null, indication = null) {
                        if (devUnlocked) return@clickable
                        taps++
                        when {
                            taps >= 7 -> { devUnlocked = true; prefs.edit().putBoolean(DEV_UNLOCKED, true).apply()
                                Toast.makeText(context, "已打开开发者选项", Toast.LENGTH_SHORT).show() }
                            taps >= 4 -> Toast.makeText(context, "再点 ${7 - taps} 下打开开发者选项", Toast.LENGTH_SHORT).show()
                        }
                    }.padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }

        if (devUnlocked && AppGraph.isUpdaterReady) PreviewCard()

        AboutCard("文鱼是什么") {
            AboutText("文鱼把个人日常记录和人物关系资料放在一起。你可以回看自己的一天，也可以围绕一个人整理原话、经历和需要继续了解的问题。")
            Spacer(Modifier.height(10.dp))
            AboutText("AI 帮你整理材料、提出观察和沟通建议。原文留着，判断可以核对；它不替你确定别人的内心，也不做心理诊断。", secondary = true)
        }

        AboutCard("主要能做什么") {
            HIGHLIGHTS.forEachIndexed { index, (title, description) ->
                if (index > 0) Spacer(Modifier.height(16.dp))
                AboutEntry(title, description)
            }
        }

        AboutCard("使用场景") {
            USE_CASES.forEachIndexed { index, (title, description) ->
                if (index > 0) Spacer(Modifier.height(18.dp))
                AboutEntry(title, description)
            }
        }

        AboutCard("资料与隐私") {
            AboutText("不用注册公共账号，资料默认保存在手机。本地转写在设备上处理原音；使用云端转写或智能分析时，选定材料会发送到你配置的服务。")
            Spacer(Modifier.height(10.dp))
            AboutText("只保存和处理你有权使用的资料。未保留的个人采集原音默认三天清理，需要长期保存时请先按天保留或导出。", secondary = true)
        }

        AboutCard("开源与来源") {
            AboutText("文鱼是灵犀桥项目的手机端，基于涛声 TaoSheng 开发，保留上游 MIT 许可与署名。", secondary = true)
            Spacer(Modifier.height(16.dp))
            HomepageLink()
            Spacer(Modifier.height(12.dp))
            HomepageLink(UPSTREAM_URL,"上游来源 · TaoSheng")
            Spacer(Modifier.height(8.dp))
            Text("长按可复制链接", style = RsType.caption, color = c.textTertiary)
        }
    }
}

@Composable
private fun AboutCard(title: String, content: @Composable () -> Unit) {
    val c = Rs.colors
    RsCard(modifier = Modifier.testTag("about-section-$title"), padding = PaddingValues(horizontal = 20.dp, vertical = 20.dp)) {
        Text(title, style = RsType.title.copy(fontSize = 18.sp, lineHeight = 26.sp), color = c.text)
        Spacer(Modifier.height(14.dp))
        content()
    }
}

@Composable
private fun AboutText(text: String, secondary: Boolean = false) {
    Text(text, style = RsType.body.copy(lineHeight = 25.sp), color = if (secondary) Rs.colors.textSecondary else Rs.colors.text)
}

@Composable
private fun AboutEntry(title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = RsType.body.copy(fontWeight = FontWeight.Medium, lineHeight = 23.sp), color = Rs.colors.text)
        AboutText(description, secondary = true)
    }
}

/** 点一下用浏览器打开；长按复制（国内打不开时方便发到电脑上） */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomepageLink(url:String=HOMEPAGE_URL,label:String="本项目主页 · 灵犀桥") {
    val c = Rs.colors
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).combinedClickable(
            onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
            onLongClick = {
                clipboard.setText(AnnotatedString(url))
                Toast.makeText(context, "已复制链接", Toast.LENGTH_SHORT).show()
            },
        ).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = RsType.body.copy(lineHeight = 24.sp), color = c.text)
            Text(url.removePrefix("https://"), style = RsType.subhead.copy(lineHeight = 21.sp), color = c.accent)
        }
        Text("›", style = RsType.headline.copy(fontWeight = FontWeight.Normal), color = c.textTertiary, textAlign = TextAlign.End)
    }
}

private const val DEV_UNLOCKED = "devOptionsUnlocked"

/** “0.2.12-preview.1” 显示成“0.2.12 预览版 1”；正式版原样 */
fun displayVersion(version: String): String =
    Regex("""^(.+)-preview\.(\d+)$""").matchEntire(version)?.let { "${it.groupValues[1]} 预览版 ${it.groupValues[2]}" } ?: version

/** 开发者选项：接收预览版 */
@Composable
private fun PreviewCard() {
    val c = Rs.colors
    val updater = AppGraph.updater
    val on by updater.previewChannel.collectAsState()
    AboutCard("开发者选项") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("接收预览版", style = RsType.body, color = c.text)
                Spacer(Modifier.height(2.dp))
                Text("给开发测试用的新版本，可能不稳定。关掉后等下一个正式版自动换回。",
                    style = RsType.caption, color = c.textSecondary)
            }
            Spacer(Modifier.width(12.dp))
            app.resound.ui.skills.Toggle(on) { updater.setPreviewChannel(it) }
        }
        if (updater.isPreviewBuild) {
            Spacer(Modifier.height(8.dp))
            Text("当前装的是预览版", style = RsType.caption, color = c.accent)
        }
    }
}

/** 导出问题记录并交给系统分享；底层没装配时不显示 */
@Composable
internal fun ExportProblemPill(label: String = "导出问题记录", onDone: () -> Unit = {}) {
    if (!AppGraph.isProblemReportsReady) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    Pill(if (busy) "正在导出…" else label, filled = false) {
        if (busy) return@Pill
        busy = true
        scope.launch {
            runCatching {
                val file = AppGraph.problemReports.export()
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(Intent.createChooser(send, "发送问题记录"))
                AppGraph.problemReports.dismissCrashNotice()
                onDone()
            }.onFailure { Toast.makeText(context, "没能导出，请稍后再试", Toast.LENGTH_SHORT).show() }
            busy = false
        }
    }
}

/** 今天页顶部：上次闪退了，提示一次导出问题记录 */
@Composable
fun CrashNotice(topGap: Dp = 0.dp) {
    if (!AppGraph.isProblemReportsReady) return
    val c = Rs.colors
    val at by AppGraph.problemReports.lastCrashAt.collectAsState()
    if (at == null) return
    Spacer(Modifier.height(topGap))
    RsCard(padding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)) {
        Text("应用上次意外退出了", style = RsType.body, color = c.text)
        Spacer(Modifier.height(2.dp))
        Text("导出问题记录发给开发，方便查原因。", style = RsType.caption, color = c.textSecondary)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ExportProblemPill()
            Text("知道了", style = RsType.subhead, color = c.textSecondary,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable { AppGraph.problemReports.dismissCrashNotice() }
                    .padding(horizontal = 12.dp, vertical = 8.dp))
        }
    }
}
