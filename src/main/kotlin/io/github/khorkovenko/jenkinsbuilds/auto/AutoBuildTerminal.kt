package io.github.khorkovenko.jenkinsbuilds.auto

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import com.intellij.terminal.frontend.view.TerminalView
import com.intellij.terminal.ui.TerminalWidget
import com.intellij.ui.content.Content
import org.jetbrains.plugins.terminal.LocalTerminalDirectRunner
import org.jetbrains.plugins.terminal.TerminalTabState
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.io.File

private val LOG = logger<AutoBuildTerminal>()

/**
 * Where the command of an auto-build rule can run: a new terminal tab of one of the shells of this machine, a new SSH
 * session of one of the SSH configurations of the IDE, or a process of its own shown in the Run tool window.
 * A place is stored in the rule by its [Target.id].
 */
object AutoBuildTargets {

    const val RUN = "run"
    const val POWERSHELL = "shell:powershell"
    const val CMD = "shell:cmd"
    const val GIT_BASH = "shell:gitbash"
    const val DEFAULT_SHELL = "shell:default"
    const val BASH = "shell:bash"
    const val ZSH = "shell:zsh"
    const val SSH_PREFIX = "ssh:"
    const val SSH_LABEL = "SSH: "

    /**
     * The name of an SSH configuration with what tells it apart first: `user@host:22 password` becomes
     * `host:22 (user)`, because a list of configurations of one user is otherwise a column of the same beginnings.
     * The word the IDE adds for the kind of login (`password`, `key pair`) is left out. A name the user gave the
     * configuration is kept as it is.
     */
    fun sshName(name: String): String {
        val match = Regex("^([^@\\s]+)@(\\S+)(\\s.*)?$").find(name.trim()) ?: return name.trim()
        return "${match.groupValues[2]} (${match.groupValues[1]})"
    }

    class Target(val id: String, val label: String) {
        override fun toString(): String = label
    }

    /** `bash.exe` of Git for Windows, or null when it is not installed in one of its usual places. */
    fun gitBash(): String? = listOfNotNull(
        System.getenv("ProgramFiles")?.let { "$it\\Git\\bin\\bash.exe" },
        System.getenv("ProgramFiles(x86)")?.let { "$it\\Git\\bin\\bash.exe" },
        System.getenv("LOCALAPPDATA")?.let { "$it\\Programs\\Git\\bin\\bash.exe" },
    ).firstOrNull { File(it).isFile }

    /** The local shells a new terminal tab can be opened with, as the Terminal tool window offers them itself. */
    fun shells(windows: Boolean = SystemInfo.isWindows, gitBash: String? = if (windows) gitBash() else null): List<Target> =
        if (windows) {
            listOfNotNull(
                Target(POWERSHELL, "Windows PowerShell"),
                Target(CMD, "Command Prompt"),
                gitBash?.let { Target(GIT_BASH, "Git Bash") },
            )
        } else {
            listOfNotNull(
                Target(DEFAULT_SHELL, "Terminal (default shell)"),
                Target(BASH, "bash").takeIf { File("/bin/bash").isFile },
                Target(ZSH, "zsh").takeIf { File("/bin/zsh").isFile },
            )
        }

    /** Everything a rule can choose from: the local shells, the SSH configurations and the Run window. */
    fun all(project: Project): List<Target> {
        val ssh = try {
            AutoBuildSsh.of(project)?.configurations().orEmpty().map { Target(SSH_PREFIX + it.first, SSH_LABEL + sshName(it.second)) }.sortedBy { it.label.lowercase() }
        } catch (e: Throwable) {
            LOG.info("Cannot list the SSH configurations", e)
            emptyList()
        }
        return shells() + ssh + Target(RUN, "Run window")
    }

    /** The program a terminal tab is started with for a local shell; null for the shell set in the terminal settings. */
    fun shellCommand(id: String, gitBash: String? = gitBash()): List<String>? = when (id) {
        POWERSHELL -> listOf("powershell.exe")
        CMD -> listOf("cmd.exe")
        GIT_BASH -> gitBash?.let { listOf(it, "--login", "-i") }
        BASH -> listOf("/bin/bash")
        ZSH -> listOf("/bin/zsh")
        else -> null
    }

    /**
     * Where the command of [rule] runs. A rule saved before places could be chosen has none: it goes to the Run
     * window if it was set to that, otherwise to the first shell of this machine.
     */
    fun effective(rule: AutoBuildRule, windows: Boolean = SystemInfo.isWindows): String = when {
        rule.commandTarget.isNotEmpty() -> rule.commandTarget
        !rule.commandInTerminal -> RUN
        windows -> POWERSHELL
        else -> DEFAULT_SHELL
    }

    /** The text the drop-down shows for [rule], also when its place is not available any more. */
    fun label(rule: AutoBuildRule, available: List<Target>): String {
        val id = effective(rule)
        available.firstOrNull { it.id == id }?.let { return it.label }
        return if (id.startsWith(SSH_PREFIX)) SSH_LABEL + rule.commandTargetName.ifEmpty { id.removePrefix(SSH_PREFIX) } + " (missing)"
        else rule.commandTargetName.ifEmpty { id } + " (missing)"
    }
}

/** SSH sessions of the IDE. Implemented only when the SSH plugin of the IDE is there, so ask with [of]. */
interface AutoBuildSsh {
    /** Id and name of every SSH configuration (Settings | Tools | SSH Configurations). */
    fun configurations(): List<Pair<String, String>>

    /**
     * Opens a new SSH session tab for the configuration [id] and types [command] into it once it is connected.
     * Returns null when the session was started, or why it was not.
     */
    fun run(id: String, command: String): String?

    companion object {
        fun of(project: Project): AutoBuildSsh? = project.getService(AutoBuildSsh::class.java)
    }
}

/**
 * Types a command into a terminal tab and presses Enter, so it runs in the shell of that tab exactly as if the user
 * had typed it. Works with the classic and with the reworked terminal. To be called on the UI thread.
 */
object AutoBuildTerminal {

    fun toolWindow(project: Project): ToolWindow? = ToolWindowManager.getInstance(project).getToolWindow("Terminal")

    /**
     * Opens a new terminal tab named [title] that runs [shell] (null: the shell of the terminal settings) in [folder]
     * and sends [command] to it. Returns null when it was sent, or why it was not.
     */
    fun runInNewTab(project: Project, title: String, shell: List<String>?, folder: String?, command: String): String? {
        val toolWindow = toolWindow(project) ?: return "The Terminal tool window is not available in this project."
        val state = TerminalTabState()
        state.myTabName = title
        state.myIsUserDefinedTabTitle = true
        state.myShellCommand = shell
        state.myWorkingDirectory = folder
        TerminalToolWindowManager.getInstance(project).createNewSession(LocalTerminalDirectRunner.createTerminalRunner(project), state)
        toolWindow.activate(null)
        // The new tab is the selected one
        val content = toolWindow.contentManagerIfCreated?.selectedContent ?: return "The new terminal tab did not open."
        return if (sendTo(project, content, command)) null else "The new terminal tab does not take commands."
    }

    /** Sends [command] to the terminal of [content]; false when the tab has no terminal yet. */
    fun sendTo(project: Project, content: Content, command: String): Boolean {
        val view = reworked(project, content)
        if (view != null) {
            send(view, command)
            return true
        }
        val widget = TerminalToolWindowManager.findWidgetByContent(content) ?: return false
        send(widget, command)
        return true
    }

    private fun reworked(project: Project, content: Content): TerminalView? = try {
        TerminalToolWindowTabsManager.getInstance(project).tabs.firstOrNull { it.content === content }?.view
    } catch (e: LinkageError) {
        LOG.info("Reworked terminal API is not available, using the classic one", e)
        null
    }

    private fun send(view: TerminalView, command: String) = view.createSendTextBuilder().shouldExecute().send(command)

    private fun send(widget: TerminalWidget, command: String) = widget.sendCommandToExecute(command)
}
