package app.resound.ui.analysis

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.resound.ui.theme.RsType

@Composable
internal fun DailyStatusEditor(current:String?,onDismiss:()->Unit,onSave:(String?)->Unit) {
    var draft by remember{mutableStateOf(current.orEmpty())}
    var error by remember{mutableStateOf<String?>(null)}
    fun save(value:String?){runCatching{onSave(value)}.onFailure{error=it.message?:"保存失败，请重试"}}
    // 显式内容边界避免平台wrap宽度与权重行反复互相测量。
    androidx.compose.ui.window.Dialog(onDismissRequest=onDismiss,properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)) {
      Surface(modifier=Modifier.width(340.dp).height(510.dp),shape=androidx.compose.foundation.shape.RoundedCornerShape(24.dp)) {
       Column(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("今日状态",style=RsType.title)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("😊 开心","🥰 愉悦","😌 放松","💪 有活力","😐 平静","😴 疲惫","😔 低落","😟 紧张").chunked(2).forEach{pair->
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    pair.forEach{value->FilledTonalButton(onClick={draft=value},modifier=Modifier.weight(1f)){Text(value,style=RsType.body)}}
                }
            }
            RoundInput(draft,{if(it.codePointCount(0,it.length)<=24)draft=it},modifier=Modifier.fillMaxWidth().testTag("daily-status-input"),label={Text("自定义表情和名称")},singleLine=true)
            Text("仅保存今天的状态",style=RsType.caption)
            TextButton(onClick={save(null)}){Text("恢复自动显示")}
            error?.let{Text(it)}
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
            TextButton(onClick=onDismiss){Text("取消")}
            TextButton(enabled=draft.isNotBlank(),onClick={save(draft.trim())}){Text("保存状态")}
        }
       }
      }
    }
}
