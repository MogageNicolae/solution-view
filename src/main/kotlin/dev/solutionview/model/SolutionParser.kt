package dev.solutionview.model

import org.w3c.dom.Element
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/** Reads classic `.sln` files and the newer XML `.slnx` files into a [Solution]. */
object SolutionParser {
    private const val SOLUTION_FOLDER_TYPE = "2150E333-8FDC-42A3-9474-1A3956D46DE8"

    private val PROJECT_LINE = Regex(
        """^Project\("\{([^}]+)}"\)\s*=\s*"([^"]*)"\s*,\s*"([^"]*)"\s*,\s*"\{([^}]+)}"""",
    )
    private val NESTED_LINE = Regex("""^\{([^}]+)}\s*=\s*\{([^}]+)}$""")
    private val SOLUTION_ITEM_LINE = Regex("""^(.+?)\s*=\s*(.+)$""")

    fun isSolutionFile(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext == "sln" || ext == "slnx"
    }

    fun parse(path: Path): Solution {
        val text = Files.readString(path)
        return if (path.fileName.toString().endsWith(".slnx", ignoreCase = true)) {
            parseSlnx(path, text)
        } else {
            parseSln(path, text)
        }
    }

    // ---- .sln ------------------------------------------------------------------------------

    private class RawEntry(
        val guid: String,
        val isFolder: Boolean,
        val name: String,
        val relativePath: String,
        val files: MutableList<String> = mutableListOf(),
    )

    fun parseSln(path: Path, text: String): Solution {
        val baseDir = path.toAbsolutePath().parent
        val entries = LinkedHashMap<String, RawEntry>()
        val parentOf = HashMap<String, String>()

        var current: RawEntry? = null
        var inSolutionItems = false
        var inNestedProjects = false

        for (rawLine in text.removePrefix("﻿").lineSequence()) {
            val line = rawLine.trim()
            when {
                line.startsWith("Project(") -> {
                    val match = PROJECT_LINE.find(line) ?: continue
                    val (typeGuid, name, relPath, guid) = match.destructured
                    val entry = RawEntry(
                        guid = guid.uppercase(),
                        isFolder = typeGuid.equals(SOLUTION_FOLDER_TYPE, ignoreCase = true),
                        name = name,
                        relativePath = relPath,
                    )
                    entries[entry.guid] = entry
                    current = entry
                }
                line == "EndProject" -> {
                    current = null
                    inSolutionItems = false
                }
                line.startsWith("ProjectSection(SolutionItems)") -> inSolutionItems = true
                line == "EndProjectSection" -> inSolutionItems = false
                inSolutionItems && current != null -> {
                    SOLUTION_ITEM_LINE.find(line)?.let { current.files += it.groupValues[1] }
                }
                line.startsWith("GlobalSection(NestedProjects)") -> inNestedProjects = true
                line == "EndGlobalSection" -> inNestedProjects = false
                inNestedProjects -> {
                    NESTED_LINE.find(line)?.let {
                        parentOf[it.groupValues[1].uppercase()] = it.groupValues[2].uppercase()
                    }
                }
            }
        }

        val childrenOf = HashMap<String?, MutableList<RawEntry>>()
        for (entry in entries.values) {
            val parent = parentOf[entry.guid]?.takeIf { entries.containsKey(it) }
            childrenOf.getOrPut(parent) { mutableListOf() } += entry
        }

        fun build(entry: RawEntry, seen: Set<String>): SolutionItem? {
            if (entry.isFolder) {
                if (entry.guid in seen) return null // broken file with a nesting loop
                val nextSeen = seen + entry.guid
                return SolutionFolder(
                    name = entry.name,
                    children = childrenOf[entry.guid].orEmpty().mapNotNull { build(it, nextSeen) },
                    files = entry.files.map { PathResolver.resolve(baseDir, it) },
                )
            }
            // Web site projects and similar use URLs or folders. We only show real project files.
            if (entry.relativePath.contains("://")) return null
            if (!entry.relativePath.contains('.')) return null
            return SolutionProject(entry.name, PathResolver.resolve(baseDir, entry.relativePath))
        }

        val roots = childrenOf[null].orEmpty().mapNotNull { build(it, emptySet()) }
        return Solution(path.toAbsolutePath(), roots)
    }

    // ---- .slnx -----------------------------------------------------------------------------

    private class FolderBuilder(val name: String) {
        val folders = LinkedHashMap<String, FolderBuilder>()
        val projects = mutableListOf<SolutionProject>()
        val files = mutableListOf<Path>()

        fun folder(segment: String): FolderBuilder = folders.getOrPut(segment) { FolderBuilder(segment) }

        fun toItems(): List<SolutionItem> =
            folders.values.map { SolutionFolder(it.name, it.toItems(), it.files.toList()) } + projects
    }

    fun parseSlnx(path: Path, text: String): Solution {
        val baseDir = path.toAbsolutePath().parent
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isNamespaceAware = false
        }
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(text.removePrefix("﻿"))))
        val root = FolderBuilder("")

        fun folderFor(fullName: String): FolderBuilder {
            var node = root
            for (segment in fullName.split('/').filter { it.isNotBlank() }) node = node.folder(segment)
            return node
        }

        fun visit(element: Element, target: FolderBuilder) {
            val children = element.childNodes
            for (i in 0 until children.length) {
                val child = children.item(i) as? Element ?: continue
                when (child.tagName) {
                    "Folder" -> {
                        val name = child.getAttribute("Name")
                        // Folder names are full paths such as "/src/libs/". Older previews nested
                        // the elements instead, so a relative name is resolved against the parent.
                        val folder = if (name.startsWith("/")) folderFor(name) else target.folder(name.trim('/'))
                        visit(child, folder)
                    }
                    "Project" -> {
                        val rel = child.getAttribute("Path")
                        if (rel.isBlank()) continue
                        val projectPath = PathResolver.resolve(baseDir, rel)
                        val displayName = child.getAttribute("DisplayName").ifBlank {
                            projectPath.fileName.toString().substringBeforeLast('.')
                        }
                        target.projects += SolutionProject(displayName, projectPath)
                    }
                    "File" -> {
                        val rel = child.getAttribute("Path")
                        if (rel.isNotBlank()) target.files.add(PathResolver.resolve(baseDir, rel))
                    }
                }
            }
        }

        visit(document.documentElement, root)
        return Solution(path.toAbsolutePath(), root.toItems())
    }
}
