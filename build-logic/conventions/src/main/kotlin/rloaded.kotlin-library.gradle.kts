// Conventions for pure Kotlin library modules: no Minecraft or Fabric classes, unit tested.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("com.diffplug.spotless")
    id("org.jetbrains.kotlinx.kover")
}

val libs = versionCatalogs.named("libs")

// Libraries compile to Java 21 bytecode: the server bootstrap uses them and starts on any Java 21+.
kotlin {
    explicitApi()
    jvmToolchain {
        languageVersion = JavaLanguageVersion.of(25)
        vendor = JvmVendorSpec.JETBRAINS
    }
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        freeCompilerArgs.add("-Xjdk-release=21")
        allWarningsAsErrors = true
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
}

dependencies {
    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testImplementation(libs.findLibrary("junit-jupiter").get())
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())
}

tasks.test {
    useJUnitPlatform()
}

spotless {
    kotlin {
        ktlint(libs.findVersion("ktlint").get().requiredVersion)
    }
    kotlinGradle {
        ktlint(libs.findVersion("ktlint").get().requiredVersion)
    }
}

kover {
    reports {
        verify {
            rule {
                minBound(85)
            }
        }
    }
}

// detekt 1.23 embeds a Kotlin 2.0 compiler that cannot parse the version string of Java 25,
// so it runs from its command line tool on a Java 21 runtime. See docs/adr/0004-build-toolchain.md.
val detektCli = configurations.dependencyScope("detektCli")
val detektCliClasspath =
    configurations.resolvable("detektCliClasspath") {
        extendsFrom(detektCli.get())
    }

dependencies {
    detektCli(libs.findLibrary("detekt-cli").get())
    detektCli("io.github.calamech.rloaded.build:detekt-rules")
}

val detekt =
    tasks.register<JavaExec>("detekt") {
        description = "Runs detekt on the Kotlin sources, failing on any finding."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        val sources = files("src/main/kotlin", "src/test/kotlin")
        val config = rootProject.file("config/detekt/detekt.yml")
        val report = layout.buildDirectory.file("reports/detekt/detekt.txt")
        inputs.files(sources).withPathSensitivity(PathSensitivity.RELATIVE).skipWhenEmpty()
        inputs.file(config).withPathSensitivity(PathSensitivity.NONE)
        outputs.file(report)
        classpath(detektCliClasspath)
        mainClass = "io.gitlab.arturbosch.detekt.cli.Main"
        javaLauncher =
            javaToolchains.launcherFor {
                languageVersion = JavaLanguageVersion.of(21)
                vendor = JvmVendorSpec.JETBRAINS
            }
        argumentProviders.add {
            listOf(
                "--input",
                sources.filter { it.exists() }.joinToString(","),
                "--config",
                config.path,
                "--build-upon-default-config",
                "--report",
                "txt:${report.get().asFile.path}",
            )
        }
    }

tasks.check {
    dependsOn(detekt)
}
