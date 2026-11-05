package dev.solutionview.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import dev.solutionview.SolutionService
import dev.solutionview.build.BuildVerb
import dev.solutionview.build.DotnetBuildService
import dev.solutionview.model.SolutionProject
import dev.solutionview.toNioPathOrNull
import java.nio.file.Path

/**
 * The objects behind the selected Project view nodes. The view hands actions the tree nodes
 * themselves (PlatformCoreDataKeys.SELECTED_ITEMS), so each node is unwrapped to its value.
 */
internal fun selectedValues(e: AnActionEvent): List<Any> =
    e.getData(PlatformCoreDataKeys.SELECTED_ITEMS).orEmpty().mapNotNull { item ->
        (item as? com.intellij.ide.util.treeView.AbstractTreeNode<*>)?.value ?: item
    }

private val AnActionEvent.solutionService: SolutionService? get() = project?.let(SolutionService::getInstance)

// ---- solution-wide build actions ---------------------------------------------------------------

abstract class SolutionBuildAction(private val verb: BuildVerb) : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val solution = e.solutionService?.solution
        // In a right-click menu, only offer it on the solution node, as Rider does.
        val onSolutionNode = selectedValues(e).any { it is dev.solutionview.model.Solution }
        e.presentation.isVisible = solution != null && (!e.isFromContextMenu || onSolutionNode)
        e.presentation.isEnabled = solution != null && project?.service<DotnetBuildService>()?.isRunning == false
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val solution = SolutionService.getInstance(project).solution ?: return
        project.service<DotnetBuildService>().start(solution.path, verb, "${verb.title} solution '${solution.name}'")
    }
}

class BuildSolutionAction : SolutionBuildAction(BuildVerb.BUILD)
class RebuildSolutionAction : SolutionBuildAction(BuildVerb.REBUILD)
class CleanSolutionAction : SolutionBuildAction(BuildVerb.CLEAN)

class StopBuildAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val running = e.project?.service<DotnetBuildService>()?.isRunning == true
        e.presentation.isVisible = e.solutionService?.solution != null
        e.presentation.isEnabled = running
    }

    override fun actionPerformed(e: AnActionEvent) {
        e.project?.service<DotnetBuildService>()?.stop()
    }
}

// ---- project build actions (right-click menu) ----------------------------------------------

/** The project the right-click happened on: a project node, the project file, or a file inside it. */
internal fun selectedProject(e: AnActionEvent): SolutionProject? {
    val project = e.project ?: return null
    val service = SolutionService.getInstance(project)
    selectedValues(e).firstNotNullOfOrNull { it as? SolutionProject }?.let { return it }
    val file = e.getData(CommonDataKeys.VIRTUAL_FILE)?.toNioPathOrNull() ?: return null
    val all = service.solution?.allProjects().orEmpty()
    return all.firstOrNull { it.path == file } ?: service.projectFor(file)
}

abstract class ProjectBuildAction(private val verb: BuildVerb) : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val selected = selectedProject(e)
        e.presentation.isVisible = selected != null
        e.presentation.isEnabled = selected != null && e.project?.service<DotnetBuildService>()?.isRunning == false
        if (selected != null) e.presentation.text = "${verb.title} '${selected.name}'"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selected = selectedProject(e) ?: return
        project.service<DotnetBuildService>().start(selected.path, verb, "${verb.title} project '${selected.name}'")
    }
}

class BuildProjectAction : ProjectBuildAction(BuildVerb.BUILD)
class RebuildProjectAction : ProjectBuildAction(BuildVerb.REBUILD)
class CleanProjectAction : ProjectBuildAction(BuildVerb.CLEAN)

class EditProjectFileAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val selected = selectedValues(e).firstNotNullOfOrNull { it as? SolutionProject }
        e.presentation.isEnabledAndVisible = selected != null
        if (selected != null) e.presentation.text = "Edit '${selected.path.fileName}'"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selected = selectedValues(e).firstNotNullOfOrNull { it as? SolutionProject } ?: return
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(selected.path) ?: return
        FileEditorManager.getInstance(project).openFile(file, true)
    }
}

// ---- Solution tab toolbar ------------------------------------------------------------------

class ShowAllFilesAction : DumbAwareToggleAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean = e.solutionService?.showAllFiles == true

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        e.solutionService?.showAllFiles = state
    }
}

class ReloadSolutionAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        dev.solutionview.DotnetCli.resetCache()
        e.solutionService?.reload()
    }
}

class SelectSolutionAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        // Only useful when the folder holds more than one solution file.
        e.presentation.isEnabledAndVisible = (e.solutionService?.findSolutionFiles()?.size ?: 0) > 1
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return
        val service = SolutionService.getInstance(project)
        val files = service.findSolutionFiles()
        val base = service.solution?.directory
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(files)
            .setTitle("Select Solution")
            .setRenderer(com.intellij.ui.SimpleListCellRenderer.create("") { path: Path ->
                base?.let { runCatching { it.relativize(path).toString() }.getOrNull() } ?: path.toString()
            })
            .setItemChosenCallback { service.selectSolution(it) }
            .createPopup()
            .showInBestPositionFor(e.dataContext)
    }
}
