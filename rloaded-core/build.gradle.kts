plugins {
    id("rloaded.kotlin-library")
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.core)
}
