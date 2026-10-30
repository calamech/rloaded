// Conventions for Java library modules: the agent.
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    `java-library`
    jacoco
    id("rloaded.java-quality")
}

val libs = versionCatalogs.named("libs")

// Java 21 bytecode, like the Kotlin libraries, compiled by JetBrains Runtime 25.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
        vendor = JvmVendorSpec.JETBRAINS
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
}

dependencies {
    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testImplementation(libs.findLibrary("junit-jupiter").get())
    testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events(TestLogEvent.FAILED)
        exceptionFormat = TestExceptionFormat.FULL
    }
}

// Kover reports no sources for a module without the Kotlin plugin, so Java modules use JaCoCo.
jacoco {
    toolVersion = libs.findVersion("jacoco").get().requiredVersion
}

tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.85".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
