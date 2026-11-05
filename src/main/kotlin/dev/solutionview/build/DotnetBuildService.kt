package dev.solutionview.build

import com.intellij.build.BuildViewManager
import com.intellij.build.DefaultBuildDescriptor
import com.intellij.build.FilePosition
import com.intellij.build.events.MessageEvent
import com.intellij.build.events.impl.FailureResultImpl
import com.intellij.build.events.impl.FileMessageEventImpl
import com.intellij.build.events.impl.FinishBuildEventImpl
import com.intellij.build.events.impl.FinishEventImpl
import com.intellij.build.events.impl.MessageEventImpl
import com.intellij.build.events.impl.OutputBuildEventImpl
import com.intellij.build.events.impl.StartBuildEventImpl
import com.intellij.build.events.impl.StartEventImpl
import com.intellij.build.events.impl.SuccessResultImpl
import com.intellij.execution.ExecutionException
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import dev.solutionview.DotnetCli
import java.io.File
import java.nio.file.Path

enum class BuildVerb(val title: String, val pastTense: String, val arguments: List<String>) {
    BUILD("Build", "Build", listOf("build")),
    REBUILD("Rebuild", "Rebuild", listOf("build", "--no-incremental")),
    CLEAN("Clean", "Clean", listOf("clean")),
}

/**
 * Runs `dotnet build`, `dotnet build --no-incremental` or `dotnet clean` and shows the result
 * in the platform's Build tool window: a tree of projects with their errors and warnings,
 * plus the raw console output.
 */
@Service(Service.Level.PROJECT)
class DotnetBuildService(private val project: Project) {
    @Volatile
    private var running: OSProcessHandler? = null

    val isRunning: Boolean get() = running?.isProcessTerminated == false

    fun stop() {
        running?.destroyProcess()
    }

    /** [target] is a .sln, .slnx or project file. [label] is shown in the Build tool window. */
    fun start(target: Path, verb: BuildVerb, label: String) {
        if (isRunning) {
            notify("A build is already running.", NotificationType.WARNING)
            return
        }
        val dotnet = DotnetCli.path()
        if (dotnet == null) {
            DotnetCli.resetCache()
            notify(
                "The dotnet CLI was not found. Install the .NET SDK from https://dot.net, " +
                    "or add it to PATH or DOTNET_ROOT, and try again.",
                NotificationType.ERROR,
            )
            return
        }
        ApplicationManager.getApplication().invokeAndWait { FileDocumentManager.getInstance().saveAllDocuments() }

        val commandLine = DotnetCli.commandLine(
            dotnet,
            *verb.arguments.toTypedArray(),
            target.toString(),
            "-nologo",
            "-tl:off",
            "-clp:NoSummary",
            // Compilers print relative paths by default. Full paths make every error clickable.
            "-p:GenerateFullPaths=true",
        ).withWorkingDirectory(target.parent)

        val handler = try {
            OSProcessHandler(commandLine)
        } catch (e: ExecutionException) {
            notify("Could not start dotnet: ${e.message}", NotificationType.ERROR)
            return
        }
        running = handler

        val buildId = Any()
        val viewManager = project.service<BuildViewManager>()
        val descriptor = DefaultBuildDescriptor(buildId, label, target.parent.toString(), System.currentTimeMillis())
            .withRestartAction(RerunAction(project, target, verb, label))
            .withAction(StopAction(handler))
        descriptor.isActivateToolWindowWhenAdded = true
        descriptor.isActivateToolWindowWhenFailed = true

        viewManager.onEvent(buildId, StartBuildEventImpl(descriptor, "${verb.title} ${target.fileName}…"))
        val reporter = DiagnosticReporter(buildId, viewManager, target.parent)

        handler.addProcessListener(object : ProcessListener {
            private val stdout = LineBuffer()
            private val stderr = LineBuffer()

            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                if (outputType == ProcessOutputTypes.SYSTEM) return
                val isStdout = outputType != ProcessOutputTypes.STDERR
                viewManager.onEvent(buildId, OutputBuildEventImpl(buildId, event.text, isStdout))
                (if (isStdout) stdout else stderr).append(event.text).forEach(reporter::accept)
            }

            override fun processTerminated(event: ProcessEvent) {
                stdout.flush()?.let(reporter::accept)
                stderr.flush()?.let(reporter::accept)
                val succeeded = event.exitCode == 0 && !reporter.hasErrors
                reporter.finishProjects()
                val message = when {
                    succeeded -> "${verb.pastTense} succeeded"
                    reporter.hasErrors -> "${verb.title} failed: ${reporter.errorCount} error(s), ${reporter.warningCount} warning(s)"
                    else -> "${verb.title} failed (exit code ${event.exitCode})"
                }
                val result = if (succeeded) SuccessResultImpl() else FailureResultImpl()
                viewManager.onEvent(buildId, FinishBuildEventImpl(buildId, null, System.currentTimeMillis(), message, result))
                running = null
            }
        })
        handler.startNotify()
    }

    private fun notify(message: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("Solution View")
            .createNotification(message, type)
            .notify(project)
    }

    private class RerunAction(
        private val project: Project,
        private val target: Path,
        private val verb: BuildVerb,
        private val label: String,
    ) : DumbAwareAction("Run Again", null, AllIcons.Actions.Rerun) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = !project.service<DotnetBuildService>().isRunning
        }
        override fun actionPerformed(e: AnActionEvent) = project.service<DotnetBuildService>().start(target, verb, label)
    }

    private class StopAction(private val handler: OSProcessHandler) :
        DumbAwareAction("Stop", null, AllIcons.Actions.Suspend) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = !handler.isProcessTerminated
        }
        override fun actionPerformed(e: AnActionEvent) = handler.destroyProcess()
    }
}

/** Splits streamed process output into whole lines. */
internal class LineBuffer {
    private val pending = StringBuilder()

    fun append(text: String): List<String> {
        pending.append(text)
        val lines = mutableListOf<String>()
        while (true) {
            val end = pending.indexOf("\n")
            if (end < 0) break
            lines += pending.substring(0, end).trimEnd('\r')
            pending.delete(0, end + 1)
        }
        return lines
    }

    fun flush(): String? = pending.toString().takeIf { it.isNotBlank() }.also { pending.setLength(0) }
}

/**
 * Turns parsed MSBuild diagnostics into Build tool window events, grouped by project,
 * as Rider groups them.
 */
private class DiagnosticReporter(
    private val buildId: Any,
    private val viewManager: BuildViewManager,
    private val workDir: Path,
) {
    private val seen = HashSet<MsBuildDiagnostic>()
    private val projectNodes = LinkedHashMap<String, Boolean>() // node id -> has errors
    var errorCount = 0
        private set
    var warningCount = 0
        private set
    val hasErrors: Boolean get() = errorCount > 0

    fun accept(line: String) {
        val diagnostic = MsBuildOutputParser.parse(line) ?: return
        // MSBuild repeats each message when several projects or targets report it.
        if (!seen.add(diagnostic)) return
        if (diagnostic.severity == Severity.ERROR) errorCount++ else warningCount++

        val parent = diagnostic.project?.let(::projectNode) ?: buildId
        val kind = if (diagnostic.severity == Severity.ERROR) MessageEvent.Kind.ERROR else MessageEvent.Kind.WARNING
        val title = listOfNotNull(diagnostic.code, diagnostic.message).joinToString(": ")
        val group = when {
            diagnostic.code == null -> "Build"
            diagnostic.code.startsWith("CS") -> "C# compiler"
            diagnostic.code.startsWith("FS") -> "F# compiler"
            diagnostic.code.startsWith("BC") -> "Visual Basic compiler"
            diagnostic.code.startsWith("NU") -> "NuGet"
            diagnostic.code.startsWith("MSB") -> "MSBuild"
            else -> "Analyzers"
        }

        val file = diagnostic.file?.let { resolveFile(it, diagnostic.project) }
        val event = if (file != null) {
            val position = FilePosition(file, (diagnostic.line - 1).coerceAtLeast(0), (diagnostic.column - 1).coerceAtLeast(0))
            FileMessageEventImpl(parent, kind, group, title, line, position)
        } else {
            MessageEventImpl(parent, kind, group, title, line)
        }
        if (diagnostic.severity == Severity.ERROR && parent != buildId) projectNodes[parent as String] = true
        viewManager.onEvent(buildId, event)
    }

    fun finishProjects() {
        for ((id, failed) in projectNodes) {
            val result = if (failed) FailureResultImpl() else SuccessResultImpl()
            viewManager.onEvent(buildId, FinishEventImpl(id, buildId, System.currentTimeMillis(), id.substringAfterLast('|'), result))
        }
    }

    private fun projectNode(projectPath: String): String {
        val id = "project|$projectPath|${File(projectPath).nameWithoutExtension}"
        if (!projectNodes.containsKey(id)) {
            projectNodes[id] = false
            viewManager.onEvent(buildId, StartEventImpl(id, buildId, System.currentTimeMillis(), File(projectPath).nameWithoutExtension))
        }
        return id
    }

    private fun resolveFile(raw: String, project: String?): File {
        val file = File(raw)
        if (file.isAbsolute) return file
        val base = project?.let { File(it).parentFile } ?: workDir.toFile()
        return File(base, raw)
    }
}
