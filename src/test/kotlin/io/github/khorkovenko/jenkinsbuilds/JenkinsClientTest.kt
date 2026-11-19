package io.github.khorkovenko.jenkinsbuilds

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.khorkovenko.jenkinsbuilds.api.ConnectionConfig
import io.github.khorkovenko.jenkinsbuilds.api.JenkinsClient
import io.github.khorkovenko.jenkinsbuilds.api.JenkinsException
import io.github.khorkovenko.jenkinsbuilds.model.ParamDef
import io.github.khorkovenko.jenkinsbuilds.model.ParamKind
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Base64
import java.util.Collections

/**
 * Runs the client against a small imitation of Jenkins on localhost: Basic authentication, a crumb that is bound to a
 * session cookie and can expire, redirects, and the answers the real server gives. The login is made up for the test.
 */
class JenkinsClientTest {

    private val user = "tester"
    private val token = "made-up-test-token"
    private val expectedAuth = "Basic " + Base64.getEncoder().encodeToString("$user:$token".toByteArray())
    private val git = "net.uaznia.lukanus.hudson.plugins.gitparameter.GitParameterDefinition"

    private lateinit var server: HttpServer
    private lateinit var other: HttpServer
    private lateinit var base: String

    /** `METHOD path?query` of every request the imitation got. */
    private val requests: MutableList<String> = Collections.synchronizedList(ArrayList())
    private val bodies: MutableList<String> = Collections.synchronizedList(ArrayList())
    private val foreignAuth: MutableList<String?> = Collections.synchronizedList(ArrayList())

    @Volatile private var crumbsIssued = 0
    @Volatile private var csrf = true
    @Volatile private var rejectNextPost = false

    private fun HttpExchange.reply(status: Int, body: String = "", type: String = "application/json", headers: Map<String, String> = emptyMap()) {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", type)
        for ((k, v) in headers) responseHeaders.add(k, v)
        if (bytes.isEmpty()) {
            sendResponseHeaders(status, -1)
        } else {
            sendResponseHeaders(status, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
        }
        close()
    }

    @Before
    fun start() {
        other = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        other.createContext("/") { ex ->
            foreignAuth += ex.requestHeaders.getFirst("Authorization")
            ex.reply(200, "{}")
        }
        other.start()

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex -> handle(ex) }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun stop() {
        server.stop(0)
        other.stop(0)
    }

    private fun handle(ex: HttpExchange) {
        val path = ex.requestURI.rawPath
        val query = ex.requestURI.rawQuery
        val method = ex.requestMethod
        requests += "$method $path" + (if (query == null) "" else "?$query")
        val body = String(ex.requestBody.readAllBytes())

        if (ex.requestHeaders.getFirst("Authorization") != expectedAuth) {
            ex.reply(401, "<html><head><title>Error 401</title></head><body><h2>HTTP ERROR 401 Unauthorized</h2></body></html>", "text/html", mapOf("X-Jenkins" to "2.462.1"))
            return
        }
        if (method == "POST") {
            bodies += body
            if (csrf) {
                val crumbOk = ex.requestHeaders.getFirst("Jenkins-Crumb") == "crumb-$crumbsIssued" && crumbsIssued > 0
                val cookieOk = ex.requestHeaders.getFirst("Cookie")?.contains("JSESSIONID.test=session-$crumbsIssued") == true
                if (rejectNextPost || !crumbOk || !cookieOk) {
                    rejectNextPost = false
                    ex.reply(403, "<html><body><h2>HTTP ERROR 403 No valid crumb was included in the request</h2></body></html>", "text/html")
                    return
                }
            }
        }
        when {
            path == "/whoAmI/api/json" -> ex.reply(200, """{"anonymous":false,"authenticated":true,"name":"$user"}""", headers = mapOf("X-Jenkins" to "2.462.1"))
            path == "/crumbIssuer/api/json" -> {
                if (!csrf) {
                    ex.reply(404, "<html><body>Not Found</body></html>", "text/html")
                } else {
                    crumbsIssued++
                    ex.reply(
                        200, """{"crumb":"crumb-$crumbsIssued","crumbRequestField":"Jenkins-Crumb"}""",
                        headers = mapOf("Set-Cookie" to "JSESSIONID.test=session-$crumbsIssued; Path=/; HttpOnly"),
                    )
                }
            }
            path == "/api/json" -> ex.reply(200, """{"primaryView":{"name":"all"},"jobs":[{"name":"x","url":"http://internal:8080/job/x/","color":"blue"}],"views":[]}""")
            path == "/job/x/api/json" -> ex.reply(200, """{"name":"x","buildable":true,"builds":[{"number":7,"building":true,"timestamp":1}]}""")
            path == "/job/broken/api/json" -> ex.reply(500, "<html><body><h1>Oops!</h1><p>A problem occurred while processing the request.</p></body></html>", "text/html")
            path == "/job/x/buildWithParameters" || path == "/job/x/build" ->
                ex.reply(201, headers = mapOf("Location" to "http://internal:8080/queue/item/912/"))
            path == "/job/x/7/stop" -> ex.reply(302, headers = mapOf("Location" to "$base/job/x/7/"))
            path == "/queue/cancelItem" -> ex.reply(204)
            path == "/queue/item/912/api/json" -> ex.reply(200, """{"id":912,"why":null,"cancelled":false,"executable":{"number":8}}""")
            path == "/queue/item/1/api/json" -> ex.reply(404, "<html><body>Not Found</body></html>", "text/html")
            path == "/job/x/7/logText/progressiveText" -> {
                val start = query?.substringAfter("start=")?.toLong() ?: 0
                if (start == 0L) {
                    ex.reply(200, "first line\n", "text/plain;charset=utf-8", mapOf("X-Text-Size" to "11", "X-More-Data" to "true"))
                } else {
                    ex.reply(200, "Finished: SUCCESS\n", "text/plain;charset=utf-8", mapOf("X-Text-Size" to "29"))
                }
            }
            path == "/job/x/7/artifact/build/app.war" -> {
                val bytes = ByteArray(2048) { (it % 251).toByte() }
                ex.responseHeaders.add("Content-Type", "application/octet-stream")
                if (method == "HEAD") {
                    ex.responseHeaders.add("Content-Length", bytes.size.toString())
                    ex.sendResponseHeaders(200, -1)
                } else {
                    ex.sendResponseHeaders(200, bytes.size.toLong())
                    ex.responseBody.use { it.write(bytes) }
                }
                ex.close()
            }
            path == "/job/x/descriptorByName/$git/fillValueItems" -> {
                if (method == "POST") {
                    ex.reply(200, """{"values":[{"name":"origin/master","value":"origin/master"},{"name":"origin/cp/2.2/dev","value":"origin/cp/2.2/dev"}]}""")
                } else {
                    ex.reply(405, "<html><body>POST required</body></html>", "text/html")
                }
            }
            path == "/job/moved/api/json" -> ex.reply(302, headers = mapOf("Location" to "http://127.0.0.1:${other.address.port}/elsewhere"))
            else -> ex.reply(404, "<html><body><h2>HTTP ERROR 404 Not Found</h2></body></html>", "text/html")
        }
    }

    private fun client(secret: String = token, url: String = base) = JenkinsClient(ConnectionConfig(url, user, secret, 10))

    @Test
    fun testConnectionReportsUserAndVersion() {
        val message = client().testConnection()
        assertTrue(message, message.contains("Jenkins 2.462.1"))
        assertTrue(message, message.contains("as tester"))
        assertTrue(message, message.contains("HTTP 200"))
        // The login goes with the first request: there is exactly one, and it was accepted
        assertEquals(1, requests.size)
    }

    @Test
    fun wrongPasswordIsReportedWithStatusAndText() {
        try {
            client(secret = "wrong").testConnection()
            fail("a wrong password must not connect")
        } catch (e: JenkinsException) {
            assertEquals(401, e.status)
            assertTrue(e.message, e.message!!.contains("HTTP 401 Unauthorized"))
            assertTrue(e.message, e.message!!.contains("Wrong user name, password or API token"))
            assertFalse(e.message, e.message!!.contains("<h2>"))
            assertFalse("the secret is never part of a message", e.message!!.contains("wrong"))
        }
    }

    @Test
    fun aWrongAddressIsReported() {
        try {
            client(url = "$base/no-such-prefix").testConnection()
            fail("not a Jenkins address")
        } catch (e: JenkinsException) {
            assertEquals(404, e.status)
            assertTrue(e.message, e.message!!.contains("HTTP 404"))
        }
        try {
            JenkinsClient(ConnectionConfig("http://127.0.0.1:1", user, token, 2)).testConnection()
            fail("nothing listens there")
        } catch (e: JenkinsException) {
            assertEquals(0, e.status)
            assertTrue(e.message, e.message!!.contains("127.0.0.1"))
        }
    }

    @Test
    fun jobsAndBuildsAreLoaded() {
        val c = client()
        assertEquals(listOf("x"), c.loadTree().jobs.map { it.fullName })
        val job = c.loadJob("x", 30)
        assertTrue(job.builds.single().building)
        assertTrue(requests.any { it.startsWith("GET /job/x/api/json?tree=") })
    }

    @Test
    fun serverErrorKeepsItsText() {
        try {
            client().loadJob("broken", 30)
            fail("HTTP 500 must fail")
        } catch (e: JenkinsException) {
            assertEquals(500, e.status)
            assertTrue(e.message, e.message!!.contains("HTTP 500"))
            assertTrue(e.message, e.message!!.contains("A problem occurred while processing the request."))
        }
    }

    @Test
    fun parameterizedBuildSendsCrumbSessionAndForm() {
        val c = client()
        val id = c.triggerBuild("x", listOf("Branch_Selector" to "origin/cp/2.2/dev", "SKIP_TESTS" to "true"))
        assertEquals(912L, id)
        assertEquals("Branch_Selector=origin%2Fcp%2F2.2%2Fdev&SKIP_TESTS=true", bodies.single())
        assertEquals(listOf("GET /crumbIssuer/api/json", "POST /job/x/buildWithParameters"), requests.toList())

        // The crumb is reused for the next POST
        assertEquals(912L, c.triggerBuild("x", null))
        assertEquals("POST /job/x/build", requests.last())
        assertEquals(1, crumbsIssued)
        assertEquals(8, c.queueItem(912)!!.executableNumber)
        assertNull(c.queueItem(1))
    }

    @Test
    fun expiredCrumbIsFetchedAgain() {
        val c = client()
        c.triggerBuild("x", null)
        rejectNextPost = true
        assertEquals(912L, c.triggerBuild("x", null))
        assertEquals(2, crumbsIssued)
        assertEquals(
            listOf("GET /crumbIssuer/api/json", "POST /job/x/build", "POST /job/x/build", "GET /crumbIssuer/api/json", "POST /job/x/build"),
            requests.toList(),
        )
    }

    @Test
    fun serverWithoutCsrfProtection() {
        csrf = false
        val c = client()
        assertEquals(912L, c.triggerBuild("x", null))
        c.triggerBuild("x", null)
        assertEquals(1, requests.count { it == "GET /crumbIssuer/api/json" })
    }

    @Test
    fun stopAndCancelDoNotFollowTheRedirect() {
        val c = client()
        c.stopBuild("x", 7)
        c.cancelQueueItem(912)
        assertEquals(listOf("GET /crumbIssuer/api/json", "POST /job/x/7/stop", "POST /queue/cancelItem?id=912"), requests.toList())
    }

    @Test
    fun credentialsAreNotSentToAnotherHost() {
        try {
            client().loadJob("moved", 30)
        } catch (_: JenkinsException) {
            // the other host answers with something that is not a job; only the header matters here
        }
        assertEquals(1, foreignAuth.size)
        assertNull("the Authorization header must stay on the configured server", foreignAuth[0])
    }

    @Test
    fun consoleIsReadInPieces() {
        val c = client()
        val first = c.console("x", 7, 0)
        assertEquals("first line\n", first.text)
        assertEquals(11L, first.nextStart)
        assertTrue(first.more)
        val second = c.console("x", 7, first.nextStart)
        assertEquals("Finished: SUCCESS\n", second.text)
        assertEquals(29L, second.nextStart)
        assertFalse(second.more)
        assertEquals("GET /job/x/7/logText/progressiveText?start=11", requests.last())
    }

    @Test
    fun artifactSizeAndDownload() {
        val c = client()
        val url = "$base/job/x/7/artifact/build/app.war"
        assertEquals(2048L, c.contentLength(url))
        assertNull(c.contentLength("$base/job/x/7/artifact/missing"))
        val dir = Files.createTempDirectory("jenkins-builds-test")
        try {
            val target = dir.resolve("app.war").toFile()
            c.download(url, target, null)
            assertArrayEquals(ByteArray(2048) { (it % 251).toByte() }, target.readBytes())
            try {
                c.download("$base/job/x/7/artifact/missing", dir.resolve("missing").toFile(), null)
                fail("a missing artifact must fail")
            } catch (e: JenkinsException) {
                assertEquals(404, e.status)
            }
            // A failed download leaves neither a broken file nor its temporary part behind
            assertEquals(listOf("app.war"), dir.toFile().list()!!.sorted())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun branchValuesAreFetchedWithPost() {
        val definition = ParamDef("Branch_Selector", ParamKind.LIST, null, null, emptyList(), git)
        assertEquals(listOf("origin/master", "origin/cp/2.2/dev"), client().parameterValues("x", definition))
        assertEquals("POST /job/x/descriptorByName/$git/fillValueItems?param=Branch_Selector", requests.last())
    }
}
