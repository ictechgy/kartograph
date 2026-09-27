package dev.kartograph.gradle

import dev.kartograph.core.BuildWitness
import dev.kartograph.core.InputFingerprint
import dev.kartograph.index.ContentFingerprint
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import org.junit.jupiter.api.io.TempDir

/** 같은 바이트의 외부 입력이 여러 위치에 있을 때의 결정적 연결과 거부 사유를 확인한다. */
class CompilerInputBinderTest {
    private val witness = BuildWitness(":feature:debug", "kotlin", ":feature:compileDebugKotlin",
        listOf("sources", "buildConfig", "compiler", "options").map { InputFingerprint(it, it, "a".repeat(64)) },
        listOf(InputFingerprint("classes", "classes", "b".repeat(64))))

    @Test
    fun `content identical jars prefer compiler inputs and pair repeated inputs with distinct files`(@TempDir root: Path) {
        val project = Files.createDirectories(root.resolve("project"))
        // AndroidX KMP 분할 artifact의 빈 stub JAR처럼 서로 다른 위치의 같은 바이트다.
        val compilerFirst = empty(root.resolve("transforms/a/lifecycle-runtime-api.jar"))
        val compilerSecond = empty(root.resolve("transforms/b/savedstate-ktx-runtime.jar"))
        val snapshotOnly = empty(root.resolve("transforms/c/lifecycle-viewmodel-runtime.jar"))
        val input = ContentFingerprint.capture(project, compilerFirst, "classpath", "compileDebugKotlin-classpath-1")
        val binder = CompilerInputBinder(project, listOf(listOf(compilerFirst, compilerSecond), listOf(snapshotOnly, compilerFirst)))

        val first = binder.bind(witness, input, null, emptySet())
        assertEquals(compilerFirst, first)
        assertEquals(compilerSecond, binder.bind(witness, input, null, setOf(first)))
        assertEquals(compilerFirst, binder.bind(witness, input, null, setOf(compilerFirst, compilerSecond)))
        assertEquals(snapshotOnly, CompilerInputBinder(project, listOf(emptyList(), listOf(snapshotOnly))).bind(witness, input, null, emptySet()))
    }

    @Test
    fun `missing inputs and identical directories name the task input and candidates without absolute paths`(@TempDir root: Path) {
        val project = Files.createDirectories(root.resolve("project"))
        val jar = empty(root.resolve("libs/one.jar"))
        val input = ContentFingerprint.capture(project, jar, "classpath", "compileDebugKotlin-classpath-4")
        Files.write(jar, byteArrayOf(1))
        val missing = assertFailsWith<IllegalArgumentException> {
            CompilerInputBinder(project, listOf(listOf(jar))).bind(witness, input, null, emptySet())
        }
        assertContains(missing.message.orEmpty(), ":feature:compileDebugKotlin")
        assertContains(missing.message.orEmpty(), "external/compileDebugKotlin-classpath-4")
        assertFalse(missing.message.orEmpty().contains(root.toString()))

        val inside = Files.createDirectories(project.resolve("build/empty-a"))
        val outside = Files.createDirectories(root.resolve("outside/empty-b"))
        val directory = ContentFingerprint.capture(project, outside, "directory-watch", "optional-classes")
        val ambiguous = assertFailsWith<IllegalArgumentException> {
            CompilerInputBinder(project, listOf(listOf(inside, outside))).bind(witness, directory, null, emptySet())
        }
        val message = ambiguous.message.orEmpty()
        assertContains(message, "ambiguous for :feature:compileDebugKotlin")
        assertContains(message, "build/empty-a")
        assertContains(message, "<external>/outside/empty-b")
        assertFalse(message.contains(root.toString()))
    }

    private fun empty(path: Path): Path {
        Files.createDirectories(path.parent)
        // 항목이 없는 ZIP의 end-of-central-directory 레코드만 쓴다.
        return Files.write(path, byteArrayOf(0x50, 0x4b, 0x05, 0x06) + ByteArray(18))
    }
}
