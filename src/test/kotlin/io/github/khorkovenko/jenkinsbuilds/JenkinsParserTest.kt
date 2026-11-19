package io.github.khorkovenko.jenkinsbuilds

import io.github.khorkovenko.jenkinsbuilds.api.JenkinsParser
import io.github.khorkovenko.jenkinsbuilds.model.ParamKind
import io.github.khorkovenko.jenkinsbuilds.ui.ConsoleText.Kind as ConsoleKind
import io.github.khorkovenko.jenkinsbuilds.ui.Format
import io.github.khorkovenko.jenkinsbuilds.ui.JenkinsPanel.Badge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class JenkinsParserTest {

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/fixtures/$name")!!.use { String(it.readAllBytes(), Charsets.UTF_8) }

    @Test
    fun serverTreeHasJobsFoldersAndViews() {
        val tree = JenkinsParser.parseServerTree(fixture("server.json"))
        assertEquals("all", tree.primaryView)
        assertEquals(
            listOf("customersportal-2.2-dev", "customersportal-2.2-stable", "customersportal-custom", "4.0-cpetp-dev", "tools"),
            tree.jobs.map { it.name },
        )

        val dev = tree.jobs[0]
        assertFalse(dev.container)
        assertEquals("blue", dev.color)
        assertEquals("D2.2.310", dev.lastBuild!!.title)
        assertEquals("SUCCESS", dev.lastBuild!!.result)

        val custom = tree.jobs[2]
        assertTrue(custom.running)
        assertTrue(custom.lastBuild!!.building)
        assertNull(custom.lastBuild!!.result)

        // No fullName in the answer: taken from the url
        assertEquals("4.0-cpetp-dev", tree.jobs[3].fullName)
        assertNull(tree.jobs[3].lastBuild)

        val folder = tree.jobs[4]
        assertTrue(folder.container)
        val multibranch = folder.children.single()
        assertTrue(multibranch.container)
        assertEquals("tools/deploy scripts", multibranch.fullName)
        val branch = multibranch.children.single()
        assertEquals("tools/deploy scripts/feature%2Fone", branch.fullName)
        assertEquals("feature/one", branch.label)
        assertFalse(branch.container)
    }

    @Test
    fun viewsAndSectionsGroupTheJobs() {
        val tree = JenkinsParser.parseServerTree(fixture("server.json"))
        assertEquals(listOf("all", "Portal", "Tools"), tree.views.map { it.name })

        val portal = tree.views[1]
        assertEquals(listOf("Customer Portal 2.2", "CPETP 4.0"), portal.sections.map { it.name })
        assertEquals(listOf("customersportal-2.2-dev", "customersportal-custom"), portal.sections[0].jobs.map { it.fullName })
        // The job of a section is the full node of the job tree, with its last build
        assertEquals("C0.0.202", portal.sections[0].jobs[1].lastBuild!!.title)
        // A section that only names a regular expression
        assertEquals(listOf("4.0-cpetp-dev"), portal.sections[1].jobs.map { it.fullName })

        // A folder listed by a view brings its children along
        val tools = tree.views[2].jobs.single()
        assertTrue(tools.container)
        assertEquals(1, tools.children.size)
    }

    @Test
    fun jobDetailsHaveParametersQueueAndBuilds() {
        val job = JenkinsParser.parseJobDetails(fixture("job-custom.json"), "customersportal-custom")
        assertTrue(job.buildable)
        assertEquals(912L, job.queueItem!!.id)
        assertTrue(job.queueItem!!.why!!.startsWith("Build #202"))

        assertEquals(
            listOf("Branch_Selector", "PROFILE", "SKIP_TESTS", "VERSION_SUFFIX", "NOTES", "DEPLOY_KEY"),
            job.params.map { it.name },
        )
        val byName = job.params.associateBy { it.name }
        val branch = byName.getValue("Branch_Selector")
        assertEquals(ParamKind.LIST, branch.kind)
        assertEquals("origin/master", branch.defaultValue)
        assertEquals("net.uaznia.lukanus.hudson.plugins.gitparameter.GitParameterDefinition", branch.className)
        assertTrue(branch.choices.isEmpty())

        assertEquals(ParamKind.CHOICE, byName.getValue("PROFILE").kind)
        assertEquals(listOf("dev", "stage", "prod"), byName.getValue("PROFILE").choices)
        assertEquals(ParamKind.BOOLEAN, byName.getValue("SKIP_TESTS").kind)
        assertEquals("true", byName.getValue("SKIP_TESTS").defaultValue)
        assertEquals(ParamKind.STRING, byName.getValue("VERSION_SUFFIX").kind)
        assertEquals(ParamKind.TEXT, byName.getValue("NOTES").kind)
        assertEquals("line1\nline2", byName.getValue("NOTES").defaultValue)
        assertEquals(ParamKind.PASSWORD, byName.getValue("DEPLOY_KEY").kind)
        assertNull("a password default is never shown", byName.getValue("DEPLOY_KEY").defaultValue)

        assertEquals(listOf(202, 201, 200), job.builds.map { it.number })
        assertTrue(job.builds[0].building)
        assertEquals("C0.0.202", job.builds[0].title)
        assertEquals("khorkovenko", job.builds[0].startedBy)
        assertEquals("khorkovenko", job.builds[1].startedBy)
        assertEquals("#200", job.builds[2].title)
        assertEquals("timer", job.builds[2].startedBy)
        assertEquals("khorkovenko", job.builds[0].startedById)
        assertNull(job.builds[2].startedById)
    }

    @Test
    fun jobWithoutParametersIsNotParameterized() {
        val job = JenkinsParser.parseJobDetails("""{"name":"x","buildable":false,"property":[{}],"builds":[]}""", "folder/x")
        assertTrue(job.params.isEmpty())
        assertFalse(job.buildable)
        assertEquals("folder/x", job.fullName)
        assertNull(job.queueItem)
    }

    @Test
    fun buildDetailsHaveEverything() {
        val build = JenkinsParser.parseBuildDetails(fixture("build-201.json"))
        assertEquals(201, build.number)
        assertEquals("C0.0.201", build.title)
        assertEquals("SUCCESS", build.result)
        assertEquals(195000L, build.duration)
        assertNull(build.builtOn)

        assertEquals(listOf("Started by user khorkovenko", "Rebuilds build #200"), build.causes)
        assertEquals(
            listOf("Branch_Selector" to "origin/cp/2.2/CP-372", "SKIP_TESTS" to "true", "DEPLOY_KEY" to ""),
            build.parameters,
        )
        assertEquals(listOf("ROOT-C0.0.201-plain.war", "release notes.txt"), build.artifacts.map { it.fileName })
        assertEquals("build/libs/ROOT-C0.0.201-plain.war", build.artifacts[0].relativePath)

        val git = build.git.single()
        assertEquals("0a1b2c3d4e5f60718293a4b5c6d7e8f901234567", git.sha1)
        assertEquals(listOf("refs/remotes/origin/cp/2.2/CP-372"), git.branches)
        assertEquals(listOf("https://git.example.test/portal/customersportal.git"), git.remoteUrls)

        assertEquals(120, build.tests!!.total)
        assertEquals(2, build.tests!!.failed)
        assertEquals(3, build.tests!!.skipped)

        val change = build.changes.single()
        assertEquals("Kostiantyn H", change.author)
        assertTrue(change.message.startsWith("CP-372 fix <login> form"))
        assertEquals(2, change.paths.size)
        assertEquals(listOf("Kostiantyn H"), build.culprits)
    }

    @Test
    fun pipelineChangeSetsAreRead() {
        val json = """{"number":5,"building":true,"changeSets":[{"items":[{"commitId":"abc","msg":"one"}]},{"items":[{"commitId":"def","msg":"two"}]}]}"""
        val build = JenkinsParser.parseBuildDetails(json)
        assertEquals(listOf("one", "two"), build.changes.map { it.message })
        assertTrue(build.building)
        assertEquals("#5", build.title)
    }

    @Test
    fun summaryEscapesAndListsTheBuild() {
        val build = JenkinsParser.parseBuildDetails(fixture("build-201.json"))
        val html = Format.summaryHtml("customersportal-custom", build)
        assertTrue(html.contains("Rebuilds build #200"))
        assertTrue(html.contains("refs/remotes/origin/cp/2.2/CP-372"))
        assertTrue(html.contains("origin/cp/2.2/CP-372"))
        assertTrue(html.contains("120 total, 115 passed, 2 failed, 3 skipped"))
        assertTrue(html.contains("CP-372 fix &lt;login&gt; form"))
        assertFalse(html.contains("<login>"))
        assertTrue(html.contains("3 min 15 s"))
    }

    @Test
    fun queueItemTurnsIntoBuild() {
        val waiting = JenkinsParser.parseQueueItem("""{"id":912,"why":"Waiting for next available executor","cancelled":false,"executable":null}""")
        assertEquals(912L, waiting.id)
        assertNull(waiting.executableNumber)
        assertFalse(waiting.cancelled)

        val started = JenkinsParser.parseQueueItem("""{"id":912,"why":null,"cancelled":false,"executable":{"_class":"hudson.model.FreeStyleBuild","number":203}}""")
        assertEquals(203, started.executableNumber)
        assertNull(started.why)

        assertTrue(JenkinsParser.parseQueueItem("""{"id":913,"cancelled":true}""").cancelled)
    }

    @Test
    fun crumbAndWhoAmI() {
        val crumb = JenkinsParser.parseCrumb("""{"_class":"hudson.security.csrf.DefaultCrumbIssuer","crumb":"abc123","crumbRequestField":"Jenkins-Crumb"}""")
        assertEquals("Jenkins-Crumb", crumb!!.field)
        assertEquals("abc123", crumb.value)
        assertEquals(".crumb", JenkinsParser.parseCrumb("""{"crumb":"x","crumbRequestField":".crumb"}""")!!.field)
        assertNull(JenkinsParser.parseCrumb("{}"))

        val who = JenkinsParser.parseWhoAmI("""{"anonymous":false,"authenticated":true,"name":"khorkovenko"}""")
        assertEquals("khorkovenko", who.name)
        assertFalse(who.anonymous)
        assertTrue(JenkinsParser.parseWhoAmI("""{"anonymous":true,"authenticated":false,"name":"anonymous"}""").anonymous)
    }

    @Test
    fun branchValuesOfAGitParameter() {
        val json = """{"_class":"net.uaznia.lukanus.hudson.plugins.gitparameter.jobs.JobWrapper${'$'}ItemsErrorModel","errors":[],"values":[
            {"name":"origin/cp/2.2/dev","selected":false,"value":"origin/cp/2.2/dev"},
            {"name":"origin/master","selected":true,"value":"origin/master"},
            {"name":"origin/cp/2.1/CP-356","selected":false,"value":"origin/cp/2.1/CP-356"},
            {"name":"origin/master","selected":false,"value":"origin/master"}]}"""
        assertEquals(listOf("origin/cp/2.2/dev", "origin/master", "origin/cp/2.1/CP-356"), JenkinsParser.parseFillValues(json))
        assertEquals(listOf("a", "b"), JenkinsParser.parseFillValues("""["a","b"]"""))
        assertTrue(JenkinsParser.parseFillValues("""{"values":[]}""").isEmpty())
        try {
            JenkinsParser.parseFillValues("""{"values":[],"errors":["Cannot reach <b>repository</b>"]}""")
            fail("errors without values must fail")
        } catch (e: JenkinsParser.ParseException) {
            assertEquals("Cannot reach repository", e.message)
        }
    }

    @Test
    fun anHtmlAnswerIsReportedAsText() {
        try {
            JenkinsParser.parseServerTree("<html><head><title>x</title><style>b{}</style></head><body><h1>Authentication  required</h1></body></html>")
            fail("HTML is not a job tree")
        } catch (e: JenkinsParser.ParseException) {
            assertNotNull(e.message)
            assertTrue(e.message!!.contains("Authentication required"))
            assertFalse(e.message!!.contains("<h1>"))
        }
    }

    @Test
    fun htmlToTextShortens() {
        assertEquals("", JenkinsParser.htmlToText(null))
        assertEquals("a & b < c", JenkinsParser.htmlToText("<p>a &amp; b &lt; c</p>"))
        assertEquals("aaaaa...", JenkinsParser.htmlToText("aaaaaaaaaa", 5))
    }

    @Test
    fun consoleNotesAndColoursAreRemoved() {
        val esc = "\u001B"
        val raw = "Started by user $esc[8mha:////4AbCdEf+/=$esc[0mkhorkovenko\n$esc[1;31mERROR$esc[0m done\n"
        assertEquals("Started by user khorkovenko\nERROR done\n", JenkinsParser.cleanConsole(raw))
        assertEquals("plain", JenkinsParser.cleanConsole("plain"))
    }

    @Test
    fun formatting() {
        assertEquals("3 min 15 s", Format.duration(195000))
        assertEquals("1 h 2 min", Format.duration(3_725_000))
        assertEquals("45 s", Format.duration(45_000))
        assertEquals("", Format.duration(0))
        assertEquals("Running", Format.result(null, true))
        assertEquals("Success", Format.result("SUCCESS", false))
        assertEquals("Not built", Format.result("NOT_BUILT", false))
        assertTrue(Format.size(68L * 1024 * 1024).endsWith("MiB"))
        assertEquals("512 B", Format.size(512))
    }

    @Test
    fun consoleLinesGetAColour() {
        val k = io.github.khorkovenko.jenkinsbuilds.ui.ConsoleText
        assertEquals(ConsoleKind.ERROR, k.kind("Finished: FAILURE"))
        assertEquals(ConsoleKind.ERROR, k.kind("Build step 'Invoke Gradle script' marked build as failure"))
        assertEquals(ConsoleKind.ERROR, k.kind("Build did not succeed and the project is configured to only push after a successful build"))
        assertEquals(ConsoleKind.ERROR, k.kind("java.lang.IllegalStateException: boom"))
        assertEquals(ConsoleKind.ERROR, k.kind("\tat com.example.Main.run(Main.java:12)"))
        assertEquals(ConsoleKind.ERROR, k.kind("src/A.java:3: error: cannot find symbol"))
        assertEquals(ConsoleKind.SUCCESS, k.kind("Finished: SUCCESS"))
        assertEquals(ConsoleKind.SUCCESS, k.kind("BUILD SUCCESSFUL in 51s"))
        assertEquals(ConsoleKind.WARNING, k.kind("Skipped archiving because build is not successful"))
        assertEquals(ConsoleKind.WARNING, k.kind("warning: [deprecation] foo() has been deprecated"))
        assertEquals(ConsoleKind.WARNING, k.kind("Finished: UNSTABLE"))
        assertEquals(ConsoleKind.STEP, k.kind("Started by user khorkovenko"))
        assertEquals(ConsoleKind.STEP, k.kind("> Task :compileJava"))
        assertEquals(ConsoleKind.STEP, k.kind("[description-setter] Description set: "))
        assertEquals(ConsoleKind.STEP, k.kind("+ ./gradlew clean build"))
        assertEquals(ConsoleKind.NORMAL, k.kind("Collecting metadata..."))
        assertEquals(ConsoleKind.NORMAL, k.kind("Sending e-mails to: someone@example.test"))
        assertEquals(ConsoleKind.NORMAL, k.kind(""))
        assertEquals(ConsoleKind.NORMAL, k.kind("[INFO] Building customersportal 2.2"))
        assertEquals(ConsoleKind.SUCCESS, k.kind("[INFO] BUILD SUCCESS"))
        assertEquals(ConsoleKind.ERROR, k.kind("[ERROR] Failed to execute goal"))
    }

    @Test
    fun consoleRunsKeepTheTextAndSearchFindsIt() {
        val k = io.github.khorkovenko.jenkinsbuilds.ui.ConsoleText
        val text = "Started by user x\nline one\nline two\nERROR: bad\n\tat a.B.c(B.java:1)\nFinished: FAILURE"
        val runs = k.runs(text)
        assertEquals(text, runs.joinToString("") { it.second })
        assertEquals(listOf(ConsoleKind.STEP, ConsoleKind.NORMAL, ConsoleKind.ERROR), runs.map { it.first })
        assertEquals("line one\nline two\n", runs[1].second)
        assertTrue(k.runs("").isEmpty())

        assertEquals(listOf(18, 27), k.matches(text, "LINE"))
        assertTrue(k.matches(text, "").isEmpty())
        assertTrue(k.matches(text, "absent").isEmpty())
        assertEquals(2, k.matches("aaaa", "aa").size)
        assertEquals(1, k.matches("aaaa", "a", limit = 1).size)
    }

    @Test
    fun aPushIsToldFromFetchPullAndCommit() {
        val logic = io.github.khorkovenko.jenkinsbuilds.auto.AutoBuildLogic
        // Push: the remote branch arrives at the commit the local branch already was at
        assertTrue(logic.pushed("b", "a", "b", "b"))
        // First push of a new branch
        assertTrue(logic.pushed("b", "", "b", "b"))
        // Commit: only the local branch moved
        assertFalse(logic.pushed("a", "a", "b", "a"))
        // Fetch: the remote branch moved away
        assertFalse(logic.pushed("a", "a", "a", "c"))
        // Pull or fast-forward: both moved, the local one too
        assertFalse(logic.pushed("a", "a", "c", "c"))
        // Nothing moved
        assertFalse(logic.pushed("b", "b", "b", "b"))
        // Seen for the first time: nothing is known yet
        assertFalse(logic.pushed("", "", "b", "b"))
        // The branch has no remote counterpart, or is gone
        assertFalse(logic.pushed("b", "", "b", null))
        assertFalse(logic.pushed("b", "a", null, "b"))
    }

    @Test
    fun autoBuildRulesFireOncePerPush() {
        val settings = io.github.khorkovenko.jenkinsbuilds.auto.AutoBuildSettings()
        val rule = io.github.khorkovenko.jenkinsbuilds.auto.AutoBuildRule()
        rule.branch = "cp/2.2/dev"
        rule.root = "/repo"
        rule.job = "customersportal-custom"
        rule.parameterized = true
        rule.params = mutableListOf("Branch_Selector=origin/cp/2.2/dev", "NOTE=a=b")
        rule.lastLocal = "a"
        rule.lastRemote = "a"
        settings.replaceRules(listOf(rule))
        assertEquals(listOf("Branch_Selector" to "origin/cp/2.2/dev", "NOTE" to "a=b"), rule.parameters())
        assertTrue(rule.describe().contains("cp/2.2/dev") && rule.describe().contains("customersportal-custom"))
        assertEquals(listOf("cp/2.2/dev"), settings.branchesWatched("/repo"))
        assertTrue(settings.branchesWatched("/other").isEmpty())

        assertTrue("a commit is no push", settings.observe("/repo", "cp/2.2/dev", "b", "a").isEmpty())
        assertTrue("another repository", settings.observe("/other", "cp/2.2/dev", "b", "b").isEmpty())
        assertEquals(listOf("customersportal-custom"), settings.observe("/repo", "cp/2.2/dev", "b", "b").map { it.job })
        assertTrue("the same state again builds nothing", settings.observe("/repo", "cp/2.2/dev", "b", "b").isEmpty())

        // Switched off: the push is still recorded, so switching on later does not build old pushes
        settings.enabled = false
        settings.observe("/repo", "cp/2.2/dev", "c", "b")
        assertTrue(settings.observe("/repo", "cp/2.2/dev", "c", "c").isEmpty())
        settings.enabled = true
        assertTrue(settings.observe("/repo", "cp/2.2/dev", "c", "c").isEmpty())
        assertTrue(settings.active())
        settings.replaceRules(settings.rules().onEach { it.enabled = false })
        assertFalse(settings.active())
    }

    @Test
    fun badgeOfTheToolWindow() {
        val p = io.github.khorkovenko.jenkinsbuilds.ui.JenkinsPanel
        assertEquals(Badge.NONE, p.badgeFor(emptyList(), false))
        assertEquals(Badge.RUNNING, p.badgeFor(emptyList(), true))
        // One of several builds finished unseen: green although another still runs
        assertEquals(Badge.FINISHED, p.badgeFor(listOf("SUCCESS"), true))
        assertEquals(Badge.FINISHED, p.badgeFor(listOf("SUCCESS"), false))
        assertEquals(Badge.FAILED, p.badgeFor(listOf("SUCCESS", "FAILURE"), true))
    }
}
