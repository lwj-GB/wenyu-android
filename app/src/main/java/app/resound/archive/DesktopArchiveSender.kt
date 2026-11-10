package app.resound.archive

import android.content.Context
import app.resound.transcribe.awaitResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit

/** 只保存接收地址。临时配对码由发送面板持有，不写入偏好或日志。 */
internal class DesktopReceiverSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("desktop-receiver", Context.MODE_PRIVATE)
    val address: String get() = prefs.getString("address", "").orEmpty()
    fun saveAddress(value: String) {
        val address = desktopReceiverAddress(value)
        check(prefs.edit().putString("address", address).commit()) { "电脑地址未保存，请重试" }
    }
}

/** 使用数字IPv4避免DNS重绑定；不给公网、重定向或系统代理发送日记。 */
internal fun desktopReceiverAddress(input: String): String {
    val uri = runCatching { URI(input.trim()) }.getOrNull()
    require(uri != null && uri.scheme == "http" && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
        uri.rawPath.orEmpty() in setOf("", "/") && uri.port in 1..65535) {
        "请填写电脑显示的 http://局域网IP:端口，不含路径、账号或查询参数"
    }
    val parts = uri.host.orEmpty().split('.')
    require(parts.size == 4 && parts.all { it.matches(Regex("0|[1-9][0-9]{0,2}")) && it.toInt() in 0..255 }) {
        "请使用电脑显示的局域网IPv4地址；USB调试可用127.0.0.1"
    }
    val p = parts.map(String::toInt)
    val local = p[0] == 127 || p[0] == 10 || (p[0] == 172 && p[1] in 16..31) ||
        (p[0] == 192 && p[1] == 168) || (p[0] == 169 && p[1] == 254)
    require(local) { "只允许发送到局域网或本机地址，请检查电脑接收地址" }
    return "http://${parts.joinToString(".")}:${uri.port}"
}

internal data class DesktopArchiveReceipt(val id: String, val alreadyExists: Boolean)

internal class DesktopArchiveSender(private val client: OkHttpClient = OkHttpClient.Builder()
    .proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
    .connectTimeout(5, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
    .writeTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()) {
    fun close() {
        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
    suspend fun send(address: String, pairingCode: String, snapshot: DesktopArchiveSnapshot): DesktopArchiveReceipt = withContext(Dispatchers.IO) {
        val base = desktopReceiverAddress(address)
        val code = pairingCode.trim()
        require(code.matches(Regex("[0-9]{8}"))) { "请输入电脑显示的8位配对码" }
        val bytes = snapshot.json.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 20 * 1024 * 1024) { "当天归档超过20 MiB，请先使用其他方式分享" }
        try {
            client.newCall(Request.Builder().url("$base/archive").header("Authorization", "Bearer $code")
                .post(bytes.toRequestBody("application/json; charset=utf-8".toMediaType())).build()).awaitResponse().use { response ->
                when (response.code) {
                    401, 403 -> throw IllegalStateException("配对码无效或接收已到期，请在电脑重新开启接收并填写新码")
                    413 -> throw IllegalStateException("归档太大，电脑未接收；可使用其他方式分享")
                    429 -> throw IllegalStateException("尝试过于频繁，请稍后重试")
                    422 -> throw IllegalStateException("电脑未接受这份归档，请确认两端版本并重新开启接收")
                }
                check(response.isSuccessful) { "电脑未确认归档，请检查接收状态后重试" }
                val body = requireNotNull(response.body)
                val raw = body.byteStream().use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(1024)
                    while (out.size() <= 16_384) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                    }
                    out.toByteArray()
                }
                check(raw.size <= 16_384) { "电脑回执无效，请刷新电脑手机页核对后重试" }
                val receipt = JSONObject(raw.toString(Charsets.UTF_8))
                check(receipt.optString("status") in setOf("created", "exists") && receipt.optString("id") == snapshot.sha256 &&
                    receipt.optString("date") == snapshot.date && receipt.optInt("lineCount", -1) == snapshot.lineCount &&
                    receipt.optInt("todoCount", -1) == snapshot.todoCount) {
                    "电脑回执与本次归档不一致，请刷新电脑手机页核对后重试"
                }
                DesktopArchiveReceipt(snapshot.sha256, receipt.getString("status") == "exists")
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (known: IllegalStateException) { throw known }
        catch (_: java.net.SocketTimeoutException) { throw IllegalStateException("等待电脑回执超时；手机资料保留，可用同一快照重试") }
        catch (_: Exception) { throw IllegalStateException("未收到有效的电脑回执，请检查同一Wi-Fi、接收地址及电脑接收开关；手机资料保留，可重试") }
    }
}
