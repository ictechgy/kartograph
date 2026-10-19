package dev.kartograph.index

import dev.kartograph.core.RetentionReason
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.Reader
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import javax.xml.stream.EventFilter
import javax.xml.stream.StreamFilter
import javax.xml.stream.XMLEventReader
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLReporter
import javax.xml.stream.XMLResolver
import javax.xml.stream.XMLStreamReader
import javax.xml.stream.util.XMLEventAllocator
import javax.xml.transform.Source
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import org.junit.jupiter.api.io.TempDir

class AndroidXmlScannerTest {
    @Test
    fun `finds custom views and named fragments while ignoring comments`(@TempDir projectRoot: Path) {
        val resourceRoot = projectRoot.resolve("app/src/main/res")
        val layout = resourceRoot.resolve("layout/screen.xml")
        layout.parent.createDirectories()
        layout.writeText(
            """
                <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
                  <dev.fixture.CustomView />
                  <!-- <dev.fixture.CommentedOutView /> -->
                  <view class="dev.fixture.ClassAttributeView" />
                  <fragment android:name="dev.fixture.NamedFragment" />
                </LinearLayout>
            """.trimIndent(),
        )

        val references = AndroidXmlScanner(projectRoot).scan(resourceRoot)

        assertEquals(
            listOf(
                "dev/fixture/CustomView" to 2,
                "dev/fixture/ClassAttributeView" to 4,
                "dev/fixture/NamedFragment" to 5,
            ),
            references.map { it.nodeId.value.removePrefix("class:") to assertNotNull(it.location).line },
        )
        assertEquals(setOf(RetentionReason.XML_LAYOUT), references.map { it.reason }.toSet())
        assertEquals(
            setOf("app/src/main/res/layout/screen.xml"),
            references.map { assertNotNull(it.location).path }.toSet(),
        )
    }

    @Test
    fun `records generated resources under a relocated build directory with the conventional build prefix`(@TempDir root: Path) {
        val projectRoot = root.resolve("repo/feature").createDirectories()
        val buildRoot = root.resolve("outside/feature")
        val resourceRoot = buildRoot.resolve("generated/res/debug")
        resourceRoot.resolve("layout").createDirectories()
        resourceRoot.resolve("layout/generated.xml").writeText("<dev.fixture.GeneratedView />")

        val reference = AndroidXmlScanner(projectRoot, buildRoot).scan(resourceRoot).single()

        assertEquals("build/generated/res/debug/layout/generated.xml", assertNotNull(reference.location).path)
        assertFailsWith<AndroidResourceScanningException> { AndroidXmlScanner(projectRoot).scan(resourceRoot) }
    }

    @Test
    fun `rejects external XML entities without exposing their path or content`(@TempDir projectRoot: Path) {
        val secret = projectRoot.parent.resolve("outside-secret.txt")
        secret.writeText("not-for-xml")
        val resourceRoot = projectRoot.resolve("app/src/main/res")
        val layout = resourceRoot.resolve("layout/external.xml")
        layout.parent.createDirectories()
        layout.writeText(
            """
                <!DOCTYPE view [<!ENTITY secret SYSTEM "${secret.toUri()}">]>
                <view class="&secret;" />
            """.trimIndent(),
        )

        val error = assertFailsWith<AndroidResourceScanningException> {
            AndroidXmlScanner(projectRoot).scan(resourceRoot)
        }

        assertFalse(error.message.orEmpty().contains(secret.toString()))
        assertFalse(error.message.orEmpty().contains("not-for-xml"))
    }

    @Test
    fun `points to a custom view name at the start of a multiline tag`(@TempDir projectRoot: Path) {
        val resourceRoot = projectRoot.resolve("res")
        val layout = resourceRoot.resolve("layout/multiline.xml")
        layout.parent.createDirectories()
        layout.writeText(
            """
                <dev.fixture.MultilineView
                    xmlns:android="http://schemas.android.com/apk/res/android"
                    android:layout_width="match_parent"
                    android:layout_height="match_parent" />
            """.trimIndent(),
        )

        val reference = AndroidXmlScanner(projectRoot).scan(resourceRoot).single()

        assertEquals(1, assertNotNull(reference.location).line)
    }

    @Test
    fun `reads the fragment hosted by FragmentContainerView`(@TempDir projectRoot: Path) {
        val resourceRoot = projectRoot.resolve("res")
        val layout = resourceRoot.resolve("layout/fragment.xml")
        layout.parent.createDirectories()
        layout.writeText(
            """
                <androidx.fragment.app.FragmentContainerView
                    xmlns:android="http://schemas.android.com/apk/res/android"
                    android:name="dev.fixture.HostedFragment" />
            """.trimIndent(),
        )

        val reference = AndroidXmlScanner(projectRoot).scan(resourceRoot).single()

        assertEquals("class:dev/fixture/HostedFragment", reference.nodeId.value)
        assertEquals(3, assertNotNull(reference.location).line)
    }

    @Test
    fun `treats a user class named FragmentContainerView as a custom view`(@TempDir projectRoot: Path) {
        val resourceRoot = projectRoot.resolve("res")
        val layout = resourceRoot.resolve("layout/custom.xml")
        layout.parent.createDirectories()
        layout.writeText("<dev.fixture.FragmentContainerView />")

        val reference = AndroidXmlScanner(projectRoot).scan(resourceRoot).single()

        assertEquals("class:dev/fixture/FragmentContainerView", reference.nodeId.value)
    }

    @Test
    fun `retains nested custom view binary names`(@TempDir projectRoot: Path) {
        val resourceRoot = projectRoot.resolve("res")
        val layout = resourceRoot.resolve("layout/nested.xml")
        layout.parent.createDirectories()
        layout.writeText("<view class=\"dev.fixture.Outer${'$'}InnerView\" />")

        val reference = AndroidXmlScanner(projectRoot).scan(resourceRoot).single()

        assertEquals("class:dev/fixture/Outer${'$'}InnerView", reference.nodeId.value)
    }

    @Test
    fun `scan shares one XML factory and parses the bytes it read for line positions`(@TempDir projectRoot: Path) {
        // 리뷰 지적 재현(index-performance#3): resource XML마다 XMLInputFactory를 새로 만들고, 줄 위치용으로 읽은 파일을 StAX가 다시 열었다.
        // 문서화된 factory 조회 규칙(factory class 이름의 시스템 속성)으로 계수용 factory를 끼워 생성 횟수와 reader 입력을 센다.
        val resourceRoot = projectRoot.resolve("res")
        writeXml(resourceRoot.resolve("layout/a.xml"), "<dev.fixture.AView />")
        writeXml(resourceRoot.resolve("drawable/b.xml"), "<vector />")
        writeXml(resourceRoot.resolve("layout/c.xml"), "<view class=\"dev.fixture.CView\" />")
        val property = XMLInputFactory::class.java.name
        val previous = System.getProperty(property)
        CountingXmlInputFactory.created.set(0)
        CountingXmlInputFactory.inputs.clear()
        System.setProperty(property, CountingXmlInputFactory::class.java.name)
        val references = try {
            AndroidXmlScanner(projectRoot).scan(resourceRoot)
        } finally {
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
        }

        assertEquals(listOf("class:dev/fixture/AView", "class:dev/fixture/CView"), references.map { it.nodeId.value })
        // 스캔 한 번에 factory 하나, 파일마다 reader 하나다. reader는 줄 위치를 만든 바이트를 메모리에서 읽는다 — 파일을 다시 열지 않는다.
        assertEquals(1, CountingXmlInputFactory.created.get())
        assertEquals(List(3) { ByteArrayInputStream::class.java }, CountingXmlInputFactory.inputs.toList())
    }

    @Test
    fun `rejects XML that is not UTF-8 even when it references no class`(@TempDir projectRoot: Path) {
        // 줄 위치를 쓰지 않는 파일도 엄격한 UTF-8로 읽는다 — 선언한 인코딩으로는 파싱되는 Latin-1 XML도 조용히 통과시키지 않는다.
        val resourceRoot = projectRoot.resolve("res")
        val strings = resourceRoot.resolve("values/strings.xml")
        strings.parent.createDirectories()
        strings.writeBytes(
            "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n<resources><string name=\"cafe\">café</string></resources>\n"
                .toByteArray(Charsets.ISO_8859_1),
        )

        val error = assertFailsWith<AndroidResourceScanningException> { AndroidXmlScanner(projectRoot).scan(resourceRoot) }

        assertEquals("Android XML cannot be read", error.message)
    }

    @Test
    fun `keeps file order and line numbers across line endings and reference-free files`(@TempDir projectRoot: Path) {
        // index-performance#3 수정의 보존 대조: 한 번 읽은 바이트로 만든 줄 목록도 Files.readAllLines처럼 `\n`·`\r`·`\r\n`에서 끊고
        // 끝 줄바꿈 뒤에 빈 줄을 만들지 않아, 여러 파일의 결과 순서와 줄 번호가 그대로다.
        val resourceRoot = projectRoot.resolve("res")
        writeXml(resourceRoot.resolve("layout/b_crlf.xml"), "<LinearLayout>\r\n\r\n  <dev.fixture.CrlfView />\r\n</LinearLayout>\r\n")
        writeXml(resourceRoot.resolve("layout/a_cr.xml"), "<LinearLayout>\r  <view\r    class=\"dev.fixture.CrView\" />\r</LinearLayout>")
        writeXml(resourceRoot.resolve("drawable/icon.xml"), "<vector>\n  <path />\n</vector>\n")
        writeXml(
            resourceRoot.resolve("layout/c_lf.xml"),
            "\n<LinearLayout>\n  <dev.fixture.FirstView />\n  <fragment android:name=\"dev.fixture.LfFragment\"\n" +
                "      xmlns:android=\"http://schemas.android.com/apk/res/android\" />\n</LinearLayout>",
        )

        val references = AndroidXmlScanner(projectRoot).scan(resourceRoot)

        assertEquals(
            listOf(
                Triple("res/layout/a_cr.xml", "class:dev/fixture/CrView", 3),
                Triple("res/layout/b_crlf.xml", "class:dev/fixture/CrlfView", 3),
                Triple("res/layout/c_lf.xml", "class:dev/fixture/FirstView", 3),
                Triple("res/layout/c_lf.xml", "class:dev/fixture/LfFragment", 4),
            ),
            references.map { Triple(assertNotNull(it.location).path, it.nodeId.value, it.location?.line) },
        )
    }

    private fun writeXml(path: Path, text: String) {
        path.parent.createDirectories()
        path.writeText(text)
    }

    /**
     * 생성 횟수와 stream reader 입력을 기록하는 XMLInputFactory다. factory class 이름의 시스템 속성으로
     * [XMLInputFactory.newFactory]가 이 class를 만들게 한다. 동작은 모두 JDK 기본 구현에 맡긴다.
     */
    class CountingXmlInputFactory : XMLInputFactory() {
        private val delegate: XMLInputFactory = XMLInputFactory.newDefaultFactory()

        init {
            created.incrementAndGet()
        }

        override fun createXMLStreamReader(stream: InputStream): XMLStreamReader {
            inputs += stream.javaClass
            return delegate.createXMLStreamReader(stream)
        }

        override fun createXMLStreamReader(reader: Reader): XMLStreamReader = delegate.createXMLStreamReader(reader)
        override fun createXMLStreamReader(source: Source): XMLStreamReader = delegate.createXMLStreamReader(source)
        override fun createXMLStreamReader(stream: InputStream, encoding: String?): XMLStreamReader =
            delegate.createXMLStreamReader(stream, encoding)
        override fun createXMLStreamReader(systemId: String?, stream: InputStream): XMLStreamReader =
            delegate.createXMLStreamReader(systemId, stream)
        override fun createXMLStreamReader(systemId: String?, reader: Reader): XMLStreamReader =
            delegate.createXMLStreamReader(systemId, reader)
        override fun createXMLEventReader(reader: Reader): XMLEventReader = delegate.createXMLEventReader(reader)
        override fun createXMLEventReader(systemId: String?, reader: Reader): XMLEventReader =
            delegate.createXMLEventReader(systemId, reader)
        override fun createXMLEventReader(reader: XMLStreamReader): XMLEventReader = delegate.createXMLEventReader(reader)
        override fun createXMLEventReader(source: Source): XMLEventReader = delegate.createXMLEventReader(source)
        override fun createXMLEventReader(stream: InputStream): XMLEventReader = delegate.createXMLEventReader(stream)
        override fun createXMLEventReader(stream: InputStream, encoding: String?): XMLEventReader =
            delegate.createXMLEventReader(stream, encoding)
        override fun createXMLEventReader(systemId: String?, stream: InputStream): XMLEventReader =
            delegate.createXMLEventReader(systemId, stream)
        override fun createFilteredReader(reader: XMLStreamReader, filter: StreamFilter): XMLStreamReader =
            delegate.createFilteredReader(reader, filter)
        override fun createFilteredReader(reader: XMLEventReader, filter: EventFilter): XMLEventReader =
            delegate.createFilteredReader(reader, filter)
        override fun getXMLResolver(): XMLResolver? = delegate.xmlResolver
        override fun setXMLResolver(resolver: XMLResolver?) {
            delegate.xmlResolver = resolver
        }
        override fun getXMLReporter(): XMLReporter? = delegate.xmlReporter
        override fun setXMLReporter(reporter: XMLReporter?) {
            delegate.xmlReporter = reporter
        }
        override fun setProperty(name: String, value: Any?) = delegate.setProperty(name, value)
        override fun getProperty(name: String): Any? = delegate.getProperty(name)
        override fun isPropertySupported(name: String): Boolean = delegate.isPropertySupported(name)
        override fun setEventAllocator(allocator: XMLEventAllocator?) {
            delegate.eventAllocator = allocator
        }
        override fun getEventAllocator(): XMLEventAllocator? = delegate.eventAllocator

        companion object {
            /** 만들어진 factory 수다. */
            val created = AtomicInteger()

            /** `createXMLStreamReader(InputStream)`이 받은 입력의 class다. */
            val inputs: MutableList<Class<*>> = Collections.synchronizedList(mutableListOf())
        }
    }
}
