package io.github.khorkovenko.jenkinsbuilds.auto

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.ssh.config.unified.SshConfig
import com.intellij.ssh.config.unified.SshConfigManager
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.util.concurrency.AppExecutorUtil
import com.jetbrains.plugins.remotesdk.console.RunSshConsoleAction
import com.jetbrains.plugins.remotesdk.console.SshConfigConnector
import com.jetbrains.plugins.remotesdk.console.SshConsoleOptionsProvider
import com.jetbrains.plugins.remotesdk.console.SshTerminalRunnerFactory
import java.util.concurrent.TimeUnit

/**
 * SSH sessions through the SSH plugin of the IDE, the same ones as "New SSH Session..." of the Terminal tool window:
 * the address, the user and the saved password or key come from Settings | Tools | SSH Configurations.
 * Loaded only when that plugin is enabled (see `jenkinsBuilds-ssh.xml`).
 */
class AutoBuildSshImpl(private val project: Project) : AutoBuildSsh {

    private fun configs(): List<SshConfig> = SshConfigManager.getInstance(project).configs

    private fun nameOf(config: SshConfig): String =
        config.name?.takeIf { it.isNotBlank() } ?: config.presentableShortName

    override fun configurations(): List<Pair<String, String>> =
        configs().map { it.id to nameOf(it) }.sortedBy { it.second.lowercase() }

    override fun run(id: String, command: String): String? {
        val config = configs().firstOrNull { it.id == id }
            ?: return "The SSH configuration of this rule does not exist any more, so the command was not run."
        val terminal = AutoBuildTerminal.toolWindow(project) ?: return "The Terminal tool window is not available in this project."
        terminal.activate {
            val contents = terminal.contentManager
            // The session opens its tab when the connection is made: wait for that tab, then type the command into it
            val listener = object : ContentManagerListener {
                override fun contentAdded(event: ContentManagerEvent) {
                    contents.removeContentManagerListener(this)
                    typeWhenReady(event.content, command, 0)
                }
            }
            contents.addContentManagerListener(listener)
            // No tab after a while (wrong password, host away): stop waiting, so a later tab does not get the command
            AppExecutorUtil.getAppScheduledExecutorService().schedule({
                ApplicationManager.getApplication().invokeLater({
                    if (!project.isDisposed) contents.removeContentManagerListener(listener)
                }, ModalityState.any())
            }, CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            RunSshConsoleAction.connectToSsh(project, SshConfigConnector(config), contents) { credentials ->
                SshTerminalRunnerFactory.getInstance(project).createRunner(SshConsoleOptionsProvider.getInstance(project), credentials)
            }
        }
        return null
    }

    /** The terminal of a new tab is attached a moment after the tab appears: try until it is there. */
    private fun typeWhenReady(content: Content, command: String, attempt: Int) {
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed || !content.isValid) return@invokeLater
            val sent = try {
                AutoBuildTerminal.sendTo(project, content, command)
            } catch (_: Throwable) {
                false
            }
            if (!sent && attempt < 40) {
                AppExecutorUtil.getAppScheduledExecutorService().schedule({ typeWhenReady(content, command, attempt + 1) }, 250, TimeUnit.MILLISECONDS)
            }
        }, ModalityState.any())
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 120L
    }
}
