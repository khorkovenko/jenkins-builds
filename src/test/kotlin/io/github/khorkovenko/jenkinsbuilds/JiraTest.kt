package io.github.khorkovenko.jenkinsbuilds

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.khorkovenko.jenkinsbuilds.auto.AutoBuildJira
import io.github.khorkovenko.jenkinsbuilds.auto.AutoBuildRule
import io.github.khorkovenko.jenkinsbuilds.jira.JiraClient
import io.github.khorkovenko.jenkinsbuilds.jira.JiraConfig
import io.github.khorkovenko.jenkinsbuilds.jira.JiraException
import io.github.khorkovenko.jenkinsbuilds.jira.JiraIssue
import io.github.khorkovenko.jenkinsbuilds.jira.JiraKeys
import io.github.khorkovenko.jenkinsbuilds.jira.JiraParser
import io.github.khorkovenko.jenkinsbuilds.jira.JiraUrls
import io.github.khorkovenko.jenkinsbuilds.jira.JiraUser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.Base64
import java.util.Collections

/** Jira: the JSON, the addresses, and the client against a small imitation of Jira on localhost. The login is made up. */
class JiraTest {

    private val search = """{"total":2,"issues":[
        {"key":"CP-372","fields":{"summary":"Fix the login form","status":{"name":"In Progress"},"assignee":{"name":"khorkovenko","displayName":"Kostiantyn H"},
          "issuetype":{"name":"Bug"},"priority":{"name":"Major"},"updated":"2026-11-18T12:34:56.000+0200"}},
        {"key":"CP-371","fields":{"summary":"Date picker","status":{"name":"Open"},"assignee":null,"issuetype":{"name":"Task"},"priority":null,"updated":"2026-11-17T09:00:00.000+0200"}}]}"""

    private val issue = """{"key":"CP-372","fields":{"summary":"Fix the login form","status":{"name":"In Progress"},
        "assignee":{"accountId":"5b10ac","displayName":"Kostiantyn H"},"reporter":{"displayName":"Team Lead"},"issuetype":{"name":"Bug"},
        "priority":{"name":"Major"},"created":"2026-11-10T08:00:00.000+0200","updated":"2026-11-18T12:34:56.000+0200",
        "description":"Steps:\n1. open the page",
        "comment":{"comments":[
          {"author":{"displayName":"Team Lead"},"created":"2026-11-11T10:00:00.000+0200","body":"Please look"},
          {"author":{"name":"tester"},"created":"2026-11-12T11:30:00.000+0200",
           "body":{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"Still "},{"type":"text","text":"broken"}]},{"type":"paragraph","content":[{"type":"text","text":"on stage"}]}]}}]}}}"""

    @Test
    fun issuesOfASearch() {
        val issues = JiraParser.parseIssues(search)
        assertEquals(listOf("CP-372", "CP-371"), issues.map { it.key })
        assertEquals(JiraIssue("CP-372", "Fix the login form", "In Progress", "Kostiantyn H", "Bug", "Major", "2026-11-18 12:34"), issues[0])
        assertNull(issues[1].assignee)
        assertEquals("", issues[1].priority)
        assertTrue(JiraParser.parseIssues("""{"issues":[]}""").isEmpty())
    }

    @Test
    fun oneIssueWithDescriptionAndComments() {
        val details = JiraParser.parseIssue(issue)
        assertEquals("CP-372", details.issue.key)
        assertEquals("Steps:\n1. open the page", details.description)
        assertEquals("Team Lead", details.reporter)
        assertEquals("2026-11-10 08:00", details.created)
        assertEquals(listOf("Team Lead", "tester"), details.comments.map { it.author })
        assertEquals("Please look", details.comments[0].body)
        // A comment in the document format of the newer API is read as its text
        assertEquals("Still broken\non stage", details.comments[1].body)
    }

    @Test
    fun usersOfCloudAndServer() {
        val server = JiraParser.parseUsers("""[{"name":"ivanov","key":"ivanov","displayName":"Ivan Ivanov"},{"name":"ivanov","displayName":"Again"}]""")
        assertEquals(listOf(JiraUser("ivanov", "Ivan Ivanov", false)), server)
        val cloud = JiraParser.parseUsers("""[{"accountId":"5b10ac","displayName":"Ivan Ivanov"}]""")
        assertEquals(JiraUser("5b10ac", "Ivan Ivanov", true), cloud.single())
        assertEquals("""{"name":"ivanov"}""", JiraParser.assigneeBody(server.single()))
        assertEquals("""{"accountId":"5b10ac"}""", JiraParser.assigneeBody(cloud.single()))
        assertTrue(JiraParser.parseMyself("""{"accountId":"x","displayName":"Me"}""").cloud)
        assertFalse(JiraParser.parseMyself("""{"name":"me","displayName":"Me"}""").cloud)
    }

    @Test
    fun projectsTypesBodiesAndErrors() {
        assertEquals(listOf("ABC  Alpha", "CP  Customer Portal"), JiraParser.parseProjects("""[{"key":"CP","name":"Customer Portal"},{"key":"ABC","name":"Alpha"}]""").map { it.toString() })
        assertEquals(listOf("Bug", "Task"), JiraParser.parseIssueTypes("""{"key":"CP","issueTypes":[{"name":"Bug","subtask":false},{"name":"Sub-task","subtask":true},{"name":"Task"}]}"""))
        assertEquals("CP-400", JiraParser.parseCreatedKey("""{"id":"1","key":"CP-400"}"""))
        assertEquals("""{"body":"Deployed \"ok\"\nhttps://x"}""", JiraParser.commentBody("Deployed \"ok\"\nhttps://x"))
        assertEquals(
            """{"fields":{"project":{"key":"CP"},"issuetype":{"name":"Bug"},"summary":"S","description":"D"}}""",
            JiraParser.createBody("CP", "Bug", "S", "D"),
        )
        assertFalse(JiraParser.createBody("CP", "Bug", "S", " ").contains("description"))
        assertEquals("Issue does not exist; summary: is required", JiraParser.error("""{"errorMessages":["Issue does not exist"],"errors":{"summary":"is required"}}"""))
        assertEquals("Unauthorized (401)", JiraParser.error("<html><body><h1>Unauthorized (401)</h1></body></html>"))
    }

    @Test
    fun theFilterKeepsIssuesThatHaveEveryWord() {
        val issues = JiraParser.parseIssues(search)
        assertEquals(listOf("CP-372", "CP-371"), JiraParser.filter(issues, "  ").map { it.key })
        assertEquals(listOf("CP-372"), JiraParser.filter(issues, "login").map { it.key })
        assertEquals(listOf("CP-372"), JiraParser.filter(issues, "KOSTIANTYN bug").map { it.key })
        assertEquals(listOf("CP-371"), JiraParser.filter(issues, "cp-371").map { it.key })
        assertEquals(listOf("CP-371"), JiraParser.filter(issues, "open task").map { it.key })
        assertTrue(JiraParser.filter(issues, "login task").isEmpty())
    }

    @Test
    fun issueKeysInBranchNames() {
        assertEquals("CP-372", JiraKeys.first("cp/2.2/CP-372"))
        assertEquals("ABC-12", JiraKeys.first("origin/feature/ABC-12-login-form"))
        assertEquals("A1B-7", JiraKeys.first("fix_A1B-7"))
        assertNull(JiraKeys.first("cp/2.2/dev"))
        assertNull(JiraKeys.first("release-2"))
        assertNull(JiraKeys.first(null))
        assertTrue(JiraKeys.isKey(" CP-372 "))
        assertFalse(JiraKeys.isKey("cp-372"))
        assertFalse(JiraKeys.isKey("CP-372 and more"))
    }

    @Test
    fun addresses() {
        val base = "https://jira.example.test"
        assertEquals(base, JiraUrls.normalizeBase("jira.example.test/"))
        assertTrue(JiraUrls.search(base, "assignee = currentUser()", 100).startsWith("$base/rest/api/2/search?jql=assignee+%3D+currentUser%28%29&fields="))
        assertTrue(JiraUrls.search(base, "x", 100, newEndpoint = true).startsWith("$base/rest/api/2/search/jql?jql=x"))
        assertTrue(JiraUrls.search(base, "x", 100000).endsWith("maxResults=500"))
        assertTrue(JiraUrls.issue(base, "CP-372").startsWith("$base/rest/api/2/issue/CP-372?fields="))
        assertEquals("$base/rest/api/2/issue/CP-372/comment", JiraUrls.comments(base, "CP-372"))
        assertEquals("$base/rest/api/2/issue/CP-372/assignee", JiraUrls.assignee(base, "CP-372"))
        assertEquals("$base/rest/api/2/user/assignable/search?issueKey=CP-372&username=iv&maxResults=50", JiraUrls.assignable(base, "CP-372", "iv", false))
        assertEquals("$base/rest/api/2/user/assignable/search?issueKey=CP-372&query=iv&maxResults=50", JiraUrls.assignable(base, "CP-372", "iv", true))
        assertEquals("$base/browse/CP-372", JiraUrls.browse(base, "CP-372"))
    }

    @Test
    fun theJiraStepOfAnAutoBuildRule() {
        val rule = AutoBuildRule()
        rule.branch = "cp/2.2/dev"
        rule.job = "customersportal-custom"
        rule.params = mutableListOf("Branch_Selector=origin/cp/2.2/CP-371")
        assertEquals("", AutoBuildJira.describe(rule))
        // No key in the pushed branch: the branch the job builds is looked at
        assertEquals("CP-371", AutoBuildJira.issueKey(rule))
        rule.branch = "cp/2.2/CP-372"
        assertEquals("CP-372", AutoBuildJira.issueKey(rule))
        rule.jiraIssue = "ABC-1"
        assertEquals("ABC-1", AutoBuildJira.issueKey(rule))

        rule.jiraEnabled = true
        assertEquals("ABC-1: comment the build link", AutoBuildJira.describe(rule))
        assertNull(AutoBuildJira.assignee(rule))
        rule.jiraAssigneeId = "tester"
        rule.jiraAssigneeName = "Test Engineer"
        rule.jiraComment = "  Deployed to stage  "
        assertEquals(JiraUser("tester", "Test Engineer", false), AutoBuildJira.assignee(rule))
        assertEquals("ABC-1: assign to Test Engineer, comment \"Deployed to stage\"", AutoBuildJira.describe(rule))
        assertEquals("Deployed to stage\nhttps://j/job/x/7/", AutoBuildJira.comment(rule, "https://j/job/x/7/"))
        rule.jiraComment = ""
        assertEquals("https://j/job/x/7/", AutoBuildJira.comment(rule, "https://j/job/x/7/"))
        rule.jiraIssue = ""
        assertEquals("CP-372 (from the branch): assign to Test Engineer, comment the build link", AutoBuildJira.describe(rule))
        // A copy of a rule keeps its Jira step
        rule.copy().let {
            assertTrue(it.jiraEnabled)
            assertEquals("tester", it.jiraAssigneeId)
            assertEquals("Test Engineer", it.jiraAssigneeName)
        }
    }

    // ---- the client against an imitation of Jira --------------------------------------------------------------------

    private val user = "tester"
    private val token = "made-up-jira-token"
    private lateinit var server: HttpServer
    private lateinit var base: String
    private val requests: MutableList<String> = Collections.synchronizedList(ArrayList())
    private val bodies: MutableList<String> = Collections.synchronizedList(ArrayList())
    @Volatile private var cloud = false
    @Volatile private var expectedAuth = "Basic " + Base64.getEncoder().encodeToString("$user:$token".toByteArray())

    private fun HttpExchange.reply(status: Int, body: String = "") {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", "application/json")
        if (bytes.isEmpty()) sendResponseHeaders(status, -1) else {
            sendResponseHeaders(status, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
        }
        close()
    }

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val path = ex.requestURI.rawPath
            val query = ex.requestURI.rawQuery
            requests += "${ex.requestMethod} $path" + (if (query == null) "" else "?$query")
            val body = String(ex.requestBody.readAllBytes())
            if (ex.requestMethod != "GET") bodies += body
            when {
                ex.requestHeaders.getFirst("Authorization") != expectedAuth ->
                    ex.reply(401, """{"errorMessages":["You are not authenticated."],"errors":{}}""")
                path == "/rest/api/2/myself" -> ex.reply(200, if (cloud) """{"accountId":"me-id","displayName":"Me"}""" else """{"name":"tester","displayName":"Me"}""")
                path == "/rest/api/2/search" -> if (cloud) ex.reply(410, """{"errorMessages":["The requested API has been removed."]}""") else ex.reply(200, search)
                path == "/rest/api/2/search/jql" -> ex.reply(200, search)
                path == "/rest/api/2/issue/CP-372" && ex.requestMethod == "GET" -> ex.reply(200, issue)
                path == "/rest/api/2/issue/CP-372/comment" -> ex.reply(201, """{"id":"1"}""")
                path == "/rest/api/2/issue/CP-372/assignee" -> ex.reply(204)
                path == "/rest/api/2/user/assignable/search" ->
                    ex.reply(200, if (cloud) """[{"accountId":"5b10ac","displayName":"Ivan Ivanov"}]""" else """[{"name":"ivanov","displayName":"Ivan Ivanov"}]""")
                path == "/rest/api/2/project" -> ex.reply(200, """[{"key":"CP","name":"Customer Portal"}]""")
                path == "/rest/api/2/project/CP" -> ex.reply(200, """{"key":"CP","issueTypes":[{"name":"Bug"},{"name":"Task"}]}""")
                path == "/rest/api/2/issue" && ex.requestMethod == "POST" ->
                    if (body.contains("\"summary\":\"\"")) ex.reply(400, """{"errorMessages":[],"errors":{"summary":"You must specify a summary of the issue."}}""")
                    else ex.reply(201, """{"id":"10","key":"CP-400"}""")
                else -> ex.reply(404, """{"errorMessages":["Issue Does Not Exist"],"errors":{}}""")
            }
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun stop() {
        server.stop(0)
    }

    private fun client(secret: String = token, username: String = user) = JiraClient(JiraConfig(base, username, secret, 10))

    @Test
    fun loginAndSearch() {
        val c = client()
        assertEquals("Connected to Jira as Me.", c.testConnection())
        assertEquals(listOf("CP-372", "CP-371"), c.search(io.github.khorkovenko.jenkinsbuilds.jira.JiraQuery.DEFAULT).map { it.key })
        assertTrue(requests.last().startsWith("GET /rest/api/2/search?jql="))
        // The login goes with the first request
        assertEquals(2, requests.size)
    }

    @Test
    fun aRefusedLoginSaysWhy() {
        try {
            client(secret = "wrong").myself()
            fail("a wrong token must not log in")
        } catch (e: JiraException) {
            assertEquals(401, e.status)
            assertTrue(e.message, e.message!!.contains("HTTP 401"))
            assertTrue(e.message, e.message!!.contains("You are not authenticated."))
            assertFalse("the secret is never part of a message", e.message!!.contains("wrong"))
        }
    }

    @Test
    fun aPersonalAccessTokenIsSentAsBearer() {
        expectedAuth = "Bearer $token"
        assertEquals("Me", client(username = "").myself().name)
        assertFalse(JiraConfig(base, "u", "s3cr3t-value", 10).toString().contains("s3cr3t-value"))
    }

    @Test
    fun jiraCloudUsesTheNewSearchAndAccountIds() {
        cloud = true
        val c = client()
        assertEquals(2, c.search("x").size)
        assertTrue(requests.any { it.startsWith("GET /rest/api/2/search?") })
        assertTrue(requests.last().startsWith("GET /rest/api/2/search/jql?"))

        val users = c.users("CP-372", "iv")
        assertTrue(requests.last().contains("query=iv"))
        c.assign("CP-372", users.single())
        assertEquals("""{"accountId":"5b10ac"}""", bodies.last())
        assertEquals("PUT /rest/api/2/issue/CP-372/assignee", requests.last())
    }

    @Test
    fun commentAssignAndReadOnAServer() {
        val c = client()
        assertEquals("Fix the login form", c.issue("CP-372").issue.summary)
        c.addComment("CP-372", "Built\nhttps://jenkins/job/x/7/")
        assertEquals("POST /rest/api/2/issue/CP-372/comment", requests.last())
        assertEquals("""{"body":"Built\nhttps://jenkins/job/x/7/"}""", bodies.last())
        val ivan = c.users("CP-372", "iv").single()
        assertTrue(requests.last().contains("username=iv"))
        c.assign("CP-372", ivan)
        assertEquals("""{"name":"ivanov"}""", bodies.last())
        try {
            c.issue("CP-999")
            fail("an unknown issue must fail")
        } catch (e: JiraException) {
            assertEquals(404, e.status)
            assertTrue(e.message, e.message!!.contains("Issue Does Not Exist"))
        }
    }

    @Test
    fun aNewIssue() {
        val c = client()
        assertEquals("CP", c.projects().single().key)
        assertEquals(listOf("Bug", "Task"), c.issueTypes("CP"))
        assertEquals("CP-400", c.createIssue("CP", "Bug", "Broken", "Details"))
        try {
            c.createIssue("CP", "Bug", "", "")
            fail("an empty summary is refused")
        } catch (e: JiraException) {
            assertEquals(400, e.status)
            assertTrue(e.message, e.message!!.contains("summary: You must specify a summary"))
        }
    }
}
