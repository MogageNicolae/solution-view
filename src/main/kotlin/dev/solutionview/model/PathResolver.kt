package dev.solutionview.model

import java.nio.file.Files
import java.nio.file.Path

/**
 * Turns the relative paths found in .sln, .slnx and MSBuild files into absolute paths.
 *
 * Solution files written on Windows always use `\` and often use a different letter case
 * from the files on disk. Windows and macOS do not care about case, but Linux does.
 * So when the exact path does not exist, we walk the path one segment at a time and
 * pick the entry whose name matches when we ignore case.
 */
object PathResolver {
    fun resolve(base: Path, raw: String): Path {
        val relative = raw.trim().replace('\\', '/')
        val direct = base.resolve(relative).normalize()
        if (Files.exists(direct)) return direct
        return resolveIgnoringCase(base.normalize(), relative) ?: direct
    }

    private fun resolveIgnoringCase(base: Path, relative: String): Path? {
        var current = base
        for (segment in relative.split('/')) {
            if (segment.isEmpty() || segment == ".") continue
            if (segment == "..") {
                current = current.parent ?: return null
                continue
            }
            val exact = current.resolve(segment)
            if (Files.exists(exact)) {
                current = exact
                continue
            }
            if (!Files.isDirectory(current)) return null
            current = Files.list(current).use { entries ->
                entries.filter { it.fileName.toString().equals(segment, ignoreCase = true) }.findFirst().orElse(null)
            } ?: return null
        }
        return current
    }
}
