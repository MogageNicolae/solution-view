package dev.solutionview.model

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/** Result of running an external command. */
data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String)

/** Runs a command in a directory. The plugin uses the IDE's process API; tests can fake it. */
fun interface CommandRunner {
    fun run(command: List<String>, workDir: Path): CommandResult
}

/**
 * Finds out which files, packages and frameworks a project has.
 *
 * SDK-style projects add files through globs and imported .props/.targets files. Only MSBuild
 * knows the final list. `dotnet msbuild -getItem` (.NET 8 and later) runs the evaluation
 * phase without building and prints the result as JSON. That takes about a second per project.
 *
 * When `dotnet` is missing or the evaluation fails, we read the project XML and apply the
 * SDK's default globs ourselves. That result is close but not exact.
 */
class MsBuildEvaluator(
    private val dotnet: String?,
    private val runner: CommandRunner,
) {
    fun evaluate(projectPath: Path): MsBuildProject {
        if (dotnet == null) return XmlProjectReader.read(projectPath, "The dotnet CLI was not found")
        val outerJson = try {
            runEvaluation(dotnet, projectPath, targetFramework = null)
        } catch (e: EvaluationException) {
            return XmlProjectReader.read(projectPath, e.message)
        }
        val outer = try {
            MsBuildJson.parse(projectPath, outerJson)
        } catch (e: Exception) {
            return XmlProjectReader.read(projectPath, "Could not read MSBuild output: ${e.message}")
        }
        if (!MsBuildJson.isCrossTargeting(outerJson) || outer.targetFrameworks.isEmpty()) return outer

        // With <TargetFrameworks> (plural), MSBuild evaluates an "outer" project that only
        // dispatches to one "inner" build per framework. The SDK adds its default files in the
        // inner builds only, so the outer result has none. Like Rider, we show the files of one
        // framework: the last one listed, which is usually the newest.
        val framework = outer.targetFrameworks.last()
        return try {
            val inner = MsBuildJson.parse(projectPath, runEvaluation(dotnet, projectPath, framework))
            inner.copy(targetFrameworks = outer.targetFrameworks)
        } catch (e: Exception) {
            outer
        }
    }

    private class EvaluationException(override val message: String) : Exception(message)

    private fun runEvaluation(dotnet: String, projectPath: Path, targetFramework: String?): String {
        val command = buildList {
            add(dotnet)
            add("msbuild")
            add(projectPath.toString())
            add("-nologo")
            targetFramework?.let { add("-property:TargetFramework=$it") }
            PROPERTIES.forEach { add("-getProperty:$it") }
            ITEM_TYPES.forEach { add("-getItem:$it") }
        }
        val result = try {
            runner.run(command, projectPath.parent)
        } catch (e: Exception) {
            throw EvaluationException(e.message ?: e.toString())
        }
        if (result.exitCode != 0) {
            val message = (result.stdout + "\n" + result.stderr).lines().firstOrNull { it.contains("error") }
                ?: "dotnet msbuild exited with code ${result.exitCode}"
            throw EvaluationException(message.trim())
        }
        return result.stdout
    }

    companion object {
        val PROPERTIES = listOf("TargetFramework", "TargetFrameworks", "OutputType", "IsTestProject")
        val FILE_ITEM_TYPES = listOf(
            "Compile", "None", "Content", "EmbeddedResource", "Page", "ApplicationDefinition",
            "Resource", "AdditionalFiles", "AvaloniaResource", "AvaloniaXaml", "Folder",
            // C++ projects (.vcxproj). They usually only load through the XML reader on macOS and Linux.
            "ClCompile", "ClInclude", "ResourceCompile", "Text", "Image", "Natvis", "CustomBuild",
        )
        val ITEM_TYPES = FILE_ITEM_TYPES + listOf("PackageReference", "ProjectReference")
    }
}

/** Parses the JSON that `dotnet msbuild -getProperty:... -getItem:...` prints. */
object MsBuildJson {
    /** True when the JSON comes from the "outer" evaluation of a multi-targeted project. */
    fun isCrossTargeting(json: String): Boolean {
        val start = json.indexOf('{')
        if (start < 0) return false
        val properties = JsonParser.parseString(json.substring(start)).asJsonObject.getAsJsonObject("Properties") ?: return false
        return properties.str("TargetFrameworks") != null && properties.str("TargetFramework") == null
    }

    fun parse(projectPath: Path, json: String): MsBuildProject {
        val start = json.indexOf('{')
        require(start >= 0) { "no JSON object in output" }
        val root = JsonParser.parseString(json.substring(start)).asJsonObject
        val properties = root.getAsJsonObject("Properties") ?: JsonObject()
        val items = root.getAsJsonObject("Items") ?: JsonObject()
        val projectDir = projectPath.parent

        fun itemsOf(type: String): List<JsonObject> =
            items.getAsJsonArray(type)?.mapNotNull { it as? JsonObject }.orEmpty()

        val files = MsBuildEvaluator.FILE_ITEM_TYPES.flatMap { type ->
            itemsOf(type).mapNotNull { item ->
                // NuGet packages and SDK targets add helper files with Visible="false". IDEs hide them.
                if (item.str("Visible").equals("false", ignoreCase = true)) return@mapNotNull null
                val identity = item.str("Identity") ?: return@mapNotNull null
                val fullPath = item.str("FullPath")?.let { Path.of(it) } ?: PathResolver.resolve(projectDir, identity)
                ProjectItem(fullPath.normalize(), type, item.str("Link")?.replace('\\', '/'))
            }
        }

        val frameworks = listOfNotNull(properties.str("TargetFrameworks"), properties.str("TargetFramework"))
            .firstOrNull()
            ?.split(';')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

        return MsBuildProject(
            projectPath = projectPath,
            items = files.distinctBy { it.path to it.itemType },
            targetFrameworks = frameworks,
            packages = itemsOf("PackageReference").mapNotNull { item ->
                item.str("Identity")?.let { PackageReference(it, item.str("Version") ?: item.str("VersionOverride")) }
            },
            projectReferences = itemsOf("ProjectReference").mapNotNull { item ->
                item.str("FullPath")?.let { Path.of(it).normalize() }
                    ?: item.str("Identity")?.let { PathResolver.resolve(projectDir, it) }
            },
            outputType = properties.str("OutputType"),
            source = LoadSource.MSBUILD,
            isTestProject = properties.str("IsTestProject").equals("true", ignoreCase = true),
        )
    }

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.ifBlank { null }
}

/**
 * Reads a project file without MSBuild. It applies the .NET SDK default globs for C# and
 * Visual Basic, and reads the explicit, ordered Compile items for F#.
 */
object XmlProjectReader {
    private val SKIPPED_DIRS = setOf("bin", "obj", ".git", ".vs", ".idea", "node_modules", "packages")

    fun read(projectPath: Path, error: String?): MsBuildProject {
        val projectDir = projectPath.parent
        val root = try {
            parseXml(projectPath)
        } catch (e: Exception) {
            null
        }

        fun elements(tag: String): List<Element> {
            if (root == null) return emptyList()
            val nodes = root.getElementsByTagName(tag)
            return (0 until nodes.length).map { nodes.item(it) as Element }
        }

        fun property(name: String): String? = elements(name).lastOrNull()?.textContent?.trim()?.ifEmpty { null }

        val explicitItems = explicitItems(root, projectDir) + sharedProjectItems(root, projectDir)

        // Only SDK-style projects (<Project Sdk="...">) pick files up through globs. Old-style
        // projects list every file, so globbing them would show files the project does not have.
        val isSdkStyle = root != null && (root.hasAttribute("Sdk") || elements("Sdk").isNotEmpty() ||
            elements("Import").any { it.hasAttribute("Sdk") })
        val globbed = if (!isSdkStyle) emptyList() else when (ProjectKind.of(projectPath)) {
            ProjectKind.CSHARP -> globDefaults(projectDir, "cs")
            ProjectKind.VISUAL_BASIC -> globDefaults(projectDir, "vb")
            else -> emptyList()
        }

        val frameworks = (property("TargetFrameworks") ?: property("TargetFramework"))
            ?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

        return MsBuildProject(
            projectPath = projectPath,
            items = (explicitItems + globbed).distinctBy { it.path },
            targetFrameworks = frameworks,
            packages = elements("PackageReference").mapNotNull { element ->
                val name = element.getAttribute("Include").ifBlank { return@mapNotNull null }
                val version = element.getAttribute("Version").ifBlank {
                    elements("Version").firstOrNull { it.parentNode == element }?.textContent?.trim()
                }
                PackageReference(name, version?.ifBlank { null })
            },
            projectReferences = elements("ProjectReference").mapNotNull { element ->
                element.getAttribute("Include").ifBlank { null }?.let { PathResolver.resolve(projectDir, it) }
            },
            outputType = property("OutputType"),
            source = LoadSource.XML_FALLBACK,
            error = error,
            isTestProject = property("IsTestProject").equals("true", ignoreCase = true) ||
                elements("PackageReference").any { it.getAttribute("Include") == "Microsoft.NET.Test.Sdk" },
        )
    }

    private fun explicitItems(root: Element?, baseDir: Path, thisFileDir: Path = baseDir): List<ProjectItem> {
        if (root == null) return emptyList()
        return MsBuildEvaluator.FILE_ITEM_TYPES.flatMap { type ->
            val nodes = root.getElementsByTagName(type)
            (0 until nodes.length).mapNotNull { i ->
                val element = nodes.item(i) as Element
                val include = element.getAttribute("Include")
                    .replace("\$(MSBuildThisFileDirectory)", thisFileDir.toString() + "/")
                    .ifBlank { return@mapNotNull null }
                if (include.contains('*') || include.contains('$')) return@mapNotNull null
                if (element.getAttribute("Visible").equals("false", ignoreCase = true)) return@mapNotNull null
                ProjectItem(PathResolver.resolve(baseDir, include), type, element.getAttribute("Link").ifBlank { null })
            }
        }
    }

    /**
     * Shared projects (.shproj) keep their files in a .projitems file, imported with
     * Label="Shared". Paths there start with $(MSBuildThisFileDirectory).
     */
    private fun sharedProjectItems(root: Element?, projectDir: Path): List<ProjectItem> {
        if (root == null) return emptyList()
        val imports = root.getElementsByTagName("Import")
        return (0 until imports.length).flatMap { i ->
            val element = imports.item(i) as Element
            val project = element.getAttribute("Project")
            if (!project.endsWith(".projitems", ignoreCase = true) || project.contains('$')) return@flatMap emptyList()
            val file = PathResolver.resolve(projectDir, project)
            val items = try {
                parseXml(file)
            } catch (e: Exception) {
                return@flatMap emptyList()
            }
            explicitItems(items, file.parent, file.parent)
        }
    }

    /** Source files become Compile items, every other file becomes a None item, like the SDK does. */
    private fun globDefaults(projectDir: Path, sourceExtension: String): List<ProjectItem> {
        val result = mutableListOf<ProjectItem>()
        fun walk(dir: Path, isRoot: Boolean) {
            val children = try {
                Files.list(dir).use { it.toList() }
            } catch (e: Exception) {
                return
            }
            // A nested folder with its own project file belongs to that other project.
            if (!isRoot && children.any { it.extension.endsWith("proj") }) return
            for (child in children.sortedBy { it.name }) {
                if (child.isDirectory()) {
                    if (child.name.lowercase() !in SKIPPED_DIRS && !child.name.startsWith(".")) walk(child, false)
                } else if (!child.extension.endsWith("proj") && !child.name.endsWith(".user")) {
                    val type = if (child.extension.equals(sourceExtension, ignoreCase = true)) "Compile" else "None"
                    result += ProjectItem(child.normalize(), type)
                }
            }
        }
        walk(projectDir, true)
        return result
    }

    private fun parseXml(path: Path): Element {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        return Files.newInputStream(path).use { factory.newDocumentBuilder().parse(it).documentElement }
    }
}
