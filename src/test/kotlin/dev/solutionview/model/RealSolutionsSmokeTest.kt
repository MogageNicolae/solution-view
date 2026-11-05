package dev.solutionview.model

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.name

/**
 * Runs the parser and the MSBuild evaluator on real solutions, without the IDE.
 *
 *     SOLUTION_VIEW_SMOKE_DIR=~/dev/MyRepo ./gradlew unitTest --tests '*RealSolutionsSmokeTest*'
 *
 * It only reads: `dotnet msbuild -getItem` evaluates projects and does not build or restore them.
 * The report goes to build/smoke-report.txt.
 */
class RealSolutionsSmokeTest {
    @Test
    fun `evaluate every solution in a folder`() {
        val root = System.getenv("SOLUTION_VIEW_SMOKE_DIR")?.let { Path.of(it.replaceFirst("~", System.getProperty("user.home"))) }
        assumeTrue(root != null && Files.isDirectory(root), "SOLUTION_VIEW_SMOKE_DIR is not set")
        val dotnet = DotnetLocator.find(HostOs.current(), System.getenv(), System.getProperty("user.home")) {
            Files.isExecutable(Path.of(it))
        }

        val solutions = Files.walk(root!!, 4).use { paths ->
            paths.filter { SolutionParser.isSolutionFile(it.name) && !it.toString().contains("/node_modules/") }.toList()
        }.sorted()

        val evaluator = MsBuildEvaluator(dotnet) { command, workDir ->
            val process = ProcessBuilder(command).directory(workDir.toFile()).redirectErrorStream(false).start()
            val out = process.inputStream.bufferedReader().readText()
            val err = process.errorStream.bufferedReader().readText()
            process.waitFor(180, TimeUnit.SECONDS)
            CommandResult(process.exitValue(), out, err)
        }
        val pool = Executors.newFixedThreadPool(6)
        val cache = HashMap<Path, java.util.concurrent.Future<Pair<MsBuildProject, Long>>>()
        val report = StringBuilder()

        for (slnPath in solutions) {
            val started = System.currentTimeMillis()
            val solution = try {
                SolutionParser.parse(slnPath)
            } catch (e: Exception) {
                report.appendLine("!! ${root.relativize(slnPath)}: parse failed: $e")
                continue
            }
            val projects = solution.allProjects()
            val futures = projects.map { p ->
                p to synchronized(cache) {
                    cache.getOrPut(p.path) {
                        pool.submit(Callable {
                            val t = System.currentTimeMillis()
                            val result = if (Files.isRegularFile(p.path)) evaluator.evaluate(p.path)
                            else MsBuildProject(p.path, emptyList(), emptyList(), emptyList(), emptyList(), null, LoadSource.XML_FALLBACK, "missing")
                            result to (System.currentTimeMillis() - t)
                        })
                    }
                }
            }
            val results = futures.map { (p, f) -> p to f.get() }
            val msbuild = results.count { it.second.first.source == LoadSource.MSBUILD }
            val missing = results.count { it.second.first.error == "missing" }
            report.appendLine(
                "== ${root.relativize(slnPath)}: ${projects.size} projects, ${solution.items.count { it is SolutionFolder }} top folders, " +
                    "MSBuild ok $msbuild, fallback ${projects.size - msbuild - missing}, missing $missing, " +
                    "${(System.currentTimeMillis() - started) / 1000.0}s",
            )
            for ((p, r) in results) {
                val (proj, ms) = r
                val files = proj.items.count()
                val outside = proj.items.count { !it.path.startsWith(proj.directory) }
                val hidden = proj.items.count { !Files.exists(it.path) }
                val flags = listOfNotNull(
                    "test".takeIf { proj.isTestProject },
                    "exe".takeIf { proj.isExecutable },
                ).joinToString(",")
                report.appendLine(
                    "   ${proj.source.name.padEnd(12)} ${p.name.padEnd(55)} files=$files outside=$outside notOnDisk=$hidden " +
                        "tfm=${proj.targetFrameworks.joinToString(";")} pkgs=${proj.packages.size} refs=${proj.projectReferences.size} " +
                        "$flags ${ms}ms" + (proj.error?.let { "  ERR: ${it.take(160)}" } ?: ""),
                )
            }
        }
        pool.shutdown()
        val out = Path.of("build/smoke-report.txt")
        Files.createDirectories(out.parent)
        Files.writeString(out, report.toString())
        println(report)
    }
}
