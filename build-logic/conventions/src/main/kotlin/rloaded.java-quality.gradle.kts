// Gates for Java code: google-java-format, Error Prone and compiler warnings as errors, on every
// source set. Shared by the agent and by the runtime mod, which picks its own Java release.
import com.diffplug.spotless.LineEnding

plugins {
    java
    id("com.diffplug.spotless")
    id("net.ltgt.errorprone")
}

val libs = versionCatalogs.named("libs")

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

dependencies {
    errorprone(libs.findLibrary("errorprone-core").get())
}

// Error Prone runs inside javac, so every source set is compiled by check, not only those tests use.
tasks.check {
    dependsOn(sourceSets.map { it.classesTaskName })
}

spotless {
    // Same as .gitattributes, so Windows checkouts and generated files agree.
    lineEndings = LineEnding.UNIX
    java {
        googleJavaFormat(libs.findVersion("google-java-format").get().requiredVersion)
    }
    kotlinGradle {
        ktlint(libs.findVersion("ktlint").get().requiredVersion)
    }
}
