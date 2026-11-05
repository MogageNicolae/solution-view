package dev.solutionview.actions

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.impl.EditConfigurationsDialog
import com.intellij.execution.impl.ExecutionManagerImpl
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import dev.solutionview.SolutionService
import javax.swing.JComponent

/*
 * Rebased replaces the platform's run widget, run configuration selector, Stop and
 * "Edit Configurations" with empty placeholder actions. These are our own versions,
 * shown in the main toolbar like Rider's: [configuration ▾] [▶] [■].
 */

private fun AnActionEvent.showsRunToolbar(): Boolean {
    val project = project ?: return false
    return SolutionService.getInstance(project).solution != null || RunManager.getInstance(project).allSettings.isNotEmpty()
}

private fun runningDescriptors(project: Project): List<RunContentDescriptor> =
    ExecutionManagerImpl.getInstanceIfCreated(project)
        ?.getRunningDescriptors { true }
        ?.filter { it.processHandler?.isProcessTerminated == false }
        .orEmpty()

/** The run configuration selector: Rider's "startup project" box. */
class RunConfigurationSelectorAction : ComboBoxAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isVisible = e.showsRunToolbar()
        if (project == null) return
        val selected = RunManager.getInstance(project).selectedConfiguration
        e.presentation.text = selected?.name ?: "Add Configuration…"
        e.presentation.icon = selected?.configuration?.icon
        e.presentation.description = "Select the configuration that Run starts"
    }

    override fun createPopupActionGroup(button: JComponent, dataContext: DataContext): DefaultActionGroup {
        val group = DefaultActionGroup()
        val project = CommonDataKeys.PROJECT.getData(dataContext) ?: return group
        val runManager = RunManager.getInstance(project)
        val (temporary, saved) = runManager.allSettings.partition { it.isTemporary }
        for (settings in saved.sortedBy { it.name.lowercase() } + temporary) {
            group.add(SelectConfigurationAction(settings))
        }
        if (group.childrenCount > 0) group.add(Separator.getInstance())
        group.add(EditDotnetConfigurationsAction())
        runManager.selectedConfiguration?.takeIf { it.isTemporary }?.let { group.add(SaveConfigurationAction(it)) }
        return group
    }

    private class SelectConfigurationAction(private val settings: RunnerAndConfigurationSettings) :
        DumbAwareAction(settings.name, null, settings.configuration.icon) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun actionPerformed(e: AnActionEvent) {
            val project = e.project ?: return
            RunManager.getInstance(project).selectedConfiguration = settings
        }
    }

    private class SaveConfigurationAction(private val settings: RunnerAndConfigurationSettings) :
        DumbAwareAction("Save '${settings.name}' Configuration", null, AllIcons.Actions.MenuSaveall) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun actionPerformed(e: AnActionEvent) {
            val project = e.project ?: return
            RunManager.getInstance(project).makeStable(settings)
        }
    }
}

/** Runs the selected configuration. */
class RunSelectedConfigurationAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isVisible = e.showsRunToolbar()
        val selected = project?.let { RunManager.getInstance(it).selectedConfiguration }
        e.presentation.isEnabled = selected != null
        e.presentation.text = if (selected != null) "Run '${selected.name}'" else "Run"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selected = RunManager.getInstance(project).selectedConfiguration ?: return
        ProgramRunnerUtil.executeConfiguration(selected, DefaultRunExecutor.getRunExecutorInstance())
    }
}

/** Stops what is running: the selected configuration first, otherwise everything. */
class StopRunningAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isVisible = e.showsRunToolbar()
        e.presentation.isEnabled = project != null && runningDescriptors(project).isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selected = RunManager.getInstance(project).selectedConfiguration
        val forSelected = selected?.let { s ->
            ExecutionManagerImpl.getInstance(project).getRunningDescriptors { it == s }
                .filter { it.processHandler?.isProcessTerminated == false }
        }.orEmpty()
        (forSelected.ifEmpty { runningDescriptors(project) }).forEach { it.processHandler?.destroyProcess() }
    }
}

class EditDotnetConfigurationsAction : DumbAwareAction("Edit Configurations…", null, AllIcons.Actions.EditSource) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        EditConfigurationsDialog(project).show()
    }
}
