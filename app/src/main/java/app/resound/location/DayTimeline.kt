package app.resound.location

/** 同一处（半径内）的多段停留合成一个地点；家仅由用户明确标记。 */
data class PlaceGroup(val index: Int, val lat: Double, val lng: Double, val stays: List<Stay>, val home: Boolean) {
    val millis: Long get() = stays.sumOf { it.end - it.start }
}

enum class SpanKind { Stay, Move, Gap }

/** 时间轴上的一段：停留（属于某个地点）、路上、没有记录。 */
data class Span(val start: Long, val end: Long, val kind: SpanKind, val group: Int? = null)

data class DayTimeline(val spans: List<Span>, val groups: List<PlaceGroup>) {
    val homeMillis: Long get() = groups.filter { it.home }.sumOf { it.millis }
    /** 有记录、但不在家的时间（含路上）。 */
    val awayMillis: Long get() = spans.filter { it.kind != SpanKind.Gap }.sumOf { it.end - it.start } - homeMillis
    fun groupOf(stay: Stay): PlaceGroup? = groups.firstOrNull { stay in it.stays }
}

object Timeline {
    /** 相邻两点隔得比这久，就当这段没有记录（收音停了、手机关机等）。 */
    const val GAP_MS = 20 * 60_000L

    @Suppress("UNUSED_PARAMETER")
    fun groups(stays: List<Stay>, dayStart: Long, isHome: (Double, Double) -> Boolean = { _, _ -> false }): List<PlaceGroup> {
        val buckets = ArrayList<MutableList<Stay>>()
        for (s in stays) {
            val hit = buckets.firstOrNull { b -> Track.meters(b[0].lat, b[0].lng, s.lat, s.lng) <= Track.STAY_RADIUS_M }
            if (hit != null) hit += s else buckets += mutableListOf(s)
        }
        val homeBucket = buckets.firstOrNull { b -> isHome(b.map { it.lat }.average(), b.map { it.lng }.average()) }
        return buckets.sortedByDescending { b -> b.sumOf { it.end - it.start } }.mapIndexed { i, b ->
            PlaceGroup(i, b.map { it.lat }.average(), b.map { it.lng }.average(), b, b === homeBucket)
        }
    }

    /** points 已按时间排好序。to 一般是当天结束，今天则传“现在”。 */
    fun build(points: List<LocationPoint>, stays: List<Stay>, from: Long, to: Long, isHome: (Double, Double) -> Boolean = { _, _ -> false }): DayTimeline {
        val groups = groups(stays, from, isHome)
        if (points.isEmpty() || to <= from) return DayTimeline(if (to > from) listOf(Span(from, to, SpanKind.Gap)) else emptyList(), groups)
        val raw = ArrayList<Span>()
        fun add(a: Long, b: Long, kind: SpanKind, g: Int? = null) {
            val s = a.coerceIn(from, to); val e = b.coerceIn(from, to)
            if (e > s) raw += Span(s, e, kind, g)
        }
        fun between(a: Long, b: Long) {
            // a..b 之间按相邻点间隔分成“路上”或“没记录”
            val inner = points.filter { it.at in (a + 1) until b }.map { it.at }
            val marks = listOf(a) + inner + b
            for (i in 0 until marks.size - 1) add(marks[i], marks[i + 1], if (marks[i + 1] - marks[i] > GAP_MS) SpanKind.Gap else SpanKind.Move)
        }
        add(from, points.first().at, SpanKind.Gap)
        var cursor = points.first().at
        for (s in stays) {
            between(cursor, s.start)
            add(s.start, s.end, SpanKind.Stay, groups.first { s in it.stays }.index)
            cursor = s.end
        }
        val last = points.last().at
        between(cursor, last)
        add(last, to, if (to - last > GAP_MS) SpanKind.Gap else SpanKind.Move)
        // 合并相邻同类
        val merged = ArrayList<Span>()
        for (s in raw) {
            val p = merged.lastOrNull()
            if (p != null && p.kind == s.kind && p.group == s.group && p.end == s.start) merged[merged.size - 1] = p.copy(end = s.end) else merged += s
        }
        return DayTimeline(merged, groups)
    }

}
