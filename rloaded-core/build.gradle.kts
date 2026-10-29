plugins {
    id("rloaded.kotlin-library")
    `java-test-fixtures`
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.commons.compress)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.core)

    testFixturesImplementation(libs.commons.compress)
}

// The runtime mod is built against the versions of the catalog and run with the pinned ones.
tasks.test {
    val catalog = rootProject.file("gradle/libs.versions.toml")
    inputs.file(catalog).withPropertyName("catalog").withPathSensitivity(PathSensitivity.NONE)
    systemProperty("rloaded.catalog", catalog.absolutePath)
}
