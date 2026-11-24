package io.github.khorkovenko.jenkinsbuilds.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.khorkovenko.jenkinsbuilds.api.JenkinsParser
import io.github.khorkovenko.jenkinsbuilds.jira.JiraClient
import io.github.khorkovenko.jenkinsbuilds.jira.JiraConfig
import io.github.khorkovenko.jenkinsbuilds.jira.JiraUrls
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/** The Jira tab of Settings | Tools | JJ Tracker: address, login, custom query and Test Connection. */
class JiraSettingsForm {

    private val urlField = JBTextField()
    private val userField = JBTextField()
    private val secretField = JBPasswordField()
    private val testButton = JButton("Test Connection")
    private val resultLabel = JBLabel()

    /** The secret in the password store; null until it was read in the background. */
    @Volatile private var storedSecret: String? = null
    private var loadGeneration = 0

    val component: JComponent by lazy {
        urlField.emptyText.text = "https://jira.example.com or https://company.atlassian.net"
        userField.emptyText.text = "User name or e-mail; empty for a personal access token"
        secretField.emptyText.text = "Password, API token or personal access token"
        resultLabel.setCopyable(true)
        resultLabel.setAllowAutoWrapping(true)
        testButton.addActionListener { testConnection() }
        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent("Server address:", urlField)
            .addLabeledComponent("User name:", userField)
            .addTooltip("Jira Cloud: your e-mail. Jira Server or Data Center: your user name, or leave empty and enter a personal access token below.")
            .addLabeledComponent("Password or token:", secretField)
            .addTooltip("Kept in the IDE password store. Jira Cloud needs an API token (id.atlassian.com | Security | API tokens), not the password.")
            .addVerticalGap(8)
            .addComponent(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(testButton) })
            .addComponent(resultLabel)
            .panel
        form.border = JBUI.Borders.empty(8)
        JPanel(BorderLayout()).apply { add(form, BorderLayout.NORTH) }
    }

    private fun secretText(): String = String(secretField.password)

    fun isModified(): Boolean {
        val state = JenkinsSettings.getInstance().state
        val stored = storedSecret
        return JiraUrls.normalizeBase(urlField.text) != JiraUrls.normalizeBase(state.jiraUrl) ||
            userField.text.trim() != state.jiraUsername ||
            (if (stored == null) secretText().isNotEmpty() else secretText() != stored)
    }

    fun apply() {
        val settings = JenkinsSettings.getInstance()
        // The store was not read yet and the field is untouched: keep what is stored
        val secret = if (storedSecret == null && secretText().isEmpty()) settings.loadJiraSecret() else secretText()
        settings.saveJira(urlField.text, userField.text, secret)
        storedSecret = secret
        urlField.text = settings.state.jiraUrl
        userField.text = settings.state.jiraUsername
    }

    fun reset() {
        val settings = JenkinsSettings.getInstance()
        urlField.text = settings.state.jiraUrl
        userField.text = settings.state.jiraUsername
        resultLabel.text = ""
        secretField.text = storedSecret ?: ""
        val generation = ++loadGeneration
        ApplicationManager.getApplication().executeOnPooledThread {
            val secret = try {
                settings.loadJiraSecret()
            } catch (_: Exception) {
                ""
            }
            ApplicationManager.getApplication().invokeLater({
                if (generation != loadGeneration) return@invokeLater
                val untouched = secretText() == (storedSecret ?: "")
                storedSecret = secret
                if (untouched) secretField.text = secret
            }, ModalityState.any())
        }
    }

    fun dispose() {
        loadGeneration++
    }

    /** Tries the values that are in the fields right now, saved or not. */
    private fun testConnection() {
        val url = JiraUrls.normalizeBase(urlField.text)
        if (url.isEmpty()) {
            show("Enter the server address first.", false)
            return
        }
        val user = userField.text.trim()
        val typed = secretText()
        val useStored = typed.isEmpty() && storedSecret == null
        val settings = JenkinsSettings.getInstance()
        testButton.isEnabled = false
        resultLabel.foreground = UIUtil.getContextHelpForeground()
        resultLabel.text = "Connecting to $url ..."
        ApplicationManager.getApplication().executeOnPooledThread {
            var ok = false
            val message = try {
                val secret = if (useStored) settings.loadJiraSecret() else typed
                JiraClient(JiraConfig(url, user, secret, settings.timeoutSeconds)).testConnection().also { ok = true }
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            ApplicationManager.getApplication().invokeLater({
                testButton.isEnabled = true
                show(message, ok)
            }, ModalityState.any())
        }
    }

    private fun show(message: String, ok: Boolean) {
        resultLabel.foreground = if (ok) JBColor(0x1F7A36, 0x5FB865) else JBColor.RED
        resultLabel.text = JenkinsParser.htmlToText(message, 600)
    }

    internal fun texts(): List<String> = listOf(urlField.text, userField.text)
}
