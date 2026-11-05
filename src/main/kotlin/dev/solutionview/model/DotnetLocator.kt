package dev.solutionview.model

enum class HostOs {
    WINDOWS, MAC, LINUX;

    val pathSeparator: Char get() = if (this == WINDOWS) ';' else ':'
    val fileSeparator: Char get() = if (this == WINDOWS) '\\' else '/'
    val executableName: String get() = if (this == WINDOWS) "dotnet.exe" else "dotnet"

    companion object {
        fun current(): HostOs {
            val name = System.getProperty("os.name").lowercase()
            return when {
                name.startsWith("windows") -> WINDOWS
                name.contains("mac") || name.contains("darwin") -> MAC
                else -> LINUX
            }
        }
    }
}

/**
 * Finds the `dotnet` executable on Windows, macOS and Linux.
 *
 * The search works on strings so tests can check every OS from any machine.
 * Apps started from the macOS Dock or Finder do not get the PATH from your shell profile,
 * so the well-known install folders matter there.
 */
object DotnetLocator {
    fun candidates(os: HostOs, env: Map<String, String>, userHome: String): List<String> {
        fun getenv(name: String): String? {
            val value = if (os == HostOs.WINDOWS) {
                env.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
            } else {
                env[name]
            }
            return value?.trim()?.trim('"')?.ifEmpty { null }
        }

        fun join(dir: String, vararg parts: String): String {
            val sep = os.fileSeparator
            return (listOf(dir.trimEnd('/', '\\')) + parts).joinToString(sep.toString())
        }

        val exe = os.executableName
        val result = mutableListOf<String>()

        // Set by the dotnet CLI for the processes it starts, and by some IDE launchers.
        getenv("DOTNET_HOST_PATH")?.let { result += it }
        listOf("DOTNET_ROOT_ARM64", "DOTNET_ROOT", "DOTNET_ROOT(x86)").forEach { name ->
            getenv(name)?.let { result += join(it, exe) }
        }
        getenv("PATH")?.split(os.pathSeparator)?.map { it.trim().trim('"') }?.filter { it.isNotEmpty() }?.forEach {
            result += join(it, exe)
        }

        when (os) {
            HostOs.WINDOWS -> {
                getenv("ProgramFiles")?.let { result += join(it, "dotnet", exe) }
                getenv("ProgramW6432")?.let { result += join(it, "dotnet", exe) }
                getenv("ProgramFiles(x86)")?.let { result += join(it, "dotnet", exe) }
                getenv("LOCALAPPDATA")?.let { result += join(it, "Microsoft", "dotnet", exe) }
                result += join(userHome, ".dotnet", exe)
                result += "C:\\Program Files\\dotnet\\dotnet.exe"
            }
            HostOs.MAC -> {
                result += "/usr/local/share/dotnet/dotnet"
                result += join(userHome, ".dotnet", exe)
                result += "/opt/homebrew/bin/dotnet"
                result += "/usr/local/bin/dotnet"
            }
            HostOs.LINUX -> {
                result += "/usr/share/dotnet/dotnet"
                result += "/usr/lib/dotnet/dotnet"
                result += "/usr/lib64/dotnet/dotnet"
                result += join(userHome, ".dotnet", exe)
                result += "/snap/bin/dotnet"
                result += "/usr/bin/dotnet"
                result += "/usr/local/bin/dotnet"
            }
        }
        return result.distinct()
    }

    fun find(
        os: HostOs,
        env: Map<String, String>,
        userHome: String,
        isExecutable: (String) -> Boolean,
    ): String? = candidates(os, env, userHome).firstOrNull(isExecutable)
}
