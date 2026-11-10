package app.resound.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** 相邻页保留已展示内容，仅暂停数据订阅，避免滑动预加载触发整页查询。 */
@Composable
internal fun <T> Flow<T>.collectWhenPageActive(initial: T): State<T> {
    val active = LocalShellPageActive.current
    val displayed = remember(this) { mutableStateOf(initial) }
    LaunchedEffect(this, active) {
        if (active) this@collectWhenPageActive.collect { displayed.value = it }
    }
    return displayed
}

@Composable
internal fun <T> StateFlow<T>.collectWhenPageActive(): State<T> = collectWhenPageActive(value)
