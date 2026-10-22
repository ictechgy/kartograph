package dev.kartograph.gradle

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

/** 실제 Gradle 해석 결과의 순서와 bytes를 fixture의 격리된 build-tools classpath에 전달한다. */
internal object CompilerCallPositionRuntime {
    fun repository(): Path = Path.of(requireNotNull(System.getenv("KARTOGRAPH_KOTLIN_BUILD_TOOLS_REPOSITORY"))).also {
        require(it.isAbsolute && Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) && it.toRealPath() == it.normalize())
    }

    fun moduleReceipt(): Path = requiredFile("KARTOGRAPH_KOTLIN_BUILD_TOOLS_MODULE_RECEIPT")

    fun verifierScript(): Path = requiredFile("KARTOGRAPH_KOTLIN_BUILD_TOOLS_VERIFY_SCRIPT").also {
        require(sha256(it) == System.getenv("KARTOGRAPH_KOTLIN_BUILD_TOOLS_VERIFY_SHA256")) {
            "module verification script differs from the admitted runtime"
        }
    }

    private fun requiredFile(name: String): Path = Path.of(requireNotNull(System.getenv(name))).also {
        require(it.isAbsolute && Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && Files.size(it) <= 1024 * 1024)
    }

    fun buildTools(version: String): List<Path> {
        val value = requireNotNull(System.getenv("KARTOGRAPH_KOTLIN_BUILD_TOOLS_CLASSPATH")) {
            "complete resolved Kotlin build-tools classpath is required"
        }
        val manifest = Path.of(requireNotNull(System.getenv("KARTOGRAPH_KOTLIN_BUILD_TOOLS_MANIFEST")))
        return verify(value.split(File.pathSeparator).map(Path::of), manifest, version)
    }

    fun verify(paths: List<Path>, manifest: Path, version: String): List<Path> {
        require(Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(version))
        require(paths.isNotEmpty() && paths.size <= 256 && paths.distinct().size == paths.size)
        require(paths.map { it.fileName.toString() }.distinct().size == paths.size)
        require(Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS) && Files.size(manifest) <= 1024 * 1024)
        val lines = Files.readAllLines(manifest)
        require(lines.size == paths.size + 1 && lines.first() == "kartograph-build-tools-runtime\t1\t$version") {
            "build-tools receipt does not match the complete ordered classpath"
        }
        paths.forEachIndexed { ordinal, path ->
            require(path.isAbsolute && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            val fields = lines[ordinal + 1].split('\t')
            require(fields.size == 4 && fields[0] == ordinal.toString() && fields[1] == path.fileName.toString()) {
                "build-tools artifact order or identity changed"
            }
            val size = fields[2].toLongOrNull()
            require(size != null && size in 1..128L * 1024 * 1024 && size == Files.size(path))
            require(Regex("[0-9a-f]{64}").matches(fields[3]) && fields[3] == sha256(path)) {
                "build-tools artifact bytes changed"
            }
        }
        for (name in listOf("kotlin-build-tools-compat", "kotlin-build-tools-impl", "kotlin-tooling-core")) {
            require(paths.single { it.fileName.toString().startsWith("$name-") }.fileName.toString() == "$name-$version.jar") {
                "build-tools artifact differs from the collector version"
            }
        }
        return paths.toList()
    }

    fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
