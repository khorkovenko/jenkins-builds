# Jenkins Builds

An IntelliJ Platform plugin that puts Jenkins and Jira into one tool window. Browse jobs, start and follow builds, let a push start a build, run a command when it is done and update the Jira issue. It works in IntelliJ IDEA and the other JetBrains IDEs, version 2025.3 or later.

It was written for corporate servers where other Jenkins plugins fail to connect: an internal address, a self-signed or company certificate, CSRF protection, and a login that has to survive an IDE restart.

In short:

- **Jenkins:** jobs as Jenkins groups them, builds, parameterized builds with branch selectors, build details, artifacts and a console log that follows the build.
- **Auto build on push:** a rule builds a job when you push a branch, then runs a command in a terminal, an SSH session or the Run window, and comments and reassigns a Jira issue after a successful build.
- **Jira:** search with JQL or plain words, read an issue with its comments and attachments, comment, reassign, create.

Needs the bundled Terminal plugin. Git (for the auto build) and the SSH plugin of the IDE (for SSH sessions) are optional.

## Features

- **Jobs grouped the way Jenkins shows them:** views, the sections of sectioned views, folders and multibranch projects, with a status icon and the last build. The first group holds every job of the server with its folders; the views follow. A filter field narrows the tree by job name.
- **Favourite jobs:** every job has a star beside its name: click ☆ to mark it, ★ to unmark it (or right-click the job | Favourite). Favourites are listed again in a **Favourites** group at the top of the tree. The star button on the toolbar, **Show Favourites Only**, hides everything else.
- **Hide folders:** right-click a view, a section or a folder | **Hide Folder** takes it out of the tree. The eye button on the toolbar, **Show Hidden Folders**, shows the hidden ones again, greyed and marked "hidden"; right-click one | **Show Folder Again** brings it back for good. A folder is hidden by its place in the tree, so the same folder under another view stays.
- **Expand All / Collapse All** on the toolbar open or close every view and folder.
- **Favourite branches:** in the build form, a branch selector has a **★ Favourite** checkbox for the value in the field. Favourite values are listed first, are offered even when the server list cannot be loaded, and **Only favourites** shortens the list to them. They are shared by all jobs.
- **Builds of a job:** build name (such as `C0.0.202`) and number, result, start time, duration and who started it. A running build and a queued one are marked.
- **Build:** for a job without parameters after a confirmation; for a parameterized job through a form made from its parameter definitions:
  - text, multi-line text, boolean, choice and password parameters
  - branch selectors (Git Parameter, List Git Branches and similar): a text field over a list of the values the server offers, such as `origin/cp/2.2/dev`. Type to filter, pick a row or type a value yourself. When the list cannot be loaded the field still works as plain text.
- **Queue:** a build you start appears as a "Queued" row with the reason Jenkins gives, and turns into the running build when it starts.
- **Stop** a running build, **cancel** a queued one, **Rebuild** with the parameters of the selected build (the form opens with those values).
- **Build details:**
  - *Summary*: result, timings, node, causes ("Started by user ...", "Rebuilds build #..."), parameters, Git repository, revision and branch, test summary, changes since the last build
  - *Artifacts*: name, size and path; download the selection or everything to a folder with the saved login, open in the browser, copy the link
  - *Console*: the log, following a running build
- **Open in Browser** and **Copy URL** for the selected build or job.
- **Refresh while something runs:** the selected job, its queue items and the selected build are loaded again every few seconds while a build runs or waits. Nothing is polled when all is idle or the tool window is hidden.

- **Auto build on push:** the **Auto** button (lightning) on the toolbar opens a list of rules "when this branch of the project is pushed, build that Jenkins job". Press + and choose: the branch of the project whose push starts the build (a drop-down); the Jenkins job, in the same tree as in the tool window (favourites, all jobs with folders, views, with a filter); and, only for a job with a branch selector such as a `-custom` job, the **branch to build** from a drop-down of the branches the server offers, favourite ones first. The branch to build is preset to the remote name of your branch (`origin/<branch>`) when the job can build it. The other parameters of the job keep their defaults. Each rule has a checkbox, and one checkbox switches the whole feature; the button looks pressed while it is on. A push from the IDE or from a terminal is noticed while the project is open; a fetch, pull or commit does not start a build. The rules are kept per project in the workspace file. Needs the bundled Git plugin.
- **A command per auto-build rule:** the rules are a table with editable columns. **Command** takes a command (click the cell, type or paste, clear it to run nothing). **Run the command** chooses when: *Immediately* (as soon as the push is seen), *After the build has finished* (when the build this rule started has ended, whatever its result) or *Not applied* (the text is kept but nothing is run). **In** chooses where, and a new tab is opened every time: one of the shells of the machine as the Terminal tool window offers them (*Windows PowerShell*, *Command Prompt*, *Git Bash*), *SSH: &lt;name&gt;* for each entry of Settings | Tools | SSH Configurations (the session is opened with the saved login and the command is typed into it once it is connected), or *Run window* (a process of its own through `cmd.exe /c` on Windows and `/bin/sh -c` elsewhere, in the folder of the repository). The names `JENKINS_JOB`, `JENKINS_BRANCH`, `JENKINS_URL` and, after the build, `JENKINS_BUILD_NUMBER`, `JENKINS_BUILD_RESULT` (`SUCCESS`, `FAILURE`, ...) and `JENKINS_BUILD_URL` can be used as `$NAME`, `${NAME}` or `%NAME%`: in a terminal or an SSH session their values are written into the command before it is typed, in the Run window they are environment variables. The command is stored as plain text in the workspace file of the project, so do not put a password into it.
- **Jira after a successful build:** the last column of the rules table. Double-click it (or press the pencil button) to choose an issue (type its key, or press Choose... to search Jira with a query and pick it from a list), a new assignee and a comment. When the build of the rule ends with SUCCESS, the comment is added to the issue with the link to the Jenkins build on a line of its own, and the issue is reassigned if an assignee is set. The issue can be left empty: then the key in the branch name is used (`cp/2.2/CP-372` gives `CP-372`). A failed build changes nothing in Jira.
- **In Progress group:** jobs that are building right now are listed again in an **In Progress** group near the top of the tree, with a spinner. It appears and opens by itself and goes away when nothing is building.
- **Seeing that the auto build is on:** the Auto button is a solid orange square with a white lightning while it is on and a grey outline while it is off, and the tool window button carries a small orange lightning in its lower left corner.
- **Badge on the tool window button:** blue while a build of yours runs or waits in the queue (started from the plugin, or by your user in a job you have open), also while the tool window is hidden. When one of them finishes unseen the badge turns green (red if it did not succeed) and a notification appears; opening the tool window clears it.
- **Coloured, searchable console:** errors and stack traces are red, warnings yellow, `Finished: SUCCESS` and `BUILD SUCCESSFUL` green, build steps blue. The search field above the log marks every match; Enter goes to the next one, Shift+Enter to the previous.
- **Adaptive tool window:** docked at the bottom, the jobs, the builds and the build details stand side by side; docked left or right (or whenever the window is taller than wide), they are stacked. The divider positions are remembered separately for both layouts.

Open it from **View | Tool Windows | Jenkins Builds** (docked at the bottom by default; move it with the tool window's ⋮ menu | Move To). The plugin has no default shortcuts.

## Jira

The tool window has two tabs in its title bar: **Jenkins** and **Jira**.

- **Login:** Settings | Tools | Jenkins Builds has a **Jira** tab beside the Jenkins one: server address, user name, password or token, and Test Connection. Jira Cloud: your e-mail and an API token. Jira Server or Data Center: your user name and password, or leave the user name empty and enter a personal access token. The secret is kept in the IDE password store.
- **One field, like the search page of Jira:** type a query (`assignee = khorkovenko`, `project = CP AND status = Open ORDER BY updated DESC`) and press Enter. Plain words work too: while you type they narrow the list that is shown, and Enter searches Jira for them; an issue key finds that issue. An empty field lists your open issues.
- **Lock:** the lock button on the toolbar freezes the text of the field: it cannot be changed and is still there, and searched again, after the IDE is restarted. Unlock to edit it.
- **An issue:** double-click (or Enter) opens it with its fields, description, attachments and all comments. Every comment is a block of its own under a title bar with the author, the time and its number. There you can add a comment and reassign the issue; the user is found by typing a part of the name. **Refresh** in the top right corner reads the issue again, so new comments appear without closing the window.
- **Pictures, videos and files:** attachments are listed as links, and a picture or video that a comment or the description embeds is a link in that place. A click downloads the file to your Downloads folder with the saved login (an existing file is not overwritten) and a notification offers to show it in its folder. Web addresses in the text open in the browser.
- **New issue:** the + button asks for project, issue type, summary and description.
- Open in Browser and Copy Key for the selected issue.

The plugin uses version 2 of the Jira REST API, which Jira Cloud and Jira Server both understand. It was written against that public API only.

## Setting up the connection

**Settings | Tools | Jenkins Builds**, or the gear button in the tool window.

| Field | Meaning |
| --- | --- |
| Server address | The address of the Jenkins start page, for example `https://jenkins.example.com` or `http://host:8080/jenkins`. A trailing slash, a missing `https://` or a pasted `/login` tail are corrected. |
| User name | Your Jenkins user. Leave it empty for anonymous access. |
| Password or API token | Either works. An API token (Jenkins: your user \| Security \| API Token) is the better choice: it keeps working when the password changes and needs no CSRF crumb. |
| Timeout | Seconds to wait for the server. |
| Builds listed per job | How many of the newest builds the build list loads. |
| Refresh while a build runs | The interval of the automatic refresh, in seconds. |

**Test Connection** tries the values in the fields (saved or not) and shows what the server answered: `Connected to Jenkins 2.x as <user> (HTTP 200 OK)`, or the HTTP status, its reason and the text of the error page.

Where things are stored:

- address, user name and the numbers: `jenkinsBuilds.xml` in the IDE configuration folder, written on Apply or OK
- the password or token: the IDE password store (Settings | Appearance & Behavior | System Settings | Passwords), never in a settings file. If that page is set to "Do not save, forget passwords after restart", the settings page of the plugin shows a warning, because then the IDE itself drops the password on exit.

### Certificates and proxies

The plugin talks to the server through the IDE's own HTTP stack, so:

- a self-signed or company certificate brings up the IDE dialog "Untrusted Server's Certificate"; accept it once and it is kept in **Settings | Tools | Server Certificates**
- the proxy of **Settings | Appearance & Behavior | System Settings | HTTP Proxy** is used

### What the client does to connect reliably

- sends the `Authorization: Basic` header with the first request instead of waiting for a challenge
- fetches the CSRF crumb from `/crumbIssuer/api/json`, sends it with every POST together with the session it belongs to, and fetches a new one when a POST is answered with 403
- builds every address from the one you entered, not from the `url` fields of the answers (those carry the root URL configured inside Jenkins, which is often not the reachable one)
- sends the login only to the configured server, never to a host a redirect points to
- uses `tree=` queries so the answers stay small

## Build and run

Requirements: nothing to install beforehand. The Gradle wrapper downloads Gradle, and JDK 21 is downloaded automatically.

```
./gradlew runIde        # starts a sandbox IDE with the plugin installed
./gradlew test          # unit tests
./gradlew buildPlugin   # builds the installable zip into build/distributions/
./gradlew verifyPlugin  # checks compatibility with recent IDE versions
```

To install a build in your own IDE: Settings | Plugins | ⚙ | Install Plugin from Disk…, then pick the zip.

## Tests

The tests need no Jenkins server:

- `JenkinsParserTest`: the JSON of the job tree, a parameterized job, a build, queue items, crumbs and branch lists, from fixtures in `src/test/resources/fixtures`
- `JenkinsUrlsTest`: address normalization, job paths, form encoding, `tree=` queries
- `JenkinsUiTest`: builds the tool window (both layouts), the settings page and the build form inside a headless IDE
- `JenkinsClientTest`: the HTTP client against an imitation of Jenkins on localhost (Basic login, a crumb bound to a session, an expired crumb, redirects, error pages, console pieces, artifact download)

## Project layout

```
build.gradle.kts                         IntelliJ Platform Gradle Plugin config (target IDE, signing, publishing)
src/main/resources/META-INF/plugin.xml   tool window, settings page, notification group
src/main/kotlin/io/github/khorkovenko/jenkinsbuilds/
  api/JenkinsUrls.kt                     every address and tree= query
  api/JenkinsParser.kt                   JSON answers to the model
  api/JenkinsClient.kt                   HTTP: login, crumb, session, errors
  model/Models.kt                        jobs, views, builds, parameters
  settings/JenkinsSettings.kt            saved settings and the password store
  settings/JenkinsConfigurable.kt        Settings | Tools | Jenkins Builds
  ui/JenkinsPanel.kt                     the tool window
  ui/BuildParametersDialog.kt            the form of a parameterized build
  ui/Format.kt                           texts and the Summary tab
```

## Limits

- Folders are followed five levels deep.
- The sections of a sectioned view are shown when the server lists their jobs, their job names or their regular expression in its API; otherwise the jobs of the view are listed without sections.
- File parameters and the dynamic parameters of the Active Choices plugin are offered as plain text fields.
- The artifact size comes from one HEAD request per artifact, for the first 50 artifacts of a build.
- The console shows the log from its beginning; a very long log takes a while to load.

## License

MIT, see [LICENSE](LICENSE).
