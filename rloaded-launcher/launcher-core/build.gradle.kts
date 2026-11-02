plugins {
    id("rloaded.kotlin-library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    api(project(":rloaded-core"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(testFixtures(project(":rloaded-core")))
}
