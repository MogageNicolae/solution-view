package dev.solutionview.view

import com.intellij.ide.SelectInTarget
import com.intellij.ide.dnd.aware.DnDAwareTree
import com.intellij.ide.impl.ProjectViewSelectInTarget
import com.intellij.ide.projectView.ViewSettings
import com.intellij.ide.projectView.impl.AbstractProjectViewPaneWithAsyncSupport
import com.intellij.ide.projectView.impl.ProjectTreeStructure
import com.intellij.ide.projectView.impl.ProjectViewTree
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.ide.util.treeView.AbstractTreeStructureBase
import com.intellij.ide.util.treeView.NodeDescriptor
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.NaturalComparator
import com.intellij.psi.PsiFileSystemItem
import dev.solutionview.SolutionIcons
import dev.solutionview.SolutionListener
import dev.solutionview.SolutionService
import javax.swing.Icon
import javax.swing.tree.DefaultTreeModel

/** The "Solution" tab of the Project tool window, next to the normal "Project" file tree. */
class SolutionViewPane(private val ideProject: Project) : AbstractProjectViewPaneWithAsyncSupport(ideProject) {

    init {
        ideProject.messageBus.connect(this).subscribe(SolutionListener.TOPIC, SolutionListener {
            if (!ideProject.isDisposed) updateFromRoot(true)
        })
    }

    override fun getTitle(): String = "Solution"

    override fun getIcon(): Icon = SolutionIcons.Solution

    override fun getId(): String = ID

    /** Lower weight comes first, so the tab sits left of "Project", as in Rider. */
    override fun getWeight(): Int = -1

    override fun createSelectInTarget(): SelectInTarget = SolutionSelectInTarget(ideProject)

    override fun createStructure(): AbstractTreeStructureBase = object : ProjectTreeStructure(ideProject, ID) {
        override fun createRoot(project: Project, settings: ViewSettings): AbstractTreeNode<*> =
            SolutionRootNode(project, settings)

        override fun isShowLibraryContents(): Boolean = false
        override fun isShowModules(): Boolean = false
        override fun isFlattenModules(): Boolean = false
        override fun isShowScratchesAndConsoles(): Boolean = false
    }

    override fun createTree(treeModel: DefaultTreeModel): DnDAwareTree = ProjectViewTree(treeModel)

    /** Dependencies first, then solution folders and folders, then projects, then files. */
    override fun createComparator(): Comparator<NodeDescriptor<*>> = Comparator { a, b ->
        val byWeight = a.weight.compareTo(b.weight)
        if (byWeight != 0) byWeight else NaturalComparator.INSTANCE.compare(a.toString(), b.toString())
    }

    override fun isFileNestingEnabled(): Boolean = true

    override fun isDefaultPane(project: Project): Boolean = SolutionService.getInstance(project).solution != null

    override fun supportsFoldersAlwaysOnTop(): Boolean = false

    override fun supportsSortByType(): Boolean = false

    override fun supportsCompactDirectories(): Boolean = false

    override fun addToolbarActions(actionGroup: DefaultActionGroup) {
        val actions = ActionManager.getInstance()
        listOf("SolutionView.ShowAllFiles", "SolutionView.SelectSolution", "SolutionView.Reload")
            .mapNotNull(actions::getAction)
            .forEach(actionGroup::addAction)
    }

    companion object {
        const val ID = "SolutionPane"
    }
}

private class SolutionSelectInTarget(private val ideProject: Project) : ProjectViewSelectInTarget(ideProject) {
    override fun toString(): String = "Solution"

    override fun getMinorViewId(): String = SolutionViewPane.ID

    override fun getWeight(): Float = 0.5f

    override fun canSelect(file: PsiFileSystemItem): Boolean {
        val virtualFile = file.virtualFile ?: return false
        return SolutionService.getInstance(ideProject).containsFile(virtualFile)
    }
}
