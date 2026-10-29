package dev.kartograph.index

import dev.kartograph.core.*
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import java.util.HexFormat
import java.util.jar.JarFile
import javax.xml.stream.XMLStreamConstants
import org.objectweb.asm.*

/** 보존 root를 만들지 않고 compiled annotation과 개별 XML View의 관측만 수집한다. */
public object AndroidBindingScanner {
    public val ANNOTATIONS: Set<String> = setOf("androidx/databinding/BindingAdapter", "android/databinding/BindingAdapter")
    public const val MAX_BYTES: Int = 64 * 1024 * 1024
    public const val MAX_ROWS: Int = 50_000

    public fun scan(
        graph: CodeGraph, classRoots: List<Path>, resourceRoots: List<Path>, project: Path,
        overlayRoots: List<Path> = emptyList(), buildRoot: Path? = null,
    ): AndroidBindingObservations {
        val methods = graph.nodes.values.filter { node -> node.annotations.any(ANNOTATIONS::contains) }
        if (methods.isEmpty()) return AndroidBindingObservations(emptyList(), emptyList(), emptyList())
        val owners = methods.mapNotNull { it.jvmSignature?.substringBefore('#') }.toSet()
        val adapters = ArrayList<AndroidBindingAdapter>()
        val elements = ArrayList<AndroidBindingElement>()
        val layouts = ArrayList<AndroidBindingLayout>()
        val limitations = ArrayList<String>()
        val seen = HashSet<String>()
        var bytes = 0L
        fun read(input: java.io.InputStream, maximum: Int): ByteArray {
            val allowed = minOf(maximum.toLong(), MAX_BYTES - bytes).toInt()
            require(allowed > 0) { "aggregate byte budget exceeded" }
            val content = input.readNBytes(allowed + 1)
            require(content.size <= allowed) { "input byte budget exceeded" }
            bytes += content.size
            return content
        }
        fun accept(content: ByteArray) {
            val reader = ClassReader(content)
            if (reader.className !in owners || !seen.add(reader.className)) return
            reader.accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor? {
                    val id = JvmNodeId.methodId(reader.className, name, descriptor)
                    if (graph.nodes[id]?.annotations?.any(ANNOTATIONS::contains) != true) return null
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
                            if (Type.getType(descriptor).internalName !in ANNOTATIONS) return null
                            val attributes = ArrayList<String>()
                            var requireAll = true
                            return object : AnnotationVisitor(Opcodes.ASM9) {
                                override fun visit(name: String?, value: Any?) {
                                    if (name == "requireAll" && value is Boolean) requireAll = value
                                }
                                override fun visitArray(name: String?): AnnotationVisitor? = if (name != "value") null else object : AnnotationVisitor(Opcodes.ASM9) {
                                    override fun visit(name: String?, value: Any?) {
                                        require(attributes.size < 64 && value is String && value.length in 1..256) { "invalid binding adapter attributes" }
                                        attributes += canonicalAttribute(value)
                                    }
                                }
                                override fun visitEnd() {
                                    require(adapters.size < MAX_ROWS) { "adapter count budget exceeded" }
                                    val arguments = Type.getArgumentTypes(graph.nodes.getValue(id).jvmSignature!!.substringAfter('#').substringAfter(name))
                                    val offset = if (arguments.firstOrNull()?.descriptor in COMPONENTS) 1 else 0
                                    val values = arguments.drop(offset + 1).map { it.descriptor }
                                    val supported = access and Opcodes.ACC_PUBLIC != 0 && attributes.isNotEmpty() &&
                                        attributes.distinct().size == attributes.size &&
                                        values.size in setOf(attributes.size, attributes.size * 2) &&
                                        (values.size != attributes.size * 2 || values.take(attributes.size) == values.drop(attributes.size)) &&
                                        arguments.getOrNull(offset)?.sort == Type.OBJECT
                                    adapters += AndroidBindingAdapter(id, attributes.toList(), requireAll, values,
                                        arguments.getOrNull(offset)?.takeIf { it.sort == Type.OBJECT }?.internalName, supported)
                                }
                            }
                        }
                    }
                }
            }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        }
        val roots = (resourceRoots + overlayRoots).map { it.toAbsolutePath().normalize() }.distinct().sorted()
        val realProject = realProjectRoot(project)
        fun label(path: Path) = projectRelativePath(realProject, path, lazy { realBuildRoot(buildRoot) })
        try {
            for (root in classRoots) {
                if (Files.isDirectory(root)) for (owner in owners.sorted()) {
                    if (owner in seen) continue
                    val file = root.resolve("$owner.class").normalize()
                    require(file.startsWith(root.toAbsolutePath().normalize()) || file.startsWith(root.normalize())) { "class owner escapes root" }
                    if (Files.isRegularFile(file, NOFOLLOW_LINKS)) Files.newInputStream(file).use { accept(read(it, 16 * 1024 * 1024)) }
                } else JarFile(root.toFile(), false).use { jar ->
                    // multi-release 선택은 main indexer의 SDK 문맥에 맡긴다. 다른 사본을 읽어 값을 확정하지 않는다.
                    require(jar.manifest?.mainAttributes?.getValue("Multi-Release") != "true") { "multi-release annotation input unsupported" }
                    for (owner in owners.sorted()) if (owner !in seen) jar.getJarEntry("$owner.class")?.let { entry ->
                        jar.getInputStream(entry).use { accept(read(it, 16 * 1024 * 1024)) }
                    }
                }
            }
            if (seen != owners) limitations += "binding-annotation-input-unavailable"
            var entries = 0
            for (root in roots) {
                require(Files.isDirectory(root, NOFOLLOW_LINKS)) { "resource root unavailable" }
                val rootLabel = label(root)
                Files.walk(root, 128).use { paths ->
                    val bounded = paths.iterator().asSequence().take(100_001).toList()
                    require(bounded.size <= 100_000) { "resource traversal budget exceeded" }
                    for (file in bounded.sorted()) {
                        require(++entries <= 100_000 && !Files.isSymbolicLink(file)) { "resource traversal limit or symlink" }
                        if (Files.isDirectory(file, NOFOLLOW_LINKS)) {
                            require(root.relativize(file).nameCount < 128) { "resource depth budget exceeded" }
                            continue
                        }
                        require(Files.isRegularFile(file, NOFOLLOW_LINKS)) { "unsupported resource entry" }
                        require(!file.fileName.toString().endsWith(".flat")) { "compiled resources unsupported" }
                        val relative = root.relativize(file).toString().replace('\\', '/')
                        if (!Regex("layout(?:-[A-Za-z0-9+_-]+)?/[a-z_][a-z0-9_]*\\.xml").matches(relative)) continue
                        val content = Files.newInputStream(file).use { read(it, AndroidLayoutXmlParser.MAX_XML_BYTES) }
                        require(layouts.size < MAX_ROWS) { "layout count budget exceeded" }
                        layouts += AndroidBindingLayout(rootLabel, relative, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)))
                        elements += parse(content, label(file), relative, rootLabel)
                        require(elements.size <= MAX_ROWS) { "XML element budget exceeded" }
                    }
                }
            }
        } catch (_: Exception) {
            // 부분 입력으로 부재를 확정하지 않는다. 사용자는 모든 adapter를 unknown으로 검토한다.
            return AndroidBindingObservations(adapters, emptyList(), emptyList(), limitations = listOf("binding-input-unavailable-or-budget-exceeded"))
        }
        return AndroidBindingObservations(adapters.sortedBy { it.method }, elements, roots.map(::label),
            overlayRoots.map { label(it.toAbsolutePath().normalize()) }, limitations, layouts)
    }

    private fun parse(bytes: ByteArray, path: String, relative: String, root: String): List<AndroidBindingElement> {
        xmlSourceLines(bytes) // strict UTF-8
        val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        val reader = secureXmlInputFactory().createXMLStreamReader(ByteArrayInputStream(bytes))
        val result = ArrayList<AndroidBindingElement>()
        val variables = HashMap<String, String?>()
        var depth = 0
        var binding = false
        var ordinal = 0
        var attributes = 0
        var dataDepth: Int? = null
        try {
            while (reader.hasNext()) when (reader.next()) {
                XMLStreamConstants.DTD, XMLStreamConstants.ENTITY_REFERENCE -> error("prohibited XML content")
                XMLStreamConstants.END_ELEMENT -> {
                    if (depth == dataDepth) dataDepth = null
                    depth--
                }
                XMLStreamConstants.START_ELEMENT -> {
                    depth++
                    require(depth <= 128 && ++ordinal <= 100_000) { "XML structure budget exceeded" }
                    attributes += reader.attributeCount + reader.namespaceCount
                    require(attributes <= 10_000) { "XML attribute budget exceeded" }
                    if (depth == 1) binding = reader.localName == "layout" && reader.namespaceURI.isNullOrEmpty()
                    if (!binding) continue
                    if (depth == 2 && reader.localName == "data") dataDepth = depth
                    if (depth == 3 && dataDepth == 2 && reader.localName == "variable") {
                        val name = reader.getAttributeValue(null, "name") ?: continue
                        val type = reader.getAttributeValue(null, "type")
                        variables[name] = if (variables.containsKey(name)) null else type
                    }
                    if (dataDepth != null || reader.localName in setOf("layout", "include", "merge")) continue
                    val values = linkedMapOf<String, String?>()
                    for (i in 0 until reader.attributeCount) {
                        val value = reader.getAttributeValue(i).trim()
                        if (!(value.startsWith("@{") || value.startsWith("@={")) || !value.endsWith('}')) continue
                        val name = if (reader.getAttributeNamespace(i) == ANDROID) "android:" + reader.getAttributeLocalName(i) else reader.getAttributeLocalName(i)
                        val expression = value.substringAfter('{').dropLast(1).trim()
                        values[name] = variables[expression]
                    }
                    if (values.isEmpty()) continue
                    val view = if (reader.localName == "view") reader.getAttributeValue(null, "class") else reader.localName
                    result += AndroidBindingElement(SourceLocation(path, reader.location.lineNumber), relative, root, ordinal,
                        view?.takeIf { '.' in it }?.replace('.', '/'), values.keys.toSet(), values, hash)
                }
            }
        } finally { reader.close() }
        return result
    }

    private fun canonicalAttribute(value: String): String = if (value.startsWith("android:")) value else value.substringAfter(':')
    private val COMPONENTS = setOf("Landroidx/databinding/DataBindingComponent;", "Landroid/databinding/DataBindingComponent;")
    private const val ANDROID = "http://schemas.android.com/apk/res/android"
}
