package io.github.calamech.rloaded.launcher.app

/** The version of this launcher, which the build writes from the version catalog. */
internal object LauncherVersion {
    val current: String =
        LauncherVersion::class.java
            .getResourceAsStream("version.txt")
            ?.use { it.readBytes().decodeToString().trim() }
            ?: error("the launcher was built without its version")
}
