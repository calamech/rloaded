import com.diffplug.spotless.LineEnding

plugins {
    base
    alias(libs.plugins.spotless)
}

spotless {
    // Same as .gitattributes, so Windows checkouts and generated files agree.
    lineEndings = LineEnding.UNIX
    kotlinGradle {
        target("*.gradle.kts", "*/*.gradle.kts", "conventions/src/**/*.gradle.kts")
        ktlint(libs.versions.ktlint.get())
    }
    kotlin {
        target("conventions/src/**/*.kt")
        ktlint(libs.versions.ktlint.get())
    }
    java {
        target("detekt-rules/src/**/*.java")
        googleJavaFormat(
            libs.versions.google.java.format
                .get(),
        )
    }
}

tasks.check {
    dependsOn(subprojects.map { "${it.path}:check" })
}
