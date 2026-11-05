package dev.solutionview.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SolutionParserTest {
    @TempDir
    lateinit var dir: Path

    private fun touch(relative: String): Path {
        val file = dir.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, "")
        return file
    }

    @Test
    fun `sln with nested solution folders, solution items and backslash paths`() {
        touch("src/App/App.csproj")
        touch("src/Lib/Lib.fsproj")
        touch("tests/App.Tests/App.Tests.csproj")
        touch("README.md")
        val sln = dir.resolve("Demo.sln")
        val text = """
            |
            |Microsoft Visual Studio Solution File, Format Version 12.00
            |# Visual Studio Version 17
            |Project("{2150E333-8FDC-42A3-9474-1A3956D46DE8}") = "src", "src", "{11111111-1111-1111-1111-111111111111}"
            |EndProject
            |Project("{FAE04EC0-301F-11D3-BF4B-00C04F79EFBC}") = "App", "src\App\App.csproj", "{22222222-2222-2222-2222-222222222222}"
            |EndProject
            |Project("{F2A71F9B-5D33-465A-A702-920D77279786}") = "Lib", "src\Lib\Lib.fsproj", "{33333333-3333-3333-3333-333333333333}"
            |EndProject
            |Project("{2150E333-8FDC-42A3-9474-1A3956D46DE8}") = "Solution Items", "Solution Items", "{44444444-4444-4444-4444-444444444444}"
            |	ProjectSection(SolutionItems) = preProject
            |		README.md = README.md
            |	EndProjectSection
            |EndProject
            |Project("{9A19103F-16F7-4668-BE54-9A1E7A4F7556}") = "App.Tests", "tests\App.Tests\App.Tests.csproj", "{55555555-5555-5555-5555-555555555555}"
            |EndProject
            |Global
            |	GlobalSection(NestedProjects) = preSolution
            |		{22222222-2222-2222-2222-222222222222} = {11111111-1111-1111-1111-111111111111}
            |		{33333333-3333-3333-3333-333333333333} = {11111111-1111-1111-1111-111111111111}
            |	EndGlobalSection
            |EndGlobal
        """.trimMargin().replace("\n", "\r\n")

        val solution = SolutionParser.parseSln(sln, text)

        assertEquals("Demo", solution.name)
        assertEquals(listOf("src", "Solution Items", "App.Tests"), solution.items.map { it.name })
        val src = solution.items[0] as SolutionFolder
        assertEquals(listOf("App", "Lib"), src.children.map { it.name })
        assertEquals(dir.resolve("src/App/App.csproj"), (src.children[0] as SolutionProject).path)
        assertEquals(ProjectKind.FSHARP, (src.children[1] as SolutionProject).kind)
        assertEquals(listOf(dir.resolve("README.md")), (solution.items[1] as SolutionFolder).files)
        assertEquals(3, solution.allProjects().size)
    }

    @Test
    fun `sln path with wrong letter case is matched to the file on disk`() {
        val real = touch("Src/MyApp/MyApp.csproj")
        val text = """
            |Project("{9A19103F-16F7-4668-BE54-9A1E7A4F7556}") = "MyApp", "src\myapp\MYAPP.csproj", "{22222222-2222-2222-2222-222222222222}"
            |EndProject
        """.trimMargin()

        val project = SolutionParser.parseSln(dir.resolve("X.sln"), text).items.single() as SolutionProject

        assertTrue(Files.isSameFile(real, project.path))
    }

    @Test
    fun `sln ignores web site projects and nesting loops`() {
        val text = """
            |Project("{E24C65DC-7377-472B-9ABA-BC803B73C61A}") = "Site", "http://localhost/site", "{66666666-6666-6666-6666-666666666666}"
            |EndProject
            |Project("{2150E333-8FDC-42A3-9474-1A3956D46DE8}") = "A", "A", "{77777777-7777-7777-7777-777777777777}"
            |EndProject
            |Global
            |	GlobalSection(NestedProjects) = preSolution
            |		{77777777-7777-7777-7777-777777777777} = {77777777-7777-7777-7777-777777777777}
            |	EndGlobalSection
            |EndGlobal
        """.trimMargin()

        val solution = SolutionParser.parseSln(dir.resolve("Loop.sln"), text)

        assertEquals(emptyList<SolutionItem>(), solution.items)
    }

    @Test
    fun `slnx with flat folder paths, display names and files`() {
        touch("src/App/App.csproj")
        touch("src/libs/Core/Core.csproj")
        touch("Directory.Build.props")
        val text = """
            |<Solution>
            |  <Configurations>
            |    <Platform Name="Any CPU" />
            |  </Configurations>
            |  <Folder Name="/Solution Items/">
            |    <File Path="Directory.Build.props" />
            |  </Folder>
            |  <Folder Name="/src/">
            |    <Project Path="src\App\App.csproj" />
            |  </Folder>
            |  <Folder Name="/src/libs/">
            |    <Project Path="src/libs/Core/Core.csproj" DisplayName="CoreLib" />
            |  </Folder>
            |  <Project Path="tools/Tool/Tool.vbproj" />
            |</Solution>
        """.trimMargin()

        val solution = SolutionParser.parseSlnx(dir.resolve("Demo.slnx"), text)

        assertEquals(listOf("Solution Items", "src", "Tool"), solution.items.map { it.name })
        val src = solution.items[1] as SolutionFolder
        assertEquals(listOf("libs", "App"), src.children.map { it.name })
        val libs = src.children[0] as SolutionFolder
        assertEquals("CoreLib", libs.children.single().name)
        assertEquals(listOf(dir.resolve("Directory.Build.props")), (solution.items[0] as SolutionFolder).files)
        assertEquals(ProjectKind.VISUAL_BASIC, (solution.items[2] as SolutionProject).kind)
    }

    @Test
    fun `parse picks the format from the file extension`() {
        touch("A/A.csproj")
        val slnx = dir.resolve("Pick.slnx")
        Files.writeString(slnx, """<Solution><Project Path="A/A.csproj" /></Solution>""")

        assertEquals(listOf("A"), SolutionParser.parse(slnx).items.map { it.name })
        assertTrue(SolutionParser.isSolutionFile("x.SLN"))
        assertTrue(SolutionParser.isSolutionFile("x.slnx"))
    }
}
