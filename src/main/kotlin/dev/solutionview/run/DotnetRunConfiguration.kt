package dev.solutionview.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configuration.EnvironmentVariablesData
import com.intellij.execution.configuration.EnvironmentVariablesTextFieldWithBrowseButton
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.filters.RegexpFilter
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.RawCommandLineEditor
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.execution.ParametersListUtil
import dev.solutionview.DotnetCli
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent

class DotnetRunOptions : RunConfigurationOptions() {
    var projectPath by string()
    var programArguments by string()
    var workingDirectory by string()
    var targetFramework by string()
    var launchProfile by string()
    var environment by map<String, String>()
    var passParentEnvironment by property(true)
}

/** Runs one project with `dotnet run`, which builds it first, as Rider's "Run" does. */
class DotnetRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    RunConfigurationBase<DotnetRunOptions>(project, factory, name) {

    public override fun getOptions(): DotnetRunOptions = super.getOptions() as DotnetRunOptions

    var projectPath: String?
        get() = options.projectPath
        set(value) {
            options.projectPath = value
        }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = DotnetRunSettingsEditor(project)

    override fun checkConfiguration() {
        val path = options.projectPath
        if (path.isNullOrBlank()) throw RuntimeConfigurationError("Choose a project file")
        if (!Files.isRegularFile(Path.of(path))) throw RuntimeConfigurationError("Project file not found: $path")
        if (DotnetCli.path() == null) throw RuntimeConfigurationError("The dotnet CLI was not found")
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
        object : CommandLineState(environment) {
            init {
                addConsoleFilters(*dotnetConsoleFilters(project))
            }

            override fun startProcess(): ProcessHandler {
                val dotnet = DotnetCli.path() ?: throw ExecutionException("The dotnet CLI was not found")
                val projectFile = Path.of(options.projectPath ?: throw ExecutionException("No project file"))
                val args = buildList {
                    add("run")
                    add("--project")
                    add(projectFile.toString())
                    options.targetFramework?.takeIf { it.isNotBlank() }?.let { add("--framework"); add(it) }
                    options.launchProfile?.takeIf { it.isNotBlank() }?.let { add("--launch-profile"); add(it) }
                    val programArgs = ParametersListUtil.parse(options.programArguments.orEmpty())
                    if (programArgs.isNotEmpty()) {
                        add("--")
                        addAll(programArgs)
                    }
                }
                val workDir = options.workingDirectory?.takeIf { it.isNotBlank() } ?: projectFile.parent.toString()
                val commandLine: GeneralCommandLine = DotnetCli.commandLine(dotnet, *args.toTypedArray())
                    .withWorkingDirectory(Path.of(workDir))
                    .withEnvironment(options.environment)
                if (!options.passParentEnvironment) {
                    commandLine.withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE)
                }
                val handler = KillableColoredProcessHandler(commandLine)
                // Ctrl+C first, so the app can shut down cleanly. Stop again to kill it.
                handler.setShouldKillProcessSoftly(true)
                ProcessTerminatedListener.attach(handler)
                return handler
            }
        }
}

/** Makes compiler errors and exception stack traces in the console clickable. */
internal fun dotnetConsoleFilters(project: Project) = arrayOf(
    // Stack trace frame: "   at App.Program.Main() in /src/App/Program.cs:line 12"
    RegexpFilter(project, "in ${RegexpFilter.FILE_PATH_MACROS}:line ${RegexpFilter.LINE_MACROS}"),
    // MSBuild error: "/src/App/Program.cs(12,5): error CS0103: ..."
    RegexpFilter(project, "${RegexpFilter.FILE_PATH_MACROS}\\(${RegexpFilter.LINE_MACROS},${RegexpFilter.COLUMN_MACROS}\\)"),
)

private class DotnetRunSettingsEditor(private val project: Project) : SettingsEditor<DotnetRunConfiguration>() {
    private val projectField = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.createSingleFileDescriptor()
                .withFileFilter { it.extension?.endsWith("proj") == true }
                .withTitle("Select Project File"),
        )
    }
    private val argumentsField = RawCommandLineEditor()
    private val workingDirField = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Working Directory"))
    }
    private val frameworkField = JBTextField()
    private val launchProfileField = JBTextField()
    private val environmentField = EnvironmentVariablesTextFieldWithBrowseButton()

    override fun resetEditorFrom(configuration: DotnetRunConfiguration) {
        val o = configuration.options
        projectField.text = o.projectPath.orEmpty()
        argumentsField.text = o.programArguments.orEmpty()
        workingDirField.text = o.workingDirectory.orEmpty()
        frameworkField.text = o.targetFramework.orEmpty()
        launchProfileField.text = o.launchProfile.orEmpty()
        environmentField.data = EnvironmentVariablesData.create(o.environment, o.passParentEnvironment)
    }

    override fun applyEditorTo(configuration: DotnetRunConfiguration) {
        val o = configuration.options
        o.projectPath = projectField.text.trim().ifEmpty { null }
        o.programArguments = argumentsField.text.ifBlank { null }
        o.workingDirectory = workingDirField.text.trim().ifEmpty { null }
        o.targetFramework = frameworkField.text.trim().ifEmpty { null }
        o.launchProfile = launchProfileField.text.trim().ifEmpty { null }
        o.environment = LinkedHashMap(environmentField.envs)
        o.passParentEnvironment = environmentField.isPassParentEnvs
    }

    override fun createEditor(): JComponent = panel {
        row("Project:") { cell(projectField).align(AlignX.FILL) }
        row("Program arguments:") { cell(argumentsField).align(AlignX.FILL) }
        row("Working directory:") {
            cell(workingDirField).align(AlignX.FILL).comment("Empty means the project folder")
        }
        row("Environment variables:") { cell(environmentField).align(AlignX.FILL) }
        row("Target framework:") {
            cell(frameworkField).align(AlignX.FILL).comment("For multi-target projects, for example net8.0")
        }
        row("Launch profile:") {
            cell(launchProfileField).align(AlignX.FILL).comment("A profile from Properties/launchSettings.json. Empty means the first one")
        }
    }
}
