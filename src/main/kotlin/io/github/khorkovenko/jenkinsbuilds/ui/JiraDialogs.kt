package io.github.khorkovenko.jenkinsbuilds.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.CollectionListModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.khorkovenko.jenkinsbuilds.api.JenkinsParser
import com.intellij.icons.AllIcons
import com.intellij.ide.actions.RevealFileAction
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.util.ui.HTMLEditorKitBuilder
import io.github.khorkovenko.jenkinsbuilds.jira.JiraAttachment
import io.github.khorkovenko.jenkinsbuilds.jira.JiraHtml
import io.github.khorkovenko.jenkinsbuilds.jira.JiraIssue
import io.github.khorkovenko.jenkinsbuilds.jira.JiraIssueDetails
import io.github.khorkovenko.jenkinsbuilds.jira.JiraQuery
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import javax.swing.JList
import java.io.File
import javax.swing.JEditorPane
import javax.swing.event.HyperlinkEvent
import io.github.khorkovenko.jenkinsbuilds.jira.JiraProject
import io.github.khorkovenko.jenkinsbuilds.jira.JiraUrls
import io.github.khorkovenko.jenkinsbuilds.jira.JiraUser
import io.github.khorkovenko.jenkinsbuilds.settings.JenkinsSettings
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

/** What the Jira dialogs ask the server. The tests put their own answers here. */
interface JiraServer {
    /** Runs [work] away from the UI thread and hands its result to [done] on the UI thread. */
    fun <T> run(work: () -> T, done: (Result<T>) -> Unit)

    fun issue(key: String): JiraIssueDetails
    fun search(jql: String): List<JiraIssue>
    fun addComment(key: String, text: String)
    fun assign(key: String, user: JiraUser)
    fun users(key: String?, query: String): List<JiraUser>
    fun projects(): List<JiraProject>
    fun issueTypes(project: String): List<String>
    fun createIssue(project: String, type: String, summary: String, description: String): String

    /** Saves the file of [attachment] as [target]. */
    fun download(attachment: JiraAttachment, target: File)
}

/** The Jira server of the settings. */
object LiveJira : JiraServer {
    private fun client() = JenkinsSettings.getInstance().jiraClient()

    override fun <T> run(work: () -> T, done: (Result<T>) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching(work)
            ApplicationManager.getApplication().invokeLater({ done(result) }, ModalityState.any())
        }
    }

    override fun issue(key: String) = client().issue(key)
    override fun search(jql: String) = client().search(jql)
    override fun addComment(key: String, text: String) = client().addComment(key, text)
    override fun assign(key: String, user: JiraUser) = client().assign(key, user)
    override fun users(key: String?, query: String) = client().users(key, query)
    override fun projects() = client().projects()
    override fun issueTypes(project: String) = client().issueTypes(project)
    override fun createIssue(project: String, type: String, summary: String, description: String) =
        client().createIssue(project, type, summary, description)

    override fun download(attachment: JiraAttachment, target: File) = client().download(attachment, target)
}

private fun message(error: Throwable): String = JenkinsParser.htmlToText(error.message ?: error.javaClass.simpleName, 400)

/**
 * One issue: its fields, description, attachments and comments, with a field to add a comment and a button to
 * reassign it. Every comment is a block of its own under a title bar. A click on an attachment saves the file to
 * the Downloads folder. Refresh reads the issue again, so new comments show up without closing the window.
 * [changed] is called after the issue was commented or reassigned, so the list behind can load again.
 */
class JiraIssueDialog(
    private val project: Project,
    private val key: String,
    private val server: JiraServer = LiveJira,
    private val changed: () -> Unit = {},
) : DialogWrapper(project) {

    private val header = JBLabel(key)
    private val meta = JBLabel(" ")
    private val body = JEditorPane()
    private val refreshButton = JButton("Refresh", AllIcons.Actions.Refresh)
    private val commentField = JBTextArea(3, 40)
    private val commentButton = JButton("Add Comment")
    private val assignButton = JButton("Reassign...")
    private val statusLabel = JBLabel(" ")
    private var details: JiraIssueDetails? = null
    private var html = ""

    init {
        title = key
        isModal = true
        init()
        load()
    }

    override fun getDimensionServiceKey(): String = "io.github.khorkovenko.jenkinsbuilds.JiraIssueDialog"

    override fun createActions(): Array<Action> = arrayOf(
        object : AbstractAction("Open in Browser") {
            override fun actionPerformed(e: ActionEvent?) = BrowserUtil.browse(JiraUrls.browse(JenkinsSettings.getInstance().jiraUrl, key))
        },
        okAction.also { it.putValue(Action.NAME, "Close") },
    )

    override fun createCenterPanel(): JComponent {
        header.font = JBUI.Fonts.label().asBold().biggerOn(2f)
        meta.foreground = UIUtil.getContextHelpForeground()
        body.editorKit = HTMLEditorKitBuilder.simple()
        body.isEditable = false
        body.border = JBUI.Borders.empty(4, 8)
        body.background = UIUtil.getListBackground()
        body.addHyperlinkListener { event ->
            if (event.eventType == HyperlinkEvent.EventType.ACTIVATED) followLink(event.description ?: "")
        }
        commentField.lineWrap = true
        commentField.wrapStyleWord = true
        commentField.emptyText.text = "Write a comment"
        statusLabel.font = JBUI.Fonts.smallFont()
        refreshButton.toolTipText = "Read the issue again: new comments and attachments"
        refreshButton.addActionListener { load() }
        commentButton.addActionListener { addComment() }
        assignButton.addActionListener { reassign() }
        commentField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = updateButtons()
        })

        // The title on the left, Refresh in the top right corner, under the cross that closes the window
        val titles = JPanel(BorderLayout(0, JBUI.scale(2))).apply {
            add(header, BorderLayout.NORTH)
            add(meta, BorderLayout.CENTER)
        }
        val top = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            add(titles, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply { add(refreshButton) }, BorderLayout.EAST)
        }
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            add(commentButton)
            add(assignButton)
            add(statusLabel)
        }
        val bottom = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            add(JBScrollPane(commentField), BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
        }
        updateButtons()
        return JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            add(top, BorderLayout.NORTH)
            add(JBScrollPane(body), BorderLayout.CENTER)
            add(bottom, BorderLayout.SOUTH)
            preferredSize = Dimension(JBUI.scale(820), JBUI.scale(620))
        }
    }

    private fun status(text: String, error: Boolean = false) {
        statusLabel.foreground = if (error) JBColor.RED else UIUtil.getContextHelpForeground()
        statusLabel.text = text.ifEmpty { " " }
    }

    private fun updateButtons() {
        commentButton.isEnabled = details != null && commentField.text.isNotBlank()
        assignButton.isEnabled = details != null
    }

    /** Reads the issue; also what the Refresh button does. */
    internal fun load() {
        status("Loading $key ...")
        refreshButton.isEnabled = false
        server.run({ server.issue(key) }) { result ->
            if (isDisposed) return@run
            refreshButton.isEnabled = true
            result.onSuccess { show(it) }.onFailure { status(message(it), error = true) }
            updateButtons()
        }
    }

    private fun hex(color: java.awt.Color): String = "#%02x%02x%02x".format(color.red, color.green, color.blue)

    private fun show(read: JiraIssueDetails) {
        val first = details == null
        details = read
        val issue = read.issue
        header.text = "${issue.key}  ${issue.summary}"
        meta.text = listOf(
            issue.type, issue.status, issue.priority,
            "Assignee: " + (issue.assignee ?: "nobody"),
            "Reporter: " + (read.reporter ?: "unknown"),
            "Updated: " + issue.updated,
        ).filter { it.isNotBlank() }.joinToString("   |   ")
        val position = body.visibleRect
        html = JiraHtml.issue(read, hex(JBColor(0xDDE6F3, 0x3B4252)), hex(UIUtil.getContextHelpForeground()))
        body.text = html
        // The first time the window opens at the top; after a refresh it stays where the reader was
        if (first) body.caretPosition = 0 else ApplicationManager.getApplication().invokeLater({ body.scrollRectToVisible(position) }, ModalityState.any())
        status("")
    }

    internal fun shownText(): String = html

    internal fun shownHeader(): String = header.text + "\n" + meta.text

    internal fun setComment(text: String) {
        commentField.text = text
    }

    /** A click in the text: an attachment is downloaded, anything else opens in the browser. */
    internal fun followLink(link: String) {
        if (!link.startsWith(JiraHtml.ATTACHMENT)) {
            if (link.startsWith("http://") || link.startsWith("https://")) BrowserUtil.browse(link)
            return
        }
        val attachment = details?.attachments?.firstOrNull { it.id == link.removePrefix(JiraHtml.ATTACHMENT) } ?: return
        val target = freeName(downloads(), attachment.filename)
        status("Downloading ${attachment.filename} ...")
        server.run({ server.download(attachment, target) }) { result ->
            result.onSuccess {
                if (!isDisposed) status("Saved ${target.path}")
                val notification = NotificationGroupManager.getInstance().getNotificationGroup("Jenkins Builds")
                    .createNotification("Jira attachment", "${target.name} saved to ${target.parent}", NotificationType.INFORMATION)
                notification.addAction(NotificationAction.createSimple("Show in folder") { RevealFileAction.openFile(target) })
                notification.notify(project)
            }.onFailure { if (!isDisposed) status(message(it), error = true) }
        }
    }

    /** Where attachments are saved; the tests put a folder of their own here. */
    internal var downloads: () -> File = {
        File(System.getProperty("user.home"), "Downloads").takeIf { it.isDirectory } ?: File(System.getProperty("user.home"))
    }

    /** `shot.png`, or `shot (1).png` and so on when a file of that name is already there. */
    private fun freeName(directory: File, name: String): File {
        val safe = File(name).name
        var candidate = File(directory, safe)
        val stem = safe.substringBeforeLast('.', safe)
        val extension = if (safe.contains('.')) "." + safe.substringAfterLast('.') else ""
        var i = 1
        while (candidate.exists()) candidate = File(directory, "$stem (${i++})$extension")
        return candidate
    }

    internal fun addComment() {
        val text = commentField.text.trim()
        if (text.isEmpty() || details == null) return
        commentButton.isEnabled = false
        status("Adding the comment ...")
        server.run({ server.addComment(key, text) }) { result ->
            if (isDisposed) return@run
            result.onSuccess {
                commentField.text = ""
                changed()
                load()
            }.onFailure {
                status(message(it), error = true)
                updateButtons()
            }
        }
    }

    private fun reassign() {
        val chooser = JiraUserChooser(project, key, server)
        if (!chooser.showAndGet()) return
        assignTo(chooser.chosen() ?: return)
    }

    internal fun assignTo(user: JiraUser) {
        status("Assigning to ${user.name} ...")
        server.run({ server.assign(key, user) }) { result ->
            if (isDisposed) return@run
            result.onSuccess {
                changed()
                load()
            }.onFailure { status(message(it), error = true) }
        }
    }
}

/**
 * Finds a Jira user by typing a part of the name. With an [issueKey] only the users that issue can be assigned to
 * are offered.
 */
class JiraUserChooser(project: Project, private val issueKey: String?, private val server: JiraServer = LiveJira) : DialogWrapper(project) {

    private val field = JBTextField()
    private val model = CollectionListModel<JiraUser>()
    private val list = JBList(model)
    private val statusLabel = JBLabel(" ")
    private var generation = 0

    init {
        title = if (issueKey.isNullOrBlank()) "Choose a Jira User" else "Assign $issueKey To"
        setOKButtonText("Choose")
        init()
        search()
    }

    fun chosen(): JiraUser? = list.selectedValue

    override fun getPreferredFocusedComponent(): JComponent = field

    override fun createCenterPanel(): JComponent {
        field.emptyText.text = "Type a part of the name"
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.emptyText.text = "No users"
        statusLabel.font = JBUI.Fonts.smallFont()
        statusLabel.foreground = UIUtil.getContextHelpForeground()
        field.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = search()
        })
        return JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            add(field, BorderLayout.NORTH)
            add(JBScrollPane(list), BorderLayout.CENTER)
            add(statusLabel, BorderLayout.SOUTH)
            preferredSize = Dimension(JBUI.scale(420), JBUI.scale(360))
        }
    }

    internal fun type(text: String) {
        field.text = text
    }

    internal fun offered(): List<JiraUser> = model.items

    internal fun select(index: Int) {
        list.selectedIndex = index
    }

    /** Asks the server for the users that match the field; an answer to an older text is dropped. */
    private fun search() {
        val query = field.text.trim()
        val mine = ++generation
        statusLabel.foreground = UIUtil.getContextHelpForeground()
        statusLabel.text = "Searching ..."
        server.run({ server.users(issueKey, query) }) { result ->
            if (mine != generation || isDisposed) return@run
            result.onSuccess { users ->
                model.replaceAll(users)
                if (users.isNotEmpty()) list.selectedIndex = 0
                statusLabel.text = if (users.isEmpty()) "No user matches" else "${users.size} users"
            }.onFailure {
                statusLabel.foreground = JBColor.RED
                statusLabel.text = message(it)
            }
        }
    }

    override fun doValidate(): ValidationInfo? = if (chosen() == null) ValidationInfo("Choose a user", list) else null
}

/** A new issue: project, type, summary and description. [created] is the key of the issue after OK. */
class JiraNewIssueDialog(project: Project, private val server: JiraServer = LiveJira) : DialogWrapper(project) {

    private val projectBox = ComboBox<JiraProject>()
    private val typeBox = ComboBox<String>()
    private val summaryField = JBTextField()
    private val descriptionArea = JBTextArea(8, 50)
    private val statusLabel = JBLabel(" ")
    private var generation = 0

    var created: String? = null
        private set

    init {
        title = "New Jira Issue"
        setOKButtonText("Create")
        init()
        status("Loading the projects ...")
        server.run({ server.projects() }) { result ->
            if (isDisposed) return@run
            result.onSuccess { projects ->
                projectBox.model = DefaultComboBoxModel(projects.toTypedArray())
                status(if (projects.isEmpty()) "No project is visible to this login." else "")
                loadTypes()
            }.onFailure { status(message(it), error = true) }
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = summaryField

    override fun getDimensionServiceKey(): String = "io.github.khorkovenko.jenkinsbuilds.JiraNewIssueDialog"

    override fun createCenterPanel(): JComponent {
        projectBox.isSwingPopup = false
        projectBox.addActionListener { loadTypes() }
        descriptionArea.lineWrap = true
        descriptionArea.wrapStyleWord = true
        statusLabel.font = JBUI.Fonts.smallFont()
        return FormBuilder.createFormBuilder()
            .addLabeledComponent("Project:", JPanel(BorderLayout()).apply { add(projectBox, BorderLayout.CENTER) })
            .addLabeledComponent("Issue type:", JPanel(BorderLayout()).apply { add(typeBox, BorderLayout.CENTER) })
            .addLabeledComponent("Summary:", summaryField)
            .addLabeledComponent(JBLabel("Description:"), JBScrollPane(descriptionArea), JBUI.scale(8), true)
            .addComponent(statusLabel)
            .panel.apply { preferredSize = Dimension(JBUI.scale(620), preferredSize.height) }
    }

    private fun status(text: String, error: Boolean = false) {
        statusLabel.foreground = if (error) JBColor.RED else UIUtil.getContextHelpForeground()
        statusLabel.text = text.ifEmpty { " " }
    }

    private fun loadTypes() {
        val chosen = projectBox.selectedItem as? JiraProject ?: return
        val mine = ++generation
        server.run({ server.issueTypes(chosen.key) }) { result ->
            if (mine != generation || isDisposed) return@run
            result.onSuccess { typeBox.model = DefaultComboBoxModel(it.toTypedArray()) }.onFailure { status(message(it), error = true) }
        }
    }

    internal fun fill(summary: String, description: String) {
        summaryField.text = summary
        descriptionArea.text = description
    }

    internal fun choices(): Pair<List<String>, List<String>> =
        (0 until projectBox.itemCount).map { projectBox.getItemAt(it).key } to (0 until typeBox.itemCount).map { typeBox.getItemAt(it) }

    override fun doValidate(): ValidationInfo? = when {
        projectBox.selectedItem == null -> ValidationInfo("Choose the project", projectBox)
        typeBox.selectedItem == null -> ValidationInfo("Choose the issue type", typeBox)
        summaryField.text.isBlank() -> ValidationInfo("Enter the summary", summaryField)
        else -> null
    }

    /** Creates the issue; the dialog stays open with the server's message when that fails. */
    override fun doOKAction() {
        val chosen = projectBox.selectedItem as? JiraProject ?: return
        val type = typeBox.selectedItem as? String ?: return
        val summary = summaryField.text.trim()
        val description = descriptionArea.text.trim()
        isOKActionEnabled = false
        status("Creating the issue ...")
        server.run({ server.createIssue(chosen.key, type, summary, description) }) { result ->
            if (isDisposed) return@run
            isOKActionEnabled = true
            result.onSuccess {
                created = it
                super@JiraNewIssueDialog.doOKAction()
            }.onFailure { status(message(it), error = true) }
        }
    }
}

/**
 * The Jira step of an auto-build rule: which issue, whom to assign it to and what to comment after a successful
 * build. The link to the Jenkins build is always added to the comment. [applyTo] writes the choices into a rule.
 */
class JiraActionDialog(
    private val project: Project,
    rule: io.github.khorkovenko.jenkinsbuilds.auto.AutoBuildRule,
    private val server: JiraServer = LiveJira,
) : DialogWrapper(project) {

    private val enabledBox = com.intellij.ui.components.JBCheckBox("After a successful build, update a Jira issue", rule.jiraEnabled)
    private val issueField = JBTextField(rule.jiraIssue)
    private val issueButton = JButton("Choose...")
    private val assigneeLabel = JBLabel()
    private val chooseButton = JButton("Choose...")
    private val nobodyButton = JButton("Do Not Reassign")
    private val commentArea = JBTextArea(rule.jiraComment, 5, 40)
    private var assignee: JiraUser? = io.github.khorkovenko.jenkinsbuilds.auto.AutoBuildJira.assignee(rule)
    private val derived: String? = io.github.khorkovenko.jenkinsbuilds.auto.AutoBuildJira.issueKey(rule.copy().also { it.jiraIssue = "" })

    init {
        title = "Jira After the Build of ${rule.job}"
        init()
    }

    override fun createCenterPanel(): JComponent {
        issueField.emptyText.text = if (derived != null) "$derived (from the branch name)" else "For example CP-372; empty: the key in the branch name"
        commentArea.lineWrap = true
        commentArea.wrapStyleWord = true
        commentArea.emptyText.text = "For example: Deployed to the test server, ready for testing"
        issueButton.toolTipText = "Search Jira and pick the issue from a list"
        issueButton.addActionListener {
            val chooser = JiraIssueChooser(project, server)
            if (chooser.showAndGet()) chooser.chosen()?.let { issueField.text = it }
        }
        chooseButton.addActionListener {
            val key = issueField.text.trim().ifEmpty { derived }
            val chooser = JiraUserChooser(project, key, server)
            if (chooser.showAndGet()) setAssignee(chooser.chosen())
        }
        nobodyButton.addActionListener { setAssignee(null) }
        enabledBox.addActionListener { enable() }
        setAssignee(assignee)
        enable()
        val who = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            add(assigneeLabel)
            add(chooseButton)
            add(nobodyButton)
        }
        val hint = JBLabel("The link to the Jenkins build is added to the comment. Nothing is done when the build fails.").apply {
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getContextHelpForeground()
        }
        return FormBuilder.createFormBuilder()
            .addComponent(enabledBox)
            .addLabeledComponent("Issue:", JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                add(issueField, BorderLayout.CENTER)
                add(issueButton, BorderLayout.EAST)
            })
            .addLabeledComponent("Reassign to:", who)
            .addLabeledComponent(JBLabel("Comment:"), JBScrollPane(commentArea), JBUI.scale(8), true)
            .addComponent(hint)
            .panel.apply { preferredSize = Dimension(JBUI.scale(640), preferredSize.height) }
    }

    internal fun issueText(): String = issueField.text

    private fun enable() {
        val on = enabledBox.isSelected
        listOf(issueField, issueButton, chooseButton, nobodyButton, commentArea).forEach { it.isEnabled = on }
    }

    internal fun setAssignee(user: JiraUser?) {
        assignee = user
        assigneeLabel.text = user?.name ?: "nobody (the assignee stays)"
    }

    internal fun fill(enabled: Boolean, issue: String, comment: String) {
        enabledBox.isSelected = enabled
        issueField.text = issue
        commentArea.text = comment
        enable()
    }

    override fun doValidate(): ValidationInfo? {
        val issue = issueField.text.trim()
        return when {
            !enabledBox.isSelected -> null
            issue.isNotEmpty() && !io.github.khorkovenko.jenkinsbuilds.jira.JiraKeys.isKey(issue) -> ValidationInfo("An issue key looks like CP-372", issueField)
            issue.isEmpty() && derived == null -> ValidationInfo("The branch name has no issue key: enter the issue", issueField)
            else -> null
        }
    }

    /** Writes what the dialog says into [rule]. */
    fun applyTo(rule: io.github.khorkovenko.jenkinsbuilds.auto.AutoBuildRule) {
        rule.jiraEnabled = enabledBox.isSelected
        rule.jiraIssue = issueField.text.trim()
        rule.jiraComment = commentArea.text.trim()
        rule.jiraAssigneeId = assignee?.id ?: ""
        rule.jiraAssigneeName = assignee?.name ?: ""
        rule.jiraAssigneeCloud = assignee?.cloud ?: false
    }
}

/**
 * Picks an issue from a list instead of typing its key: the field takes a query as the Jira tab does (JQL or plain
 * words, Enter to search; empty lists your open issues) and the list below shows what the server found.
 */
class JiraIssueChooser(project: Project, private val server: JiraServer = LiveJira, startQuery: String = "") : DialogWrapper(project) {

    private val field = JBTextField(startQuery)
    private val model = CollectionListModel<JiraIssue>()
    private val list = JBList(model)
    private val statusLabel = JBLabel(" ")
    private var generation = 0

    init {
        title = "Choose a Jira Issue"
        setOKButtonText("Choose")
        init()
        search()
    }

    /** The key of the chosen issue. */
    fun chosen(): String? = list.selectedValue?.key

    override fun getPreferredFocusedComponent(): JComponent = field

    override fun getDimensionServiceKey(): String = "io.github.khorkovenko.jenkinsbuilds.JiraIssueChooser"

    override fun createCenterPanel(): JComponent {
        field.emptyText.text = "JQL such as  project = CP AND status = Open  or plain words; Enter to search. Empty: my open issues"
        // Enter in the field searches; it must not press the Choose button
        field.addActionListener { search() }
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.emptyText.text = "No issues"
        list.cellRenderer = object : ColoredListCellRenderer<JiraIssue>() {
            override fun customizeCellRenderer(l: JList<out JiraIssue>, value: JiraIssue?, index: Int, selected: Boolean, hasFocus: Boolean) {
                if (value == null) return
                append(value.key, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                append("  " + value.summary)
                append("  " + listOf(value.status, value.assignee ?: "unassigned").filter { it.isNotBlank() }.joinToString(", "), SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
        object : com.intellij.ui.DoubleClickListener() {
            override fun onDoubleClick(event: java.awt.event.MouseEvent): Boolean {
                if (chosen() == null) return false
                doOKAction()
                return true
            }
        }.installOn(list)
        statusLabel.font = JBUI.Fonts.smallFont()
        statusLabel.foreground = UIUtil.getContextHelpForeground()
        return JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            add(field, BorderLayout.NORTH)
            add(JBScrollPane(list), BorderLayout.CENTER)
            add(statusLabel, BorderLayout.SOUTH)
            preferredSize = Dimension(JBUI.scale(720), JBUI.scale(420))
        }
    }

    internal fun type(text: String) {
        field.text = text
        search()
    }

    internal fun offered(): List<String> = model.items.map { it.key }

    internal fun select(index: Int) {
        list.selectedIndex = index
    }

    internal fun statusText(): String = statusLabel.text

    private fun search() {
        val jql = JiraQuery.toJql(field.text)
        val mine = ++generation
        statusLabel.foreground = UIUtil.getContextHelpForeground()
        statusLabel.text = "Searching: $jql"
        server.run({ server.search(jql) }) { result ->
            if (mine != generation || isDisposed) return@run
            result.onSuccess { issues ->
                model.replaceAll(issues)
                if (issues.isNotEmpty()) list.selectedIndex = 0
                statusLabel.text = if (issues.isEmpty()) "No issue matches" else "${issues.size} issues. Double-click one, or select it and press Choose."
            }.onFailure {
                statusLabel.foreground = JBColor.RED
                statusLabel.text = message(it)
            }
        }
    }

    override fun doValidate(): ValidationInfo? = if (chosen() == null) ValidationInfo("Choose an issue", list) else null
}
