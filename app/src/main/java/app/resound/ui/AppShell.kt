package app.resound.ui

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.CompositionLocalProvider
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.springClick

/**
 * 外壳：底部悬浮胶囊切换各页。
 * 稳定路由ID与显示顺序分开；内容可手指左右滑动。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppShell(tabs: List<Pair<String, @Composable () -> Unit>>, request: Pair<Int, Long>? = null) {
    val c = Rs.colors
    val routeOrder = remember(tabs.size) { shellRouteOrder(tabs.size) }
    val pager = rememberPagerState(initialPage = routeOrder.indexOf(0).coerceAtLeast(0)) { tabs.size }
    val scope = rememberCoroutineScope()
    val tab = routeOrder[pager.currentPage]
    val pageDensity = LocalDensity.current
    // 外部（如点通知）要求切到某一页；Long 用来区分同一页的多次请求
    androidx.compose.runtime.LaunchedEffect(request) { request?.let { routeOrder.indexOf(it.first).takeIf { page -> page >= 0 }?.let { page -> pager.scrollToPage(page) } } }
    val navRequest by ShellNav.request.collectAsState()
    androidx.compose.runtime.LaunchedEffect(navRequest) {
        navRequest?.let { handled ->
            routeOrder.indexOf(handled.first).takeIf { it >= 0 }?.let { page ->
                pager.scrollToPage(page)
                // 导航只执行一次；切换模式重新挂载时不能重放，也不能清掉期间的新请求。
                ShellNav.request.compareAndSet(handled, null)
            }
        }
    }
    val holder = rememberSaveableStateHolder()
    // 再点一下当前底栏图标的次数，按页记；页面拿到后回到顶部
    val reselect = remember(tabs.size) { androidx.compose.runtime.mutableStateListOf(*Array(tabs.size) { 0 }) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val labels = routeOrder.map { tabs[it].first }
    val haze = rememberHazeState()
    val keyboard = WindowInsets.isImeVisible
    Box(Modifier.fillMaxSize().background(c.background)) {
        // 各页沿实际显示顺序滑动；页面内列表操作优先消费自己的横向手势。
        HorizontalPager(pager, Modifier.hazeSource(haze), key = { routeOrder[it] }, userScrollEnabled = !keyboard) { page ->
            val t = routeOrder[page]
            // 平板、折叠屏展开、横屏时内容不拉满：限制最大宽度并居中，手机上不受影响
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = MaxContentWidth).fillMaxSize()) {
                    // 保留相邻页稳定布局与保存状态，只暂停未停稳页的数据订阅和统计读取。
                    holder.SaveableStateProvider(t) {
                        CompositionLocalProvider(LocalTabReselect provides reselect.getOrElse(t) { 0 },
                            LocalShellPageSelected provides (t == routeOrder[pager.settledPage]),
                            LocalShellPageActive provides (t == routeOrder[pager.settledPage] && !pager.isScrollInProgress), LocalDensity provides pageDensity) {
                            Box(Modifier.fillMaxSize().testTag("shell-content-$t")) { tabs[t].second() }
                        }
                    }
                }
            }
        }
        if (!keyboard) {
            // 页面底部渐隐：内容滚到底栏附近时慢慢淡进背景色，底栏不会和卡片撞色
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(
                Brush.verticalGradient(0f to c.background.copy(alpha = 0f), 0.45f to c.background.copy(alpha = 0.88f), 1f to c.background),
            )) {
                Box(Modifier.height(ShellBottomPadding + 34.dp))
                Box(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
            // 底栏单独一块：只在选中页变化时重画，切页时页面重建不会牵连它
            TabBar(labels, pager.currentPage, { page ->
                val target = routeOrder[page]
                if (target == tab) { reselect[target] = reselect[target] + 1; haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove) }
                else scope.launch { pager.animateScrollToPage(page) }
            }, haze, Modifier.align(Alignment.BottomCenter))
        }
        // 听原声的迷你播放条（浮在底栏上方）；切到后台就停，收音同时恢复
        app.resound.ui.transcript.PlayerBar(ShellBottomPadding + 8.dp)
        // 后台导出的进度小胶囊（有播放条时让到它上面）；点开或点通知时打开导出面板
        Box(Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars)) {
            app.resound.ui.transcript.ExportCapsule(ShellBottomPadding + 26.dp + if (app.resound.ui.transcript.LinePlayer.active) 76.dp else 0.dp)
        }
        // 播放卡片“回到这件事”
        app.resound.ui.transcript.PlayerNav.openEvent?.let { e ->
            if (app.resound.AppGraph.isMemoryReady) app.resound.ui.today.EventSheet(e, app.resound.AppGraph.memory) { app.resound.ui.transcript.PlayerNav.openEvent = null }
        }
        // 播放卡片“回到这次导入”
        app.resound.ui.transcript.PlayerNav.openImport?.let { id ->
            app.resound.ui.importer.ImportRecordSheet(id, onOpenEvent = { app.resound.ui.transcript.PlayerNav.openEvent = it }) { app.resound.ui.transcript.PlayerNav.openImport = null }
        }
        app.resound.ui.transcript.ExportUi.openJob?.let { id -> app.resound.ui.transcript.ExportJobSheet(id) { app.resound.ui.transcript.ExportUi.openJob = null } }
        app.resound.ui.today.UndoBar.Host(ShellBottomPadding)
        app.resound.ui.redo.RedoUi.openJob?.let { id -> app.resound.ui.redo.RedoJobSheet(id) { app.resound.ui.redo.RedoUi.openJob = null } }
        androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) { app.resound.ui.transcript.LinePlayer.stop() }
    }
}

@Composable
internal fun TabBar(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, haze: HazeState, modifier: Modifier) {
    val c = Rs.colors
    // 细亮边：上沿亮、下沿淡，像玻璃边缘反光
    val rim = if (c.isDark) Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.05f)))
    else Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.95f), Color.White.copy(alpha = 0.35f)))
    Box(
        modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(bottom = 14.dp)
            .shadow(if (c.isDark) 0.dp else 20.dp, CircleShape, ambientColor = c.shadow, spotColor = c.shadow)
            .clip(CircleShape),
    ) {
        // 毛玻璃单独一层底板：模糊底下的页面，再罩半透明卡片色；旧系统（安卓 12 以下）自动退成半透明底。
        // 不能直接加在按钮那一行上，否则会缓存按钮的绘制，切页时选中底色不跟着走
        Box(Modifier.matchParentSize().hazeEffect(haze) {
            blurRadius = 22.dp
            backgroundColor = c.background
            tints = listOf(HazeTint(c.card.copy(alpha = if (c.isDark) 0.62f else 0.78f)))
            fallbackTint = HazeTint(c.card.copy(alpha = 0.96f))
            noiseFactor = 0f
        }.border(1.dp, rim, CircleShape))
        // 底栏字最多放大到 1.15 倍（系统字体再大也不再跟），窄屏时收紧左右留白，保证五个页签一行放得下
        val density = LocalDensity.current
        val narrow = LocalConfiguration.current.screenWidthDp < 360
        CompositionLocalProvider(LocalDensity provides Density(density.density, minOf(density.fontScale, 1.15f))) {
        Row(Modifier.then(if (labels.size > 5) Modifier.fillMaxWidth() else Modifier).padding(5.dp)) {
            labels.forEachIndexed { i, label ->
                val on = i == selected
                val bg by animateColorAsState(if (on) c.accentSoft else c.accentSoft.copy(alpha = 0f), spring(stiffness = Spring.StiffnessMediumLow), label = "tabBg")
                val fg by animateColorAsState(if (on) c.accent else c.textSecondary, label = "tabFg")
                Box(Modifier.then(if (labels.size > 5) Modifier.weight(1f) else Modifier).clip(CircleShape).background(bg).springClick { onSelect(i) }.testTag("shell-tab-$i")
                    .padding(horizontal = if (labels.size > 5) 3.dp else if (narrow) 9.dp else 13.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                    Text(label, style = RsType.headline, color = fg, maxLines = 1, softWrap = false)
                }
            }
        }
        }
    }
}

/** 宽屏上内容区的最大宽度，约等于大屏手机竖屏宽度的 1.5 倍。 */
val MaxContentWidth = 640.dp

/** 给页面底部留出悬浮胶囊的位置。 */
val ShellBottomPadding = 76.dp

/** Pager预加载相邻页时，权限数据只由当前可见页读取。 */
/** 导航归属与滑动中的订阅暂停分开，取消拖动不会离开当前页面。 */
val LocalShellPageSelected = androidx.compose.runtime.staticCompositionLocalOf { true }

val LocalShellPageActive = androidx.compose.runtime.staticCompositionLocalOf { true }
