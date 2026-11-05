package dev.solutionview.model

import java.nio.file.Path

/**
 * Arranges the flat MSBuild item list into a folder tree, the way Rider shows a project.
 *
 * Folder keys are relative paths joined with `/` on every OS ("" is the project folder).
 * Files from outside the project folder go where their `Link` metadata says, or at the top.
 */
class ProjectContents(val project: MsBuildProject) {
    private val childDirs = HashMap<String, MutableSet<String>>()
    private val filesByDir = HashMap<String, MutableList<Path>>()
    private val included = HashSet<Path>()

    init {
        val root = project.directory.normalize()
        for (item in project.items) {
            val path = item.path.normalize()
            if (item.itemType == "Folder") {
                if (path.startsWith(root)) registerDir(segments(root.relativize(path)))
                continue
            }
            if (!included.add(path)) continue
            val location: List<String> = when {
                path.startsWith(root) -> segments(root.relativize(path)).dropLast(1)
                item.link != null -> item.link.split('/').filter { it.isNotEmpty() }.dropLast(1)
                else -> emptyList()
            }
            registerDir(location)
            filesByDir.getOrPut(key(location)) { mutableListOf() }.add(path)
        }
    }

    fun childDirectories(relativeDir: String): List<String> = childDirs[relativeDir].orEmpty().sorted()

    fun files(relativeDir: String): List<Path> = filesByDir[relativeDir].orEmpty()

    fun isIncluded(path: Path): Boolean = path.normalize() in included

    fun hasDirectory(relativeDir: String): Boolean = relativeDir.isEmpty() || childDirs.containsKey(relativeDir) ||
        filesByDir.containsKey(relativeDir)

    private fun registerDir(segments: List<String>) {
        for (i in segments.indices) {
            val parent = key(segments.subList(0, i))
            childDirs.getOrPut(parent) { sortedSetOf() } += segments[i]
            childDirs.getOrPut(key(segments.subList(0, i + 1))) { sortedSetOf() }
        }
    }

    companion object {
        fun key(segments: List<String>): String = segments.joinToString("/")

        fun segments(relative: Path): List<String> =
            (0 until relative.nameCount).map { relative.getName(it).toString() }.filter { it.isNotEmpty() }

        fun child(parent: String, name: String): String = if (parent.isEmpty()) name else "$parent/$name"
    }
}
