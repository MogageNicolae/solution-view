import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.20"
    id("org.jetbrains.intellij.platform") version "2.20.0"
}

group = "dev.solutionview"
// Release builds pass -PpluginVersion=1.2.3 (taken from the git tag).
version = providers.gradleProperty("pluginVersion").getOrElse("0.1.0")

fun defaultRebasedPath(): String {
    val os = System.getProperty("os.name").lowercase()
    val home = System.getProperty("user.home")
    return when {
        os.contains("mac") -> "/Applications/Rebased.app"
        os.contains("win") -> (System.getenv("LOCALAPPDATA") ?: "$home\\AppData\\Local") + "\\Programs\\Rebased"
        else -> "$home/Applications/Rebased"
    }
}

val rebasedPath: String = providers.gradleProperty("rebasedPath")
    .orElse(providers.environmentVariable("REBASED_PATH"))
    .getOrElse(defaultRebasedPath())

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        local(rebasedPath)
    }
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // The IDE supplies these at runtime. Plain JUnit tests need their own copies.
    testImplementation(kotlin("stdlib", "2.3.20"))
    testImplementation("com.google.code.gson:gson:2.11.0")
}

// Build with the same JetBrains Runtime (Java 25) that Rebased ships, and emit Java 21 bytecode,
// which is the minimum that platform 2026.1 (build 261) needs.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
}

intellijPlatform {
    buildSearchableOptions = false
    instrumentCode = false
    pluginConfiguration {
        id = "dev.solutionview"
        name = "Solution View"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "261"
            untilBuild = provider { null }
        }
    }
}

// The default `test` task validates the target as a full JetBrains IDE before it runs. Rebased
// renames its core plugin descriptor (META-INF/RebasedPlugin.xml), so that check fails.
// Our tests are plain JUnit tests that need no running IDE, so they run in this task instead.
val unitTest = tasks.register<Test>("unitTest") {
    description = "Runs the plain JUnit tests."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.test {
    enabled = false
    dependsOn(unitTest)
}

tasks.check {
    dependsOn(unitTest)
}
