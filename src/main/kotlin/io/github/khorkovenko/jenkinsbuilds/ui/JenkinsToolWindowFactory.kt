package io.github.khorkovenko.jenkinsbuilds.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/** The tool window has two tabs in its title bar: Jenkins (jobs and builds) and Jira (issues). */
class JenkinsToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val jenkins = JenkinsPanel(project, toolWindow)
        val jenkinsTab = ContentFactory.getInstance().createContent(jenkins, "Jenkins", false)
        jenkinsTab.setDisposer(jenkins)
        jenkinsTab.isCloseable = false
        toolWindow.contentManager.addContent(jenkinsTab)

        val jira = JiraPanel(project)
        val jiraTab = ContentFactory.getInstance().createContent(jira, "Jira", false)
        jiraTab.setDisposer(jira)
        jiraTab.isCloseable = false
        toolWindow.contentManager.addContent(jiraTab)
    }
}
