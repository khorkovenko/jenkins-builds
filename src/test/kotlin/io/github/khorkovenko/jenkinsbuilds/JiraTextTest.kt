package io.github.khorkovenko.jenkinsbuilds

import com.sun.net.httpserver.HttpServer
import io.github.khorkovenko.jenkinsbuilds.jira.JiraAttachment
import io.github.khorkovenko.jenkinsbuilds.jira.JiraClient
import io.github.khorkovenko.jenkinsbuilds.jira.JiraComment
import io.github.khorkovenko.jenkinsbuilds.jira.JiraConfig
import io.github.khorkovenko.jenkinsbuilds.jira.JiraException
import io.github.khorkovenko.jenkinsbuilds.jira.JiraHtml
import io.github.khorkovenko.jenkinsbuilds.jira.JiraIssue
import io.github.khorkovenko.jenkinsbuilds.jira.JiraIssueDetails
import io.github.khorkovenko.jenkinsbuilds.jira.JiraParser
import io.github.khorkovenko.jenkinsbuilds.jira.JiraQuery
import io.github.khorkovenko.jenkinsbuilds.jira.JiraUrls
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Base64

/** The one query field of the Jira tab, the drawing of an issue with its comments, and attachments. */
class JiraTextTest {

    private val shot = JiraAttachment("55", "shot 1.png", "image/png", 204800)
    private val clip = JiraAttachment("56", "clip.mp4", "video/mp4", 5 * 1024 * 1024)
    private val log = JiraAttachment("57", "server.log", "text/plain", 12)

    @Test
    fun theFieldTakesJqlWordsOrAKey() {
        assertEquals(JiraQuery.DEFAULT, JiraQuery.toJql("   "))
        // A query goes to the server as it is
        assertEquals("assignee = khorkovenko", JiraQuery.toJql(" assignee = khorkovenko "))
        assertEquals("project = CP ORDER BY updated DESC", JiraQuery.toJql("project = CP ORDER BY updated DESC"))
        assertEquals("summary ~ login", JiraQuery.toJql("summary ~ login"))
        assertEquals("assignee is EMPTY", JiraQuery.toJql("assignee is EMPTY"))
        assertEquals("status in (Open, Reopened)", JiraQuery.toJql("status in (Open, Reopened)"))
        assertEquals("watcher = currentUser()", JiraQuery.toJql("watcher = currentUser()"))
        // Plain words become a text search, a key a search for that issue
        assertEquals("text ~ \"login form\" ORDER BY updated DESC", JiraQuery.toJql("login form"))
        assertEquals("text ~ \"the page is broken\" ORDER BY updated DESC", JiraQuery.toJql("the page is broken"))
        assertEquals("text ~ \"say \\\"hi\\\"\" ORDER BY updated DESC", JiraQuery.toJql("say \"hi\""))
        assertEquals("key = CP-372", JiraQuery.toJql("cp-372"))

        assertTrue(JiraQuery.isJql("assignee = khorkovenko"))
        assertFalse(JiraQuery.isJql("login form"))
        assertFalse(JiraQuery.isJql("this is in the order"))
    }

    @Test
    fun attachmentsOfAnIssue() {
        val details = JiraParser.parseIssue(
            """{"key":"CP-1","fields":{"summary":"S","attachment":[
               {"id":"55","filename":"shot 1.png","mimeType":"image/png","size":204800,"content":"http://internal/secure/attachment/55/shot+1.png"},
               {"id":"56","filename":"clip.mp4","mimeType":"video/mp4","size":5242880},
               {"filename":"no id.txt"}]}}""",
        )
        assertEquals(listOf(shot, clip), details.attachments)
        assertEquals(listOf("image", "video", "file"), listOf(shot, clip, log).map { it.kind })
        // The address is built from the entered one, not from what the server calls itself
        assertEquals("https://jira.example.test/secure/attachment/55/shot%201.png", JiraUrls.attachment("https://jira.example.test", shot))
        assertTrue(JiraUrls.issue("https://j", "CP-1").endsWith(",comment,attachment"))
    }

    @Test
    fun picturesVideosAndAddressesBecomeLinks() {
        val files = listOf(shot, clip, log)
        // Jira's markup for an embedded picture or video, with and without options
        assertEquals(
            "See <a href=\"jira-attachment:55\">[image] shot 1.png</a> and <a href=\"jira-attachment:56\">[video] clip.mp4</a>",
            JiraHtml.rich("See !shot 1.png|thumbnail! and !clip.mp4!", files),
        )
        // Its markup for a link to a file
        assertEquals("Log: <a href=\"jira-attachment:57\">[file] server.log</a>", JiraHtml.rich("Log: [^server.log]", files))
        // Something between exclamation marks that is no attachment stays as it was written
        assertEquals("Wow!really!", JiraHtml.rich("Wow!really!", files))
        assertEquals("[^gone.txt]", JiraHtml.rich("[^gone.txt]", files))
        // Web addresses, bare and named
        assertEquals(
            "Build <a href=\"https://jenkins/job/x/7/\">https://jenkins/job/x/7/</a>.",
            JiraHtml.rich("Build https://jenkins/job/x/7/.", files),
        )
        assertEquals("<a href=\"https://example.test/a\">the page</a>", JiraHtml.rich("[the page|https://example.test/a]", files))
        // Text is escaped and line breaks are kept
        assertEquals("a &lt;b&gt; &amp; c<br>d", JiraHtml.rich("a <b> & c\nd", files))
    }

    @Test
    fun everyCommentIsABlockOfItsOwn() {
        val details = JiraIssueDetails(
            JiraIssue("CP-1", "S", "Open", null, "Bug", "", ""), "Steps\n!shot 1.png!", "Lead", "",
            listOf(JiraComment("Team Lead", "2026-11-11 10:00", "Please <look>"), JiraComment("", "2026-11-12 11:30", "Video: [^clip.mp4]")),
            listOf(shot, clip),
        )
        val html = JiraHtml.issue(details, "#aabbcc", "#112233")
        assertTrue(html.contains("<h3>Description</h3>"))
        assertTrue(html.contains("<h3>Attachments (2)</h3>"))
        assertTrue(html.contains("<a href=\"jira-attachment:56\">[video] clip.mp4</a><font color=\"#112233\"> (5.0 MB)</font>"))
        assertTrue(html.contains("<h3>Comments (2)</h3>"))
        // One table per comment, each with its own title bar: author, time and number
        assertEquals(2, Regex("<table ").findAll(html).count())
        assertTrue(html.contains("<td bgcolor=\"#aabbcc\"><b>Team Lead</b>&nbsp;&nbsp;<font color=\"#112233\">2026-11-11 10:00&nbsp;&nbsp;#1</font></td>"))
        assertTrue(html.contains("<b>Unknown</b>"))
        assertTrue(html.contains("#2</font>"))
        assertTrue(html.contains("Please &lt;look&gt;"))
        // The description comes before the comments, the first comment before the second
        assertTrue(html.indexOf("Steps") < html.indexOf("Team Lead"))
        assertTrue(html.indexOf("#1<") < html.indexOf("#2<"))

        val empty = JiraHtml.issue(details.copy(description = " ", comments = emptyList(), attachments = emptyList()))
        assertTrue(empty.contains("No description."))
        assertTrue(empty.contains("No comments yet"))
        assertFalse(empty.contains("Attachments"))
    }

    @Test
    fun anAttachmentIsDownloadedWithTheLogin() {
        val expected = "Basic " + Base64.getEncoder().encodeToString("tester:made-up-jira-token".toByteArray())
        val bytes = ByteArray(3000) { (it % 200).toByte() }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val ok = ex.requestHeaders.getFirst("Authorization") == expected && ex.requestURI.rawPath == "/secure/attachment/55/shot%201.png"
            if (ok) {
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            } else {
                ex.sendResponseHeaders(404, -1)
            }
            ex.close()
        }
        server.start()
        val dir = Files.createTempDirectory("jenkins-builds-jira")
        try {
            val client = JiraClient(JiraConfig("http://127.0.0.1:${server.address.port}", "tester", "made-up-jira-token", 10))
            val target = dir.resolve("shot 1.png").toFile()
            client.download(shot, target)
            assertArrayEquals(bytes, target.readBytes())
            try {
                client.download(clip, dir.resolve("clip.mp4").toFile())
                fail("a missing attachment must fail")
            } catch (e: JiraException) {
                assertEquals(404, e.status)
            }
            // A failed download leaves neither a broken file nor its temporary part behind
            assertEquals(listOf("shot 1.png"), dir.toFile().list()!!.sorted())
        } finally {
            server.stop(0)
            dir.toFile().deleteRecursively()
        }
    }
}
