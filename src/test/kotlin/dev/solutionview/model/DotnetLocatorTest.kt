package dev.solutionview.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DotnetLocatorTest {
    @Test
    fun `windows reads env names in any case and uses backslashes`() {
        val env = mapOf(
            "Path" to "C:\\Windows\\system32;\"C:\\Tools\\dotnet\"",
            "PROGRAMFILES" to "C:\\Program Files",
            "LOCALAPPDATA" to "C:\\Users\\me\\AppData\\Local",
        )
        val found = DotnetLocator.find(HostOs.WINDOWS, env, "C:\\Users\\me") { it == "C:\\Tools\\dotnet\\dotnet.exe" }
        assertEquals("C:\\Tools\\dotnet\\dotnet.exe", found)

        val all = DotnetLocator.candidates(HostOs.WINDOWS, env, "C:\\Users\\me")
        assertTrue("C:\\Program Files\\dotnet\\dotnet.exe" in all)
        assertTrue("C:\\Users\\me\\AppData\\Local\\Microsoft\\dotnet\\dotnet.exe" in all)
    }

    @Test
    fun `mac falls back to the installer folder when PATH is minimal`() {
        val env = mapOf("PATH" to "/usr/bin:/bin")
        val found = DotnetLocator.find(HostOs.MAC, env, "/Users/me") { it == "/usr/local/share/dotnet/dotnet" }
        assertEquals("/usr/local/share/dotnet/dotnet", found)
    }

    @Test
    fun `DOTNET_ROOT comes before PATH and linux defaults`() {
        val env = mapOf("DOTNET_ROOT" to "/opt/dotnet/", "PATH" to "/usr/bin")
        val all = DotnetLocator.candidates(HostOs.LINUX, env, "/home/me")
        assertEquals("/opt/dotnet/dotnet", all.first())
        assertTrue(all.indexOf("/usr/bin/dotnet") < all.indexOf("/usr/share/dotnet/dotnet"))
        assertTrue("/home/me/.dotnet/dotnet" in all)
    }

    @Test
    fun `nothing found returns null`() {
        assertEquals(null, DotnetLocator.find(HostOs.LINUX, emptyMap(), "/home/me") { false })
    }
}
