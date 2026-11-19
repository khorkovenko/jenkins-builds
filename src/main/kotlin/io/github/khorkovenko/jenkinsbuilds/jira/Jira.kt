package io.github.khorkovenko.jenkinsbuilds.jira

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.khorkovenko.jenkinsbuilds.api.JenkinsParser
import io.github.khorkovenko.jenkinsbuilds.api.JenkinsUrls
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * A Jira user. Jira Cloud names a user by an account id, Jira Server and Data Center by a user name: [id] is whichever
 * this server uses and [cloud] says which it is.
 */
data class JiraUser(val id: String, val name: String, val cloud: Boolean) {
    override fun toString(): String = name
}

data class JiraIssue(
    val key: String,
    val summary: String,
    val status: String,
    val assignee: String?,
    val type: String,
    val priority: String,
    val updated: String,
)

data class JiraComment(val author: String, val created: String, val body: String)

/** A file attached to an issue: a screenshot, a video, a log. */
data class JiraAttachment(val id: String, val filename: String, val mimeType: String, val size: Long) {
    /** `image`, `video` or `file`: what the link to it is labelled with. */
    val kind: String
        get() = when {
            mimeType.startsWith("image/") -> "image"
            mimeType.startsWith("video/") -> "video"
            else -> "file"
        }
}

data class JiraIssueDetails(
    val issue: JiraIssue,
    val description: String,
    val reporter: String?,
    val created: String,
    val comments: List<JiraComment>,
    val attachments: List<JiraAttachment> = emptyList(),
)

/**
 * What the one field of the Jira tab means. It takes JQL as the search page of Jira does (`assignee = khorkovenko`),
 * and also plain words or an issue key for people who do not want to write a query.
 */
object JiraQuery {
    /** The list shown while the field is empty. */
    const val DEFAULT = "assignee = currentUser() AND resolution = Unresolved ORDER BY updated DESC"

    private val OPERATOR = Regex("[=~<>]|\\border\\s+by\\b|\\b(is|in|was)\\s+(not\\s+)?(empty|null|\\()|\\w+\\(\\)", RegexOption.IGNORE_CASE)

    /** True when [text] is a query; false for plain words such as `login form`. */
    fun isJql(text: String): Boolean = OPERATOR.containsMatchIn(text)

    /** The query sent to the server for [text]: itself when it is JQL, a key or a text search otherwise. */
    fun toJql(text: String): String {
        val trimmed = text.trim()
        return when {
            trimmed.isEmpty() -> DEFAULT
            isJql(trimmed) -> trimmed
            JiraKeys.isKey(trimmed.uppercase()) -> "key = ${trimmed.uppercase()}"
            else -> "text ~ \"" + trimmed.replace("\\", "\\\\").replace("\"", "\\\"") + "\" ORDER BY updated DESC"
        }
    }
}

data class JiraProject(val key: String, val name: String) {
    override fun toString(): String = "$key  $name"
}

/** Addresses of the Jira REST API, version 2: it is the one both Jira Cloud and Jira Server understand. */
object JiraUrls {
    private const val FIELDS = "summary,status,assignee,issuetype,priority,updated"

    private fun enc(text: String): String = URLEncoder.encode(text, StandardCharsets.UTF_8)

    fun normalizeBase(raw: String): String = JenkinsUrls.normalizeBase(raw)

    fun myself(base: String): String = "$base/rest/api/2/myself"

    /** [newEndpoint]: Jira Cloud replaced `/search` by `/search/jql`; the old one answers 404 or 410 there. */
    fun search(base: String, jql: String, max: Int, newEndpoint: Boolean = false): String =
        "$base/rest/api/2/search" + (if (newEndpoint) "/jql" else "") + "?jql=${enc(jql)}&fields=$FIELDS&maxResults=${max.coerceIn(1, 500)}"

    fun issue(base: String, key: String): String =
        "$base/rest/api/2/issue/${enc(key)}?fields=$FIELDS,description,reporter,created,comment,attachment"

    /** Where the file of an attachment is downloaded from. */
    fun attachment(base: String, attachment: JiraAttachment): String =
        "$base/secure/attachment/${enc(attachment.id)}/" + enc(attachment.filename).replace("+", "%20")

    fun comments(base: String, key: String): String = "$base/rest/api/2/issue/${enc(key)}/comment"

    fun assignee(base: String, key: String): String = "$base/rest/api/2/issue/${enc(key)}/assignee"

    /** Users an issue can be assigned to. Jira Cloud searches by `query`, Jira Server by `username`. */
    fun assignable(base: String, key: String, query: String, cloud: Boolean): String =
        "$base/rest/api/2/user/assignable/search?issueKey=${enc(key)}&${if (cloud) "query" else "username"}=${enc(query)}&maxResults=50"

    fun users(base: String, query: String, cloud: Boolean): String =
        "$base/rest/api/2/user/search?${if (cloud) "query" else "username"}=${enc(query.ifEmpty { if (cloud) "" else "." })}&maxResults=50"

    fun projects(base: String): String = "$base/rest/api/2/project"

    fun project(base: String, key: String): String = "$base/rest/api/2/project/${enc(key)}"

    fun createIssue(base: String): String = "$base/rest/api/2/issue"

    /** The page of an issue in the browser. */
    fun browse(base: String, key: String): String = "$base/browse/${enc(key)}"
}

/** Finds issue keys such as `CP-372` in branch names and other text. */
object JiraKeys {
    private val KEY = Regex("(?<![A-Za-z0-9])[A-Z][A-Z0-9]+-\\d+(?![A-Za-z0-9])")

    /** The first issue key in [text]: `cp/2.2/CP-372` and `origin/feature/ABC-12-login` give `CP-372` and `ABC-12`. */
    fun first(text: String?): String? = text?.let { KEY.find(it)?.value }

    fun isKey(text: String): Boolean = KEY.matches(text.trim())
}

/** Turns the answers of the Jira REST API into the model and makes the bodies it is sent. No network, so it is unit-tested. */
object JiraParser {

    class ParseException(message: String) : RuntimeException(message)

    private fun parse(json: String): JsonElement = try {
        JsonParser.parseString(json)
    } catch (_: Exception) {
        throw ParseException("The server did not answer with JSON: " + JenkinsParser.htmlToText(json, 200))
    }

    private fun obj(json: String): JsonObject =
        parse(json).takeIf { it.isJsonObject }?.asJsonObject ?: throw ParseException("Unexpected answer: " + JenkinsParser.htmlToText(json, 200))

    private fun JsonObject.el(name: String): JsonElement? = get(name)?.takeUnless { it.isJsonNull }
    private fun JsonObject.str(name: String): String? = el(name)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.child(name: String): JsonObject? = el(name)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.list(name: String): List<JsonObject> =
        el(name)?.takeIf { it.isJsonArray }?.asJsonArray?.filter { it.isJsonObject }?.map { it.asJsonObject } ?: emptyList()

    /**
     * The text of a description or a comment. Version 2 of the API sends plain text (with wiki markup); a server that
     * answers with the document format of version 3 gets its text nodes joined.
     */
    private fun text(element: JsonElement?): String = when {
        element == null || element.isJsonNull -> ""
        element.isJsonPrimitive -> element.asString
        element.isJsonObject -> {
            val o = element.asJsonObject
            val own = o.str("text") ?: ""
            val inner = o.el("content")?.takeIf { it.isJsonArray }?.asJsonArray?.joinToString("") { text(it) } ?: ""
            val breakAfter = o.str("type") in setOf("paragraph", "heading", "listItem", "hardBreak", "codeBlock")
            own + inner + if (breakAfter) "\n" else ""
        }
        element.isJsonArray -> element.asJsonArray.joinToString("") { text(it) }
        else -> ""
    }

    /** `2026-11-18T12:34:56.000+0200` as `2026-11-18 12:34`. */
    fun time(raw: String?): String = raw?.take(16)?.replace('T', ' ') ?: ""

    fun user(o: JsonObject?): JiraUser? {
        if (o == null) return null
        val account = o.str("accountId")
        val id = account ?: o.str("name") ?: o.str("key") ?: return null
        return JiraUser(id, o.str("displayName") ?: o.str("name") ?: id, account != null)
    }

    fun parseMyself(json: String): JiraUser = user(obj(json)) ?: throw ParseException("The server did not say who is logged in")

    private fun issue(o: JsonObject): JiraIssue? {
        val key = o.str("key") ?: return null
        val f = o.child("fields") ?: JsonObject()
        return JiraIssue(
            key = key,
            summary = f.str("summary") ?: "",
            status = f.child("status")?.str("name") ?: "",
            assignee = f.child("assignee")?.let { it.str("displayName") ?: it.str("name") },
            type = f.child("issuetype")?.str("name") ?: "",
            priority = f.child("priority")?.str("name") ?: "",
            updated = time(f.str("updated")),
        )
    }

    fun parseIssues(json: String): List<JiraIssue> = obj(json).list("issues").mapNotNull { issue(it) }

    fun parseIssue(json: String): JiraIssueDetails {
        val o = obj(json)
        val issue = issue(o) ?: throw ParseException("The answer is not an issue")
        val f = o.child("fields") ?: JsonObject()
        val comments = f.child("comment")?.list("comments")?.map { c ->
            JiraComment(
                author = c.child("author")?.let { it.str("displayName") ?: it.str("name") } ?: "",
                created = time(c.str("created")),
                body = text(c.el("body")).trim(),
            )
        } ?: emptyList()
        return JiraIssueDetails(
            issue = issue,
            description = text(f.el("description")).trim(),
            reporter = f.child("reporter")?.let { it.str("displayName") ?: it.str("name") },
            created = time(f.str("created")),
            comments = comments,
            attachments = f.list("attachment").mapNotNull { a ->
                JiraAttachment(
                    id = a.str("id") ?: return@mapNotNull null,
                    filename = a.str("filename") ?: return@mapNotNull null,
                    mimeType = a.str("mimeType") ?: "",
                    size = a.el("size")?.takeIf { it.isJsonPrimitive }?.asLong ?: -1,
                )
            },
        )
    }

    fun parseUsers(json: String): List<JiraUser> {
        val element = parse(json)
        val array: JsonArray = when {
            element.isJsonArray -> element.asJsonArray
            element.isJsonObject -> element.asJsonObject.el("users")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
            else -> JsonArray()
        }
        return array.filter { it.isJsonObject }.mapNotNull { user(it.asJsonObject) }.distinctBy { it.id }
    }

    fun parseProjects(json: String): List<JiraProject> {
        val element = parse(json)
        if (!element.isJsonArray) return emptyList()
        return element.asJsonArray.filter { it.isJsonObject }.mapNotNull { e ->
            val o = e.asJsonObject
            JiraProject(o.str("key") ?: return@mapNotNull null, o.str("name") ?: "")
        }.sortedBy { it.key }
    }

    /** The issue types a project offers for a new issue, sub-task types left out. */
    fun parseIssueTypes(json: String): List<String> =
        obj(json).list("issueTypes").filter { it.el("subtask")?.asBoolean != true }.mapNotNull { it.str("name") }

    fun parseCreatedKey(json: String): String = obj(json).str("key") ?: throw ParseException("The server did not name the new issue")

    /** The message of a Jira error answer: `{"errorMessages":[...],"errors":{field: text}}`. */
    fun error(body: String): String {
        val o = try {
            JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject
        } catch (_: Exception) {
            null
        } ?: return JenkinsParser.htmlToText(body, 300)
        val messages = o.el("errorMessages")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf { m -> m.isJsonPrimitive }?.asString } ?: emptyList()
        val fields = o.child("errors")?.entrySet()?.map { "${it.key}: ${if (it.value.isJsonPrimitive) it.value.asString else it.value}" } ?: emptyList()
        return (messages + fields).joinToString("; ").ifEmpty { o.str("message") ?: JenkinsParser.htmlToText(body, 300) }
    }

    // ---- bodies ------------------------------------------------------------------------------------------------------

    fun commentBody(text: String): String = JsonObject().also { it.addProperty("body", text) }.toString()

    /** Jira Cloud wants `accountId`, Jira Server `name`. */
    fun assigneeBody(user: JiraUser): String =
        JsonObject().also { it.addProperty(if (user.cloud) "accountId" else "name", user.id) }.toString()

    fun createBody(project: String, type: String, summary: String, description: String): String {
        val fields = JsonObject()
        fields.add("project", JsonObject().also { it.addProperty("key", project) })
        fields.add("issuetype", JsonObject().also { it.addProperty("name", type) })
        fields.addProperty("summary", summary)
        if (description.isNotBlank()) fields.addProperty("description", description)
        return JsonObject().also { it.add("fields", fields) }.toString()
    }

    /**
     * The issues of [issues] that have every word of [filter] in their key, summary, status, assignee, type or
     * priority, in the order they came. An empty filter keeps all.
     */
    fun filter(issues: List<JiraIssue>, filter: String): List<JiraIssue> {
        val words = filter.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return issues
        return issues.filter { issue ->
            val text = listOf(issue.key, issue.summary, issue.status, issue.assignee ?: "", issue.type, issue.priority).joinToString(" ").lowercase()
            words.all { it in text }
        }
    }
}
