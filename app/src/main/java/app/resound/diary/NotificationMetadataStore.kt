package app.resound.diary

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

internal data class NotificationCounts(val from: Long?, val until: Long?, val coveredMillis: Long,
    val counts: Map<String, Int>, val hours: Map<Long, Int>, val truncated: Boolean, val recordedZones: Set<String>)

/** 长期日聚合、有界分钟聚合、去重哈希。没有通知对象、标识原文、标题或正文。 */
internal class NotificationMetadataStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("notification-metadata", Context.MODE_PRIVATE)
    private fun state() = JSONObject(prefs.getString("state", "{}")!!).apply {
        if (!has("buckets")) put("buckets", JSONArray())
        if (!has("active")) put("active", JSONObject())
        if (!has("days")) put("days", JSONObject())
    }
    private fun save(state: JSONObject) { check(prefs.edit().putString("state", state.toString()).remove("writeError").commit()) }
    fun recordError() { prefs.edit().putBoolean("writeError", true).apply() }

    fun connected(at: Long) = synchronized(lock) {
        val state = state()
        if (state.optLong("connectedFrom", 0) > at) return@synchronized
        // 进程曾失联时不补算未知缺口。日覆盖已累计到最后一次回调。
        state.put("connectedFrom", at).put("lastSeen", at)
        prune(state, at); save(state); listenerConnected = true
    }
    fun disconnected(at: Long) = synchronized(lock) {
        val state = state()
        if (state.optLong("connectedFrom", 0) > at) return@synchronized
        checkpoint(state, at); state.remove("connectedFrom")
        listenerConnected = false; prune(state, at); save(state)
    }
    private fun day(state: JSONObject, at: Long, zone: ZoneId): JSONObject {
        val date = Instant.ofEpochMilli(at).atZone(zone).toLocalDate().toString()
        val key = "${zone.id}|$date"
        val days = state.getJSONObject("days")
        if (!days.has(key)) days.put(key, JSONObject().put("date", date).put("zone", zone.id)
            .put("counts", JSONObject()).put("coveredMillis", 0))
        return days.getJSONObject(key)
    }
    private fun checkpoint(state: JSONObject, at: Long) {
        if (!state.has("connectedFrom")) return
        val previous = state.optLong("lastSeen", at)
        if (at <= previous) return
        val zone = ZoneId.systemDefault()
        var from = previous
        while (from < at) {
            val tomorrow = Instant.ofEpochMilli(from).atZone(zone).toLocalDate().plusDays(1)
                .atStartOfDay(zone).toInstant().toEpochMilli()
            val until = minOf(at, tomorrow)
            val row = day(state, from, zone)
            row.put("from", minOf(row.optLong("from", from), from))
                .put("until", maxOf(row.optLong("until", until), until))
                .put("coveredMillis", row.optLong("coveredMillis", 0) + until - from)
                .put("observedAt", at)
            from = until
        }
        state.put("lastSeen", at)
    }
    fun posted(packageName: String, identityHash: String, postedAt: Long, receivedAt: Long) = synchronized(lock) {
        if (!listenerConnected) return@synchronized
        val state = state()
        val began = state.optLong("connectedFrom", receivedAt)
        if (receivedAt < began) return@synchronized
        val active = state.getJSONObject("active")
        val duplicate = active.has(identityHash)
        active.put(identityHash, receivedAt)
        // 授权前已存在的通知不补算；同一标识更新不再计数。
        if (!duplicate && postedAt >= began && postedAt <= receivedAt) {
            val minute = postedAt / 60_000L * 60_000L
            val buckets = state.getJSONArray("buckets")
            var bucket: JSONObject? = null
            for (i in 0 until buckets.length()) {
                val row = buckets.getJSONObject(i)
                if (row.getLong("at") == minute && row.getString("package") == packageName) { bucket = row; break }
            }
            if (bucket == null) buckets.put(JSONObject().put("at", minute).put("package", packageName).put("count", 1))
            else bucket.put("count", bucket.getInt("count") + 1)
            val totals = day(state, postedAt, ZoneId.systemDefault()).getJSONObject("counts")
            totals.put(packageName, totals.optInt(packageName, 0) + 1)
        }
        checkpoint(state, receivedAt); prune(state, receivedAt); save(state)
    }
    fun removed(identityHash: String, at: Long) = synchronized(lock) {
        if (!listenerConnected) return@synchronized
        val state = state()
        if (at < state.optLong("connectedFrom", at)) return@synchronized
        state.getJSONObject("active").remove(identityHash)
        checkpoint(state, at); save(state)
    }

    fun read(start: Long, end: Long, now: Long = System.currentTimeMillis()): NotificationCounts = synchronized(lock) {
        if (end <= start) return@synchronized NotificationCounts(null, null, 0, emptyMap(), emptyMap(), false, emptySet())
        check(!prefs.getBoolean("writeError", false))
        val state = state(); val zone = ZoneId.systemDefault()
        val selectedDate = Instant.ofEpochMilli(start).atZone(zone).toLocalDate().toString()
        val days = state.getJSONObject("days")
        val rows = days.keys().asSequence().map { days.getJSONObject(it) }.filter { it.getString("date") == selectedDate }.toList()
        val counts = mutableMapOf<String, Int>(); val hours = mutableMapOf<Long, Int>()
        val zones = rows.mapTo(mutableSetOf()) { it.getString("zone") }
        var from: Long? = null; var until: Long? = null; var covered = 0L
        for (row in rows) {
            val values = row.getJSONObject("counts")
            values.keys().forEach { pkg -> counts[pkg] = counts.getOrDefault(pkg, 0) + values.getInt(pkg) }
            covered += row.optLong("coveredMillis", 0)
            if (row.has("from")) from = minOf(from ?: Long.MAX_VALUE, row.getLong("from"))
            if (row.has("until")) until = maxOf(until ?: Long.MIN_VALUE, row.getLong("until"))
        }
        // 只临时延伸当前仍连接的观测，不把读页面时的估计写成永久历史。
        if (listenerConnected && state.has("connectedFrom")) {
            val liveFrom = maxOf(start, state.optLong("lastSeen", now))
            val liveUntil = minOf(end, now)
            if (liveUntil > liveFrom) {
                covered += liveUntil - liveFrom
                from = minOf(from ?: Long.MAX_VALUE, liveFrom); until = maxOf(until ?: Long.MIN_VALUE, liveUntil)
                zones += zone.id
            }
        }
        // 历史日总计按记录时区保留；跨时区时不伪造对应的新时区小时分布。
        if (zones.all { it == zone.id }) {
            val buckets = state.getJSONArray("buckets")
            for (i in 0 until buckets.length()) {
                val row = buckets.getJSONObject(i); val at = row.getLong("at")
                if (at >= start && at < end) {
                    val hour = Instant.ofEpochMilli(at).atZone(zone).withMinute(0).withSecond(0).withNano(0).toInstant().toEpochMilli()
                    hours[hour] = hours.getOrDefault(hour, 0) + row.getInt("count")
                }
            }
        }
        NotificationCounts(from, until, covered, counts, hours, state.optBoolean("truncated", false), zones)
    }
    private fun prune(state: JSONObject, now: Long) {
        val oldest = now - 31 * 86_400_000L
        val buckets = state.getJSONArray("buckets")
        val kept = (0 until buckets.length()).map { buckets.getJSONObject(it) }.filter { it.getLong("at") >= oldest }.sortedBy { it.getLong("at") }
        if (kept.size > 20_000) state.put("truncated", true)
        state.put("buckets", JSONArray(kept.takeLast(20_000)))
        val active = state.getJSONObject("active")
        val keys = active.keys().asSequence().toList().sortedBy { active.getLong(it) }
        if (keys.size > 5_000) { keys.take(keys.size - 5_000).forEach { active.remove(it) }; state.put("truncated", true) }
        // 日聚合不随分钟明细滚动删除。
    }
    companion object {
        private val lock = Any()
        @Volatile internal var listenerConnected = false
    }
}
