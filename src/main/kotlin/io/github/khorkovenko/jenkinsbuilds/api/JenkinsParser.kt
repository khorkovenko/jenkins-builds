package io.github.khorkovenko.jenkinsbuilds.api

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.khorkovenko.jenkinsbuilds.model.Artifact
import io.github.khorkovenko.jenkinsbuilds.model.BuildDetails
import io.github.khorkovenko.jenkinsbuilds.model.BuildRef
import io.github.khorkovenko.jenkinsbuilds.model.ChangeEntry
import io.github.khorkovenko.jenkinsbuilds.model.Crumb
import io.github.khorkovenko.jenkinsbuilds.model.GitInfo
import io.github.khorkovenko.jenkinsbuilds.model.JobDetails
import io.github.khorkovenko.jenkinsbuilds.model.JobNode
import io.github.khorkovenko.jenkinsbuilds.model.ParamDef
import io.github.khorkovenko.jenkinsbuilds.model.ParamKind
import io.github.khorkovenko.jenkinsbuilds.model.QueueItem
import io.github.khorkovenko.jenkinsbuilds.model.ServerTree
import io.github.khorkovenko.jenkinsbuilds.model.TestSummary
import io.github.khorkovenko.jenkinsbuilds.model.ViewNode
import io.github.khorkovenko.jenkinsbuilds.model.ViewSection
import io.github.khorkovenko.jenkinsbuilds.model.WhoAmI

/** Turns the answers of the Jenkins JSON API into the model. No network and no IDE classes, so it is unit-tested. */
object JenkinsParser {

    class ParseException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

    // ---- JSON helpers -----------------------------------------------------------------------------------------------

    private fun root(json: String): JsonObject {
        val element = try {
            JsonParser.parseString(json)
        } catch (e: Exception) {
            throw ParseException("The server did not answer with JSON: " + htmlToText(json, 200), e)
        }
        if (!element.isJsonObject) throw ParseException("Unexpected answer: " + htmlToText(json, 200))
        return element.asJsonObject
    }

    private fun JsonObject.el(name: String): JsonElement? = get(name)?.takeUnless { it.isJsonNull }

    private fun JsonObject.str(name: String): String? {
        val e = el(name) ?: return null
        return if (e.isJsonPrimitive) e.asString else e.toString()
    }

    private fun JsonObject.long(name: String): Long {
        val e = el(name) ?: return 0
        return try {
            if (e.isJsonPrimitive) e.asLong else 0
        } catch (_: NumberFormatException) {
            0
        }
    }

    private fun JsonObject.int(name: String): Int = long(name).toInt()

    private fun JsonObject.bool(name: String): Boolean {
        val e = el(name) ?: return false
        return e.isJsonPrimitive && e.asJsonPrimitive.let { if (it.isBoolean) it.asBoolean else it.asString == "true" }
    }

    private fun JsonObject.obj(name: String): JsonObject? = el(name)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.arr(name: String): JsonArray? = el(name)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonObject.objects(name: String): List<JsonObject> = arr(name).objects()

    private fun JsonArray?.objects(): List<JsonObject> =
        this?.filter { it.isJsonObject }?.map { it.asJsonObject } ?: emptyList()

    private fun JsonObject.strings(name: String): List<String> =
        arr(name)?.filter { it.isJsonPrimitive }?.map { it.asString } ?: emptyList()

    // ---- jobs and views ---------------------------------------------------------------------------------------------

    fun parseServerTree(json: String): ServerTree {
        val o = root(json)
        val jobs = o.objects("jobs").mapNotNull { parseJob(it, "") }
        val index = HashMap<String, JobNode>()
        fun collect(list: List<JobNode>) {
            for (job in list) {
                index[job.fullName] = job
                collect(job.children)
            }
        }
        collect(jobs)
        val views = o.objects("views").mapNotNull { parseView(it, index, 0) }
        return ServerTree(jobs, views, o.obj("primaryView")?.str("name"))
    }

    private fun parseJob(o: JsonObject, parentFullName: String): JobNode? {
        val name = o.str("name") ?: return null
        val fullName = o.str("fullName")?.takeIf { it.isNotBlank() }
            ?: JenkinsUrls.fullNameFromUrl(o.str("url"))?.takeIf { it.substringAfterLast('/') == name }
            ?: if (parentFullName.isEmpty()) name else "$parentFullName/$name"
        val cls = o.str("_class")
        val children = o.objects("jobs").mapNotNull { parseJob(it, fullName) }
        val container = o.arr("jobs") != null || (cls != null && isContainerClass(cls))
        return JobNode(
            name = name,
            fullName = fullName,
            displayName = o.str("displayName"),
            color = o.str("color"),
            jobClass = cls,
            lastBuild = o.obj("lastBuild")?.let { parseBuildRef(it) },
            children = children,
            container = container,
        )
    }

    private fun isContainerClass(cls: String): Boolean =
        cls.contains("Folder") || cls.contains("MultiBranch") || cls.contains("OrganizationFolder")

    /** A job listed by a view carries no children: take the full node from the job tree when it is known. */
    private fun resolve(o: JsonObject, index: Map<String, JobNode>): JobNode? {
        val job = parseJob(o, "") ?: return null
        return index[job.fullName] ?: job
    }

    private fun parseView(o: JsonObject, index: Map<String, JobNode>, depth: Int): ViewNode? {
        val name = o.str("name") ?: return null
        val jobs = o.objects("jobs").mapNotNull { resolve(it, index) }
        val sections = o.objects("sections").mapIndexedNotNull { i, s -> parseSection(s, i, jobs, index) }
        val views = if (depth < 3) o.objects("views").mapNotNull { parseView(it, index, depth + 1) } else emptyList()
        return ViewNode(name, jobs, sections, views)
    }

    /**
     * A section of a sectioned view. Depending on the plugin version a section lists its jobs itself, or only says
     * which names or which regular expression it includes; then the jobs are picked from the jobs of the view and of
     * the server.
     */
    private fun parseSection(o: JsonObject, position: Int, viewJobs: List<JobNode>, index: Map<String, JobNode>): ViewSection? {
        val name = o.str("name")?.takeIf { it.isNotBlank() } ?: "Section ${position + 1}"
        val listed = (o.objects("jobs") + o.objects("items")).mapNotNull { resolve(it, index) }
        if (listed.isNotEmpty()) return ViewSection(name, listed.distinctBy { it.fullName })

        val names = o.strings("jobNames").toSet()
        val regex = o.str("includeRegex")?.takeIf { it.isNotBlank() }?.let {
            try {
                Regex(it)
            } catch (_: Exception) {
                null
            }
        }
        if (names.isEmpty() && regex == null) return if (o.has("jobs") || o.has("items")) ViewSection(name, emptyList()) else null
        val pool = (viewJobs + index.values).distinctBy { it.fullName }
        val picked = pool.filter { it.fullName in names || it.name in names || (regex != null && regex.matches(it.name)) }
        return ViewSection(name, picked.sortedBy { it.fullName.lowercase() })
    }

    // ---- builds -----------------------------------------------------------------------------------------------------

    private fun parseBuildRef(o: JsonObject): BuildRef = BuildRef(
        number = o.int("number"),
        displayName = o.str("displayName"),
        result = o.str("result"),
        building = o.bool("building"),
        timestamp = o.long("timestamp"),
        duration = o.long("duration"),
        estimatedDuration = o.long("estimatedDuration"),
        startedBy = startedBy(o.objects("actions")),
        startedById = causes(o.objects("actions")).firstNotNullOfOrNull { c -> c.str("userId")?.takeIf { it.isNotBlank() } },
    )

    private fun causes(actions: List<JsonObject>): List<JsonObject> = actions.flatMap { it.objects("causes") }

    /** The user that started the build, or the first cause when no user did ("Started by timer"). */
    private fun startedBy(actions: List<JsonObject>): String? {
        val causes = causes(actions)
        causes.firstNotNullOfOrNull { c -> (c.str("userName") ?: c.str("userId"))?.takeIf { it.isNotBlank() } }?.let { return it }
        return causes.firstNotNullOfOrNull { it.str("shortDescription")?.takeIf { d -> d.isNotBlank() } }
            ?.removePrefix("Started by ")
    }

    fun parseJobDetails(json: String, fallbackFullName: String): JobDetails {
        val o = root(json)
        val definitions = (o.objects("property") + o.objects("actions"))
            .flatMap { it.objects("parameterDefinitions") }
            .mapNotNull { parseParamDef(it) }
            .distinctBy { it.name }
        return JobDetails(
            name = o.str("name") ?: fallbackFullName.substringAfterLast('/'),
            fullName = o.str("fullName")?.takeIf { it.isNotBlank() } ?: fallbackFullName,
            displayName = o.str("displayName"),
            description = o.str("description"),
            color = o.str("color"),
            buildable = o.el("buildable") == null || o.bool("buildable"),
            queueItem = o.obj("queueItem")?.let { parseQueueItem(it) },
            params = definitions,
            builds = o.objects("builds").map { parseBuildRef(it) },
        )
    }

    private fun parseParamDef(o: JsonObject): ParamDef? {
        val name = o.str("name") ?: return null
        val cls = o.str("_class")
        val type = o.str("type") ?: cls?.substringAfterLast('.') ?: ""
        val key = cls ?: type
        val choices = o.strings("choices")
        val served = o.obj("allValueItems")?.objects("values")?.mapNotNull { it.str("value") ?: it.str("name") } ?: emptyList()
        val kind = when {
            key.endsWith("BooleanParameterDefinition") -> ParamKind.BOOLEAN
            key.endsWith("PasswordParameterDefinition") -> ParamKind.PASSWORD
            key.contains("GitParameterDefinition") || key.contains("ListGitBranches") || type.startsWith("PT_") -> ParamKind.LIST
            choices.isNotEmpty() -> ParamKind.CHOICE
            served.isNotEmpty() -> ParamKind.LIST
            key.endsWith("TextParameterDefinition") -> ParamKind.TEXT
            else -> ParamKind.STRING
        }
        val default = if (kind == ParamKind.PASSWORD) null else o.obj("defaultParameterValue")?.str("value")
        return ParamDef(
            name = name,
            kind = kind,
            description = o.str("description")?.takeIf { it.isNotBlank() },
            defaultValue = default,
            choices = choices.ifEmpty { served },
            className = cls,
        )
    }

    fun parseBuildDetails(json: String): BuildDetails {
        val o = root(json)
        val actions = o.objects("actions")
        val causes = causes(actions).mapNotNull { it.str("shortDescription")?.takeIf { d -> d.isNotBlank() } }.distinct()
        val parameters = actions.flatMap { it.objects("parameters") }.mapNotNull { p ->
            val name = p.str("name") ?: return@mapNotNull null
            name to (p.str("value") ?: "")
        }
        val git = actions.filter { it.has("lastBuiltRevision") || it.has("remoteUrls") }.map { a ->
            val revision = a.obj("lastBuiltRevision")
            GitInfo(
                sha1 = revision?.str("SHA1"),
                branches = revision?.objects("branch")?.mapNotNull { it.str("name") } ?: emptyList(),
                remoteUrls = a.strings("remoteUrls"),
            )
        }.distinct()
        val tests = actions.firstOrNull { it.has("totalCount") }
            ?.let { TestSummary(it.int("totalCount"), it.int("failCount"), it.int("skipCount")) }
        val changeSets = listOfNotNull(o.obj("changeSet")) + o.objects("changeSets")
        return BuildDetails(
            number = o.int("number"),
            displayName = o.str("displayName"),
            fullDisplayName = o.str("fullDisplayName"),
            description = o.str("description"),
            result = o.str("result"),
            building = o.bool("building"),
            timestamp = o.long("timestamp"),
            duration = o.long("duration"),
            estimatedDuration = o.long("estimatedDuration"),
            builtOn = o.str("builtOn")?.takeIf { it.isNotBlank() },
            causes = causes,
            parameters = parameters,
            artifacts = o.objects("artifacts").mapNotNull { a ->
                val path = a.str("relativePath") ?: a.str("fileName") ?: return@mapNotNull null
                Artifact(a.str("fileName") ?: path.substringAfterLast('/'), path)
            },
            git = git,
            changes = changeSets.flatMap { it.objects("items") }.map { parseChange(it) },
            tests = tests,
            culprits = o.objects("culprits").mapNotNull { it.str("fullName") },
        )
    }

    private fun parseChange(o: JsonObject): ChangeEntry = ChangeEntry(
        commitId = o.str("commitId") ?: o.str("id") ?: o.str("revision"),
        message = (o.str("comment") ?: o.str("msg") ?: "").trim(),
        author = o.obj("author")?.str("fullName") ?: o.str("authorEmail"),
        timestamp = o.long("timestamp"),
        paths = o.strings("affectedPaths"),
    )

    // ---- small answers ----------------------------------------------------------------------------------------------

    fun parseQueueItem(json: String): QueueItem = parseQueueItem(root(json))

    private fun parseQueueItem(o: JsonObject): QueueItem = QueueItem(
        id = o.long("id"),
        why = o.str("why"),
        cancelled = o.bool("cancelled"),
        executableNumber = o.obj("executable")?.takeIf { it.has("number") }?.int("number"),
    )

    fun parseCrumb(json: String): Crumb? {
        val o = root(json)
        val value = o.str("crumb") ?: return null
        return Crumb(o.str("crumbRequestField") ?: "Jenkins-Crumb", value)
    }

    fun parseWhoAmI(json: String): WhoAmI {
        val o = root(json)
        return WhoAmI(o.str("name"), o.bool("anonymous"), o.bool("authenticated"))
    }

    /**
     * The values a drop-down parameter offers: `{"values":[{"name":"origin/master","value":"origin/master"}]}`.
     * Fails with the server's own message when it sends errors instead of values.
     */
    fun parseFillValues(json: String): List<String> {
        val element = try {
            JsonParser.parseString(json)
        } catch (e: Exception) {
            throw ParseException("The server did not answer with JSON: " + htmlToText(json, 200), e)
        }
        val items: List<JsonElement>
        var errors = emptyList<String>()
        when {
            element.isJsonArray -> items = element.asJsonArray.toList()
            element.isJsonObject -> {
                val o = element.asJsonObject
                items = o.arr("values")?.toList() ?: emptyList()
                errors = o.arr("errors")?.map { if (it.isJsonPrimitive) it.asString else it.toString() } ?: emptyList()
            }
            else -> items = emptyList()
        }
        val values = items.mapNotNull { item ->
            when {
                item.isJsonPrimitive -> item.asString
                item.isJsonObject -> item.asJsonObject.let { it.str("value") ?: it.str("name") }
                else -> null
            }
        }.filter { it.isNotBlank() }.distinct()
        if (values.isEmpty() && errors.isNotEmpty()) throw ParseException(errors.joinToString("; ") { htmlToText(it, 300) })
        return values
    }

    // ---- text -------------------------------------------------------------------------------------------------------

    private val CONSOLE_NOTE = Regex("\u001B\\[8mha:.*?\u001B\\[0m", RegexOption.DOT_MATCHES_ALL)
    private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

    /** Removes the hidden annotations Jenkins embeds in the console text and the ANSI colour codes. */
    fun cleanConsole(text: String): String {
        if (text.indexOf('\u001B') < 0) return text
        return ANSI.replace(CONSOLE_NOTE.replace(text, ""), "")
    }

    private val SCRIPT = Regex("<(script|style|head)[^>]*>.*?</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val TAG = Regex("<[^>]+>")
    private val SPACE = Regex("\\s+")

    /** The readable text of an HTML error page, shortened to [limit] characters. */
    fun htmlToText(html: String?, limit: Int = 400): String {
        if (html.isNullOrBlank()) return ""
        val text = TAG.replace(SCRIPT.replace(html, " "), " ")
            .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
        val flat = SPACE.replace(text, " ").trim()
        return if (flat.length > limit) flat.take(limit).trimEnd() + "..." else flat
    }
}
