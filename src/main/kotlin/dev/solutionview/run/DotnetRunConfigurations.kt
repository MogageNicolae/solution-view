package dev.solutionview.run

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.project.Project
import dev.solutionview.ProjectState
import dev.solutionview.SolutionService
import dev.solutionview.model.SolutionProject

/** Creates and starts the run configurations the Solution tab and its actions need. */
object DotnetRunConfigurations {
    private val LOG = com.intellij.openapi.diagnostic.logger<DotnetRunConfigurations>()

    /** The ".NET Project" configuration for [solutionProject], created when it does not exist yet. */
    fun findOrCreateRun(project: Project, solutionProject: SolutionProject): RunnerAndConfigurationSettings {
        val runManager = RunManager.getInstance(project)
        val type = DotnetRunConfigurationType.getInstance()
        runManager.getConfigurationSettingsList(type)
            .firstOrNull { (it.configuration as? DotnetRunConfiguration)?.projectPath == solutionProject.path.toString() }
            ?.let { return it }
        val settings = runManager.createConfiguration(solutionProject.name, type.factory)
        (settings.configuration as DotnetRunConfiguration).projectPath = solutionProject.path.toString()
        runManager.addConfiguration(settings)
        return settings
    }

    /**
     * Rider lists a run configuration for every project you can start. We do the same, and pick
     * one as the startup project when nothing is selected yet. Call on the UI thread.
     */
    fun ensureForExecutables(project: Project) {
        if (project.isDisposed) return
        val service = SolutionService.getInstance(project)
        val solution = service.solution ?: return
        val executables = solution.allProjects().filter {
            (service.projectState(it.path) as? ProjectState.Loaded)?.project?.isExecutable == true
        }
        if (executables.isEmpty()) return
        val created = executables.map { findOrCreateRun(project, it) }
        val runManager = RunManager.getInstance(project)
        LOG.info("Run configurations for ${created.joinToString { it.name }}; selected: ${runManager.selectedConfiguration?.name}")
        if (runManager.selectedConfiguration == null) {
            // Prefer the project named like the solution, then the first one.
            runManager.selectedConfiguration = created.firstOrNull { it.name == solution.name } ?: created.first()
        }
    }

    fun run(project: Project, settings: RunnerAndConfigurationSettings) {
        RunManager.getInstance(project).selectedConfiguration = settings
        ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
    }
}
