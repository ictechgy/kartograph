package dev.kartograph.index

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** 명시적인 compiler inventory는 재탐색하지 않는다. 잘못된 파일을 조용히 생략하지 않는다. */
internal fun sourceInventoryFiles(sources: Collection<Path>): List<Path> = sources.map { source ->
    require(!Files.isSymbolicLink(source) && Files.isRegularFile(source, NOFOLLOW_LINKS)) {
        "source inventory contains an unavailable or symbolic file"
    }
    require(source.fileName.toString().let { it.endsWith(".java") || it.endsWith(".kt") }) {
        "source inventory contains an unsupported file"
    }
    source.toRealPath()
}.distinct().sortedBy(Path::toString)
