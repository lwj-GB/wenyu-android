package app.resound.analysis

/** Keep internal evidence keys in the stored links, out of the prose people read. */
fun readableAnalysisText(text: String, sourceIds: Collection<String>): String {
    val ids = sourceIds.filter { it.isNotBlank() }.toSet()
    val uuid = Regex("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b")
    val brackets = Regex("\\[([^\\]\\n]*)\\]|【([^】\\n]*)】|（([^）\\n]*)）|\\(([^)\\n]*)\\)")
    val clean = brackets.replace(text) { match ->
        val contents = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }.orEmpty()
        val values = contents.replace(Regex("^(?:来源|依据|sources?|source_ids?|id)\\s*[:：]\\s*", RegexOption.IGNORE_CASE), "")
            .split(Regex("[,，、;；\\s]+" )).filter { it.isNotEmpty() }
        if (values.isNotEmpty() && values.all { it in ids || uuid.matches(it) }) "" else match.value
    }
    return uuid.replace(clean, "").replace(Regex("[ \\t]+([，。；：！？])"), "$1").trim()
}

fun readableAnalysisReport(person: AnalysisPerson, report: AnalysisReport): String =
    readableAnalysisText(report.markdown, person.answers.keys + person.materials.map { it.id } +
        person.events.map { it.id } + report.claims.flatMap { it.sources } + report.claims.map { it.id } +
        person.reports.flatMap { listOf(it.id, "report:" + it.id) })
