package dev.kartograph.index

import dev.kartograph.core.EdgeKind
import dev.kartograph.core.EdgeOrigin
import dev.kartograph.core.NodeId
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

/** 실제 javac Signature에서만 남는 타입을 선언 소유 간선과 캐시 양쪽에서 검증한다. */
class GenericSignatureReferenceTest {
    @Test
    fun `javac generic signatures preserve owned references without duplicate descriptor weights`(@TempDir root: Path) {
        val source = root.resolve("Api.java")
        Files.writeString(source, """
            package probe;
            import java.util.List;
            import java.util.Map;
            public class Api<T extends Bound & BoundInterface> extends Parent<SuperDto> implements Contract<InterfaceDto> {
                public List<FieldDto> field;
                public Map<FieldDto, List<? extends FieldDto[]>> nested;
                public Outer<OuterDto>.Inner<InnerDto> inner;
                public FieldDto plain;
                public List<ReturnDto> returns() { return null; }
                public void parameters(List<ParameterDto> values) {}
                public <M extends MethodBound & MethodInterface> M bounded(M value) { return value; }
                public <E extends GenericException> void throwing() throws E {}
                public Parent<FieldDto> duplicate(Parent<FieldDto> value) { return value; }
                private String decoy = "probe.Decoy";
            }
            class Bound {}
            interface BoundInterface {}
            class Parent<T> {}
            interface Contract<T> {}
            class SuperDto {}
            class InterfaceDto {}
            class FieldDto {}
            class ReturnDto {}
            class ParameterDto {}
            class MethodBound {}
            interface MethodInterface {}
            class GenericException extends Exception {}
            class Outer<T> { class Inner<U> {} }
            class OuterDto {}
            class InnerDto {}
            class Decoy {}
        """.trimIndent())
        val classes = root.resolve("classes").createDirectories()
        val errors = ByteArrayOutputStream()
        assertEquals(0, requireNotNull(ToolProvider.getSystemJavaCompiler()).run(null, null, errors,
            "--release", "17", "-g", "-d", classes.toString(), source.toString()), errors.toString())
        val cache = ClassIndexCache(root.resolve("cache"), "generic-signature-regression")
        val uncached = ClassFileIndexer().indexWithObservations(listOf(classes))
        val cold = ClassFileIndexer(cache).indexWithObservations(listOf(classes))
        val warm = ClassFileIndexer(cache).indexWithObservations(listOf(classes))
        assertEquals(uncached.graph.nodes, cold.graph.nodes)
        assertEquals(uncached.graph.edges, cold.graph.edges)
        assertEquals(cold.graph.edges, warm.graph.edges)
        assertEquals(cold.graph.externalCalls, warm.graph.externalCalls)
        assertEquals(cold.statistics.classFiles, warm.statistics.cacheHits)
        assertEquals(0, warm.statistics.parsedClasses)
        val graph = warm.graph
        fun references(owner: NodeId): Set<String> = graph.outgoingEdgesFrom(owner)
            .filter { it.kind == EdgeKind.REFERENCE && it.target != JvmNodeId.classId("probe/Api") }
            .map { edge ->
                assertEquals(1, edge.weight, "signature and descriptor must not double-count ${edge.target}")
                assertEquals(EdgeOrigin.BYTECODE, edge.origin)
                edge.target.value.removePrefix("class:probe/")
            }.toSet()
        assertEquals(setOf("Bound", "BoundInterface", "SuperDto", "InterfaceDto"), references(JvmNodeId.classId("probe/Api")))
        assertEquals(setOf("FieldDto"), references(JvmNodeId.fieldId("probe/Api", "field", "Ljava/util/List;")))
        assertEquals(setOf("FieldDto"), references(JvmNodeId.fieldId("probe/Api", "nested", "Ljava/util/Map;")))
        assertEquals(setOf("FieldDto"), references(JvmNodeId.fieldId("probe/Api", "plain", "Lprobe/FieldDto;")))
        assertEquals(setOf("Outer", "Outer\$Inner", "OuterDto", "InnerDto"),
            references(JvmNodeId.fieldId("probe/Api", "inner", "Lprobe/Outer\$Inner;")))
        assertEquals(setOf("ReturnDto"), references(JvmNodeId.methodId("probe/Api", "returns", "()Ljava/util/List;")))
        assertEquals(setOf("ParameterDto"), references(JvmNodeId.methodId("probe/Api", "parameters", "(Ljava/util/List;)V")))
        assertEquals(setOf("MethodBound", "MethodInterface"), references(JvmNodeId.methodId("probe/Api", "bounded", "(Lprobe/MethodBound;)Lprobe/MethodBound;")))
        assertEquals(setOf("GenericException"), references(JvmNodeId.methodId("probe/Api", "throwing", "()V")))
        assertEquals(setOf("Parent", "FieldDto"), references(JvmNodeId.methodId("probe/Api", "duplicate", "(Lprobe/Parent;)Lprobe/Parent;")))
        assertFalse(graph.edges.any { it.source.value.contains("probe/Api") && it.target == JvmNodeId.classId("probe/Decoy") })
    }

    @Test
    fun `malformed and excessive generic signatures fail closed`(@TempDir root: Path) {
        listOf("L", "Ljava/util/List<".repeat(514) + "Ljava/lang/String;" + ">;".repeat(514)).forEach { signature ->
            val classes = root.resolve("classes").createDirectories()
            classes.resolve("Broken.class").writeBytes(ClassWriter(0).apply {
                visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "probe/Broken", null, "java/lang/Object", null)
                visitField(Opcodes.ACC_PUBLIC, "value", "Ljava/util/List;", signature, null).visitEnd()
                visitEnd()
            }.toByteArray())
            assertFailsWith<ClassIndexingException> { ClassFileIndexer().index(listOf(classes)) }
        }
    }
}
