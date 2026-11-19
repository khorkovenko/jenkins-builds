package io.github.khorkovenko.jenkinsbuilds

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khorkovenko.jenkinsbuilds.auto.AutoBuildRule
import io.github.khorkovenko.jenkinsbuilds.jira.JiraAttachment
import io.github.khorkovenko.jenkinsbuilds.jira.JiraComment
import io.github.khorkovenko.jenkinsbuilds.jira.JiraIssue
import io.github.khorkovenko.jenkinsbuilds.jira.JiraIssueDetails
import io.github.khorkovenko.jenkinsbuilds.jira.JiraProject
import io.github.khorkovenko.jenkinsbuilds.jira.JiraUser
import io.github.khorkovenko.jenkinsbuilds.settings.JenkinsConfigurable
import io.github.khorkovenko.jenkinsbuilds.settings.JenkinsSettings
import io.github.khorkovenko.jenkinsbuilds.ui.JiraActionDialog
import io.github.khorkovenko.jenkinsbuilds.ui.JiraIssueChooser
import io.github.khorkovenko.jenkinsbuilds.ui.JiraIssueDialog
import io.github.khorkovenko.jenkinsbuilds.ui.JiraNewIssueDialog
import io.github.khorkovenko.jenkinsbuilds.ui.JiraPanel
import io.github.khorkovenko.jenkinsbuilds.ui.JiraServer
import io.github.khorkovenko.jenkinsbuilds.ui.JiraUserChooser
import java.io.File
import java.nio.file.Files

/** The Jira tab and its dialogs, built for real inside a headless IDE. [FakeJira] plays the server and answers at once. */
class JiraUiTest : BasePlatformTestCase() {

    private val all = listOf(
        JiraIssue("CP-372", "Fix the login form", "In Progress", "Kostiantyn H", "Bug", "Major", "2026-11-18 12:34"),
        JiraIssue("CP-371", "Date picker", "Open", null, "Task", "", "2026-11-17 09:00"),
        JiraIssue("ABC-5", "Login page text", "Done", "Ivan Ivanov", "Story", "Minor", "2026-11-16 09:00"),
    )

    private inner class FakeJira : JiraServer {
        val calls = ArrayList<String>()
        var comments = mutableListOf(JiraComment("Team Lead", "2026-11-11 10:00", "Please look at !shot.png|thumbnail!"))
        var assignee: String? = "Kostiantyn H"
        var failComment = false
        val shot = JiraAttachment("55", "shot.png", "image/png", 2048)

        override fun <T> run(work: () -> T, done: (Result<T>) -> Unit) = done(runCatching(work))

        override fun issue(key: String): JiraIssueDetails {
            calls += "issue $key"
            return JiraIssueDetails(
                JiraIssue(key, "Fix the login form", "In Progress", assignee, "Bug", "Major", "2026-11-18 12:34"),
                "Steps to reproduce", "Team Lead", "2026-11-10 08:00", comments.toList(), listOf(shot),
            )
        }

        override fun search(jql: String): List<JiraIssue> {
            calls += "search $jql"
            if (jql.contains("broken query")) error("HTTP 400 Bad Request. Error in the JQL Query")
            return if (jql.contains("project = ABC")) all.filter { it.key.startsWith("ABC") } else all
        }

        override fun addComment(key: String, text: String) {
            calls += "comment $key $text"
            if (failComment) error("HTTP 403 Forbidden")
            comments += JiraComment("Me", "2026-11-19 09:00", text)
        }

        override fun assign(key: String, user: JiraUser) {
            calls += "assign $key ${user.id}"
            assignee = user.name
        }

        override fun users(key: String?, query: String): List<JiraUser> {
            calls += "users $key $query"
            return listOf(JiraUser("ivanov", "Ivan Ivanov", false), JiraUser("petrov", "Petro Petrov", false)).filter { it.name.contains(query, ignoreCase = true) }
        }

        override fun projects(): List<JiraProject> = listOf(JiraProject("ABC", "Alpha"), JiraProject("CP", "Customer Portal"))

        override fun issueTypes(project: String): List<String> = if (project == "ABC") listOf("Story") else listOf("Bug", "Task")

        override fun createIssue(project: String, type: String, summary: String, description: String, assignee: JiraUser?): String {
            calls += "create $project $type $summary ${assignee?.id}"
            return "$project-1"
        }

        override fun download(attachment: JiraAttachment, target: File) {
            calls += "download ${attachment.id}"
            target.writeText("picture")
        }
    }

    private fun settings(): JenkinsSettings = JenkinsSettings.getInstance().also { it.loadState(JenkinsSettings.State()) }

    private fun panel(): JiraPanel = JiraPanel(project).also { Disposer.register(testRootDisposable, it) }

    fun testTheTabHasOneFieldThatNarrowsAndCanBeLocked() {
        val settings = settings()
        val panel = panel()
        panel.showIssues(all)
        assertEquals(listOf("CP-372", "CP-371", "ABC-5"), panel.shownKeys())
        // Plain words narrow what is shown while they are typed
        panel.setQuery("login")
        assertEquals(listOf("CP-372", "ABC-5"), panel.shownKeys())
        panel.setQuery("login ivan")
        assertEquals(listOf("ABC-5"), panel.shownKeys())
        // A query is for the server: typing it hides nothing
        panel.setQuery("assignee = khorkovenko")
        assertEquals(3, panel.shownKeys().size)
        // The list the server found for a text is shown whole, also when the words are only in a description
        panel.setQuery("timeout")
        assertTrue(panel.shownKeys().isEmpty())
        panel.showIssues(all.take(1))
        assertEquals(listOf("CP-372"), panel.shownKeys())

        // Locked: the text cannot be edited and is kept in the settings
        panel.setQuery("assignee = khorkovenko")
        panel.setLocked(true)
        assertFalse(panel.queryEditable())
        assertEquals("assignee = khorkovenko", settings.state.jiraFilter)
        assertTrue(settings.state.jiraFilterLocked)

        // A new tab (the IDE was restarted) comes up with the locked text
        val again = panel()
        assertEquals("assignee = khorkovenko", again.queryText())
        assertFalse(again.queryEditable())

        // Unlocked: editable again, and nothing is kept for the next start
        again.setLocked(false)
        assertTrue(again.queryEditable())
        assertEquals("", settings.state.jiraFilter)
        assertEquals("", panel().queryText())
    }

    fun testAnIssueShowsCommentsInBlocksAndTakesAComment() {
        settings()
        val jira = FakeJira()
        var changes = 0
        val dialog = JiraIssueDialog(project, "CP-372", jira) { changes++ }
        try {
            assertTrue(dialog.shownHeader().contains("CP-372  Fix the login form"))
            assertTrue(dialog.shownHeader().contains("Assignee: Kostiantyn H"))
            assertTrue(dialog.shownText().contains("Steps to reproduce"))
            assertTrue(dialog.shownText().contains("Comments (1)"))
            assertTrue(dialog.shownText().contains("<b>Team Lead</b>"))
            // The picture a comment embeds is a link, and the attachments are listed
            assertTrue(dialog.shownText().contains("Please look at <a href=\"jira-attachment:55\">[image] shot.png</a>"))
            assertTrue(dialog.shownText().contains("Attachments (1)"))

            dialog.setComment("  Fixed in build 207  ")
            dialog.addComment()
            assertTrue(jira.calls.contains("comment CP-372 Fixed in build 207"))
            assertTrue("the issue is read again after the comment", dialog.shownText().contains("Comments (2)"))
            assertTrue(dialog.shownText().contains("Fixed in build 207"))
            assertEquals(1, changes)

            dialog.assignTo(JiraUser("ivanov", "Ivan Ivanov", false))
            assertTrue(jira.calls.contains("assign CP-372 ivanov"))
            assertTrue(dialog.shownHeader().contains("Assignee: Ivan Ivanov"))
            assertEquals(2, changes)

            // A refused comment changes nothing
            jira.failComment = true
            dialog.setComment("again")
            dialog.addComment()
            assertTrue(dialog.shownText().contains("Comments (2)"))
            assertEquals(2, changes)
        } finally {
            dialog.close(0)
        }
    }

    fun testRefreshReadsNewCommentsAndAClickSavesAnAttachment() {
        settings()
        val jira = FakeJira()
        val dialog = JiraIssueDialog(project, "CP-372", jira)
        val folder = Files.createTempDirectory("jenkins-builds-downloads").toFile()
        try {
            // Someone else comments in the meantime: Refresh shows it without closing the window
            jira.comments += JiraComment("Tester", "2026-11-19 10:00", "Checked on stage")
            assertFalse(dialog.shownText().contains("Checked on stage"))
            dialog.load()
            assertTrue(dialog.shownText().contains("Comments (2)"))
            assertTrue(dialog.shownText().contains("Checked on stage"))

            dialog.downloads = { folder }
            dialog.followLink("jira-attachment:55")
            assertTrue(jira.calls.contains("download 55"))
            assertEquals("picture", File(folder, "shot.png").readText())
            // A second click does not overwrite the first file
            dialog.followLink("jira-attachment:55")
            assertEquals(listOf("shot (1).png", "shot.png"), folder.list()!!.sorted())
            // A link to an attachment that is not there does nothing
            dialog.followLink("jira-attachment:999")
            assertEquals(2, folder.list()!!.size)
        } finally {
            dialog.close(0)
            folder.deleteRecursively()
        }
    }

    fun testTheUserChooserSearchesWhileTyping() {
        settings()
        val jira = FakeJira()
        val chooser = JiraUserChooser(project, "CP-372", jira)
        try {
            assertEquals(listOf("Ivan Ivanov", "Petro Petrov"), chooser.offered().map { it.name })
            chooser.type("pet")
            assertEquals(listOf("Petro Petrov"), chooser.offered().map { it.name })
            assertEquals("petrov", chooser.chosen()!!.id)
            assertTrue(jira.calls.contains("users CP-372 pet"))
            chooser.type("nobody")
            assertNull(chooser.chosen())
        } finally {
            chooser.close(1)
        }
    }

    fun testAnIssueIsPickedFromAListAfterAQuery() {
        settings()
        val jira = FakeJira()
        val chooser = JiraIssueChooser(project, jira)
        try {
            // It opens with my open issues
            assertTrue(jira.calls.first().startsWith("search assignee = currentUser()"))
            assertEquals(listOf("CP-372", "CP-371", "ABC-5"), chooser.offered())
            assertEquals("CP-372", chooser.chosen())
            chooser.type("project = ABC")
            assertEquals("search project = ABC", jira.calls.last())
            assertEquals(listOf("ABC-5"), chooser.offered())
            assertEquals("ABC-5", chooser.chosen())
            chooser.select(0)
            assertEquals(listOf("ABC-5"), chooser.chosenKeys())
            // A query the server refuses says so and keeps the list
            chooser.type("broken query = ")
            assertTrue(chooser.statusText().contains("Error in the JQL Query"))
            assertEquals(listOf("ABC-5"), chooser.offered())
        } finally {
            chooser.close(1)
        }
    }

    fun testSeveralIssuesArePickedAtOnce() {
        settings()
        val chooser = JiraIssueChooser(project, FakeJira(), multiple = true)
        try {
            chooser.select(0, 2)
            assertEquals(listOf("CP-372", "ABC-5"), chooser.chosenKeys())
        } finally {
            chooser.close(1)
        }
    }

    fun testANewIssueOffersProjectsAndTheirTypes() {
        settings()
        val dialog = JiraNewIssueDialog(project, FakeJira())
        try {
            val (projects, types) = dialog.choices()
            assertEquals(listOf("ABC", "CP"), projects)
            assertEquals(listOf("Story"), types)
            assertNull(dialog.created)
        } finally {
            dialog.close(1)
        }
    }

    fun testTheJiraStepOfARuleIsEditedInItsDialog() {
        settings()
        val rule = AutoBuildRule()
        rule.branch = "cp/2.2/CP-372"
        rule.job = "customersportal-custom"
        val dialog = JiraActionDialog(project, rule, FakeJira())
        try {
            dialog.fill(true, "", "Deployed to stage")
            dialog.setAssignee(JiraUser("5b10ac", "Test Engineer", true))
            dialog.applyTo(rule)
            assertTrue(rule.jiraEnabled)
            assertEquals("", rule.jiraIssue)
            assertEquals("Deployed to stage", rule.jiraComment)
            assertEquals("5b10ac", rule.jiraAssigneeId)
            assertEquals("Test Engineer", rule.jiraAssigneeName)
            assertTrue(rule.jiraAssigneeCloud)

            dialog.setAssignee(null)
            dialog.fill(false, "ABC-1", "")
            assertEquals("ABC-1", dialog.issueText())
            dialog.applyTo(rule)
            assertFalse(rule.jiraEnabled)
            assertEquals("ABC-1", rule.jiraIssue)
            assertEquals("", rule.jiraAssigneeId)

            // Several issues get the same comment; chosen keys are added to the ones typed
            dialog.fill(true, "ABC-1", "Deployed")
            dialog.addIssues(listOf("CP-372", "ABC-1", "CP-371"))
            assertEquals("ABC-1, CP-372, CP-371", dialog.issueText())
            dialog.applyTo(rule)
            assertEquals("ABC-1, CP-372, CP-371", rule.jiraIssue)
        } finally {
            dialog.close(1)
        }
    }

    fun testTheSettingsPageHasAJiraTabWithTheSavedLogin() {
        val state = settings().state
        state.jiraUrl = "https://jira.example.test"
        state.jiraUsername = "tester"
        val page = JenkinsConfigurable()
        try {
            page.createComponent()
            page.reset()
            assertEquals(listOf("https://jira.example.test", "tester"), page.jiraTexts())
            assertFalse(page.isModified)
        } finally {
            page.disposeUIResources()
        }
    }
}
