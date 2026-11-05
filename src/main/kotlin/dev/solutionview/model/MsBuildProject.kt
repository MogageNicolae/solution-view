package dev.solutionview.model

import java.nio.file.Path

/** What we know about one project file after MSBuild (or the fallback reader) looked at it. */
data class MsBuildProject(
    val projectPath: Path,
    /** Files that belong to the project: Compile, None, Content, EmbeddedResource, ... */
    val items: List<ProjectItem>,
    val targetFrameworks: List<String>,
    val packages: List<PackageReference>,
    val projectReferences: List<Path>,
    val outputType: String?,
    /** How the data was produced. [LoadSource.MSBUILD] is the exact answer. */
    val source: LoadSource,
    /** Set when MSBuild failed and we fell back to reading the XML. */
    val error: String? = null,
    /** True for test projects (the test SDK sets `IsTestProject`). */
    val isTestProject: Boolean = false,
) {
    /** True for projects `dotnet run` can start. */
    val isExecutable: Boolean
        get() = !isTestProject && outputType?.lowercase() in setOf("exe", "winexe")

    val directory: Path get() = projectPath.parent
}

enum class LoadSource { MSBUILD, XML_FALLBACK }

data class ProjectItem(
    val path: Path,
    val itemType: String,
    /** MSBuild `Link` metadata: where a file from outside the project folder shows up in the tree. */
    val link: String? = null,
)

data class PackageReference(val name: String, val version: String?)
