plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "io.github.khorkovenko.jenkinsbuilds"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        // The IDE the plugin is compiled against and launched with by `runIde`
        intellijIdea("2025.3")
        // Optional: the auto build on push watches the Git repositories of the project
        bundledPlugin("Git4Idea")
        // The command of an auto-build rule can be typed into the terminal: classic and reworked terminal API
        bundledPlugin("org.jetbrains.plugins.terminal")
        // Optional: SSH sessions of the SSH configurations of the IDE
        bundledPlugin("org.jetbrains.plugins.remote-run")
        // For the tests that build the tool window, the settings page and the build form inside a headless IDE
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(21)
    compilerOptions { jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY) }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "253"
            untilBuild = provider { null } // no upper bound
        }
        changeNotes = """
            <p><b>1.0.0</b>: first release.</p>
            <ul>
                <li>Jenkins: jobs by view, section and folder with favourites, hidden folders and a group of running jobs;
                    builds, parameterized builds with branch selectors, stop, cancel and rebuild; build details with
                    artifacts, changes and a coloured, searchable console log; a badge for your own builds</li>
                <li>Auto build on push, with a command in a terminal, an SSH session or the Run window, and a Jira step
                    after a successful build</li>
                <li>Jira: search with JQL or words, issues with comments and attachments, comment, reassign, create</li>
            </ul>
        """.trimIndent()
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    pluginVerification {
        ides { recommended() }
    }
}
