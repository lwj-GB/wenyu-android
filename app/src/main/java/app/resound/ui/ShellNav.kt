package app.resound.ui

import kotlinx.coroutines.flow.MutableStateFlow

/** 稳定路由ID：0今天、1问问、2记忆、3待办、4设置、5原话；显示顺序由ShellRoutes定义。 */
object ShellNav {
    val request = MutableStateFlow<Pair<Int, Long>?>(null)
    fun go(tab: Int) { request.value = tab to System.nanoTime() }

    /** 从别的页直接打开设置里的某个子页（如首页上手清单） */
    val settingsPage = MutableStateFlow<app.resound.ui.settings.SettingsPage?>(null)
    private var locationDate: java.time.LocalDate? = null
    fun openSettings(page: app.resound.ui.settings.SettingsPage, date: java.time.LocalDate? = null) {
        locationDate = if (page == app.resound.ui.settings.SettingsPage.Location) date else null
        settingsPage.value = page
        go(4)
    }
    fun takeLocationDate(): java.time.LocalDate? = locationDate.also { locationDate = null }

    /** 通知栏“删最近 10 分钟”：回到今天页并打开聆听面板的删除确认 */
    val forgetRecent = MutableStateFlow(false)
}
