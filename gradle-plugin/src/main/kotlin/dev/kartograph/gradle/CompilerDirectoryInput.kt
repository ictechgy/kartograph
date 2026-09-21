package dev.kartograph.gradle

import dev.kartograph.index.ContentFingerprint
import java.nio.file.Path

/** 내용이 같은 빈 외부 출력도 서로 바꿔 연결하지 않도록 상대 위치의 불투명 식별자를 만든다. */
internal object CompilerDirectoryInput {
    private const val PREFIX = "compiler-directory-"

    fun slot(project: Path, input: Path): String {
        val root = project.toFile().canonicalFile.toPath()
        val path = input.toFile().canonicalFile.toPath()
        // Windows의 다른 drive/UNC root에는 상대 경로가 없다. 절대 위치도 원문 대신 해시만 남긴다.
        val identity = if (root.root == path.root) listOf("relative", root.relativize(path).toString().replace('\\', '/'))
            else listOf("absolute", path.toUri().toASCIIString())
        return PREFIX + ContentFingerprint.values(identity)
    }

    fun hasIdentity(path: String): Boolean = path.startsWith("external/$PREFIX")
}
