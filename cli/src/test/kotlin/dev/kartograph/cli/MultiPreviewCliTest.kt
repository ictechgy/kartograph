package dev.kartograph.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir

class MultiPreviewCliTest {
    @Test
    fun `repeatable multipreview in a dependency retains app methods and their body dependencies`(@TempDir root: Path) {
        val library = root.resolve("library").createDirectories()
        val preview = root.resolve("Preview.java").apply { writeText("""
            package androidx.compose.ui.tooling.preview;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Repeatable(Preview.Container.class)
            @Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
            public @interface Preview {
                String name();
                @Retention(RetentionPolicy.CLASS) @Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
                public @interface Container { Preview[] value(); }
            }
        """.trimIndent()) }
        val devices = root.resolve("Devices.java").apply { writeText("""
            package lib;
            import androidx.compose.ui.tooling.preview.Preview;
            @Preview(name="small") @Preview(name="large")
            public @interface Devices {}
        """.trimIndent()) }
        val nested = root.resolve("Nested.java").apply { writeText("package lib; @Devices public @interface Nested {}") }
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler())
        assertEquals(0, compiler.run(null, null, null, "-g", "-d", library.toString(),
            preview.toString(), devices.toString(), nested.toString()))
        val classes = root.resolve("classes").createDirectories()
        val source = root.resolve("Screen.java").apply { writeText("""
            package app;
            public class Screen {
                @lib.Nested public static void preview() { new Used(); }
                public static void unused() { new Unused(); }
            }
            class Used {}
            class Unused {}
        """.trimIndent()) }
        assertEquals(0, compiler.run(null, null, null, "-g", "-cp", library.toString(), "-d", classes.toString(), source.toString()))
        fun query(symbol: String): String {
            val output = ByteArrayOutputStream()
            val error = ByteArrayOutputStream()
            assertEquals(0, KartographCli.run(arrayOf("query", symbol, "--classes", classes.toString(),
                "--project", root.toString(), "--classpath", library.toString()), PrintStream(output), PrintStream(error)), error.toString())
            return output.toString(Charsets.UTF_8)
        }
        assertContains(query("method:app/Screen#preview()V"), "\"reason\": \"runtimeEntryPoint\"")
        assertContains(query("class:app/Used"), "\"state\": \"reachable\"")
        assertContains(query("class:app/Unused"), "\"state\": \"unreachable\"")
    }
}
