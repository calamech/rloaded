package io.github.calamech.rloaded.launcher.update

/** A version of Rloaded, `major.minor.patch`, as the version catalog and the release tags write it. */
public data class Version(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<Version> {
    override fun compareTo(other: Version): Int = compareValuesBy(this, other, Version::major, Version::minor, Version::patch)

    override fun toString(): String = "$major.$minor.$patch"

    /** Reading versions. */
    public companion object {
        private val FORMAT = Regex("""(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})""")

        /** Reads `1.2.3`; anything else, a pre-release suffix included, is not a version. */
        public fun parse(text: String): Version? =
            FORMAT.matchEntire(text)?.destructured?.let { (major, minor, patch) ->
                Version(major.toInt(), minor.toInt(), patch.toInt())
            }

        /** Reads a release tag, `v1.2.3`. */
        public fun ofTag(tag: String): Version? = if (tag.startsWith("v")) parse(tag.removePrefix("v")) else null
    }
}
