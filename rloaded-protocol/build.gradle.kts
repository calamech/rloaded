plugins {
    id("rloaded.kotlin-library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    // Message types expose their serializers, so callers need the core types.
    api(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.cbor)
    implementation(libs.kotlinx.serialization.json)
}
