package io.github.khorkovenko.jenkinsbuilds.ui

import io.github.khorkovenko.jenkinsbuilds.model.BuildDetails
import java.text.SimpleDateFormat
import java.util.Date

/** Texts shown in the tool window. Plain Kotlin, no IDE classes, so the tests can call it. */
object Format {

    fun duration(millis: Long): String {
        if (millis <= 0) return ""
        val seconds = millis / 1000
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            h > 0 -> "$h h $m min"
            m > 0 -> "$m min $s s"
            seconds > 0 -> "$s s"
            else -> "$millis ms"
        }
    }

    fun dateTime(timestamp: Long): String =
        if (timestamp <= 0) "" else SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date(timestamp))

    fun size(bytes: Long): String = when {
        bytes < 0 -> ""
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> String.format("%.1f KiB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format("%.1f MiB", bytes / (1024.0 * 1024))
        else -> String.format("%.2f GiB", bytes / (1024.0 * 1024 * 1024))
    }

    /** `Running`, `Success`, `Failure`, `Unstable`, `Aborted`, `Not built`. */
    fun result(result: String?, building: Boolean): String = when {
        building -> "Running"
        result.isNullOrBlank() -> "Unknown"
        else -> result.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    fun escape(text: String?): String = (text ?: "")
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun row(name: String, valueHtml: String): String =
        "<tr><td valign='top'><b>${escape(name)}</b>&nbsp;&nbsp;</td><td>$valueHtml</td></tr>"

    /** The Summary tab of a build as HTML: status, timings, causes, parameters, Git, tests and changes. */
    fun summaryHtml(jobFullName: String, build: BuildDetails, now: Long = System.currentTimeMillis()): String {
        val sb = StringBuilder("<html><body>")
        sb.append("<h3>").append(escape(build.fullDisplayName ?: "$jobFullName ${build.title}")).append("</h3>")
        build.description?.takeIf { it.isNotBlank() }?.let { sb.append("<p>").append(escape(it)).append("</p>") }

        sb.append("<table>")
        sb.append(row("Result", escape(result(build.result, build.building))))
        sb.append(row("Build", "#${build.number}" + if (build.title != "#${build.number}") " (${escape(build.title)})" else ""))
        sb.append(row("Started", escape(dateTime(build.timestamp))))
        if (build.building) {
            val running = (now - build.timestamp).coerceAtLeast(0)
            val estimate = if (build.estimatedDuration > 0) ", usually takes ${duration(build.estimatedDuration)}" else ""
            sb.append(row("Running for", escape(duration(running) + estimate)))
        } else {
            sb.append(row("Duration", escape(duration(build.duration))))
        }
        build.builtOn?.let { sb.append(row("Node", escape(it))) }
        if (build.causes.isNotEmpty()) sb.append(row("Causes", build.causes.joinToString("<br>") { escape(it) }))
        for (git in build.git) {
            if (git.remoteUrls.isNotEmpty()) sb.append(row("Repository", git.remoteUrls.joinToString("<br>") { escape(it) }))
            git.sha1?.let { sb.append(row("Revision", "<code>${escape(it)}</code>")) }
            if (git.branches.isNotEmpty()) sb.append(row("Branch", git.branches.joinToString("<br>") { escape(it) }))
        }
        build.tests?.let { t ->
            val passed = (t.total - t.failed - t.skipped).coerceAtLeast(0)
            sb.append(row("Tests", "${t.total} total, $passed passed, ${t.failed} failed, ${t.skipped} skipped"))
        }
        if (build.culprits.isNotEmpty()) sb.append(row("Culprits", escape(build.culprits.joinToString(", "))))
        sb.append("</table>")

        if (build.parameters.isNotEmpty()) {
            sb.append("<h4>Parameters</h4><table>")
            for ((name, value) in build.parameters) sb.append(row(name, escape(value)))
            sb.append("</table>")
        }

        sb.append("<h4>Changes</h4>")
        if (build.changes.isEmpty()) {
            sb.append("<p>No changes since the last build.</p>")
        } else {
            sb.append("<table>")
            for (change in build.changes) {
                val id = change.commitId?.take(10) ?: ""
                val who = listOfNotNull(change.author, dateTime(change.timestamp).takeIf { it.isNotEmpty() }).joinToString(", ")
                val files = if (change.paths.isEmpty()) "" else " (${change.paths.size} file" + (if (change.paths.size == 1) ")" else "s)")
                sb.append("<tr><td valign='top'><code>").append(escape(id)).append("</code>&nbsp;&nbsp;</td><td>")
                    .append(escape(change.message).replace("\n", "<br>"))
                    .append("<br><font color='gray'>").append(escape(who + files)).append("</font></td></tr>")
            }
            sb.append("</table>")
        }
        return sb.append("</body></html>").toString()
    }
}
