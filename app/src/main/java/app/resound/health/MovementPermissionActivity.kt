package app.resound.health

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

/** 系统健康权限页可打开的静态使用说明；不读取任何健康记录。 */
class MovementPermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "运动数据使用说明"
        val text = TextView(this).apply {
            textSize = 17f
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            text = "灵犀桥仅在你打开日记并主动授权后，读取系统 Health Connect 提供的步数、距离、运动时长和时段。\n\n" +
                "不读取心率、睡眠、诊断或运动路线，不直接访问其他健康应用的私有数据。数据源没有提供的记录不会写成零。\n\n" +
                "此读取模块不联网、不写入或删除 Health Connect 数据。统计用于手机内展示，仅在你主动导出的日记包中附上，不会自动发送到转写或文本模型服务。\n\n" +
                "你可随时在系统健康权限页撤回授权。撤回后不再读取；已导出的文件由你在保存位置管理和删除。"
        }
        setContentView(ScrollView(this).apply { addView(text) })
    }
}
