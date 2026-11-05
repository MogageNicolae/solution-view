package dev.solutionview.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MsBuildOutputParserTest {
    @Test
    fun `unix path with line, column and project`() {
        val d = MsBuildOutputParser.parse(
            "/home/me/src/App/Program.cs(12,5): error CS0103: The name 'x' does not exist in the current context [/home/me/src/App/App.csproj]",
        )!!
        assertEquals("/home/me/src/App/Program.cs", d.file)
        assertEquals(12, d.line)
        assertEquals(5, d.column)
        assertEquals(Severity.ERROR, d.severity)
        assertEquals("CS0103", d.code)
        assertEquals("The name 'x' does not exist in the current context", d.message)
        assertEquals("/home/me/src/App/App.csproj", d.project)
    }

    @Test
    fun `windows path with drive letter, spaces, parentheses and CRLF`() {
        val d = MsBuildOutputParser.parse(
            "C:\\Program Files (x86)\\Work\\My App\\Foo.cs(3,17,3,20): warning CS8618: Non-nullable property 'Name' [C:\\Work\\My App\\App.csproj]\r",
        )!!
        assertEquals("C:\\Program Files (x86)\\Work\\My App\\Foo.cs", d.file)
        assertEquals(3, d.line)
        assertEquals(17, d.column)
        assertEquals(Severity.WARNING, d.severity)
        assertEquals("CS8618", d.code)
        assertEquals("C:\\Work\\My App\\App.csproj", d.project)
    }

    @Test
    fun `project level message without position`() {
        val d = MsBuildOutputParser.parse(
            "  C:\\src\\App\\App.csproj : error NU1101: Unable to find package Foo. No packages exist with this id [C:\\src\\App.sln]",
        )!!
        assertEquals("C:\\src\\App\\App.csproj", d.file)
        assertEquals(0, d.line)
        assertEquals("NU1101", d.code)
        assertEquals("C:\\src\\App.sln", d.project)
    }

    @Test
    fun `tool origin is not treated as a file`() {
        val d = MsBuildOutputParser.parse("MSBUILD : error MSB1009: Project file does not exist.")!!
        assertNull(d.file)
        assertEquals("MSB1009", d.code)
        assertEquals("Project file does not exist.", d.message)
    }

    @Test
    fun `ansi colors are removed`() {
        val d = MsBuildOutputParser.parse("\u001B[31m/src/A.cs(1,1): error CS1002: ; expected [/src/A.csproj]\u001B[0m")!!
        assertEquals("/src/A.cs", d.file)
        assertEquals("; expected", d.message)
    }

    @Test
    fun `ordinary output lines are ignored`() {
        assertNull(MsBuildOutputParser.parse("  App -> /src/App/bin/Debug/net8.0/App.dll"))
        assertNull(MsBuildOutputParser.parse("Build succeeded."))
        assertNull(MsBuildOutputParser.parse("    0 Warning(s)"))
        assertNull(MsBuildOutputParser.parse("  Determining projects to restore..."))
    }
}
