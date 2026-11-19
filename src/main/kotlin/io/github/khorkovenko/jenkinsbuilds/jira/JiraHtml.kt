package io.github.khorkovenko.jenkinsbuilds.jira

/**
 * Draws an issue for the issue window: the description, the attachments and the comments, each comment in a block of
 * its own under a coloured title bar. Pictures, videos and other files that a text names become links.
 * Plain Kotlin, so the tests can call it.
 */
object JiraHtml {

    /** The scheme of a link to an attachment: `jira-attachment:` and the id. A click downloads the file. */
    const val ATTACHMENT = "jira-attachment:"

    fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun size(bytes: Long): String = when {
        bytes < 0 -> ""
        bytes < 1024 -> " ($bytes B)"
        bytes < 1024L * 1024 -> " (%.0f KB)".format(bytes / 1024.0)
        else -> " (%.1f MB)".format(bytes / (1024.0 * 1024))
    }

    private fun link(attachment: JiraAttachment): String =
        "<a href=\"$ATTACHMENT${escape(attachment.id)}\">[${attachment.kind}] ${escape(attachment.filename)}</a>"

    // The texts below are searched after they were escaped, so the patterns never see a raw < or "
    private val EMBED = Regex("!([^!|\\n]+?)(\\|[^!\\n]*)?!")
    private val FILE_LINK = Regex("\\[\\^([^\\]\\n]+)]")
    private val NAMED_LINK = Regex("\\[([^\\]|\\n]+)\\|(https?://[^\\]\\s|]+)]")
    private val URL = Regex("(?<![\"=>])(https?://[^\\s<]+[^\\s<.,;:!?)\\]])")

    /**
     * A description or a comment as HTML. Jira's own markup for an embedded picture or video (`!shot.png|thumbnail!`)
     * and for a link to a file (`[^clip.mp4]`) becomes a link that downloads that attachment; web addresses become
     * links too; line breaks are kept.
     */
    fun rich(text: String, attachments: List<JiraAttachment>): String {
        val byName = attachments.associateBy { escape(it.filename) }
        var html = escape(text)
        html = EMBED.replace(html) { m -> byName[m.groupValues[1].trim()]?.let { link(it) } ?: m.value }
        html = FILE_LINK.replace(html) { m -> byName[m.groupValues[1].trim()]?.let { link(it) } ?: m.value }
        html = NAMED_LINK.replace(html) { m -> "<a href=\"${m.groupValues[2]}\">${m.groupValues[1]}</a>" }
        html = URL.replace(html) { m -> "<a href=\"${m.value}\">${m.value}</a>" }
        return html.replace("\r\n", "\n").replace("\n", "<br>")
    }

    /**
     * The whole issue. [bar] is the background of the title bar of a comment and [muted] the colour of dates, both
     * as `#rrggbb`, so the window can pass the colours of the IDE theme.
     */
    fun issue(details: JiraIssueDetails, bar: String = "#DDE6F3", muted: String = "#6C707E"): String {
        val sb = StringBuilder("<html><body>")
        sb.append("<h3>Description</h3>")
        sb.append("<div>").append(if (details.description.isBlank()) "<font color=\"$muted\">No description.</font>" else rich(details.description, details.attachments)).append("</div>")

        if (details.attachments.isNotEmpty()) {
            sb.append("<h3>Attachments (").append(details.attachments.size).append(")</h3>")
            for (attachment in details.attachments) {
                sb.append("<div>").append(link(attachment)).append("<font color=\"$muted\">").append(size(attachment.size)).append("</font></div>")
            }
            sb.append("<div><font color=\"$muted\">A click on a file saves it to your Downloads folder.</font></div>")
        }

        sb.append("<h3>").append(if (details.comments.isEmpty()) "No comments yet" else "Comments (${details.comments.size})").append("</h3>")
        for ((index, comment) in details.comments.withIndex()) {
            // One table per comment: a title bar with the author and the time, then the text, then a gap
            sb.append("<table width=\"100%\" cellspacing=\"0\" cellpadding=\"6\">")
            sb.append("<tr><td bgcolor=\"$bar\"><b>").append(escape(comment.author.ifBlank { "Unknown" })).append("</b>")
            sb.append("&nbsp;&nbsp;<font color=\"$muted\">").append(escape(comment.created)).append("&nbsp;&nbsp;#").append(index + 1).append("</font></td></tr>")
            sb.append("<tr><td>").append(rich(comment.body, details.attachments)).append("</td></tr>")
            sb.append("</table><br>")
        }
        return sb.append("</body></html>").toString()
    }
}
