package io.github.khorkovenko.jenkinsbuilds.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.khorkovenko.jenkinsbuilds.api.JenkinsParser
import io.github.khorkovenko.jenkinsbuilds.jira.JiraException
import io.github.khorkovenko.jenkinsbuilds.jira.JiraIssue
import io.github.khorkovenko.jenkinsbuilds.jira.JiraParser
import io.github.khorkovenko.jenkinsbuilds.jira.JiraQuery
import io.github.khorkovenko.jenkinsbuilds.jira.JiraUrls
import io.github.khorkovenko.jenkinsbuilds.settings.JenkinsConfigurable
import io.github.khorkovenko.jenkinsbuilds.settings.JenkinsSettings
import io.github.khorkovenko.jenkinsbuilds.settings.JenkinsSettingsListener
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.table.AbstractTableModel

/**
 * The Jira tab of the tool window. It has one field, like the search page of Jira: type a query
 * (`assignee = khorkovenko`) and press Enter. Plain words work too: while you type they narrow the list that is
 * shown, and Enter searches the server for them. The field can be locked, and a locked text is still there after
 * the IDE is restarted. An empty field lists your open issues. A double click opens an issue.
 */
class JiraPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private var disposed = false
    private var generation = 0
    private var loaded: List<JiraIssue> = emptyList()
    private var shown: List<JiraIssue> = emptyList()

    /** The text of the field the loaded list was fetched for; null before the first search. */
    private var searched: String? = null

    private val queryField = SearchTextField(false)
    private val model = IssuesModel()
    private val table = JBTable(model)
    private val statusLabel = JBLabel(" ")

    init {
        val settings = JenkinsSettings.getInstance()
        val group = DefaultActionGroup().apply {
            add(action("Search", "Run the query of the field again", AllIcons.Actions.Refresh, { true }) { search() })
            add(action("New Issue", "Create a Jira issue", AllIcons.General.Add, { JenkinsSettings.getInstance().jiraConfigured }) { newIssue() })
            add(Separator.getInstance())
            add(action("Open Issue", "Show the description and the comments, comment and reassign", AllIcons.Actions.Preview, { selected() != null }) { open() })
            add(action("Open in Browser", "Open the selected issue in the browser", AllIcons.General.Web, { selected() != null }) {
                selected()?.let { BrowserUtil.browse(JiraUrls.browse(JenkinsSettings.getInstance().jiraUrl, it.key)) }
            })
            add(action("Copy Key", "Copy the key of the selected issue", AllIcons.Actions.Copy, { selected() != null }) {
                selected()?.let { CopyPasteManager.getInstance().setContents(StringSelection(it.key)) }
            })
            add(Separator.getInstance())
            add(object : DumbAwareToggleAction("Lock the Query", "Keep the text of the field: it cannot be changed and stays after the IDE is restarted", AllIcons.Diff.Lock) {
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
                override fun isSelected(e: AnActionEvent): Boolean = JenkinsSettings.getInstance().state.jiraFilterLocked
                override fun setSelected(e: AnActionEvent, state: Boolean) = setLocked(state)
            })
            add(Separator.getInstance())
            add(action("Jira Settings", "Server address and login", AllIcons.General.Settings, { true }) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, JenkinsConfigurable::class.java)
            })
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("JenkinsBuilds.JiraToolbar", group, true)
        toolbar.targetComponent = this
        setToolbar(toolbar.component)

        queryField.textEditor.emptyText.text = "JQL such as  assignee = currentUser()  or plain words; Enter to search. Empty: my open issues"
        if (settings.state.jiraFilterLocked) queryField.text = settings.state.jiraFilter
        queryField.textEditor.isEditable = !settings.state.jiraFilterLocked
        queryField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = narrow()
        })
        queryField.textEditor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    search()
                    e.consume()
                }
            }
        })

        table.setShowGrid(false)
        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        table.emptyText.text = "Loading..."
        table.columnModel.getColumn(0).preferredWidth = JBUI.scale(90)
        table.columnModel.getColumn(1).preferredWidth = JBUI.scale(420)
        table.columnModel.getColumn(2).preferredWidth = JBUI.scale(110)
        table.columnModel.getColumn(3).preferredWidth = JBUI.scale(150)
        table.columnModel.getColumn(4).preferredWidth = JBUI.scale(120)
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                open()
                return true
            }
        }.installOn(table)
        table.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    open()
                    e.consume()
                }
            }
        })

        val top = JPanel(BorderLayout()).apply {
            add(queryField, BorderLayout.CENTER)
            border = JBUI.Borders.empty(2, 4)
        }
        statusLabel.border = JBUI.Borders.empty(2, 8)
        setContent(JPanel(BorderLayout()).apply {
            add(top, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(table, true), BorderLayout.CENTER)
            add(statusLabel, BorderLayout.SOUTH)
        })

        ApplicationManager.getApplication().messageBus.connect(this).subscribe(JenkinsSettingsListener.TOPIC, JenkinsSettingsListener {
            ApplicationManager.getApplication().invokeLater({ if (!disposed) search() }, ModalityState.any())
        })
        search()
    }

    override fun dispose() {
        disposed = true
    }

    private fun action(text: String, description: String, icon: Icon, enabled: () -> Boolean, run: () -> Unit): AnAction =
        object : DumbAwareAction(text, description, icon) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = !disposed && enabled()
            }

            override fun actionPerformed(e: AnActionEvent) {
                if (!disposed) run()
            }
        }

    private fun status(text: String, error: Boolean = false) {
        statusLabel.foreground = if (error) JBColor.RED else UIUtil.getContextHelpForeground()
        statusLabel.text = text.ifEmpty { " " }
        statusLabel.toolTipText = if (error) text else null
    }

    private fun selected(): JiraIssue? = table.selectedRow.takeIf { it in shown.indices }?.let { shown[it] }

    /** Locks the field with the text it has, or frees it again. A locked text is kept in the settings. */
    internal fun setLocked(locked: Boolean) {
        val state = JenkinsSettings.getInstance().state
        state.jiraFilterLocked = locked
        state.jiraFilter = if (locked) queryField.text else ""
        queryField.textEditor.isEditable = !locked
    }

    internal fun setQuery(text: String) {
        queryField.text = text
    }

    internal fun queryText(): String = queryField.text

    internal fun queryEditable(): Boolean = queryField.textEditor.isEditable

    /** Shows [issues] as the answer to the text that is in the field now. For the tests. */
    internal fun showIssues(issues: List<JiraIssue>) {
        searched = queryField.text.trim()
        loaded = issues
        narrow()
    }

    internal fun shownKeys(): List<String> = shown.map { it.key }

    /**
     * Plain words narrow the loaded list while they are typed. A query does not: it is for the server, on Enter.
     * Neither does the very text the list was fetched for, because the server may have found it in a description.
     */
    private fun narrow() {
        val text = queryField.text.trim()
        val keep = selected()?.key
        shown = if (text == searched || JiraQuery.isJql(text)) loaded else JiraParser.filter(loaded, text)
        model.fireTableDataChanged()
        val index = shown.indexOfFirst { it.key == keep }
        if (index >= 0) table.selectionModel.setSelectionInterval(index, index)
        if (loaded.isNotEmpty() && shown.isEmpty()) table.emptyText.text = "Nothing in the list matches: press Enter to search Jira"
    }

    /** Asks the server for what the field says. */
    private fun search() {
        val settings = JenkinsSettings.getInstance()
        if (!settings.jiraConfigured) {
            loaded = emptyList()
            searched = null
            narrow()
            table.emptyText.clear()
            table.emptyText.appendLine("Jira is not set up.")
            table.emptyText.appendLine("Open settings", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, JenkinsConfigurable::class.java)
            }
            status("")
            return
        }
        val text = queryField.text.trim()
        val jql = JiraQuery.toJql(text)
        val mine = ++generation
        status("Searching: $jql")
        AppExecutorUtil.getAppExecutorService().submit {
            val result = try {
                Result.success(settings.jiraClient().search(jql))
            } catch (e: Throwable) {
                // The login was refused: read the password store again next time
                if ((e as? JiraException)?.status == 401) settings.forgetJiraLogin()
                Result.failure(e)
            }
            ApplicationManager.getApplication().invokeLater({
                if (disposed || mine != generation) return@invokeLater
                result.onSuccess { issues ->
                    loaded = issues
                    searched = text
                    table.emptyText.text = "No issues"
                    narrow()
                    status("${issues.size} issues" + if (issues.size >= 100) " (the first 100)" else "")
                }.onFailure {
                    if (loaded.isEmpty()) table.emptyText.text = "The issues could not be loaded"
                    status(JenkinsParser.htmlToText(it.message ?: it.javaClass.simpleName, 500), error = true)
                }
            }, ModalityState.any())
        }
    }

    private fun open() {
        val issue = selected() ?: return
        JiraIssueDialog(project, issue.key, changed = { search() }).show()
    }

    private fun newIssue() {
        val dialog = JiraNewIssueDialog(project)
        if (dialog.showAndGet()) {
            status("Created ${dialog.created}")
            search()
        }
    }

    private inner class IssuesModel : AbstractTableModel() {
        private val columns = arrayOf("Key", "Summary", "Status", "Assignee", "Updated")

        override fun getRowCount(): Int = shown.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]
        override fun getValueAt(row: Int, column: Int): Any {
            val issue = shown[row]
            return when (column) {
                0 -> issue.key
                1 -> issue.summary
                2 -> issue.status
                3 -> issue.assignee ?: ""
                else -> issue.updated
            }
        }
    }
}
