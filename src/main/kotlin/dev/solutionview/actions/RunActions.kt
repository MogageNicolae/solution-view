package dev.solutionview.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import dev.solutionview.ProjectState
import dev.solutionview.SolutionService
import dev.solutionview.model.MsBuildProject
import dev.solutionview.model.SolutionProject
import dev.solutionview.run.DotnetRunConfigurations

private fun loaded(e: AnActionEvent, project: SolutionProject): MsBuildProject? {
    val ideProject = e.project ?: return null
    return (SolutionService.getInstance(ideProject).projectState(project.path) as? ProjectState.Loaded)?.project
}

/** "Run 'App'" on a project you can start. */
class RunProjectAction : DumbAwareAction(AllIcons.Actions.Execute) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val selected = selectedValues(e).firstNotNullOfOrNull { it as? SolutionProject }
        val runnable = selected != null && loaded(e, selected)?.isExecutable == true
        e.presentation.isEnabledAndVisible = runnable
        if (runnable) e.presentation.text = "Run '${selected!!.name}'"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selected = selectedValues(e).firstNotNullOfOrNull { it as? SolutionProject } ?: return
        DotnetRunConfigurations.run(project, DotnetRunConfigurations.findOrCreateRun(project, selected))
    }
}
