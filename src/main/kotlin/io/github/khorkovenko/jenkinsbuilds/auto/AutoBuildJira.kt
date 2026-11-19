package io.github.khorkovenko.jenkinsbuilds.auto

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import io.github.khorkovenko.jenkinsbuilds.api.JenkinsParser
import io.github.khorkovenko.jenkinsbuilds.api.JenkinsUrls
import io.github.khorkovenko.jenkinsbuilds.jira.JiraKeys
import io.github.khorkovenko.jenkinsbuilds.jira.JiraUser
import io.github.khorkovenko.jenkinsbuilds.settings.JenkinsSettings

/**
 * The Jira step of an auto-build rule: after a successful build the issue gets a comment with the link to the build
 * and, when the rule names one, a new assignee.
 */
object AutoBuildJira {

    /**
     * The issue a rule is about: the key written into the rule, else the first key in the name of the pushed branch,
     * else the first key in a build parameter (the branch a `-custom` job builds).
     */
    fun issueKey(rule: AutoBuildRule): String? =
        rule.jiraIssue.trim().takeIf { it.isNotEmpty() }
            ?: JiraKeys.first(rule.branch)
            ?: rule.parameters().firstNotNullOfOrNull { JiraKeys.first(it.second) }

    /** The comment: the text of the rule and, on a line of its own, the address of the build. */
    fun comment(rule: AutoBuildRule, buildUrl: String): String =
        listOf(rule.jiraComment.trim(), buildUrl).filter { it.isNotEmpty() }.joinToString("\n")

    fun assignee(rule: AutoBuildRule): JiraUser? =
        rule.jiraAssigneeId.takeIf { it.isNotEmpty() }?.let { JiraUser(it, rule.jiraAssigneeName.ifEmpty { it }, rule.jiraAssigneeCloud) }

    /** What the rules table shows for the Jira step; empty when it is off. */
    fun describe(rule: AutoBuildRule): String {
        if (!rule.jiraEnabled) return ""
        val issue = rule.jiraIssue.trim().ifEmpty { issueKey(rule)?.let { "$it (from the branch)" } ?: "issue of the branch" }
        val who = assignee(rule)?.let { "assign to ${it.name}" }
        val text = rule.jiraComment.trim().takeIf { it.isNotEmpty() }?.let { "comment \"${if (it.length > 30) it.take(30) + "..." else it}\"" } ?: "comment the build link"
        return "$issue: " + listOfNotNull(who, text).joinToString(", ")
    }

    private fun notify(project: Project, text: String, type: NotificationType) {
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            NotificationGroupManager.getInstance().getNotificationGroup("Jenkins Builds").createNotification("Jenkins auto build: Jira", text, type).notify(project)
        }, ModalityState.any())
    }

    /** Called off the UI thread when the build [number] of [rule] has ended with [result]. */
    fun afterBuild(project: Project, rule: AutoBuildRule, number: Int, result: String?) {
        if (result != "SUCCESS") {
            notify(project, "${rule.job} #$number ended with ${result ?: "no result"}: the Jira issue was left as it is.", NotificationType.INFORMATION)
            return
        }
        val key = issueKey(rule)
        if (key == null) {
            notify(project, "No issue key was found in the branch name \"${rule.branch}\": set the issue in the rule. Jira was not changed.", NotificationType.WARNING)
            return
        }
        val settings = JenkinsSettings.getInstance()
        val done = ArrayList<String>()
        try {
            val jira = settings.jiraClient()
            jira.addComment(key, comment(rule, JenkinsUrls.buildUrl(settings.url, rule.job, number)))
            done += "comment added"
            assignee(rule)?.let {
                jira.assign(key, it)
                done += "assigned to ${it.name}"
            }
            notify(project, "$key: " + done.joinToString(", ") + ".", NotificationType.INFORMATION)
        } catch (e: Exception) {
            val before = if (done.isEmpty()) "" else " Done before the failure: " + done.joinToString(", ") + "."
            notify(project, "$key: " + JenkinsParser.htmlToText(e.message ?: e.javaClass.simpleName, 400) + before, NotificationType.ERROR)
        }
    }
}
