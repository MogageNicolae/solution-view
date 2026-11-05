package dev.solutionview.model

import java.nio.file.Path

/** One entry in a solution: a solution folder or a project. */
sealed interface SolutionItem {
    val name: String
}

/** A virtual folder in the solution. It has no directory on disk. */
data class SolutionFolder(
    override val name: String,
    val children: List<SolutionItem>,
    /** Loose files attached to the folder ("Solution Items"). */
    val files: List<Path>,
) : SolutionItem

data class SolutionProject(
    override val name: String,
    /** Absolute path to the project file (.csproj, .fsproj, ...). */
    val path: Path,
) : SolutionItem {
    val kind: ProjectKind get() = ProjectKind.of(path)
    val directory: Path get() = path.parent
}

enum class ProjectKind {
    CSHARP, FSHARP, VISUAL_BASIC, SHARED, OTHER;

    companion object {
        fun of(path: Path): ProjectKind =
            when (path.fileName.toString().substringAfterLast('.', "").lowercase()) {
                "csproj" -> CSHARP
                "fsproj" -> FSHARP
                "vbproj" -> VISUAL_BASIC
                "shproj", "projitems" -> SHARED
                else -> OTHER
            }
    }
}

data class Solution(
    /** Absolute path to the .sln or .slnx file. */
    val path: Path,
    val items: List<SolutionItem>,
) {
    val name: String get() = path.fileName.toString().substringBeforeLast('.')
    val directory: Path get() = path.parent

    fun allProjects(): List<SolutionProject> {
        val result = mutableListOf<SolutionProject>()
        fun walk(items: List<SolutionItem>) {
            for (item in items) when (item) {
                is SolutionProject -> result += item
                is SolutionFolder -> walk(item.children)
            }
        }
        walk(items)
        return result
    }
}
