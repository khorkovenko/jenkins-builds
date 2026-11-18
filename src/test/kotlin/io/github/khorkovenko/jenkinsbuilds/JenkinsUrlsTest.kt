package io.github.khorkovenko.jenkinsbuilds

import io.github.khorkovenko.jenkinsbuilds.api.ConnectionConfig
import io.github.khorkovenko.jenkinsbuilds.api.JenkinsUrls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.util.Base64

class JenkinsUrlsTest {

    private val base = "https://build-master.example.test"

    @Test
    fun baseAddressIsNormalized() {
        assertEquals(base, JenkinsUrls.normalizeBase("https://build-master.example.test/"))
        assertEquals(base, JenkinsUrls.normalizeBase("  https://build-master.example.test///  "))
        assertEquals(base, JenkinsUrls.normalizeBase("build-master.example.test"))
        assertEquals(base, JenkinsUrls.normalizeBase("https://build-master.example.test/api/json?pretty=true"))
        assertEquals(base, JenkinsUrls.normalizeBase("https://build-master.example.test/login?from=%2F"))
        assertEquals("http://host:8080/jenkins", JenkinsUrls.normalizeBase("http://host:8080/jenkins/"))
        assertEquals("", JenkinsUrls.normalizeBase("   "))
    }

    @Test
    fun jobPaths() {
        assertEquals("job/customersportal-custom", JenkinsUrls.jobPath("customersportal-custom"))
        assertEquals("job/tools/job/deploy%20scripts/job/feature%252Fone", JenkinsUrls.jobPath("tools/deploy scripts/feature%2Fone"))
        assertEquals("$base/job/4.0-cpetp-dev/", JenkinsUrls.jobUrl(base, "4.0-cpetp-dev"))
        assertEquals("$base/job/a/job/b/17/", JenkinsUrls.buildUrl(base, "a/b", 17))
    }

    @Test
    fun fullNameFromJenkinsUrl() {
        assertEquals("customersportal-custom", JenkinsUrls.fullNameFromUrl("http://internal:8080/view/Portal/job/customersportal-custom/"))
        assertEquals("tools/deploy scripts/feature%2Fone", JenkinsUrls.fullNameFromUrl("http://internal:8080/jenkins/job/tools/job/deploy%20scripts/job/feature%252Fone/"))
        assertEquals("job/x", JenkinsUrls.fullNameFromUrl("http://h/job/job/job/x/"))
        assertEquals("a+b", JenkinsUrls.fullNameFromUrl("http://h/job/a+b/"))
        assertNull(JenkinsUrls.fullNameFromUrl("http://h/view/All/"))
        assertNull(JenkinsUrls.fullNameFromUrl(null))
        // Round trip
        val name = "folder/sub folder/job #1"
        assertEquals(name, JenkinsUrls.fullNameFromUrl(JenkinsUrls.jobUrl(base, name)))
    }

    @Test
    fun formEncoding() {
        assertEquals(
            "Branch_Selector=origin%2Fcp%2F2.2%2Fdev&SKIP_TESTS=true&NOTE=a+b%26c%3Dd&EMPTY=",
            JenkinsUrls.formEncode(listOf("Branch_Selector" to "origin/cp/2.2/dev", "SKIP_TESTS" to "true", "NOTE" to "a b&c=d", "EMPTY" to "")),
        )
        assertEquals("", JenkinsUrls.formEncode(emptyList()))
    }

    @Test
    fun actionUrls() {
        assertEquals("$base/job/x/buildWithParameters", JenkinsUrls.triggerUrl(base, "x", true))
        assertEquals("$base/job/x/build", JenkinsUrls.triggerUrl(base, "x", false))
        assertEquals("$base/job/x/202/stop", JenkinsUrls.stopUrl(base, "x", 202))
        assertEquals("$base/queue/cancelItem?id=912", JenkinsUrls.cancelQueueUrl(base, 912))
        assertEquals("$base/crumbIssuer/api/json", JenkinsUrls.crumbUrl(base))
        assertEquals("$base/job/x/202/logText/progressiveText?start=4096", JenkinsUrls.consoleUrl(base, "x", 202, 4096))
        assertEquals(
            "$base/job/x/201/artifact/build/libs/ROOT-C0.0.201-plain.war",
            JenkinsUrls.artifactUrl(base, "x", 201, "build/libs/ROOT-C0.0.201-plain.war"),
        )
        assertEquals("$base/job/x/201/artifact/docs/release%20notes.txt", JenkinsUrls.artifactUrl(base, "x", 201, "docs/release notes.txt"))
        assertEquals(
            "$base/job/x/descriptorByName/net.uaznia.lukanus.hudson.plugins.gitparameter.GitParameterDefinition/fillValueItems?param=Branch_Selector",
            JenkinsUrls.parameterValuesUrl(base, "x", "net.uaznia.lukanus.hudson.plugins.gitparameter.GitParameterDefinition", "Branch_Selector"),
        )
    }

    @Test
    fun queueIdFromLocationHeader() {
        assertEquals(912L, JenkinsUrls.queueIdFromLocation("https://build-master.example.test/queue/item/912/"))
        assertEquals(7L, JenkinsUrls.queueIdFromLocation("http://internal:8080/jenkins/queue/item/7"))
        assertNull(JenkinsUrls.queueIdFromLocation("https://build-master.example.test/job/x/"))
        assertNull(JenkinsUrls.queueIdFromLocation(null))
    }

    private fun treeOf(url: String): String = URLDecoder.decode(url.substringAfter("tree="), Charsets.UTF_8)

    private fun balanced(expression: String): Boolean {
        var depth = 0
        for (c in expression) {
            if (c == '[') depth++
            if (c == ']') depth--
            if (depth < 0) return false
        }
        return depth == 0
    }

    @Test
    fun treeQueriesAreWellFormed() {
        val server = JenkinsUrls.serverTreeUrl(base)
        assertTrue(server.startsWith("$base/api/json?tree="))
        assertFalse("the query must be encoded", server.substringAfter("tree=").contains("["))
        val tree = treeOf(server)
        assertTrue(balanced(tree))
        assertTrue(tree.startsWith("primaryView[name],jobs[name,fullName,displayName,url,color,lastBuild["))
        assertTrue(tree.contains("sections[name,jobNames,includeRegex,jobs["))
        // One level of jobs for the top and one for every folder level below it
        assertEquals(JenkinsUrls.FOLDER_DEPTH + 1, Regex("jobs\\[").findAll(tree.substringBefore(",views[")).count())

        val job = treeOf(JenkinsUrls.jobDetailsUrl(base, "a/b", 30))
        assertTrue(JenkinsUrls.jobDetailsUrl(base, "a/b", 30).startsWith("$base/job/a/job/b/api/json?tree="))
        assertTrue(balanced(job.replace("{0,30}", "")))
        assertTrue(job.endsWith("{0,30}"))
        assertTrue(job.contains("property[parameterDefinitions[name,type,description,choices,defaultParameterValue[name,value]"))
        assertTrue(treeOf(JenkinsUrls.jobDetailsUrl(base, "a", 100000)).endsWith("{0,500}"))

        val build = treeOf(JenkinsUrls.buildDetailsUrl(base, "a", 3))
        assertTrue(balanced(build))
        assertTrue(build.contains("artifacts[fileName,relativePath]"))
        assertTrue(build.contains("lastBuiltRevision[SHA1,branch[SHA1,name]]"))
    }

    @Test
    fun credentialsStayOnTheServer() {
        assertTrue(JenkinsUrls.sameOrigin(base, "$base/job/x/api/json"))
        assertTrue(JenkinsUrls.sameOrigin(base, "https://BUILD-MASTER.example.test:443/x"))
        assertFalse(JenkinsUrls.sameOrigin(base, "http://build-master.example.test/x"))
        assertFalse(JenkinsUrls.sameOrigin(base, "https://other.example.test/x"))
        assertFalse(JenkinsUrls.sameOrigin(base, "https://build-master.example.test:8443/x"))
        assertFalse(JenkinsUrls.sameOrigin(base, "not a url"))
    }

    @Test
    fun basicAuthorizationHeader() {
        val header = ConnectionConfig(base, "user", "päss:word", 30).authorization()!!
        assertTrue(header.startsWith("Basic "))
        assertEquals("user:päss:word", String(Base64.getDecoder().decode(header.removePrefix("Basic ")), Charsets.UTF_8))
        assertNull(ConnectionConfig(base, "", "x", 30).authorization())
        assertFalse("the secret must not leak through toString", ConnectionConfig(base, "user", "s3cr3t-value", 30).toString().contains("s3cr3t-value"))
    }
}
