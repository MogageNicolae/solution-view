package dev.solutionview

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class SolutionStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        SolutionService.getInstance(project).initialize()
    }
}
