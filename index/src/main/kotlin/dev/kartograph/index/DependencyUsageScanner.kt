package dev.kartograph.index

import dev.kartograph.core.DependencyUsage
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode

/** 컴파일된 참조를 main/test별로 수집하고 JVM·Kotlin의 공개 타입 노출 근거를 분리한다. */
public class DependencyUsageScanner {
    /** 동일 JVM 이름은 첫 class root의 정의를 사용하며, 손상된 classfile은 전체 입력 실패다. */
    public fun scan(roots: Iterable<Path>): DependencyUsage {
        val facts = linkedMapOf<String, ClassUsage>()
        try {
            roots.forEach { root ->
                when {
                    Files.isDirectory(root) -> Files.walk(root).use { paths ->
                        paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".class") }.sorted()
                            .forEach { read(Files.readAllBytes(it)).let { item -> facts.putIfAbsent(item.name, item) } }
                    }
                    Files.isRegularFile(root) && root.fileName.toString().endsWith(".jar", true) -> JarFile(root.toFile()).use { jar ->
                        jar.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".class") && !it.name.startsWith("META-INF/versions/") }
                            .sortedBy { it.name }.forEach { entry ->
                                jar.getInputStream(entry).use { read(it.readBytes()) }.let { facts.putIfAbsent(it.name, it) }
                            }
                    }
                    else -> throw ClassIndexingException("class root must be an existing class directory or JAR")
                }
            }
        } catch (error: IOException) {
            throw ClassIndexingException("dependency analysis class roots cannot be read", error)
        } catch (error: UncheckedIOException) {
            throw ClassIndexingException("dependency analysis class roots cannot be read", error)
        } catch (error: RuntimeException) {
            if (error is ClassIndexingException) throw error
            throw ClassIndexingException("invalid class file in dependency analysis inputs", error)
        }
        if (facts.isEmpty()) throw ClassIndexingException("no class files were supplied; compile the project before dependency analysis")
        val aliases = facts.values.flatMapTo(mutableSetOf()) { it.declaredAliases }
        val unownedAliases = facts.values.flatMapTo(mutableSetOf()) { it.referencedAliases } - aliases
        val modular = facts.values.any { it.name == "module-info" }
        var complete = facts.values.all { it.complete } && unownedAliases.isEmpty() && !modular
        val visible = mutableMapOf<String, Boolean>()
        fun exposed(item: ClassUsage): Boolean {
            visible[item.name]?.let { return it }
            val chain = mutableSetOf<String>()
            var current: ClassUsage? = item
            var result = true
            while (current != null) {
                if (!chain.add(current.name)) { complete = false; result = false; break }
                if (!current.visible) { result = false; break }
                val parent = current.outer ?: break
                current = facts[parent]
                if (current == null) { complete = false; result = false }
            }
            visible[item.name] = result
            return result
        }
        val api = if (modular) sortedSetOf() else facts.values.filter(::exposed).flatMapTo(sortedSetOf()) { it.api }
        if (api.any { name -> facts[name]?.let { !exposed(it) } == true }) complete = false
        val limitations = if (complete) emptyList() else listOf(
            "dependency-api-incomplete: Kotlin metadata/typealias ownership, internal exported paths or Java module API could not be fully classified; absence-based advice is withheld",
        )
        return DependencyUsage(facts.values.flatMapTo(sortedSetOf()) { it.references }, api,
            facts.keys.toSortedSet(), complete, limitations)
    }

    private fun read(bytes: ByteArray): ClassUsage {
        val reader = ClassReader(bytes)
        val node = ClassNode()
        reader.accept(node, ClassReader.SKIP_FRAMES)
        val references = ReferenceVisitor().also(node::accept).referenced
        var metadata: MetadataAnnotationValues? = null
        reader.accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitAnnotation(descriptor: String, visible: Boolean) =
                if (descriptor == "Lkotlin/Metadata;") MetadataAnnotationValues().also { metadata = it } else null
        }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        val kotlin = metadata?.toMetadata()?.let { KotlinDependencyAbi.read(it, node.name) }
        val inner = node.innerClasses.firstOrNull { it.name == node.name }
        val access = inner?.access ?: node.access
        val visible = node.outerMethod == null && notPrivate(access) && (kotlin?.visible ?: true)
        val api = sortedSetOf<String>()
        api += listOfNotNull(node.superName) + node.interfaces + signatureClassNames(node.signature)
        (node.visibleAnnotations.orEmpty() + node.invisibleAnnotations.orEmpty())
            .filter { it.desc != "Lkotlin/Metadata;" }.forEach { it.accept(ReferenceAnnotationVisitor(api)); api += descriptorClassNames(it.desc) }
        (node.visibleTypeAnnotations.orEmpty() + node.invisibleTypeAnnotations.orEmpty()).forEach {
            it.accept(ReferenceAnnotationVisitor(api)); api += descriptorClassNames(it.desc)
        }
        for (field in node.fields) {
            val known = kotlin?.fields?.get(field.name + ":" + field.desc)
            if (!notPrivate(field.access) || !(known ?: !synthetic(field.access))) continue
            api += descriptorClassNames(field.desc) + signatureClassNames(field.signature, true)
            (field.visibleAnnotations.orEmpty() + field.invisibleAnnotations.orEmpty() +
                field.visibleTypeAnnotations.orEmpty() + field.invisibleTypeAnnotations.orEmpty()).forEach {
                api += descriptorClassNames(it.desc); it.accept(ReferenceAnnotationVisitor(api))
            }
        }
        for (method in node.methods) {
            val key = method.name + method.desc
            val published = method.invisibleAnnotations.orEmpty().any { it.desc == "Lkotlin/PublishedApi;" }
            val known = kotlin?.methods?.get(key)
            if (!(published || (known ?: (notPrivate(method.access) && !synthetic(method.access))))) continue
            if (method.name == "<clinit>") continue
            val visitor = ReferenceVisitor()
            if (key in kotlin?.inlineMethods.orEmpty()) {
                method.accept(visitor)
            } else {
                // 명령·지역 변수·catch 타입은 구현 사용이다. 선언·annotation·Signature만 ABI에 남긴다.
                declaration(method).accept(visitor)
            }
            api += visitor.referenced
        }
        if (kotlin != null) { references += kotlin.allTypes; api += kotlin.apiTypes }
        return ClassUsage(node.name, inner?.outerName ?: node.outerClass, visible, references, api, kotlin?.complete ?: true,
            kotlin?.declaredAliases.orEmpty(), kotlin?.referencedAliases.orEmpty())
    }

    private fun declaration(method: MethodNode): MethodNode = MethodNode(
        method.access, method.name, method.desc, method.signature, method.exceptions.toTypedArray(),
    ).also { copy ->
        copy.visibleAnnotations = method.visibleAnnotations
        copy.invisibleAnnotations = method.invisibleAnnotations
        copy.visibleTypeAnnotations = method.visibleTypeAnnotations
        copy.invisibleTypeAnnotations = method.invisibleTypeAnnotations
        copy.visibleParameterAnnotations = method.visibleParameterAnnotations
        copy.invisibleParameterAnnotations = method.invisibleParameterAnnotations
        copy.annotationDefault = method.annotationDefault
    }

    private fun notPrivate(access: Int): Boolean = access and Opcodes.ACC_PRIVATE == 0
    private fun synthetic(access: Int): Boolean = access and Opcodes.ACC_SYNTHETIC != 0
    private data class ClassUsage(val name: String, val outer: String?, val visible: Boolean,
        val references: Set<String>, val api: Set<String>, val complete: Boolean,
        val declaredAliases: Set<String>, val referencedAliases: Set<String>)
}
