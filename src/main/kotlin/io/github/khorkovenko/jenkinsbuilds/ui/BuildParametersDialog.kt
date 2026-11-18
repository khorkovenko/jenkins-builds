package io.github.khorkovenko.jenkinsbuilds.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.CollectionListModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.khorkovenko.jenkinsbuilds.api.JenkinsParser
import io.github.khorkovenko.jenkinsbuilds.model.ParamDef
import io.github.khorkovenko.jenkinsbuilds.model.ParamKind
import io.github.khorkovenko.jenkinsbuilds.settings.JenkinsSettings
import java.awt.BorderLayout
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JList
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants
import javax.swing.event.DocumentEvent

/**
 * The form for "This build requires parameters", made from the parameter definitions of the job.
 * [initial] overrides the default values (used by Rebuild).
 */
class BuildParametersDialog(
    project: Project,
    private val jobFullName: String,
    dialogTitle: String,
    private val definitions: List<ParamDef>,
    private val initial: Map<String, String>,
) : DialogWrapper(project) {

    private val STAR = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor(0xD9A300, 0xE5C04B))
    private val readers = LinkedHashMap<String, () -> String>()
    private var firstEditor: JComponent? = null

    /** The form with the editors; the tests look into it. */
    internal var formPanel: JComponent? = null
        private set

    init {
        title = dialogTitle
        setOKButtonText("Build")
        init()
    }

    /**
     * The values to send, in the order of the definitions. A password that was left empty is not sent, so the server
     * uses the default it keeps for it.
     */
    fun values(): List<Pair<String, String>> = definitions
        .map { it to (readers[it.name]?.invoke() ?: "") }
        .filterNot { (definition, value) -> definition.kind == ParamKind.PASSWORD && value.isEmpty() }
        .map { (definition, value) -> definition.name to value }

    override fun getPreferredFocusedComponent(): JComponent? = firstEditor

    override fun getDimensionServiceKey(): String = "io.github.khorkovenko.jenkinsbuilds.BuildParametersDialog"

    override fun createCenterPanel(): JComponent {
        val form = FormBuilder.createFormBuilder()
        for (definition in definitions) {
            val editor = editor(definition)
            if (firstEditor == null) firstEditor = focusTarget(editor)
            val tall = definition.kind == ParamKind.LIST || definition.kind == ParamKind.TEXT
            form.addLabeledComponent(JBLabel(definition.name + ":"), editor, JBUI.scale(8), tall)
            // Only a description that says something: jobs often carry leftovers such as ".*" there
            definition.description?.let { JenkinsParser.htmlToText(it, 240) }
                ?.takeIf { text -> Regex("\\p{L}{3,}").containsMatchIn(text) }
                ?.let { form.addTooltip(it) }
        }
        val panel = JPanel(BorderLayout()).apply {
            add(form.panel, BorderLayout.NORTH)
            border = JBUI.Borders.empty(4)
        }
        formPanel = panel
        return JBScrollPane(panel).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            val wanted = panel.preferredSize
            preferredSize = Dimension(maxOf(JBUI.scale(520), wanted.width + JBUI.scale(24)), minOf(wanted.height + JBUI.scale(16), JBUI.scale(620)))
        }
    }

    private fun focusTarget(editor: JComponent): JComponent = (editor as? ValueListEditor)?.field ?: editor

    private fun editor(definition: ParamDef): JComponent {
        val start = initial[definition.name] ?: definition.defaultValue ?: ""
        return when (definition.kind) {
            ParamKind.BOOLEAN -> JBCheckBox().also { box ->
                box.isSelected = start.equals("true", ignoreCase = true)
                readers[definition.name] = { box.isSelected.toString() }
            }
            ParamKind.CHOICE -> {
                // A value from an earlier build may no longer be among the choices: keep it selectable
                val items = if (start.isNotEmpty() && start !in definition.choices) listOf(start) + definition.choices else definition.choices
                ComboBox(items.toTypedArray()).also { combo ->
                    combo.isSwingPopup = false // the IDE popup has speed search
                    if (start.isNotEmpty()) combo.selectedItem = start
                    readers[definition.name] = { combo.selectedItem as? String ?: "" }
                }
            }
            ParamKind.PASSWORD -> JBPasswordField().also { field ->
                field.emptyText.text = "Empty: the default of the job"
                readers[definition.name] = { String(field.password) }
            }
            ParamKind.TEXT -> {
                val area = JBTextArea(start, 4, 40)
                readers[definition.name] = { area.text }
                JBScrollPane(area)
            }
            ParamKind.LIST -> ValueListEditor(definition, start).also { editor ->
                readers[definition.name] = { editor.field.text.trim() }
                editor.load()
            }
            ParamKind.STRING -> JBTextField(start).also { field ->
                readers[definition.name] = { field.text }
            }
        }
    }

    /**
     * A text field over a list of the values the server offers. Typing filters the list, choosing a row fills the
     * field; what is sent is always the text of the field, so a value can be entered by hand when the list cannot be
     * loaded.
     */
    private inner class ValueListEditor(private val definition: ParamDef, start: String) : JPanel(BorderLayout(0, JBUI.scale(2))) {
        val field = JBTextField(start)
        private val model = CollectionListModel<String>()
        private val list = JBList(model)
        private val status = JBLabel()
        private var all: List<String> = definition.choices
        private var adjusting = false
        private val settings = JenkinsSettings.getInstance()

        /** What the user typed into the field since the list was last shown whole. */
        private var typed = ""
        private val favouriteBox = JBCheckBox("★ Favourite")
        private val onlyBox = JBCheckBox("Only favourites", settings.onlyFavouriteValues)

        init {
            field.emptyText.text = "Type to filter, or enter a value"
            list.selectionMode = ListSelectionModel.SINGLE_SELECTION
            list.visibleRowCount = 8
            list.cellRenderer = object : ColoredListCellRenderer<String>() {
                override fun customizeCellRenderer(list: JList<out String>, value: String?, index: Int, selected: Boolean, hasFocus: Boolean) {
                    append(value ?: "")
                    if (value != null && settings.isFavouriteValue(value)) append(" ★", STAR)
                }
            }
            status.font = JBUI.Fonts.smallFont()
            status.foreground = UIUtil.getContextHelpForeground()
            favouriteBox.toolTipText = "Keep the value of the field as a favourite: favourites are listed first"
            onlyBox.toolTipText = "List only the favourite values"
            val boxes = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                add(favouriteBox)
                add(onlyBox)
            }
            val bottom = JPanel(BorderLayout()).apply {
                add(boxes, BorderLayout.WEST)
                add(status, BorderLayout.CENTER)
            }
            add(field, BorderLayout.NORTH)
            add(JBScrollPane(list), BorderLayout.CENTER)
            add(bottom, BorderLayout.SOUTH)

            favouriteBox.addActionListener {
                settings.setFavouriteValue(field.text.trim(), favouriteBox.isSelected)
                filter()
            }
            onlyBox.addActionListener {
                settings.onlyFavouriteValues = onlyBox.isSelected
                typed = ""
                filter()
            }

            field.document.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) {
                    if (adjusting) return
                    typed = field.text.trim()
                    filter()
                }
            })
            list.addListSelectionListener {
                val chosen = list.selectedValue
                if (!adjusting && !it.valueIsAdjusting && chosen != null && chosen != field.text) {
                    adjusting = true
                    field.text = chosen
                    favouriteBox.isEnabled = true
                    favouriteBox.isSelected = settings.isFavouriteValue(chosen)
                    adjusting = false
                }
            }
            field.addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    val step = when (e.keyCode) {
                        KeyEvent.VK_DOWN -> 1
                        KeyEvent.VK_UP -> -1
                        else -> return
                    }
                    if (model.size == 0) return
                    val next = (list.selectedIndex + step).coerceIn(0, model.size - 1)
                    list.selectedIndex = next
                    list.ensureIndexIsVisible(next)
                    e.consume()
                }
            })
            filter()
        }

        private fun filter() {
            val value = field.text.trim()
            // Favourites first; they are offered even when the server list is missing or no longer has them
            val favourites = settings.favouriteValues.toList()
            val offered = if (onlyBox.isSelected) favourites else favourites + all.filter { it !in favourites }
            // Only what the user typed narrows the list: the default value and a chosen row leave it whole
            val exact = value in offered
            val shown = if (typed.isEmpty() || exact) offered else offered.filter { it.contains(typed, ignoreCase = true) }
            adjusting = true
            model.replaceAll(shown)
            if (exact) list.setSelectedValue(value, true) else list.clearSelection()
            favouriteBox.isEnabled = value.isNotEmpty()
            favouriteBox.isSelected = settings.isFavouriteValue(value)
            adjusting = false
        }

        fun load() {
            if (all.isNotEmpty()) {
                status.text = "${all.size} values"
                return
            }
            status.text = "Loading the values from the server..."
            ApplicationManager.getApplication().executeOnPooledThread {
                val result = runCatching { JenkinsSettings.getInstance().client().parameterValues(jobFullName, definition) }
                ApplicationManager.getApplication().invokeLater({
                    if (isDisposed) return@invokeLater
                    result.onSuccess { values ->
                        all = values
                        status.foreground = UIUtil.getContextHelpForeground()
                        status.text = if (values.isEmpty()) "The server offers no values. Enter the value by hand." else "${values.size} values"
                        filter()
                    }.onFailure { error ->
                        status.foreground = JBColor.RED
                        status.text = "The values could not be loaded: enter the value by hand. " +
                            JenkinsParser.htmlToText(error.message, 160)
                        status.toolTipText = JenkinsParser.htmlToText(error.message, 600)
                    }
                }, ModalityState.any())
            }
        }
    }
}
