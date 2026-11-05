package dev.solutionview

import com.intellij.openapi.util.IconLoader
import dev.solutionview.model.ProjectKind
import javax.swing.Icon

/** Our own icons. Rider's icons are proprietary, so we can't reuse them. */
object SolutionIcons {
    @JvmField
    val Solution: Icon = load("solution")

    @JvmField
    val CSharpProject: Icon = load("csharpProject")

    @JvmField
    val FSharpProject: Icon = load("fsharpProject")

    @JvmField
    val VbProject: Icon = load("vbProject")

    @JvmField
    val Project: Icon = load("project")

    fun forProject(kind: ProjectKind): Icon = when (kind) {
        ProjectKind.CSHARP -> CSharpProject
        ProjectKind.FSHARP -> FSharpProject
        ProjectKind.VISUAL_BASIC -> VbProject
        ProjectKind.SHARED, ProjectKind.OTHER -> Project
    }

    private fun load(name: String): Icon = IconLoader.getIcon("/icons/$name.svg", SolutionIcons::class.java)
}
