plugins {
    id("rloaded.kotlin-library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    // Results carry core and Modrinth types: Outcome, ModrinthVersion, Refusal.
    api(project(":rloaded-core"))
    api(project(":rloaded-modrinth"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(testFixtures(project(":rloaded-core")))
    testImplementation(testFixtures(project(":rloaded-modrinth")))
}

// CompatDatabaseTest reads the database the repository ships.
tasks.test {
    val database = rootProject.file("compat/compat.json")
    inputs.file(database).withPropertyName("compatDatabase").withPathSensitivity(PathSensitivity.NONE)
    systemProperty("rloaded.compatDatabase", database.absolutePath)
}
