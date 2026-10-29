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
