// Conventions for the root project: formatting of the root build scripts, and a check task
// that also runs the checks of build-logic.
import com.diffplug.spotless.LineEnding

plugins {
    base
    id("com.diffplug.spotless")
}

val libs = versionCatalogs.named("libs")

spotless {
    // Same as .gitattributes, so Windows checkouts and generated files agree.
    lineEndings = LineEnding.UNIX
    kotlinGradle {
        target("*.gradle.kts")
        ktlint(libs.findVersion("ktlint").get().requiredVersion)
    }
}

tasks.check {
    dependsOn(gradle.includedBuild("build-logic").task(":check"))
}
