package dev.kartograph.index

import com.sun.net.httpserver.HttpServer
import dev.kartograph.core.AndroidLayoutInclude
import dev.kartograph.core.AndroidLayoutIncludeKind
import dev.kartograph.core.AndroidResourceEvidence
import dev.kartograph.core.AndroidResourceKey
import dev.kartograph.core.AndroidResourceRoot
import dev.kartograph.core.InputFingerprint
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidLayoutXmlParserTest {
    @Test
    fun `literal repeated dynamic external and unsupported includes preserve authored ordinals`() {
        val observed = parse("""<layout xmlns:app="urn:ignored"><data/>
            <LinearLayout>
                <include layout="@layout/child"/>
                <include layout="@layout/child"/>
                <include layout="@dev.example:layout/child"/>
                <include layout="@android:layout/simple_list_item_1"/>
                <include layout="@{model.layout}"/>
                <include layout="?attr/chosen"/>
                <include layout="@={model.layout}"/>
                <include layout="@string/name"/>
                <include/>
                <app:include layout="@layout/ignored"/>
            </LinearLayout></layout>""")
        assertEquals((0..8).toList(), observed.includes.map { it.ordinal })
        assertEquals(listOf(AndroidLayoutIncludeKind.LITERAL, AndroidLayoutIncludeKind.LITERAL, AndroidLayoutIncludeKind.LITERAL,
            AndroidLayoutIncludeKind.EXTERNAL_NAMESPACE, AndroidLayoutIncludeKind.DYNAMIC, AndroidLayoutIncludeKind.DYNAMIC,
            AndroidLayoutIncludeKind.DYNAMIC, AndroidLayoutIncludeKind.UNSUPPORTED, AndroidLayoutIncludeKind.UNSUPPORTED),
            observed.includes.map { it.kind })
        assertEquals(9, observed.includes.map { it.id }.distinct().size)
        assertEquals(listOf("child", "child", "child", "simple_list_item_1"), observed.includes.mapNotNull { it.target?.name })
        assertEquals("android", observed.includes[3].target!!.namespace)
        assertFailsWith<UnsupportedOperationException> { (observed.includes as MutableList<AndroidLayoutInclude>).clear() }
    }

    @Test
    fun `only unqualified layout attributes supply include targets regardless of order`() {
        val observed = parse("""<a xmlns:tools="urn:tools" xmlns:app="urn:app">
            <include tools:layout="@layout/wrong"/>
            <include app:layout="@layout/wrong"/>
            <include tools:layout="@layout/wrong" layout="@layout/child"/>
            <include layout="@layout/child" app:layout="@layout/wrong"/>
        </a>""")
        assertEquals(listOf(AndroidLayoutIncludeKind.UNSUPPORTED, AndroidLayoutIncludeKind.UNSUPPORTED,
            AndroidLayoutIncludeKind.LITERAL, AndroidLayoutIncludeKind.LITERAL), observed.includes.map { it.kind })
        assertEquals(listOf(null, null, "child", "child"), observed.includes.map { it.target?.name })
    }

    @Test
    fun `predefined and numeric escapes remain safe ordinary XML`() {
        val observed = parse("""<a title="A&amp;B &quot;quoted&quot; &apos;x&apos;">
            &lt;safe&gt; &#x1F680;
            <include layout="@layout/ch&#105;ld"/>
        </a>""")
        assertEquals("child", observed.includes.single().target!!.name)
        assertEquals(AndroidLayoutIncludeKind.LITERAL, observed.includes.single().kind)
    }

    @Test
    fun `exact retained bytes determine hash including encoding and comments`() {
        val xml = "<?xml version=\"1.0\" encoding=\"UTF-16\"?><LinearLayout><!--한글--><include layout=\"@layout/child\"/></LinearLayout>"
        val bytes = xml.toByteArray(Charsets.UTF_16)
        val expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        val observed = parseBytes(bytes)
        bytes.fill(0)
        assertEquals(expected, observed.definition.file.sha256)
        assertEquals("child", observed.includes.single().target!!.name)
        assertFalse(observed.definition.file.sha256 == parse("<LinearLayout/>").definition.file.sha256)
    }

    @Test
    fun `cycles and qualifiers are facts without recursive target expansion`() {
        val a = parse("<LinearLayout><include layout=\"@layout/b\"/></LinearLayout>", "a", "land")
        val b = parse("<LinearLayout><include layout=\"@layout/a\"/></LinearLayout>", "b")
        val evidence = AndroidResourceEvidence("app:debug", NAMESPACE, listOf(ROOT), listOf(a.definition, b.definition),
            a.includes + b.includes)
        assertEquals(2, evidence.includes.size)
        assertEquals(setOf("a", "b"), evidence.includes.map { it.target!!.name }.toSet())
        assertEquals("layout-land/a.xml", a.definition.relativePath)
        assertEquals("land", a.definition.qualifier)
    }

    @Test
    fun `DTD internal entities external entities and external subsets are rejected without HTTP reads`() {
        val reads = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/entity") { exchange ->
            reads.incrementAndGet()
            val body = "<!ENTITY remote 'loaded'>".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/entity"
            val documents = listOf(
                "<!DOCTYPE LinearLayout><LinearLayout/>",
                "<!DOCTYPE LinearLayout [<!ENTITY local 'child'>]><LinearLayout/>",
                "<!DOCTYPE LinearLayout [<!ENTITY remote SYSTEM '$url'>]><LinearLayout>&remote;</LinearLayout>",
                "<!DOCTYPE LinearLayout SYSTEM '$url'><LinearLayout/>",
                "<!DOCTYPE LinearLayout [<!ENTITY % remote SYSTEM '$url'>%remote;]><LinearLayout/>",
            )
            documents.forEach { xml -> assertFailsWith<AndroidResourceScanningException> { parse(xml) } }
            assertEquals(0, reads.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `malformed XML and unsafe source identities fail closed`() {
        for (xml in listOf("", "<LinearLayout>", "<a/><b/>", "<a>&unknown;</a>", "<a><include layout='@layout/x'></a>")) {
            assertFailsWith<AndroidResourceScanningException> { parse(xml) }
        }
        for (path in listOf("../layout/screen.xml", "/tmp/screen.xml", "external/slot/layout/screen.xml", "src/./layout/screen.xml")) {
            assertFailsWith<IllegalArgumentException> {
                AndroidLayoutXmlParser.parse(KEY, ROOT.id, path, "layout/screen.xml", "", "<LinearLayout/>".toByteArray())
            }
        }
    }

    @Test
    fun `byte and nesting limits accept boundary and reject one beyond`() {
        val padding = AndroidLayoutXmlParser.MAX_XML_BYTES - "<a></a>".length
        assertTrue(parse("<a>" + " ".repeat(padding) + "</a>").includes.isEmpty())
        assertFailsWith<IllegalArgumentException> { parse("<a>" + " ".repeat(padding + 1) + "</a>") }
        val depth = AndroidLayoutXmlParser.MAX_DEPTH
        assertTrue(parse("<a>".repeat(depth) + "</a>".repeat(depth)).includes.isEmpty())
        assertFailsWith<IllegalArgumentException> { parse("<a>".repeat(depth + 1) + "</a>".repeat(depth + 1)) }
    }

    @Test
    fun `element attribute and include limits are explicit rather than truncating`() {
        val elements = AndroidLayoutXmlParser.MAX_ELEMENTS - 1
        assertTrue(parse("<a>" + "<v/>".repeat(elements) + "</a>").includes.isEmpty())
        assertFailsWith<IllegalArgumentException> { parse("<a>" + "<v/>".repeat(elements + 1) + "</a>") }
        val attributes = (0 until AndroidLayoutXmlParser.MAX_ATTRIBUTES / 2).joinToString(" ") { "a$it='x'" }
        assertTrue(parse("<a><v $attributes/><v $attributes/></a>").includes.isEmpty())
        assertFailsWith<IllegalArgumentException> { parse("<a extra='x'><v $attributes/><v $attributes/></a>") }
        val namespaces = (0 until AndroidLayoutXmlParser.MAX_ATTRIBUTES / 2).joinToString(" ") { "xmlns:p$it='urn:$it'" }
        assertTrue(parse("<a><v $namespaces/><v $namespaces/></a>").includes.isEmpty())
        assertFailsWith<IllegalArgumentException> { parse("<a xmlns:extra='urn:extra'><v $namespaces/><v $namespaces/></a>") }
        val includes = AndroidLayoutXmlParser.MAX_INCLUDES
        assertEquals(includes, parse("<a>" + "<include/>".repeat(includes) + "</a>").includes.size)
        assertFailsWith<IllegalArgumentException> { parse("<a>" + "<include/>".repeat(includes + 1) + "</a>") }
    }

    private fun parse(xml: String, name: String = "screen", qualifier: String = ""): AndroidLayoutXmlObservation =
        parseBytes(xml.toByteArray(Charsets.UTF_8), name, qualifier)

    private fun parseBytes(bytes: ByteArray, name: String = "screen", qualifier: String = ""): AndroidLayoutXmlObservation {
        val relative = "layout" + if (qualifier.isEmpty()) "/$name.xml" else "-$qualifier/$name.xml"
        return AndroidLayoutXmlParser.parse(AndroidResourceKey(NAMESPACE, "layout", name), ROOT.id,
            ROOT.input.path + "/" + relative, relative, qualifier, bytes)
    }

    private companion object {
        const val NAMESPACE = "dev.example"
        val ROOT = AndroidResourceRoot(InputFingerprint("android-resources", "src/main/res", "a".repeat(64)))
        val KEY = AndroidResourceKey(NAMESPACE, "layout", "screen")
    }
}
