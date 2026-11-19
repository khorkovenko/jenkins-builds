package io.github.khorkovenko.jenkinsbuilds

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khorkovenko.jenkinsbuilds.api.JenkinsParser
import io.github.khorkovenko.jenkinsbuilds.model.JobDetails
import io.github.khorkovenko.jenkinsbuilds.model.ParamDef
import io.github.khorkovenko.jenkinsbuilds.model.ParamKind
import io.github.khorkovenko.jenkinsbuilds.model.ServerTree
import io.github.khorkovenko.jenkinsbuilds.settings.JenkinsSettings
import io.github.khorkovenko.jenkinsbuilds.ui.AutoBuildDialog

/**
 * The form behind the + of the Auto dialog. It once failed while being built, so + did nothing: every test here
 * builds the real form. The server is played by [FakeServer], which answers at once.
 */
class AutoBuildUiTest : BasePlatformTestCase() {

    private class FakeServer : AutoBuildDialog.Server {
        val asked = ArrayList<String>()
        var branches: List<String>? = listOf("origin/master", "origin/cp/2.2/dev", "origin/cp/2.1/CP-356")

        override fun <T> run(work: () -> T, done: (Result<T>) -> Unit) = done(runCatching(work))

        override fun job(fullName: String): JobDetails {
            asked += fullName
            if (fullName == "broken") error("HTTP 500 Server Error")
            val params = when {
                fullName.endsWith("-custom") -> listOf(
                    ParamDef("Branch_Selector", ParamKind.LIST, null, "origin/master", emptyList(), "git.Param"),
                    ParamDef("PROFILE", ParamKind.CHOICE, null, "dev", listOf("dev", "prod"), null),
                )
                fullName == "4.0-cpetp-dev" -> listOf(ParamDef("PROFILE", ParamKind.CHOICE, null, "dev", listOf("dev", "prod"), null))
                else -> emptyList()
            }
            return JobDetails(fullName.substringAfterLast('/'), fullName, null, null, "blue", true, null, params, emptyList())
        }

        override fun values(fullName: String, definition: ParamDef): List<String> = branches ?: error("HTTP 404 Not Found")
    }

    private fun tree(): ServerTree = JenkinsParser.parseServerTree(
        javaClass.getResourceAsStream("/fixtures/server.json")!!.use { String(it.readAllBytes(), Charsets.UTF_8) },
    )

    private fun settings(): JenkinsSettings = JenkinsSettings.getInstance().also { it.loadState(JenkinsSettings.State()) }

    private fun form(
        server: FakeServer,
        tree: ServerTree? = tree(),
        branches: List<String> = listOf("main", "cp/2.2/dev", "feature/new"),
        current: String? = "cp/2.2/dev",
        job: String? = "customersportal-custom",
    ) = AutoBuildDialog.RuleForm(project, branches, current, tree, job, server) { "origin/$it" }

    fun testACustomJobOffersItsBranchesWithTheProjectBranchChosen() {
        settings()
        val server = FakeServer()
        val form = form(server)
        try {
            assertEquals("cp/2.2/dev", form.branch())
            assertEquals("customersportal-custom", form.job())
            assertTrue(form.isReady())
            assertEquals(listOf("origin/master", "origin/cp/2.2/dev", "origin/cp/2.1/CP-356"), form.offeredBranches())
            assertEquals("origin/cp/2.2/dev", form.buildBranch())

            val rule = form.rule()!!
            assertEquals("cp/2.2/dev", rule.branch)
            assertEquals("customersportal-custom", rule.job)
            assertTrue(rule.parameterized)
            // Only the branch selector is sent: the other parameters keep the defaults of the job
            assertEquals(listOf("Branch_Selector" to "origin/cp/2.2/dev"), rule.parameters())
        } finally {
            form.close(1)
        }
    }

    fun testTheBranchToBuildFollowsTheProjectBranchUntilItIsChosenByHand() {
        settings()
        val form = form(FakeServer())
        try {
            form.selectBranch("main")
            // origin/main is not among the branches of the job: the default of the job is taken
            assertEquals("origin/master", form.buildBranch())
            form.selectBranch("cp/2.2/dev")
            assertEquals("origin/cp/2.2/dev", form.buildBranch())

            form.selectBuildBranch("origin/cp/2.1/CP-356")
            form.selectBranch("main")
            assertEquals("origin/cp/2.1/CP-356", form.buildBranch())
            assertEquals(listOf("Branch_Selector" to "origin/cp/2.1/CP-356"), form.rule()!!.parameters())
        } finally {
            form.close(1)
        }
    }

    fun testTheJobTreeIsTheOneOfTheToolWindow() {
        val settings = settings()
        settings.setFavouriteJob("4.0-cpetp-dev", true)
        settings.setFavouriteValue("origin/cp/2.1/CP-356", true)
        val server = FakeServer()
        val form = form(server)
        try {
            assertEquals(listOf("Favourites", "In Progress", "all", "Portal", "Tools"), form.jobGroups())
            // A favourite branch is listed first
            assertEquals("origin/cp/2.1/CP-356", form.offeredBranches().first())

            // A job with parameters but no branch selector: no third drop-down, built with its defaults
            form.selectJob("4.0-cpetp-dev")
            assertEquals("4.0-cpetp-dev", form.job())
            assertTrue(form.offeredBranches().isEmpty())
            assertEquals("", form.buildBranch())
            form.rule()!!.let {
                assertTrue(it.parameterized)
                assertTrue(it.parameters().isEmpty())
            }

            // A job without parameters
            form.selectJob("customersportal-2.2-dev")
            form.rule()!!.let {
                assertEquals("customersportal-2.2-dev", it.job)
                assertFalse(it.parameterized)
            }
            assertTrue(form.statusText().contains("without parameters"))

            // The filter narrows the tree and keeps the chosen job when it still matches
            form.filterJobs("cpetp")
            assertEquals("", form.job())
            assertFalse(form.isReady())
            form.selectJob("4.0-cpetp-dev")
            assertEquals("4.0-cpetp-dev", form.job())
            assertTrue(form.isReady())
            form.filterJobs("4.0-cp")
            assertEquals("4.0-cpetp-dev", form.job())
        } finally {
            form.close(1)
        }
    }

    fun testBranchesThatCannotBeListedAreTyped() {
        settings()
        val server = FakeServer()
        server.branches = null
        val form = form(server, current = "feature/new")
        try {
            assertTrue(form.isReady())
            assertTrue(form.offeredBranches().isEmpty())
            assertEquals("origin/feature/new", form.buildBranch())
            form.selectBranch("main")
            assertEquals("origin/main", form.buildBranch())
            form.selectBuildBranch("origin/exact")
            form.selectBranch("cp/2.2/dev")
            assertEquals("origin/exact", form.buildBranch())
        } finally {
            form.close(1)
        }
    }

    fun testAJobThatCannotBeReadGivesNoRule() {
        settings()
        val form = form(FakeServer(), tree = null, job = null)
        try {
            assertEquals("", form.job())
            assertNull(form.rule())
            form.selectJob("broken")
            assertFalse(form.isReady())
            assertNull(form.rule())
            assertTrue(form.statusText().contains("HTTP 500"))
            form.selectJob("x-custom")
            assertTrue(form.isReady())
            assertEquals("origin/cp/2.2/dev", form.buildBranch())
        } finally {
            form.close(1)
        }
    }

    fun testTheFormOpensWithNothingKnown() {
        settings()
        val form = form(FakeServer(), tree = null, branches = emptyList(), current = null, job = null)
        try {
            assertEquals("", form.branch())
            assertEquals("", form.job())
            assertNull(form.rule())
            form.selectBranch("feature/x")
            form.selectJob("plain")
            assertEquals("feature/x", form.rule()!!.branch)
        } finally {
            form.close(1)
        }
    }
}
