package io.github.khorkovenko.jenkinsbuilds.jira

import com.intellij.util.io.HttpRequests
import io.github.khorkovenko.jenkinsbuilds.api.JenkinsUrls
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.net.ssl.SSLException

/** A failed Jira call with a message that is ready to be shown; [status] is the HTTP status or 0. */
class JiraException(message: String, val status: Int = 0, cause: Throwable? = null) : IOException(message, cause)

/**
 * The login to a Jira server. With a [username] it is Basic authentication (user name or e-mail with a password or an
 * API token); without one the secret is sent as a personal access token (`Bearer`), as Jira Server and Data Center
 * want it. [baseUrl] is already normalized.
 */
data class JiraConfig(val baseUrl: String, val username: String, val secret: String, val timeoutSeconds: Int) {
    fun authorization(): String? = when {
        secret.isEmpty() && username.isBlank() -> null
        username.isBlank() -> "Bearer $secret"
        else -> "Basic " + Base64.getEncoder().encodeToString("$username:$secret".toByteArray(StandardCharsets.UTF_8))
    }

    override fun toString(): String = "JiraConfig($baseUrl, $username)"
}

/**
 * The Jira REST client, on the HTTP stack of the IDE like the Jenkins one: the IDE proxy and its certificate dialog
 * apply. Every method blocks and must be called from a background thread.
 */
class JiraClient(val config: JiraConfig) {

    private class Response(val status: Int, val reason: String, val body: String) {
        val ok: Boolean get() = status in 200..299
    }

    private val base: String get() = config.baseUrl

    /** Whether the server is Jira Cloud (users have account ids); known after the first [myself]. */
    @Volatile private var cloud: Boolean? = null

    private fun send(method: String, url: String, body: String? = null): Response {
        if (base.isEmpty()) throw JiraException("The Jira server address is not set. Open Settings | Tools | JJ Tracker | Jira.")
        val builder = when (method) {
            "POST" -> HttpRequests.post(url, "application/json; charset=UTF-8")
            "PUT" -> HttpRequests.put(url, "application/json; charset=UTF-8")
            else -> HttpRequests.request(url)
        }
        val timeout = config.timeoutSeconds.coerceIn(1, 600) * 1000
        try {
            return builder.connectTimeout(timeout).readTimeout(timeout).accept("application/json").throwStatusCodeException(false)
                .tuner { connection ->
                    // The login only goes to the configured server, never to a host a redirect points to
                    if (JenkinsUrls.sameOrigin(base, connection.url.toString())) {
                        config.authorization()?.let { connection.setRequestProperty("Authorization", it) }
                        connection.setRequestProperty("X-Atlassian-Token", "no-check")
                    }
                }
                .connect { request ->
                    if (method != "GET") request.write((body ?: "").toByteArray(StandardCharsets.UTF_8))
                    val connection = request.connection as? HttpURLConnection ?: throw JiraException("Not an HTTP address: $url")
                    val status = connection.responseCode
                    val text = when {
                        status >= 400 -> connection.errorStream?.use { String(it.readNBytes(64 * 1024), StandardCharsets.UTF_8) } ?: ""
                        status == 204 || status in 300..399 -> ""
                        else -> request.readString(null)
                    }
                    Response(status, connection.responseMessage ?: "", text)
                }
        } catch (e: JiraException) {
            throw e
        } catch (e: IOException) {
            val host = try {
                java.net.URI(url).host ?: url
            } catch (_: Exception) {
                url
            }
            throw JiraException(
                when (e) {
                    is UnknownHostException -> "Unknown host $host. Check the address, the VPN and the proxy settings of the IDE."
                    is SocketTimeoutException -> "No answer from $host within ${config.timeoutSeconds} s."
                    is ConnectException -> "Cannot connect to $host: ${e.message}"
                    is SSLException -> "TLS error with $host: ${e.message}. If the certificate was rejected, accept it in Settings | Tools | Server Certificates."
                    else -> "${e.javaClass.simpleName}: ${e.message ?: "request failed"}"
                },
                0, e,
            )
        }
    }

    private fun failure(response: Response, what: String): JiraException {
        val hint = when (response.status) {
            401 -> " Wrong user name, password or token."
            403 -> " The login is not allowed to do this (or Jira asks for a CAPTCHA after failed logins: log in once in the browser)."
            404 -> " Not found: check the address and that the issue or project exists and is visible to you."
            else -> ""
        }
        val detail = JiraParser.error(response.body).let { if (it.isEmpty()) "" else " $it" }
        return JiraException("$what: HTTP ${response.status} ${response.reason}.$hint$detail".trim(), response.status)
    }

    private fun get(url: String, what: String): String {
        val response = send("GET", url)
        if (!response.ok) throw failure(response, what)
        return response.body
    }

    private fun <T> parsed(block: () -> T): T = try {
        block()
    } catch (e: JiraParser.ParseException) {
        throw JiraException(e.message ?: "Unexpected answer")
    }

    private fun isCloud(): Boolean = cloud ?: myself().cloud

    /** Who is logged in. Fails with the HTTP status and the server's text when the login is refused. */
    fun myself(): JiraUser = parsed { JiraParser.parseMyself(get(JiraUrls.myself(base), "Jira login failed")) }.also { cloud = it.cloud }

    /** A sentence for the Test Connection button. */
    fun testConnection(): String = "Connected to Jira as ${myself().name}."

    fun search(jql: String, max: Int = 100): List<JiraIssue> {
        var response = send("GET", JiraUrls.search(base, jql, max))
        // Jira Cloud has moved the search: the old address is gone there
        if (response.status == 404 || response.status == 410) response = send("GET", JiraUrls.search(base, jql, max, newEndpoint = true))
        if (!response.ok) throw failure(response, "Loading the issues failed")
        return parsed { JiraParser.parseIssues(response.body) }
    }

    fun issue(key: String): JiraIssueDetails = parsed { JiraParser.parseIssue(get(JiraUrls.issue(base, key), "Loading $key failed")) }

    fun addComment(key: String, text: String) {
        val response = send("POST", JiraUrls.comments(base, key), JiraParser.commentBody(text))
        if (!response.ok) throw failure(response, "Commenting $key failed")
    }

    fun assign(key: String, user: JiraUser) {
        val response = send("PUT", JiraUrls.assignee(base, key), JiraParser.assigneeBody(user))
        if (!response.ok) throw failure(response, "Assigning $key failed")
    }

    /** The users [key] can be assigned to whose name matches [query]; without a key, any users that match. */
    fun users(key: String?, query: String): List<JiraUser> {
        val cloud = isCloud()
        val url = if (key.isNullOrBlank()) JiraUrls.users(base, query, cloud) else JiraUrls.assignable(base, key, query, cloud)
        return parsed { JiraParser.parseUsers(get(url, "Searching users failed")) }
    }

    /** The users new issues of [project] can be assigned to whose name matches [query]. */
    fun projectUsers(project: String, query: String): List<JiraUser> {
        val url = JiraUrls.projectAssignable(base, project, query, isCloud())
        return parsed { JiraParser.parseUsers(get(url, "Searching users failed")) }
    }

    /**
     * Downloads the file of [attachment] into [target] with the saved login. The data goes to a `.part` file first,
     * so a failed download leaves no broken file under the real name.
     */
    fun download(attachment: JiraAttachment, target: java.io.File) {
        val url = JiraUrls.attachment(base, attachment)
        val part = java.io.File(target.parentFile, target.name + ".part")
        val timeout = config.timeoutSeconds.coerceIn(1, 600) * 1000
        try {
            HttpRequests.request(url).connectTimeout(timeout).readTimeout(timeout).accept("*/*")
                .tuner { connection ->
                    // Jira Cloud sends the browser on to a file server: the login stays with Jira itself
                    if (JenkinsUrls.sameOrigin(base, connection.url.toString())) {
                        config.authorization()?.let { connection.setRequestProperty("Authorization", it) }
                    }
                }
                .saveToFile(part.toPath(), null)
            java.nio.file.Files.move(part.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } catch (e: HttpRequests.HttpStatusException) {
            throw JiraException("Downloading ${attachment.filename} failed: HTTP ${e.statusCode}.", e.statusCode, e)
        } catch (e: IOException) {
            throw JiraException("Downloading ${attachment.filename} failed: ${e.message ?: e.javaClass.simpleName}", 0, e)
        } finally {
            if (part.exists()) part.delete()
        }
    }

    fun projects(): List<JiraProject> = parsed { JiraParser.parseProjects(get(JiraUrls.projects(base), "Loading the projects failed")) }

    fun issueTypes(project: String): List<String> =
        parsed { JiraParser.parseIssueTypes(get(JiraUrls.project(base, project), "Loading the issue types of $project failed")) }

    /** Creates an issue and returns its key; without an [assignee] Jira picks the default assignee of the project. */
    fun createIssue(project: String, type: String, summary: String, description: String, assignee: JiraUser? = null): String {
        val response = send("POST", JiraUrls.createIssue(base), JiraParser.createBody(project, type, summary, description, assignee))
        if (!response.ok) throw failure(response, "Creating the issue failed")
        return parsed { JiraParser.parseCreatedKey(response.body) }
    }
}
