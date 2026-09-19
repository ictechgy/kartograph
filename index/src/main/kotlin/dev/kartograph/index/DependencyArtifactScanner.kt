package dev.kartograph.index

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * 선언된 dependency artifact(JAR 또는 class directory)에 들어 있는 class 이름을 읽는다.
 * classfile을 파싱하지 않고 entry 이름만 보므로 hierarchy 입력보다 가볍다.
 */
public class DependencyArtifactScanner {
    /** artifact의 class internal 이름을 중복 없이 정렬해 반환한다. */
    public fun scan(artifact: Path): Set<String> = when {
        artifact.isDirectory() -> scanDirectory(artifact)
        artifact.isRegularFile() && artifact.fileName.toString().endsWith(".jar", ignoreCase = true) -> scanJar(artifact)
        artifact.isRegularFile() && artifact.fileName.toString().endsWith(".aar", ignoreCase = true) -> scanAar(artifact)
        else -> throw ClassIndexingException("dependency artifact must be a class directory, JAR or AAR")
    }

    private fun scanDirectory(root: Path): Set<String> = try {
        Files.walk(root).use { paths ->
            paths.filter { path -> Files.isRegularFile(path) && path.fileName.toString().endsWith(".class") }
                .map { path -> root.relativize(path).toString().replace('\\', '/').removeSuffix(".class") }
                .filter(::isAnalyzableClassName)
                .toList()
                .toSortedSet()
        }
    } catch (error: IOException) {
        throw ClassIndexingException("dependency artifact directory cannot be read", error)
    }

    private fun scanJar(jar: Path): Set<String> = try {
        JarFile(jar.toFile(), false).use { archive ->
            archive.entries().asSequence()
                .filter { entry -> !entry.isDirectory && entry.name.endsWith(".class") }
                .map { entry -> entry.name.removeSuffix(".class") }
                .filter(::isAnalyzableClassName)
                .toSortedSet()
        }
    } catch (error: IOException) {
        throw ClassIndexingException("dependency artifact JAR cannot be read", error)
    }

    private fun scanAar(aar: Path): Set<String> = try {
        JarFile(aar.toFile(), false).use { archive ->
            val names = sortedSetOf<String>()
            archive.entries().asSequence().filter { entry ->
                !entry.isDirectory && (entry.name == "classes.jar" || (entry.name.startsWith("libs/") && entry.name.endsWith(".jar")))
            }.forEach { entry ->
                val temporary = Files.createTempFile("kartograph-dependency-", ".jar")
                try {
                    archive.getInputStream(entry).use { input ->
                        Files.newOutputStream(temporary).use { output ->
                            val buffer = ByteArray(8192)
                            var total = 0L
                            var count = input.read(buffer)
                            while (count >= 0) {
                                total += count
                                if (total > 256L * 1024 * 1024) throw ClassIndexingException("embedded dependency JAR exceeds 256 MiB")
                                output.write(buffer, 0, count)
                                count = input.read(buffer)
                            }
                        }
                    }
                    // JarFile은 중앙 디렉터리까지 검증한다. 잘린 내부 ZIP을 빈 artifact로 취급하지 않는다.
                    names += scanJar(temporary)
                } finally {
                    Files.deleteIfExists(temporary)
                }
            }
            names
        }
    } catch (error: IOException) {
        throw ClassIndexingException("dependency artifact AAR cannot be read", error)
    }

    // multi-release 변형과 module/package 기술자는 일반 참조 대상이 아니므로 base entry만 센다.
    private fun isAnalyzableClassName(name: String): Boolean =
        name != "module-info" && name != "package-info" &&
            !name.endsWith("/package-info") && !name.startsWith("META-INF/versions/")
}
