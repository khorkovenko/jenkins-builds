package io.github.khorkovenko.jenkinsbuilds.api

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.util.io.HttpRequests
import io.github.khorkovenko.jenkinsbuilds.model.BuildDetails
import io.github.khorkovenko.jenkinsbuilds.model.ConsoleChunk
import io.github.khorkovenko.jenkinsbuilds.model.Crumb
import io.github.khorkovenko.jenkinsbuilds.model.JobDetails
import io.github.khorkovenko.jenkinsbuilds.model.ParamDef
import io.github.khorkovenko.jenkinsbuilds.model.QueueItem
import io.github.khorkovenko.jenkinsbuilds.model.ServerTree
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URLConnection
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLException

/** A failed call with a message that is ready to be shown; [status] is the HTTP status or 0 when there was none. */
class JenkinsException(message: String, val status: Int = 0, cause: Throwable? = null) : IOException(message, cause)

/** What is needed to talk to one server. [baseUrl] is already normalized, see [JenkinsUrls.normalizeBase]. */
data class ConnectionConfig(val baseUrl: String, val username: String, val secret: String, val timeoutSeconds: Int) {
    val timeoutMillis: Int get() = timeoutSeconds.coerceIn(1, 600) * 1000

    /** `Basic ...` for the `Authorization` header, or null when no user name is set (anonymous access). */
    fun authorization(): String? {
        if (username.isBlank()) return null
        val raw = "$username:$secret".toByteArray(StandardCharsets.UTF_8)
        return "Basic " + Base64.getEncoder().encodeToString(raw)
    }

    override fun toString(): String = "ConnectionConfig($baseUrl, $username)"
}

/**
 * The Jenkins REST client. It goes through the IDE HTTP stack ([HttpRequests]), so the IDE proxy settings apply and an
 * untrusted certificate brings up the IDE dialog that lets the user accept it (Settings | Tools | Server Certificates).
 *
 * Every method blocks and must be called from a background thread.
 */
class JenkinsClient(val config: ConnectionConfig) {

    class Response(val status: Int, val reason: String, val body: String, private val headers: Map<String?, List<String>>) {
        fun header(name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

        val ok: Boolean get() = status in 200..299
    }

    private val base: String get() = config.baseUrl

    /** Session cookies of the server. With password login a crumb is only valid together with its session. */
    private val cookies = ConcurrentHashMap<String, String>()

    @Volatile private var crumb: Crumb? = null
    @Volatile private var crumbKnown = false

    // ---- transport --------------------------------------------------------------------------------------------------

    private fun tune(connection: URLConnection, headers: Map<String, String>) {
        // Credentials only go to the configured server, never to a host a redirect points to
        if (!JenkinsUrls.sameOrigin(base, connection.url.toString())) return
        config.authorization()?.let { connection.setRequestProperty("Authorization", it) }
        if (cookies.isNotEmpty()) {
            connection.setRequestProperty("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
        }
        for ((name, value) in headers) connection.setRequestProperty(name, value)
    }

    private fun remember(connection: HttpURLConnection) {
        if (!JenkinsUrls.sameOrigin(base, connection.url.toString())) return
        val fields = try {
            connection.headerFields
        } catch (_: Exception) {
            return
        }
        for ((name, values) in fields) {
            if (!"Set-Cookie".equals(name, ignoreCase = true)) continue
            for (value in values) {
                val pair = value.substringBefore(';')
                val key = pair.substringBefore('=', "").trim()
                if (key.isNotEmpty()) cookies[key] = pair.substringAfter('=').trim()
            }
        }
    }

    private fun readError(connection: HttpURLConnection): String = try {
        connection.errorStream?.use { String(it.readNBytes(64 * 1024), StandardCharsets.UTF_8) } ?: ""
    } catch (_: IOException) {
        ""
    }

    /**
     * Sends one request and returns the answer whatever its status is. [body] makes it a form POST, [head] a HEAD.
     * Network failures become a [JenkinsException] that says what went wrong.
     */
    private fun send(
        url: String,
        body: ByteArray? = null,
        head: Boolean = false,
        accept: String = "application/json",
        headers: Map<String, String> = emptyMap(),
    ): Response {
        if (base.isEmpty()) throw JenkinsException("The Jenkins server address is not set. Open Settings | Tools | JJ Tracker.")
        val builder = when {
            body != null -> HttpRequests.post(url, "application/x-www-form-urlencoded; charset=UTF-8").followRedirects(false)
            head -> HttpRequests.head(url)
            else -> HttpRequests.request(url)
        }
        try {
            return builder
                .connectTimeout(config.timeoutMillis)
                .readTimeout(config.timeoutMillis)
                .accept(accept)
                .throwStatusCodeException(false)
                .tuner { tune(it, headers) }
                .connect { request ->
                    if (body != null) request.write(body)
                    val connection = request.connection as? HttpURLConnection
                        ?: throw JenkinsException("Not an HTTP address: $url")
                    val status = connection.responseCode
                    val reason = connection.responseMessage ?: ""
                    remember(connection)
                    val text = when {
                        head -> ""
                        status >= 400 -> readError(connection)
                        status in 300..399 -> ""
                        else -> request.readString(null)
                    }
                    Response(status, reason, text, connection.headerFields ?: emptyMap())
                }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: JenkinsException) {
            throw e
        } catch (e: IOException) {
            throw describe(e, url)
        }
    }

    private fun describe(e: IOException, url: String): JenkinsException {
        val host = try {
            java.net.URI(url).host ?: url
        } catch (_: Exception) {
            url
        }
        val message = when (e) {
            is HttpRequests.HttpStatusException -> "HTTP ${e.statusCode}: ${e.message}"
            is UnknownHostException -> "Unknown host $host. Check the address, the VPN and the proxy settings of the IDE."
            is SocketTimeoutException -> "No answer from $host within ${config.timeoutSeconds} s (${e.message})."
            is ConnectException -> "Cannot connect to $host: ${e.message}"
            is SSLException -> "TLS error with $host: ${e.message}. If the certificate was rejected, accept it in " +
                "Settings | Tools | Server Certificates and try again."
            else -> "${e.javaClass.simpleName}: ${e.message ?: "request failed"}"
        }
        val status = (e as? HttpRequests.HttpStatusException)?.statusCode ?: 0
        return JenkinsException(message, status, e)
    }

    private fun failure(response: Response, what: String): JenkinsException {
        val hint = when (response.status) {
            401 -> " Wrong user name, password or API token."
            403 -> " The user has no permission for this, or the CSRF crumb was rejected."
            404 -> " Not found: check the server address (it must be the Jenkins root, including a prefix such as /jenkins)."
            else -> ""
        }
        val text = JenkinsParser.htmlToText(response.body, 300)
        val detail = if (text.isEmpty()) "" else " $text"
        return JenkinsException("$what: HTTP ${response.status} ${response.reason}.$hint$detail".trim(), response.status)
    }

    private fun getJson(url: String, what: String): String {
        val response = send(url)
        if (!response.ok) throw failure(response, what)
        return response.body
    }

    // ---- CSRF crumb -------------------------------------------------------------------------------------------------

    /** The crumb to send with a POST, or null when the server has CSRF protection switched off. */
    private fun crumb(refresh: Boolean): Crumb? {
        if (crumbKnown && !refresh) return crumb
        synchronized(this) {
            if (crumbKnown && !refresh) return crumb
            val response = send(JenkinsUrls.crumbUrl(base))
            when {
                response.ok -> {
                    crumb = try {
                        JenkinsParser.parseCrumb(response.body)
                    } catch (_: JenkinsParser.ParseException) {
                        null
                    }
                    crumbKnown = true
                }
                response.status == 404 -> {
                    crumb = null
                    crumbKnown = true
                }
                else -> {
                    // Not allowed to read the crumb now: try the POST without one and ask again next time
                    crumb = null
                    crumbKnown = false
                }
            }
            return crumb
        }
    }

    /**
     * A form POST with the crumb. A 403 answer usually means an expired crumb, so it is fetched again and the request
     * is repeated once. A redirect is a normal answer to a Jenkins POST and counts as success.
     */
    fun post(url: String, form: List<Pair<String, String>>, what: String, tolerate: Set<Int> = emptySet()): Response {
        val body = JenkinsUrls.formEncode(form).toByteArray(StandardCharsets.UTF_8)
        fun attempt(refresh: Boolean): Response {
            val c = crumb(refresh)
            return send(url, body = body, headers = if (c == null) emptyMap() else mapOf(c.field to c.value))
        }
        var response = attempt(false)
        if (response.status == 403) response = attempt(true)
        if (response.status >= 400 && response.status !in tolerate) throw failure(response, what)
        return response
    }

    // ---- API --------------------------------------------------------------------------------------------------------

    /** Checks address and login. Returns a sentence for the user; fails with the HTTP status and the server's text. */
    fun testConnection(): String {
        val response = send(JenkinsUrls.whoAmIUrl(base))
        val version = response.header("X-Jenkins")
        if (!response.ok) {
            val notJenkins = if (version == null && response.header("X-Hudson") == null && response.status == 404) {
                " The address does not look like a Jenkins server."
            } else ""
            throw JenkinsException(failure(response, "Connection failed").message + notJenkins, response.status)
        }
        val who = try {
            JenkinsParser.parseWhoAmI(response.body)
        } catch (e: JenkinsParser.ParseException) {
            throw JenkinsException("HTTP ${response.status} ${response.reason}, but the answer is not from Jenkins. ${e.message}")
        }
        val server = "Jenkins" + (version?.let { " $it" } ?: "")
        val status = "HTTP ${response.status} ${response.reason}".trim()
        return when {
            who.anonymous && config.username.isNotBlank() ->
                throw JenkinsException("$status from $server, but the login was not accepted: the server treats the request as anonymous.")
            who.anonymous -> "Connected to $server anonymously ($status)."
            else -> "Connected to $server as ${who.name ?: config.username} ($status)."
        }
    }

    fun loadTree(): ServerTree = parse { JenkinsParser.parseServerTree(getJson(JenkinsUrls.serverTreeUrl(base), "Loading jobs failed")) }

    fun loadJob(fullName: String, limit: Int): JobDetails = parse {
        JenkinsParser.parseJobDetails(getJson(JenkinsUrls.jobDetailsUrl(base, fullName, limit), "Loading $fullName failed"), fullName)
    }

    fun loadBuild(fullName: String, number: Int): BuildDetails = parse {
        JenkinsParser.parseBuildDetails(getJson(JenkinsUrls.buildDetailsUrl(base, fullName, number), "Loading build #$number failed"))
    }

    private fun <T> parse(block: () -> T): T = try {
        block()
    } catch (e: JenkinsParser.ParseException) {
        throw JenkinsException(e.message ?: "Unexpected answer", 0, e)
    }

    /**
     * Starts a build: `buildWithParameters` when [parameters] is given, `build` otherwise.
     * Returns the id of the queue item, or null when the server did not say where it queued the build.
     */
    fun triggerBuild(fullName: String, parameters: List<Pair<String, String>>?): Long? {
        val url = JenkinsUrls.triggerUrl(base, fullName, parameters != null)
        val response = post(url, parameters ?: emptyList(), "Starting $fullName failed")
        return JenkinsUrls.queueIdFromLocation(response.header("Location"))
    }

    fun queueItem(id: Long): QueueItem? {
        val response = send(JenkinsUrls.queueItemUrl(base, id))
        if (response.status == 404) return null // Jenkins forgets finished queue items after a few minutes
        if (!response.ok) throw failure(response, "Reading queue item $id failed")
        return parse { JenkinsParser.parseQueueItem(response.body) }
    }

    fun cancelQueueItem(id: Long) {
        // Older servers answer 404 although the item was cancelled
        post(JenkinsUrls.cancelQueueUrl(base, id), emptyList(), "Cancelling queue item $id failed", tolerate = setOf(404))
    }

    fun stopBuild(fullName: String, number: Int) {
        post(JenkinsUrls.stopUrl(base, fullName, number), emptyList(), "Stopping build #$number failed")
    }

    /** The values the server offers for a list parameter such as Git Parameter. */
    fun parameterValues(fullName: String, definition: ParamDef): List<String> {
        val cls = definition.className ?: throw JenkinsException("The server did not tell the type of ${definition.name}")
        val url = JenkinsUrls.parameterValuesUrl(base, fullName, cls, definition.name)
        // Newer plugin versions want a POST, older ones only answer a GET
        var response = post(url, emptyList(), "Reading the values of ${definition.name} failed", tolerate = setOf(403, 404, 405))
        if (response.status in setOf(403, 404, 405)) response = send(url)
        if (!response.ok) throw failure(response, "Reading the values of ${definition.name} failed")
        return parse { JenkinsParser.parseFillValues(response.body) }
    }

    /** The console text from offset [start] on. */
    fun console(fullName: String, number: Int, start: Long): ConsoleChunk {
        val response = send(JenkinsUrls.consoleUrl(base, fullName, number, start), accept = "text/plain")
        if (!response.ok) throw failure(response, "Reading the console log failed")
        val size = response.header("X-Text-Size")?.toLongOrNull() ?: (start + response.body.toByteArray(StandardCharsets.UTF_8).size)
        val more = response.header("X-More-Data").equals("true", ignoreCase = true)
        return ConsoleChunk(JenkinsParser.cleanConsole(response.body), size, more)
    }

    /** The size of a file on the server from a HEAD request, or null when the server does not tell. */
    fun contentLength(url: String): Long? {
        val response = send(url, head = true, accept = "*/*")
        if (!response.ok) return null
        return response.header("Content-Length")?.toLongOrNull()
    }

    /**
     * Downloads [url] with the saved login into [target]. Cancellable through [indicator]. The data goes to a
     * `.part` file first, so a failed or cancelled download leaves no broken file under the real name.
     */
    fun download(url: String, target: File, indicator: ProgressIndicator?) {
        val part = File(target.parentFile, target.name + ".part")
        try {
            HttpRequests.request(url)
                .connectTimeout(config.timeoutMillis)
                .readTimeout(config.timeoutMillis)
                .accept("*/*")
                .tuner { tune(it, emptyMap()) }
                .saveToFile(part.toPath(), indicator)
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: IOException) {
            throw describe(e, url)
        } finally {
            if (part.exists()) part.delete()
        }
    }
}
