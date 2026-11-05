package dev.solutionview.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class MsBuildEvaluatorTest {
    @TempDir
    lateinit var dir: Path

    private fun write(relative: String, text: String = ""): Path {
        val file = dir.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
        return file
    }

    private val sdkProject = """
        <Project Sdk="Microsoft.NET.Sdk">
          <PropertyGroup>
            <OutputType>Exe</OutputType>
            <TargetFrameworks>net8.0;net10.0</TargetFrameworks>
          </PropertyGroup>
          <ItemGroup>
            <PackageReference Include="Newtonsoft.Json" Version="13.0.3" />
            <ProjectReference Include="..\Lib\Lib.csproj" />
            <Compile Include="..\Shared\Shared.cs" Link="Common\Shared.cs" />
          </ItemGroup>
        </Project>
    """.trimIndent()

    @Test
    fun `parses msbuild json output`() {
        val project = dir.resolve("App/App.csproj")
        val json = """
            {
              "Properties": { "TargetFramework": "", "TargetFrameworks": "net8.0;net10.0", "OutputType": "Exe" },
              "Items": {
                "Compile": [
                  { "Identity": "Program.cs", "FullPath": "${dir.resolve("App/Program.cs").json()}" },
                  { "Identity": "..\\Shared\\Shared.cs", "FullPath": "${dir.resolve("Shared/Shared.cs").json()}", "Link": "Common\\Shared.cs" }
                ],
                "None": [
                  { "Identity": "appsettings.json", "FullPath": "${dir.resolve("App/appsettings.json").json()}" },
                  { "Identity": "xunit.abstractions.dll", "FullPath": "/nuget/xunit.abstractions.dll", "Visible": "False" }
                ],
                "PackageReference": [ { "Identity": "Newtonsoft.Json", "Version": "13.0.3" } ],
                "ProjectReference": [ { "Identity": "..\\Lib\\Lib.csproj", "FullPath": "${dir.resolve("Lib/Lib.csproj").json()}" } ]
              }
            }
        """.trimIndent()

        val result = MsBuildJson.parse(project, json)

        assertEquals(LoadSource.MSBUILD, result.source)
        assertEquals(listOf("net8.0", "net10.0"), result.targetFrameworks)
        assertEquals("Exe", result.outputType)
        assertEquals(3, result.items.size)
        assertEquals("Common/Shared.cs", result.items[1].link)
        assertEquals(listOf(PackageReference("Newtonsoft.Json", "13.0.3")), result.packages)
        assertEquals(listOf(dir.resolve("Lib/Lib.csproj")), result.projectReferences)
    }

    @Test
    fun `xml fallback applies sdk globs and skips bin, obj and nested projects`() {
        val project = write("App/App.csproj", sdkProject)
        write("App/Program.cs")
        write("App/Models/User.cs")
        write("App/appsettings.json")
        write("App/bin/Debug/App.dll")
        write("App/obj/project.assets.json")
        write("App/Tools/Tools.csproj")
        write("App/Tools/Tool.cs")

        val result = XmlProjectReader.read(project, "no dotnet")

        val names = result.items.map { dir.resolve("App").relativize(it.path).toString().replace('\\', '/') }.toSet()
        assertTrue("Program.cs" in names)
        assertTrue("Models/User.cs" in names)
        assertTrue("appsettings.json" in names)
        assertTrue("../Shared/Shared.cs" in names)
        assertFalse(names.any { it.startsWith("bin/") || it.startsWith("obj/") || it.startsWith("Tools/") })
        assertEquals("Compile", result.items.first { it.path.endsWith("Program.cs") }.itemType)
        assertEquals("None", result.items.first { it.path.endsWith("appsettings.json") }.itemType)
        assertEquals(listOf("net8.0", "net10.0"), result.targetFrameworks)
        assertEquals("13.0.3", result.packages.single().version)
        assertEquals(LoadSource.XML_FALLBACK, result.source)
        assertEquals("no dotnet", result.error)
    }

    @Test
    fun `failed msbuild run falls back to xml and keeps the error`() {
        val project = write("App/App.csproj", sdkProject)
        val evaluator = MsBuildEvaluator("dotnet") { _, _ ->
            CommandResult(1, "App.csproj : error MSB4025: The project file could not be loaded.", "")
        }

        val result = evaluator.evaluate(project)

        assertEquals(LoadSource.XML_FALLBACK, result.source)
        assertTrue(result.error!!.contains("MSB4025"))
    }

    @Test
    fun `multi-targeted projects are evaluated again for one framework`() {
        val project = write("Lib/Lib.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\" />")
        val commands = mutableListOf<List<String>>()
        val evaluator = MsBuildEvaluator("dotnet") { command, _ ->
            commands += command
            if (command.none { it.startsWith("-property:TargetFramework=") }) {
                CommandResult(0, """{"Properties":{"TargetFramework":"","TargetFrameworks":"netstandard2.0;net8.0"},"Items":{"Compile":[]}}""", "")
            } else {
                CommandResult(
                    0,
                    """{"Properties":{"TargetFramework":"net8.0","TargetFrameworks":"netstandard2.0;net8.0"},
                       "Items":{"Compile":[{"Identity":"A.cs","FullPath":"${dir.resolve("Lib/A.cs").json()}"}]}}""",
                    "",
                )
            }
        }

        val result = evaluator.evaluate(project)

        assertEquals(2, commands.size)
        assertTrue("-property:TargetFramework=net8.0" in commands[1])
        assertEquals(listOf("A.cs"), result.items.map { it.path.fileName.toString() })
        assertEquals(listOf("netstandard2.0", "net8.0"), result.targetFrameworks)
    }

    @Test
    fun `old-style projects list only their explicit files`() {
        val project = write(
            "Old/Old.csproj",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <Project ToolsVersion="15.0" xmlns="http://schemas.microsoft.com/developer/msbuild/2003">
              <ItemGroup>
                <Compile Include="Program.cs" />
                <Compile Include="Properties\AssemblyInfo.cs" />
                <None Include="App.config" />
              </ItemGroup>
            </Project>
            """.trimIndent(),
        )
        write("Old/Program.cs")
        write("Old/Unlisted.cs")

        val names = XmlProjectReader.read(project, null).items.map { dir.resolve("Old").relativize(it.path).toString().replace('\\', '/') }

        assertEquals(listOf("Program.cs", "Properties/AssemblyInfo.cs", "App.config"), names)
    }

    @Test
    fun `shared projects read their projitems file`() {
        val project = write(
            "Shared/Shared.shproj",
            """
            <Project ToolsVersion="14.0">
              <Import Project="Shared.projitems" Label="Shared" />
            </Project>
            """.trimIndent(),
        )
        write(
            "Shared/Shared.projitems",
            """
            <Project xmlns="http://schemas.microsoft.com/developer/msbuild/2003">
              <ItemGroup>
                <Compile Include="${'$'}(MSBuildThisFileDirectory)Util\Strings.cs" />
              </ItemGroup>
            </Project>
            """.trimIndent(),
        )

        val items = XmlProjectReader.read(project, null).items

        assertEquals(listOf(dir.resolve("Shared/Util/Strings.cs")), items.map { it.path })
    }

    @Test
    fun `project contents builds a folder tree with linked files`() {
        val root = dir.resolve("App")
        val project = MsBuildProject(
            projectPath = root.resolve("App.csproj"),
            items = listOf(
                ProjectItem(root.resolve("Program.cs"), "Compile"),
                ProjectItem(root.resolve("Models/User.cs"), "Compile"),
                ProjectItem(root.resolve("Models/Dto/UserDto.cs"), "Compile"),
                ProjectItem(dir.resolve("Shared/Shared.cs"), "Compile", "Common/Shared.cs"),
                ProjectItem(dir.resolve("Other/Loose.cs"), "Compile"),
                ProjectItem(root.resolve("Empty"), "Folder"),
            ),
            targetFrameworks = listOf("net8.0"),
            packages = emptyList(),
            projectReferences = emptyList(),
            outputType = null,
            source = LoadSource.MSBUILD,
        )

        val contents = ProjectContents(project)

        assertEquals(listOf("Common", "Empty", "Models"), contents.childDirectories(""))
        assertEquals(listOf("Dto"), contents.childDirectories("Models"))
        assertEquals(listOf(root.resolve("Program.cs"), dir.resolve("Other/Loose.cs")), contents.files(""))
        assertEquals(listOf(dir.resolve("Shared/Shared.cs")), contents.files("Common"))
        assertTrue(contents.isIncluded(root.resolve("Models/Dto/UserDto.cs")))
        assertFalse(contents.isIncluded(root.resolve("bin/App.dll")))
    }

    @Test
    fun `real dotnet msbuild evaluation of an sdk project`() {
        val dotnet = DotnetLocator.find(HostOs.current(), System.getenv(), System.getProperty("user.home")) {
            Files.isExecutable(Path.of(it))
        }
        assumeTrue(dotnet != null, "dotnet is not installed")

        val project = write(
            "App/App.csproj",
            """
            <Project Sdk="Microsoft.NET.Sdk">
              <PropertyGroup><TargetFrameworks>net8.0;net10.0</TargetFrameworks><OutputType>Exe</OutputType></PropertyGroup>
              <ItemGroup><PackageReference Include="Humanizer.Core" Version="2.14.1" /></ItemGroup>
            </Project>
            """.trimIndent(),
        )
        write("App/Program.cs", "System.Console.WriteLine(1);")
        write("App/Data/Seed.cs", "class Seed {}")
        write("App/readme.txt")
        write("App/obj/Generated.cs")

        val result = MsBuildEvaluator(dotnet) { command, workDir ->
            val process = ProcessBuilder(command).directory(workDir.toFile()).start()
            val out = process.inputStream.bufferedReader().readText()
            val err = process.errorStream.bufferedReader().readText()
            process.waitFor(120, TimeUnit.SECONDS)
            CommandResult(process.exitValue(), out, err)
        }.evaluate(project)

        assertEquals(LoadSource.MSBUILD, result.source, result.error ?: "")
        val compile = result.items.filter { it.itemType == "Compile" }.map { it.path.fileName.toString() }.toSet()
        assertEquals(setOf("Program.cs", "Seed.cs"), compile)
        assertNotNull(result.items.firstOrNull { it.path.endsWith("readme.txt") && it.itemType == "None" })
        assertEquals(listOf("net8.0", "net10.0"), result.targetFrameworks)
        assertEquals("Humanizer.Core", result.packages.single().name)
    }

    private fun Path.json(): String = toString().replace("\\", "\\\\")
}
