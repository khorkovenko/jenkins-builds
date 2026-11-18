package io.github.khorkovenko.jenkinsbuilds.api

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Builds every URL the plugin calls. All of them are made from the address the user entered and the job path, never
 * from the `url` fields of the JSON: those carry the root URL configured inside Jenkins, which on a corporate server is
 * often another host name or scheme than the one that is reachable.
 */
object JenkinsUrls {

    /** How deep folders are followed below the top level. */
    const val FOLDER_DEPTH = 5

    private const val BUILD_FIELDS = "number,displayName,result,building,timestamp,duration,estimatedDuration"
    private const val CAUSE_FIELDS = "causes[shortDescription,userName,userId,upstreamBuild,upstreamProject]"

    /**
     * Turns what the user typed into a base address without a trailing slash: adds `https://` when the scheme is
     * missing, drops the query, the fragment, trailing slashes and a pasted `/api/json` or `/login` tail.
     */
    fun normalizeBase(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return ""
        if (!s.contains("://")) s = "https://$s"
        s = s.substringBefore('#').substringBefore('?')
        s = s.trimEnd('/')
        for (tail in listOf("/api/json", "/api", "/login")) {
            if (s.endsWith(tail, ignoreCase = true)) s = s.dropLast(tail.length).trimEnd('/')
        }
        return s
    }

    fun encodeSegment(segment: String): String =
        URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20")

    private fun decode(segment: String): String = try {
        URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        segment
    }

    /** `folder/sub/job` becomes `job/folder/job/sub/job/job`. */
    fun jobPath(fullName: String): String =
        fullName.split('/').filter { it.isNotEmpty() }.joinToString("/") { "job/" + encodeSegment(it) }

    fun jobUrl(base: String, fullName: String): String = "$base/${jobPath(fullName)}/"

    fun buildUrl(base: String, fullName: String, number: Int): String = "${jobUrl(base, fullName)}$number/"

    /** The job path inside a Jenkins URL such as `https://host/jenkins/view/All/job/folder/job/name/`. */
    fun fullNameFromUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val path = try {
            URI(url.trim()).rawPath
        } catch (_: Exception) {
            url.substringAfter("://").substringAfter('/', "")
        } ?: return null
        val segments = path.split('/').filter { it.isNotEmpty() }
        val names = ArrayList<String>()
        var i = 0
        while (i < segments.size) {
            if (segments[i] == "job" && i + 1 < segments.size) {
                names += decode(segments[i + 1])
                i += 2
            } else {
                i++
            }
        }
        return names.takeIf { it.isNotEmpty() }?.joinToString("/")
    }

    fun formEncode(params: List<Pair<String, String>>): String =
        params.joinToString("&") { (k, v) ->
            URLEncoder.encode(k, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8)
        }

    private fun query(vararg params: Pair<String, String>): String = "?" + formEncode(params.toList())

    // ---- JSON API ---------------------------------------------------------------------------------------------------

    private fun jobFields(depth: Int): String {
        val own = "name,fullName,displayName,url,color,lastBuild[$BUILD_FIELDS]"
        return if (depth <= 0) own else "$own,jobs[${jobFields(depth - 1)}]"
    }

    /** The `tree=` expression for the whole job tree: nested jobs and the views with their sections. */
    fun serverTreeExpression(depth: Int = FOLDER_DEPTH): String {
        val leaf = jobFields(0)
        val section = "sections[name,jobNames,includeRegex,jobs[$leaf],items[$leaf]]"
        val nested = "views[name,url,jobs[$leaf],$section]"
        return "primaryView[name],jobs[${jobFields(depth)}],views[name,url,jobs[$leaf],$section,$nested]"
    }

    fun serverTreeUrl(base: String): String = "$base/api/json" + query("tree" to serverTreeExpression())

    fun whoAmIUrl(base: String): String = "$base/whoAmI/api/json" + query("tree" to "name,anonymous,authenticated")

    fun crumbUrl(base: String): String = "$base/crumbIssuer/api/json"

    fun jobDetailsExpression(limit: Int): String {
        val params = "parameterDefinitions[name,type,description,choices,defaultParameterValue[name,value]," +
            "allValueItems[values[name,value]]]"
        val builds = "builds[$BUILD_FIELDS,actions[$CAUSE_FIELDS]]{0,${limit.coerceIn(1, 500)}}"
        return "name,fullName,displayName,description,color,buildable,inQueue,queueItem[id,why]," +
            "property[$params],actions[$params],$builds"
    }

    fun jobDetailsUrl(base: String, fullName: String, limit: Int): String =
        jobUrl(base, fullName) + "api/json" + query("tree" to jobDetailsExpression(limit))

    fun buildDetailsExpression(): String {
        val change = "items[commitId,id,revision,msg,comment,timestamp,author[fullName],authorEmail,affectedPaths]"
        return "number,displayName,fullDisplayName,description,result,building,timestamp,duration," +
            "estimatedDuration,builtOn,artifacts[fileName,relativePath]," +
            "actions[$CAUSE_FIELDS,parameters[name,value],lastBuiltRevision[SHA1,branch[SHA1,name]],remoteUrls," +
            "totalCount,failCount,skipCount],changeSet[$change],changeSets[$change],culprits[fullName]"
    }

    fun buildDetailsUrl(base: String, fullName: String, number: Int): String =
        buildUrl(base, fullName, number) + "api/json" + query("tree" to buildDetailsExpression())

    // ---- actions ----------------------------------------------------------------------------------------------------

    fun triggerUrl(base: String, fullName: String, parameterized: Boolean): String =
        jobUrl(base, fullName) + if (parameterized) "buildWithParameters" else "build"

    fun stopUrl(base: String, fullName: String, number: Int): String = buildUrl(base, fullName, number) + "stop"

    fun queueItemUrl(base: String, id: Long): String =
        "$base/queue/item/$id/api/json" + query("tree" to "id,why,cancelled,executable[number]")

    fun cancelQueueUrl(base: String, id: Long): String = "$base/queue/cancelItem" + query("id" to id.toString())

    /** The id in the `Location: .../queue/item/123/` header that answers a build request. */
    fun queueIdFromLocation(location: String?): Long? =
        location?.let { Regex("/queue/item/(\\d+)").find(it)?.groupValues?.get(1)?.toLongOrNull() }

    fun consoleUrl(base: String, fullName: String, number: Int, start: Long): String =
        buildUrl(base, fullName, number) + "logText/progressiveText" + query("start" to start.toString())

    fun artifactUrl(base: String, fullName: String, number: Int, relativePath: String): String =
        buildUrl(base, fullName, number) + "artifact/" +
            relativePath.split('/').filter { it.isNotEmpty() }.joinToString("/") { encodeSegment(it) }

    /**
     * The address a parameter such as Git Parameter fills its drop-down from; [className] is the class of the
     * parameter definition.
     */
    fun parameterValuesUrl(base: String, fullName: String, className: String, paramName: String): String =
        jobUrl(base, fullName) + "descriptorByName/" + encodeSegment(className) + "/fillValueItems" +
            query("param" to paramName)

    /** True when both addresses have the same scheme, host and port, so credentials may be sent to [url]. */
    fun sameOrigin(base: String, url: String): Boolean {
        return try {
            val a = URI(base)
            val b = URI(url)
            a.scheme.equals(b.scheme, ignoreCase = true) && a.host.equals(b.host, ignoreCase = true) &&
                port(a) == port(b)
        } catch (_: Exception) {
            false
        }
    }

    private fun port(uri: URI): Int = when {
        uri.port != -1 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        else -> 80
    }
}
