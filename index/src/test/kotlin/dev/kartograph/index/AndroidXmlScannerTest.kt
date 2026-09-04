package dev.kartograph.index

import dev.kartograph.core.RetentionReason
import java.nio.file.Path
import kotlin.io.path.createDirectories
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
}
