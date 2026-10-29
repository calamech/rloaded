// Project-specific detekt rules, loaded by the detekt task of the rloaded.kotlin-library convention.
plugins {
    `java-library`
}

group = "io.github.calamech.rloaded.build"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
        vendor = JvmVendorSpec.JETBRAINS
    }
}

dependencies {
    compileOnly(libs.detekt.api)
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}
