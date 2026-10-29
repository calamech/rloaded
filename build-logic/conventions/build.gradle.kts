plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kover.gradle.plugin)
    implementation(libs.spotless.gradle.plugin)

    testImplementation(gradleTestKit())
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The functional tests build a copy of the repository layout, so they also depend on the
// detekt rules, the detekt configuration and the version catalog.
val repositoryDir = rootDir.parentFile

tasks.test {
    useJUnitPlatform()
    val jdks =
        listOf(25, 21).map { version ->
            javaToolchains
                .launcherFor {
                    languageVersion = JavaLanguageVersion.of(version)
                    vendor = JvmVendorSpec.JETBRAINS
                }.map { it.metadata.installationPath.asFile.absolutePath }
        }
    inputs.dir(rootDir.resolve("detekt-rules/src")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(repositoryDir.resolve("config/detekt/detekt.yml")).withPathSensitivity(PathSensitivity.NONE)
    inputs.file(repositoryDir.resolve("gradle/libs.versions.toml")).withPathSensitivity(PathSensitivity.NONE)
    systemProperty("rloaded.repositoryDir", repositoryDir.absolutePath)
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf("-Drloaded.jdks=${jdks.joinToString(",") { it.get() }}")
        },
    )
}
