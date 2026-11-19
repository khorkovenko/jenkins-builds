package io.github.khorkovenko.jenkinsbuilds

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.util.SystemInfo
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.khorkovenko.jenkinsbuilds.auto.AutoBuildCommand
import io.github.khorkovenko.jenkinsbuilds.auto.AutoBuildRule

/** The command of an auto-build rule: how it is handed to the shell and what it is told about the build. */
class AutoBuildCommandTest : BasePlatformTestCase() {

    private fun rule(): AutoBuildRule = AutoBuildRule().also {
        it.branch = "cp/2.2/dev"
        it.job = "folder/customersportal-custom"
    }

    fun testTheShellOfTheSystemGetsTheWholeCommand() {
        assertEquals(listOf("cmd.exe", "/c", "echo hi"), AutoBuildCommand.shellCommand("  echo hi ", true))
        assertEquals(listOf("/bin/sh", "-c", "echo hi"), AutoBuildCommand.shellCommand("echo hi", false))
    }

    fun testTheEnvironmentTellsAboutThePushAndLaterAboutTheBuild() {
        val base = "https://jenkins.example.test"
        val before = AutoBuildCommand.environment(rule(), null, null, base)
        assertEquals("folder/customersportal-custom", before["JENKINS_JOB"])
        assertEquals("cp/2.2/dev", before["JENKINS_BRANCH"])
        assertEquals(base, before["JENKINS_URL"])
        assertFalse(before.containsKey("JENKINS_BUILD_NUMBER"))

        val after = AutoBuildCommand.environment(rule(), 207, "FAILURE", base)
        assertEquals("207", after["JENKINS_BUILD_NUMBER"])
        assertEquals("FAILURE", after["JENKINS_BUILD_RESULT"])
        assertEquals("$base/job/folder/job/customersportal-custom/207/", after["JENKINS_BUILD_URL"])
    }

    fun testTheRealShellSeesTheVariables() {
        val environment = AutoBuildCommand.environment(rule(), 207, "FAILURE", "https://jenkins.example.test")
        val dollar = '$'
        val text = if (SystemInfo.isWindows) {
            "echo %JENKINS_BUILD_RESULT%-%JENKINS_BUILD_NUMBER%"
        } else {
            "echo ${dollar}JENKINS_BUILD_RESULT-${dollar}JENKINS_BUILD_NUMBER"
        }
        val line = GeneralCommandLine(AutoBuildCommand.shellCommand(text, SystemInfo.isWindows)).withEnvironment(environment)
        val output = ExecUtil.execAndGetOutput(line)
        assertEquals(output.stderr, 0, output.exitCode)
        assertEquals("FAILURE-207", output.stdout.trim())
    }

    fun testSeveralCommandsInOneLineRunInOrder() {
        val text = if (SystemInfo.isWindows) "echo one && echo two" else "echo one && echo two"
        val output = ExecUtil.execAndGetOutput(GeneralCommandLine(AutoBuildCommand.shellCommand(text, SystemInfo.isWindows)))
        assertEquals(listOf("one", "two"), output.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() })
    }

    fun testACopyOfARuleKeepsItsCommand() {
        val rule = rule()
        assertTrue("a new rule sends its command to the terminal", rule.commandInTerminal)
        rule.command = "deploy.cmd"
        rule.commandAfterBuild = true
        rule.commandInTerminal = false
        rule.copy().let {
            assertEquals("deploy.cmd", it.command)
            assertTrue(it.commandAfterBuild)
            assertFalse(it.commandInTerminal)
        }
    }

    fun testThePlacesACommandCanRunIn() {
        val targets = io.github.khorkovenko.jenkinsbuilds.auto.AutoBuildTargets
        // Windows: what the Terminal tool window offers itself; Git Bash only when it is installed
        assertEquals(listOf("Windows PowerShell", "Command Prompt", "Git Bash"), targets.shells(true, "C:\\Git\\bin\\bash.exe").map { it.label })
        assertEquals(listOf("Windows PowerShell", "Command Prompt"), targets.shells(true, null).map { it.label })
        assertEquals(listOf("powershell.exe"), targets.shellCommand(targets.POWERSHELL))
        assertEquals(listOf("cmd.exe"), targets.shellCommand(targets.CMD))
        assertEquals(listOf("C:\\Git\\bin\\bash.exe", "--login", "-i"), targets.shellCommand(targets.GIT_BASH, "C:\\Git\\bin\\bash.exe"))
        assertNull(targets.shellCommand(targets.GIT_BASH, null))
        assertNull(targets.shellCommand(targets.DEFAULT_SHELL))

        // A rule saved before a place could be chosen
        val old = rule()
        assertEquals(targets.POWERSHELL, targets.effective(old, windows = true))
        assertEquals(targets.DEFAULT_SHELL, targets.effective(old, windows = false))
        old.commandInTerminal = false
        assertEquals(targets.RUN, targets.effective(old, windows = true))

        // The list always ends with the Run window, and a place that is gone is still named
        val all = targets.all(project)
        assertEquals("Run window", all.last().label)
        val ssh = rule().also {
            it.commandTarget = targets.SSH_PREFIX + "gone-id"
            it.commandTargetName = "build-server"
        }
        assertEquals("SSH: build-server (missing)", targets.label(ssh, all))
        // The host comes first, so the entries of one user do not all begin the same; a given name is kept
        assertEquals("build-slave10.example.test:22 (khorkovenko)", targets.sshName("khorkovenko@build-slave10.example.test:22"))
        assertEquals("customers-ng-test.example.test:4322 (khorkovenko)", targets.sshName("khorkovenko@customers-ng-test.example.test:4322 password"))
        assertEquals("host:22 (user)", targets.sshName("user@host:22 key pair"))
        assertEquals("Stage server", targets.sshName(" Stage server "))
        assertEquals("Run window", targets.label(old, all))
    }

    fun testATerminalCommandGetsTheValuesWrittenIntoItsText() {
        val d = '$'
        val environment = AutoBuildCommand.environment(rule(), 207, "SUCCESS", "https://jenkins.example.test")
        // Each way of naming a variable, in any shell
        assertEquals(
            "echo SUCCESS 207 cp/2.2/dev folder/customersportal-custom",
            AutoBuildCommand.substitute("echo ${d}JENKINS_BUILD_RESULT ${d}{JENKINS_BUILD_NUMBER} %JENKINS_BRANCH% ${d}JENKINS_JOB", environment),
        )
        // A longer name is not cut short by a shorter one, and other variables of the shell are left alone
        assertEquals(
            "curl https://jenkins.example.test/job/folder/job/customersportal-custom/207/ ${d}JENKINS_BUILD_URLS ${d}HOME n=${d}(date)",
            AutoBuildCommand.substitute("curl ${d}JENKINS_BUILD_URL ${d}JENKINS_BUILD_URLS ${d}HOME n=${d}(date)", environment),
        )
        // Before the build there is no number yet: the name stays as it is
        val before = AutoBuildCommand.environment(rule(), null, null, "https://jenkins.example.test")
        assertEquals("echo ${d}JENKINS_BUILD_NUMBER cp/2.2/dev", AutoBuildCommand.substitute("echo ${d}JENKINS_BUILD_NUMBER ${d}JENKINS_BRANCH", before))
    }
}
