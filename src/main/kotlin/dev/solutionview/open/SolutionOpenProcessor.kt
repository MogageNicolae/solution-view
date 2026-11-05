package dev.solutionview.open

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.PlatformProjectOpenProcessor
import com.intellij.projectImport.ProjectOpenProcessor
import dev.solutionview.PendingSolutions
import dev.solutionview.SolutionIcons
import dev.solutionview.model.SolutionParser
import dev.solutionview.toNioPathOrNull
import javax.swing.Icon

/**
 * Lets File > Open (and drag and drop on the welcome screen) accept a .sln or .slnx file.
 * It opens the folder that holds the file, then shows that solution in the Solution tab.
 */
class SolutionOpenProcessor : ProjectOpenProcessor() {
    override val name: String = ".NET Solution"

    override val icon: Icon get() = SolutionIcons.Solution

    override fun canOpenProject(file: VirtualFile): Boolean = !file.isDirectory && SolutionParser.isSolutionFile(file.name)

    override fun isProjectFile(file: VirtualFile): Boolean = canOpenProject(file)

    override fun lookForProjectsInDirectory(): Boolean = false

    override suspend fun openProjectAsync(
        virtualFile: VirtualFile,
        projectToClose: Project?,
        forceOpenInNewFrame: Boolean,
    ): Project? {
        val dir = remember(virtualFile) ?: return null
        return PlatformProjectOpenProcessor.getInstance().openProjectAsync(dir, projectToClose, forceOpenInNewFrame)
    }

    private fun remember(solutionFile: VirtualFile): VirtualFile? {
        val dir = solutionFile.parent ?: return null
        val dirPath = dir.toNioPathOrNull() ?: return null
        val filePath = solutionFile.toNioPathOrNull() ?: return null
        PendingSolutions.put(dirPath, filePath)
        return dir
    }
}
