package io.github.khorkovenko.jenkinsbuilds.ui

/** How the console log is coloured and searched. Plain Kotlin, so the tests can call it. */
object ConsoleText {

    enum class Kind { NORMAL, ERROR, WARNING, SUCCESS, STEP }

    private val SUCCESS = Regex("Finished: SUCCESS|BUILD SUCCESSFUL|BUILD SUCCESS\\b")
    private val ERROR = Regex(
        "\\b(ERROR|FAILURE|FAILED|FATAL|SEVERE)\\b|\\berror:|Exception\\b|^\\s+at [\\w.$<>]+\\(|^Caused by: |" +
            "marked build as failure|did not succeed",
    )
    private val WARNING = Regex("\\b(WARNING|WARN|UNSTABLE|ABORTED)\\b|\\bwarning:|^Skipped\\b|\\bdeprecated\\b", RegexOption.IGNORE_CASE)
    private val LOG_LEVEL = Regex("^\\[(INFO|DEBUG|TRACE)] ?", RegexOption.IGNORE_CASE)
    private val STEP = Regex("^(\\[Pipeline]|> Task |\\+ |\\$ |> git |Started by |Rebuilds build |Building |Checking out |\\[[\\w .-]+] )")

    /** The colour class of one line of the log. */
    fun kind(line: String): Kind = when {
        line.isBlank() -> Kind.NORMAL
        SUCCESS.containsMatchIn(line) -> Kind.SUCCESS
        ERROR.containsMatchIn(line) -> Kind.ERROR
        WARNING.containsMatchIn(line) -> Kind.WARNING
        // The level prefix of Maven and other loggers is on nearly every line: it is no build step
        LOG_LEVEL.containsMatchIn(line) -> Kind.NORMAL
        STEP.containsMatchIn(line) -> Kind.STEP
        else -> Kind.NORMAL
    }

    /**
     * Cuts [text] into runs of whole lines that share a colour, in order. Each piece keeps its line breaks, so the
     * pieces put together give [text] again.
     */
    fun runs(text: String): List<Pair<Kind, String>> {
        val result = ArrayList<Pair<Kind, String>>()
        var runStart = 0
        var runKind: Kind? = null
        var lineStart = 0
        while (lineStart < text.length) {
            val newline = text.indexOf('\n', lineStart)
            val lineEnd = if (newline < 0) text.length else newline + 1
            val kind = kind(text.substring(lineStart, lineEnd).trimEnd('\n', '\r'))
            if (runKind != null && kind != runKind) {
                result += runKind to text.substring(runStart, lineStart)
                runStart = lineStart
            }
            runKind = kind
            lineStart = lineEnd
        }
        if (runKind != null && runStart < text.length) result += runKind to text.substring(runStart)
        return result
    }

    /** The offsets where [query] occurs in [text], ignoring case; at most [limit] of them. */
    fun matches(text: CharSequence, query: String, limit: Int = 5000): List<Int> {
        if (query.isEmpty()) return emptyList()
        val haystack = text.toString().lowercase()
        val needle = query.lowercase()
        val result = ArrayList<Int>()
        var from = 0
        while (result.size < limit) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) break
            result += at
            from = at + needle.length
        }
        return result
    }
}
