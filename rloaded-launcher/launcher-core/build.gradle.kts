plugins {
    id("rloaded.kotlin-library")
}

dependencies {
    api(project(":rloaded-core"))

    testImplementation(testFixtures(project(":rloaded-core")))
}
