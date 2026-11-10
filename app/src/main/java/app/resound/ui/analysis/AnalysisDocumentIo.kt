package app.resound.ui.analysis

import android.content.Context
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal const val MAX_ANALYSIS_BACKUP_BYTES=10*1024*1024

/** Success includes close(); a returned SAF URI alone does not mean the document was written. */
internal fun writeAnalysisDocument(context:Context,uri:Uri,text:String) {
    val bytes=text.toByteArray(Charsets.UTF_8)
    require(bytes.size<=MAX_ANALYSIS_BACKUP_BYTES){"文字资料超过10MiB，请分批整理后导出"}
    val stream=context.contentResolver.openOutputStream(uri,"wt")?:error("无法写入所选文件")
    stream.use { it.write(bytes);it.flush() }
}

internal fun readAnalysisDocument(context:Context,uri:Uri):String {
    val input=context.contentResolver.openInputStream(uri)?:error("无法读取所选文件")
    val bytes=input.use { source ->
        val output=java.io.ByteArrayOutputStream()
        val buffer=ByteArray(8192)
        while(true){val count=source.read(buffer);if(count<0)break;require(output.size()+count<=MAX_ANALYSIS_BACKUP_BYTES){"备份超过10MiB，未导入"};output.write(buffer,0,count)}
        output.toByteArray()
    }
    return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
}

internal fun analysisDocumentName(name:String,extension:String):String = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"),"_").take(80).ifBlank{"分析资料"}+"."+extension
