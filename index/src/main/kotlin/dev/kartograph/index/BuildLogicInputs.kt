package dev.kartograph.index

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** 중첩된 build logic의 source/resource와 Gradle 설정 목록을 결정적으로 관찰한다. */
public object BuildLogicInputs {
    private val generatedDirectories = setOf("build", ".gradle", ".git", ".idea", ".kotlin")

    /** 아직 없는 root는 빈 목록이다. src 내부의 build 패키지 등 실제 소스 이름은 제외하지 않는다. */
    public fun files(root: Path): List<Path> {
        if (Files.notExists(root, NOFOLLOW_LINKS)) return emptyList()
        require(Files.isDirectory(root, NOFOLLOW_LINKS)) { "build logic input must be a directory" }
        val result = mutableListOf<Path>()
        fun parts(path: Path) = root.relativize(path).map { it.toString() }
        fun generated(path: Path): Boolean = parts(path).takeWhile { it != "src" }.any { it in generatedDirectories }
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult =
                if (generated(directory)) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                if (generated(file)) return FileVisitResult.CONTINUE
                val names = parts(file)
                val name = file.fileName.toString()
                if ("src" in names || "gradle" in names || name.endsWith(".gradle") ||
                    name.endsWith(".gradle.kts") || name == "gradle.properties") {
                    require(!Files.isSymbolicLink(file) && Files.isRegularFile(file, NOFOLLOW_LINKS)) {
                        "symbolic or unavailable build logic inputs are not supported"
                    }
                    result.add(file)
                } else require(!attributes.isSymbolicLink) { "symbolic build logic directories are not supported" }
                return FileVisitResult.CONTINUE
            }
        })
        return result.sortedBy { root.relativize(it).toString().replace('\\', '/') }
    }
}
