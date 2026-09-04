package dev.kartograph.index

import dev.kartograph.core.ClassHierarchy
import java.io.IOException
import java.io.InputStream
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes

/** directory와 JAR dependency에서 method body 없이 class 상속 header만 읽는다. */
public class ClassHierarchyIndexer {
    /** 입력 순서를 보존하고 중복 class는 첫 번째 hierarchy를 사용한다. */
    public fun index(
        classpathEntries: Iterable<Path>,
        referencedSupertypes: Iterable<String> = emptyList(),
    ): ClassHierarchy {
        val supertypesByClass = linkedMapOf<String, Set<String>>()
        classpathEntries.forEach { entry ->
            val facts = when {
                entry.isDirectory() -> readDirectory(entry)
                entry.isRegularFile() && entry.fileName.toString().endsWith(".jar", ignoreCase = true) -> readJar(entry)
                else -> throw ClassHierarchyIndexingException("classpath entry must be a class directory or JAR")
            }
            facts.forEach { fact -> supertypesByClass.putIfAbsent(fact.internalName, fact.supertypes) }
        }
        expandJdkHierarchy(supertypesByClass, referencedSupertypes)
        return ClassHierarchy(supertypesByClass)
    }

    private fun expandJdkHierarchy(
        supertypesByClass: MutableMap<String, Set<String>>,
        referencedSupertypes: Iterable<String>,
    ) {
        val queue = ArrayDeque(
            (supertypesByClass.values.flatten() + referencedSupertypes)
                .filter { internalName -> internalName.startsWith("java/") }
                .sorted(),
        )
        while (queue.isNotEmpty()) {
            val internalName = queue.removeFirst()
            if (internalName in supertypesByClass) continue
            val resource = ClassLoader.getSystemResourceAsStream("$internalName.class")
                ?: throw ClassHierarchyIndexingException(
                    "JDK class hierarchy is unavailable; run kartograph with a compatible JDK",
                )
            val fact = try {
                resource.use(::readJdkClass)
            } catch (error: IOException) {
                throw ClassHierarchyIndexingException("JDK class hierarchy cannot be read", error)
            }
            supertypesByClass[fact.internalName] = fact.supertypes
            fact.supertypes.filter { name -> name.startsWith("java/") }.sorted().forEach(queue::addLast)
        }
    }

    private fun readDirectory(root: Path): List<HierarchyFact> = try {
        Files.walk(root).use { paths ->
            paths.filter { path -> Files.isRegularFile(path) && path.fileName.toString().endsWith(".class") }
                .filter { path -> !root.relativize(path).startsWith(MULTI_RELEASE_PREFIX) }
                .sorted()
                .map { path -> Files.newInputStream(path).use(::readClasspathClass) }
                .toList()
        }
    } catch (error: IOException) {
        throw ClassHierarchyIndexingException("classpath directory cannot be read", error)
    } catch (error: UncheckedIOException) {
        throw ClassHierarchyIndexingException("classpath directory cannot be read", error)
    } catch (error: SecurityException) {
        throw ClassHierarchyIndexingException("classpath directory cannot be read", error)
    }

    private fun readJar(jar: Path): List<HierarchyFact> = try {
        JarFile(jar.toFile(), false).use { archive ->
            val multiRelease = archive.manifest?.mainAttributes?.getValue("Multi-Release")
                ?.equals("true", ignoreCase = true) == true
            archive.entries().asSequence()
                .mapNotNull { entry -> entry.toClassCandidate(multiRelease) }
                .groupBy(ClassCandidate::logicalName)
                .toSortedMap()
                .values
                .map { candidates -> candidates.maxBy(ClassCandidate::version).entry }
                .map { entry -> archive.getInputStream(entry).use(::readClasspathClass) }
                .toList()
        }
    } catch (error: IOException) {
        throw ClassHierarchyIndexingException("classpath JAR cannot be read", error)
    }

    private fun readClasspathClass(input: InputStream): HierarchyFact =
        readClass(input, "invalid class file in classpath")

    private fun readJdkClass(input: InputStream): HierarchyFact =
        readClass(input, "running JDK class hierarchy cannot be parsed; use a supported JDK")

    private fun readClass(input: InputStream, failureMessage: String): HierarchyFact {
        try {
            val visitor = HierarchyVisitor()
            val flags = ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES
            ClassReader(input).accept(visitor, flags)
            return visitor.fact()
        } catch (error: RuntimeException) {
            throw ClassHierarchyIndexingException(failureMessage, error)
        }
    }

    private fun JarEntry.toClassCandidate(multiRelease: Boolean): ClassCandidate? {
        if (isDirectory || !name.endsWith(".class")) return null
        if (!name.startsWith(MULTI_RELEASE_PREFIX)) return ClassCandidate(name, 0, this)
        if (!multiRelease) return null
        val versionAndName = name.removePrefix(MULTI_RELEASE_PREFIX)
        val version = versionAndName.substringBefore('/').toIntOrNull() ?: return null
        val logicalName = versionAndName.substringAfter('/', missingDelimiterValue = "")
        return logicalName.takeIf(String::isNotEmpty)
            ?.takeIf { version in 9..Runtime.version().feature() }
            ?.let { ClassCandidate(it, version, this) }
    }

    private companion object {
        const val MULTI_RELEASE_PREFIX = "META-INF/versions/"
    }
}

private data class ClassCandidate(val logicalName: String, val version: Int, val entry: JarEntry)

private class HierarchyVisitor : ClassVisitor(Opcodes.ASM9) {
    private lateinit var internalName: String
    private var supertypes: Set<String> = emptySet()

    override fun visit(
        version: Int,
        access: Int,
        name: String,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>,
    ) {
        internalName = name
        supertypes = buildSet {
            if (superName != null && superName != "java/lang/Object") add(superName)
            addAll(interfaces)
        }
    }

    fun fact(): HierarchyFact = HierarchyFact(internalName, supertypes)
}

private data class HierarchyFact(val internalName: String, val supertypes: Set<String>)

/** dependency classpath를 완전하게 읽지 못해 부분 hierarchy를 버릴 때 사용한다. */
public class ClassHierarchyIndexingException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
