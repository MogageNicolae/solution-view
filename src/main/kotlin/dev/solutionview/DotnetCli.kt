package dev.solutionview

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.util.EnvironmentUtil
import dev.solutionview.model.CommandResult
import dev.solutionview.model.CommandRunner
import dev.solutionview.model.DotnetLocator
import dev.solutionview.model.HostOs
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** Finds and runs the `dotnet` CLI with the same settings on every OS. */
object DotnetCli {
    private const val EVALUATION_TIMEOUT_MS = 120_000

    @Volatile
    private var located: String? = null

    @Volatile
    private var searched = false

    /** Path to `dotnet`, or null when it is not installed. The result is cached. */
    fun path(): String? {
        if (!searched) {
            // EnvironmentUtil holds the environment of your login shell. On macOS that matters:
            // apps started from the Dock do not see the PATH from ~/.zshrc otherwise.
            located = DotnetLocator.find(HostOs.current(), EnvironmentUtil.getEnvironmentMap(), System.getProperty("user.home")) {
                try {
                    val path = Path.of(it)
                    Files.isRegularFile(path) && Files.isExecutable(path)
                } catch (e: Exception) {
                    false
                }
            }
            searched = true
        }
        return located
    }

    fun resetCache() {
        searched = false
        located = null
    }

    fun commandLine(dotnet: String, vararg args: String): GeneralCommandLine {
        val env = mutableMapOf(
            // Keep messages in English so the error parser and the users see the same text.
            "DOTNET_CLI_UI_LANGUAGE" to "en",
            "DOTNET_NOLOGO" to "1",
            "DOTNET_SKIP_FIRST_TIME_EXPERIENCE" to "1",
            // The interactive "terminal logger" redraws lines with escape codes.
            "MSBUILDTERMINALLOGGER" to "off",
        )
        // A built app starts through a small native launcher that must find the .NET runtime.
        // When dotnet is not in the default install folder (for example ~/.dotnet), it needs DOTNET_ROOT.
        if (EnvironmentUtil.getValue("DOTNET_ROOT") == null) {
            dotnetRoot(dotnet)?.let { env["DOTNET_ROOT"] = it }
        }
        return GeneralCommandLine(dotnet, *args)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withCharset(StandardCharsets.UTF_8)
            .withEnvironment(env)
    }

    /** The folder that holds the real dotnet executable, after following symbolic links. */
    private fun dotnetRoot(dotnet: String): String? = try {
        Path.of(dotnet).toRealPath().parent?.toString()
    } catch (e: Exception) {
        null
    }

    /** Runs short commands such as `dotnet msbuild -getItem` and waits for them. */
    val runner = CommandRunner { command, workDir ->
        val commandLine = commandLine(command.first(), *command.drop(1).toTypedArray()).withWorkingDirectory(workDir)
        val output = ExecUtil.execAndGetOutput(commandLine, EVALUATION_TIMEOUT_MS)
        if (output.isTimeout) {
            CommandResult(-1, output.stdout, "Timed out after ${EVALUATION_TIMEOUT_MS / 1000} s")
        } else {
            CommandResult(output.exitCode, output.stdout, output.stderr)
        }
    }
}
