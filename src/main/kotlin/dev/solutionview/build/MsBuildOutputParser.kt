package dev.solutionview.build

enum class Severity { ERROR, WARNING }

data class MsBuildDiagnostic(
    /** Absolute or relative file path, or null for messages that are not about a file. */
    val file: String?,
    /** 1-based, or 0 when MSBuild gave no position. */
    val line: Int,
    val column: Int,
    val severity: Severity,
    val code: String?,
    val message: String,
    /** The project MSBuild names in square brackets at the end of the line. */
    val project: String?,
)

/**
 * Reads MSBuild's "canonical" error format, which compilers and tasks all use:
 *
 *     C:\src\App\Program.cs(12,5): error CS0103: The name 'x' does not exist [C:\src\App\App.csproj]
 *     /src/App/App.csproj : warning NU1603: App depends on Foo (>= 1.0.0) [/src/App/App.csproj]
 *     MSBUILD : error MSB1009: Project file does not exist.
 *
 * The keywords `error` and `warning` are part of the format and are not translated.
 */
object MsBuildOutputParser {
    private val ANSI = Regex("""\u001B\[[0-9;?]*[ -/]*[@-~]""")

    private val WITH_POSITION = Regex(
        """^\s*(?<file>.+?)\((?<line>\d+)(?:,(?<col>\d+))?(?:,\d+,\d+)?\)\s*:\s*""" +
            """(?:[\w ]+\s)?(?<sev>error|warning)\s*(?<code>[A-Za-z]+\d+)?\s*:\s*(?<msg>.*?)""" +
            """(?:\s+\[(?<proj>[^\[\]]+)])?\s*$""",
    )

    private val WITHOUT_POSITION = Regex(
        """^\s*(?<file>.+?)\s*:\s*(?:[\w ]+\s)?(?<sev>error|warning)\s*(?<code>[A-Za-z]+\d+)?\s*:\s*(?<msg>.*?)""" +
            """(?:\s+\[(?<proj>[^\[\]]+)])?\s*$""",
    )

    /** Origins that MSBuild prints in place of a file name. */
    private val TOOL_ORIGINS = setOf("msbuild", "csc", "vbc", "fsc", "dotnet", "nuget", "project")

    fun stripAnsi(text: String): String = ANSI.replace(text, "")

    fun parse(rawLine: String): MsBuildDiagnostic? {
        val line = stripAnsi(rawLine).trimEnd('\r', '\n')
        if (!line.contains("error") && !line.contains("warning")) return null

        WITH_POSITION.matchEntire(line)?.let { m ->
            return MsBuildDiagnostic(
                file = m.groups["file"]!!.value.trim(),
                line = m.groups["line"]!!.value.toInt(),
                column = m.groups["col"]?.value?.toInt() ?: 0,
                severity = severity(m.groups["sev"]!!.value),
                code = m.groups["code"]?.value,
                message = m.groups["msg"]!!.value.trim(),
                project = m.groups["proj"]?.value?.trim(),
            )
        }

        WITHOUT_POSITION.matchEntire(line)?.let { m ->
            val origin = m.groups["file"]!!.value.trim()
            return MsBuildDiagnostic(
                file = origin.takeUnless { it.lowercase() in TOOL_ORIGINS || it.contains(' ') && !looksLikePath(it) },
                line = 0,
                column = 0,
                severity = severity(m.groups["sev"]!!.value),
                code = m.groups["code"]?.value,
                message = m.groups["msg"]!!.value.trim(),
                project = m.groups["proj"]?.value?.trim(),
            )
        }
        return null
    }

    private fun severity(text: String) = if (text == "error") Severity.ERROR else Severity.WARNING

    private fun looksLikePath(text: String): Boolean =
        text.contains('/') || text.contains('\\') || text.matches(Regex("""^[A-Za-z]:.*"""))
}
