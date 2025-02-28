package org.jetbrains.tools.model.updater.impl

sealed class JpsPath(private val root: String) {
    class ProjectDir(path: String, isCommunity: Boolean) : JpsPath("\$PROJECT_DIR\$") {
        override val path = if (isCommunity || path.startsWith("..")) path else "community/$path"
    }

    class MavenRepository(mavenId: MavenId, classifier: String? = null) : JpsPath("\$MAVEN_REPOSITORY\$") {
        override val path = mavenId.toJarPath(classifier)
    }

    protected abstract val path: String

    override fun toString() = "$root/$path"
}

sealed class JpsUrl(val path: JpsPath) {
    class File(jpsPath: JpsPath) : JpsUrl(jpsPath) {
        override val url: String
            get() = "file://${path}"
    }

    class Jar(jpsPath: JpsPath, private val pathInsideJar: String = "") : JpsUrl(jpsPath) {
        override val url: String
            get() = "jar://${path}!/$pathInsideJar"
    }

    abstract val url: String
}