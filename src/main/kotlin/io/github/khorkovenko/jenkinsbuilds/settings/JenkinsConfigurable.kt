package io.github.khorkovenko.jenkinsbuilds.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.khorkovenko.jenkinsbuilds.api.ConnectionConfig
import io.github.khorkovenko.jenkinsbuilds.api.JenkinsClient
import io.github.khorkovenko.jenkinsbuilds.api.JenkinsParser
import io.github.khorkovenko.jenkinsbuilds.api.JenkinsUrls
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/** Settings | Tools | Jenkins Builds. */
class JenkinsConfigurable : SearchableConfigurable, Configurable.NoScroll {

    private var panel: JPanel? = null
    private val urlField = JBTextField()
    private val userField = JBTextField()
    private val secretField = JBPasswordField()
    private val timeoutSpinner = JSpinner(SpinnerNumberModel(30, 1, 600, 5))
    private val buildsSpinner = JSpinner(SpinnerNumberModel(30, 1, 500, 10))
    private val refreshSpinner = JSpinner(SpinnerNumberModel(5, 2, 600, 1))
    private val testButton = JButton("Test Connection")
    private val resultLabel = JBLabel()
    private val storeWarning = JBLabel()
    private val jiraForm = JiraSettingsForm()

    /** The secret in the password store; null until it was read in the background. */
    @Volatile private var storedSecret: String? = null

    /** Counts resets, so a slow read of the password store cannot overwrite a newer state of the field. */
    private var loadGeneration = 0

    override fun getId(): String = ID

    override fun getDisplayName(): String = "Jenkins Builds"

    override fun getPreferredFocusedComponent(): JComponent = urlField

    override fun createComponent(): JComponent {
        urlField.emptyText.text = "https://jenkins.example.com"
        secretField.emptyText.text = "Password or API token"
        resultLabel.setCopyable(true)
        resultLabel.setAllowAutoWrapping(true)
        storeWarning.foreground = JBColor.namedColor("Label.warningForeground", JBColor(0xA36B00, 0xE0A040))
        storeWarning.setAllowAutoWrapping(true)
        testButton.addActionListener { testConnection() }

        val testRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(testButton) }
        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent("Server address:", urlField)
            .addTooltip("The address of the Jenkins start page, including a prefix such as /jenkins when there is one.")
            .addLabeledComponent("User name:", userField)
            .addLabeledComponent("Password or API token:", secretField)
            .addTooltip("Kept in the IDE password store. An API token (Jenkins: your user | Security | API Token) is the better choice.")
            .addComponent(storeWarning)
            .addLabeledComponent("Timeout, seconds:", narrow(timeoutSpinner))
            .addLabeledComponent("Builds listed per job:", narrow(buildsSpinner))
            .addLabeledComponent("Refresh while a build runs, seconds:", narrow(refreshSpinner))
            .addVerticalGap(8)
            .addComponent(testRow)
            .addComponent(resultLabel)
            .panel
        form.border = JBUI.Borders.empty(8)
        // Two tabs on one page: the login to Jenkins and the login to Jira
        val tabs = JBTabbedPane()
        tabs.addTab("Jenkins", JPanel(BorderLayout()).apply { add(form, BorderLayout.NORTH) })
        tabs.addTab("Jira", jiraForm.component)
        return JPanel(BorderLayout()).apply { add(tabs, BorderLayout.CENTER) }.also { panel = it }
    }

    internal fun jiraTexts(): List<String> = jiraForm.texts()

    private fun narrow(spinner: JSpinner): JComponent =
        JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(spinner) }

    private fun secretText(): String = String(secretField.password)

    override fun isModified(): Boolean {
        val settings = JenkinsSettings.getInstance()
        val state = settings.state
        val stored = storedSecret
        return jiraForm.isModified() ||
            JenkinsUrls.normalizeBase(urlField.text) != JenkinsUrls.normalizeBase(state.url) ||
            userField.text.trim() != state.username ||
            // The spinners show the saved numbers brought into range, so compare with those
            timeoutSpinner.value != state.timeoutSeconds.coerceIn(1, 600) ||
            buildsSpinner.value != state.buildsLimit.coerceIn(1, 500) ||
            refreshSpinner.value != state.refreshSeconds.coerceIn(2, 600) ||
            (if (stored == null) secretText().isNotEmpty() else secretText() != stored)
    }

    override fun apply() {
        val settings = JenkinsSettings.getInstance()
        // The store was not read yet and the field is untouched: keep what is stored
        val secret = if (storedSecret == null && secretText().isEmpty()) settings.loadSecret() else secretText()
        settings.save(
            urlField.text,
            userField.text,
            secret,
            timeoutSpinner.value as Int,
            buildsSpinner.value as Int,
            refreshSpinner.value as Int,
        )
        storedSecret = secret
        urlField.text = settings.state.url
        userField.text = settings.state.username
        if (jiraForm.isModified()) jiraForm.apply()
    }

    override fun reset() {
        jiraForm.reset()
        val settings = JenkinsSettings.getInstance()
        val state = settings.state
        urlField.text = state.url
        userField.text = state.username
        timeoutSpinner.value = state.timeoutSeconds.coerceIn(1, 600)
        buildsSpinner.value = state.buildsLimit.coerceIn(1, 500)
        refreshSpinner.value = state.refreshSeconds.coerceIn(2, 600)
        resultLabel.text = ""
        secretField.text = storedSecret ?: ""

        val generation = ++loadGeneration
        ApplicationManager.getApplication().executeOnPooledThread {
            val secret = try {
                settings.loadSecret()
            } catch (_: Exception) {
                ""
            }
            val memoryOnly = try {
                settings.secretsAreMemoryOnly()
            } catch (_: Exception) {
                false
            }
            ApplicationManager.getApplication().invokeLater({
                if (generation != loadGeneration) return@invokeLater
                val untouched = secretText() == (storedSecret ?: "")
                storedSecret = secret
                if (untouched) secretField.text = secret
                storeWarning.text = if (memoryOnly) {
                    "The IDE is set not to save passwords (Settings | Appearance & Behavior | System Settings | Passwords): " +
                        "the password will be forgotten when the IDE closes."
                } else ""
                storeWarning.isVisible = memoryOnly
            }, ModalityState.any())
        }
    }

    override fun disposeUIResources() {
        jiraForm.dispose()
        loadGeneration++
        panel = null
    }

    /** Tries the values that are in the fields right now, saved or not. */
    private fun testConnection() {
        val url = JenkinsUrls.normalizeBase(urlField.text)
        if (url.isEmpty()) {
            showResult("Enter the server address first.", false)
            return
        }
        val user = userField.text.trim()
        val typed = secretText()
        val useStored = typed.isEmpty() && storedSecret == null
        val timeout = timeoutSpinner.value as Int
        testButton.isEnabled = false
        resultLabel.foreground = UIUtil.getContextHelpForeground()
        resultLabel.text = "Connecting to $url ..."
        ApplicationManager.getApplication().executeOnPooledThread {
            var ok = false
            val message = try {
                val secret = if (useStored) JenkinsSettings.getInstance().loadSecret() else typed
                JenkinsClient(ConnectionConfig(url, user, secret, timeout)).testConnection().also { ok = true }
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            ApplicationManager.getApplication().invokeLater({
                testButton.isEnabled = true
                showResult(message, ok)
            }, ModalityState.any())
        }
    }

    private fun showResult(message: String, ok: Boolean) {
        resultLabel.foreground = if (ok) JBColor(0x1F7A36, 0x5FB865) else JBColor.RED
        resultLabel.text = JenkinsParser.htmlToText(message, 600)
    }

    companion object {
        const val ID = "io.github.khorkovenko.jenkinsbuilds.settings"
    }
}
