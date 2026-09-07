package dev.kartograph.index

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.jar.JarFile
import kotlin.io.path.extension
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/** 현재 산출물에서 정적 그래프가 놓칠 runtime 채널을 실제 개수로 보고한다. */
public object RuntimeLimitationScanner {
    /** class 산출물과 source 시간을 읽어 현재 query에 해당하는 계량 한계만 반환한다. */
    public fun scan(classRoots: List<Path>, projectRoot: Path): List<String> {
        val counters = RuntimeCounters()
        val seenClasses = mutableSetOf<String>()
        classRoots.forEach { root ->
            when {
                Files.isDirectory(root) -> Files.walk(root).use { paths ->
                    paths.filter { Files.isRegularFile(it) && it.extension == "class" }.forEach { path ->
                        scanClass(Files.readAllBytes(path), Files.getLastModifiedTime(path), seenClasses, counters)
                    }
                }
                Files.isRegularFile(root) && root.fileName.toString().endsWith(".jar", ignoreCase = true) -> {
                    val jarTime = Files.getLastModifiedTime(root)
                    JarFile(root.toFile(), false).use { archive ->
                        archive.entries().asSequence()
                            .filter { entry ->
                                !entry.isDirectory && entry.name.endsWith(".class") &&
                                    !entry.name.startsWith("META-INF/versions/")
                            }
                            .forEach { entry ->
                                val modified = entry.time.takeIf { it >= 0 }?.let(FileTime::fromMillis) ?: jarTime
                                val bytes = archive.getInputStream(entry).use { input -> input.readAllBytes() }
                                scanClass(bytes, modified, seenClasses, counters)
                            }
                    }
                }
            }
        }
        var sourceCount = 0
        var staleCount = 0
        if (Files.isDirectory(projectRoot)) Files.walk(projectRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.extension in SOURCE_EXTENSIONS }
                .filter { path -> !isPrunedSource(projectRoot, path) }
                .forEach { source ->
                    sourceCount++
                    counters.newestClass?.let { built -> if (Files.getLastModifiedTime(source) > built) staleCount++ }
                }
        }
        return buildList {
            if (counters.dynamicRegistrations > 0) add(
                "dynamic-registration: ${counters.dynamicRegistrations} runtime component registration call(s) are absent from the manifest graph",
            )
            if (staleCount > 0) add(
                "index-staleness: $staleCount of $sourceCount source file(s) changed after the newest class file",
            )
            if (counters.nativeMethods > 0) add(
                "jni-methods: ${counters.nativeMethods} native method(s) may be called outside the JVM graph",
            )
            if (counters.reflectionCalls > 0) add(
                "reflection-strings: ${counters.reflectionCalls} Class.forName call(s) use runtime names",
            )
        }.sorted()
    }

    private fun scanClass(
        bytes: ByteArray,
        modified: FileTime,
        seenClasses: MutableSet<String>,
        counters: RuntimeCounters,
    ) {
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            private var include: Boolean = false

            override fun visit(
                version: Int,
                access: Int,
                name: String,
                signature: String?,
                superName: String?,
                interfaces: Array<out String>,
            ) {
                include = seenClasses.add(name)
                if (include && counters.newestClass?.let { modified > it } != false) counters.newestClass = modified
            }

            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor? {
                if (!include) return null
                if (access and Opcodes.ACC_NATIVE != 0) counters.nativeMethods++
                return object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitMethodInsn(
                        opcode: Int,
                        owner: String,
                        name: String,
                        descriptor: String,
                        isInterface: Boolean,
                    ) {
                        if (owner == "java/lang/Class" && name == "forName") counters.reflectionCalls++
                        if (name in DYNAMIC_REGISTRATION_METHODS && owner in REGISTRATION_OWNERS) {
                            counters.dynamicRegistrations++
                        }
                    }
                }
            }
        }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
    }

    private fun isPrunedSource(projectRoot: Path, path: Path): Boolean =
        ProjectTraversal.isPrunedSource(projectRoot, path)

    private data class RuntimeCounters(
        var reflectionCalls: Int = 0,
        var nativeMethods: Int = 0,
        var dynamicRegistrations: Int = 0,
        var newestClass: FileTime? = null,
    )

    private val SOURCE_EXTENSIONS = setOf("kt", "java")
    private val DYNAMIC_REGISTRATION_METHODS = setOf(
        "registerReceiver", "registerComponentCallbacks", "registerActivityLifecycleCallbacks",
    )
    private val REGISTRATION_OWNERS = setOf(
        "android/app/Activity",
        "android/app/Application",
        "android/content/Context",
        "android/content/ContextWrapper",
        "androidx/core/content/ContextCompat",
    )
}
