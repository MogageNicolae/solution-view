plugins {
    // Lets Gradle download a Java 25 toolchain on machines that have none (Windows, macOS, Linux).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "solution-view"
