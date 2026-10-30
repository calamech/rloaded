plugins {
    id("rloaded.kotlin-library")
}

dependencies {
    // Results carry core and Modrinth types: Outcome, ModrinthVersion, Refusal.
    api(project(":rloaded-core"))
    api(project(":rloaded-modrinth"))

    testImplementation(testFixtures(project(":rloaded-core")))
    testImplementation(testFixtures(project(":rloaded-modrinth")))
    testImplementation(libs.kotlinx.serialization.json)
}
