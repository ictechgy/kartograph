package dev.kartograph.index

import dev.kartograph.core.RetentionReason
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.io.TempDir

class AndroidManifestScannerTest {
    @Test
    fun `resolves relative bare and qualified component names with evidence lines`(@TempDir projectRoot: Path) {
        val manifest = projectRoot.resolve("app/src/main/AndroidManifest.xml")
        manifest.parent.createDirectories()
        manifest.writeText(
            """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                  <application>
                    <activity android:name=".RelativeActivity" />
                    <service android:name="BareService" />
                    <receiver android:name="other.QualifiedReceiver" />
                  </application>
                </manifest>
            """.trimIndent(),
        )

        val references = AndroidManifestScanner(projectRoot).scan(manifest, namespace = "dev.fixture")

        assertEquals(
            listOf(
                "dev/fixture/RelativeActivity" to 3,
                "dev/fixture/BareService" to 4,
                "other/QualifiedReceiver" to 5,
            ),
            references.map { it.nodeId.value.removePrefix("class:") to assertNotNull(it.location).line },
        )
        assertEquals(setOf(RetentionReason.MANIFEST_COMPONENT), references.map { it.reason }.toSet())
        assertEquals(
            setOf("app/src/main/AndroidManifest.xml"),
            references.map { assertNotNull(it.location).path }.toSet(),
        )
    }

    @Test
    fun `retains class like manifest metadata names and values`(@TempDir projectRoot: Path) {
        val manifest = projectRoot.resolve("AndroidManifest.xml")
        manifest.writeText(
            """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                  <application>
                    <meta-data android:name="dev.fixture.NameInitializer" android:value="androidx.startup" />
                    <meta-data android:name="dev.fixture.marker" android:value="dev.fixture.ValueInitializer" />
                  </application>
                </manifest>
            """.trimIndent(),
        )

        val references = AndroidManifestScanner(projectRoot).scan(manifest, "dev.fixture")

        assertEquals(
            listOf(
                "class:dev/fixture/NameInitializer",
                "class:androidx/startup",
                "class:dev/fixture/marker",
                "class:dev/fixture/ValueInitializer",
            ),
            references.map { it.nodeId.value },
        )
    }

    @Test
    fun `rejects unresolved component class placeholders`(@TempDir projectRoot: Path) {
        val manifest = projectRoot.resolve("AndroidManifest.xml")
        manifest.writeText(
            """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                  <application>
                    <provider android:name="${'$'}{applicationId}.DynamicProvider" />
                  </application>
                </manifest>
            """.trimIndent(),
        )

        val error = kotlin.test.assertFailsWith<AndroidResourceScanningException> {
            AndroidManifestScanner(projectRoot).scan(manifest, "dev.fixture")
        }

        assertEquals(
            "manifest contains an unresolved class placeholder at AndroidManifest.xml:3",
            error.message,
        )
        kotlin.test.assertFalse(error.message.orEmpty().contains(projectRoot.toString()))
    }

    @Test
    fun `rejects a blank component class with relative evidence`(@TempDir projectRoot: Path) {
        val manifest = projectRoot.resolve("AndroidManifest.xml")
        manifest.writeText(
            """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                  <application>
                    <activity android:name="" />
                  </application>
                </manifest>
            """.trimIndent(),
        )

        val error = kotlin.test.assertFailsWith<AndroidResourceScanningException> {
            AndroidManifestScanner(projectRoot).scan(manifest, "dev.fixture")
        }

        assertEquals("manifest component class name is blank at AndroidManifest.xml:3", error.message)
        kotlin.test.assertFalse(error.message.orEmpty().contains(projectRoot.toString()))
    }

    @Test
    fun `does not guess whether a pure metadata placeholder is a class`(@TempDir projectRoot: Path) {
        val manifest = projectRoot.resolve("AndroidManifest.xml")
        manifest.writeText(
            """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                  <application>
                    <meta-data android:name="initializer" android:value="${'$'}{initializerClass}" />
                  </application>
                </manifest>
            """.trimIndent(),
        )

        val references = AndroidManifestScanner(projectRoot).scan(manifest, "dev.fixture")

        assertEquals(emptyList(), references)
    }

    @Test
    fun `rejects a manifest outside the project root without exposing paths`(@TempDir projectRoot: Path) {
        val outsideDirectory = Files.createTempDirectory("kartograph-manifest-outside")
        val manifest = outsideDirectory.resolve("AndroidManifest.xml")
        manifest.writeText("<manifest />")

        val error = kotlin.test.assertFailsWith<AndroidResourceScanningException> {
            AndroidManifestScanner(projectRoot).scan(manifest, namespace = "dev.fixture")
        }

        kotlin.test.assertFalse(error.message.orEmpty().contains(outsideDirectory.toString()))
    }

    @Test
    fun `points to the class value inside a multiline component tag`(@TempDir projectRoot: Path) {
        val manifest = projectRoot.resolve("AndroidManifest.xml")
        manifest.writeText(
            """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                  <application>
                    <activity
                      android:name=".MultilineActivity"
                      android:exported="true" />
                  </application>
                </manifest>
            """.trimIndent(),
        )

        val reference = AndroidManifestScanner(projectRoot).scan(manifest, "dev.fixture").single()

        assertEquals(4, assertNotNull(reference.location).line)
    }

    @Test
    fun `retains the target activity without treating an activity alias as a class`(@TempDir projectRoot: Path) {
        val manifest = projectRoot.resolve("AndroidManifest.xml")
        manifest.writeText(
            """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                  <application>
                    <activity-alias
                      android:name=".LauncherAlias"
                      android:targetActivity=".AliasTargetActivity" />
                  </application>
                </manifest>
            """.trimIndent(),
        )

        val reference = AndroidManifestScanner(projectRoot).scan(manifest, "dev.fixture").single()

        assertEquals("class:dev/fixture/AliasTargetActivity", reference.nodeId.value)
        assertEquals(5, assertNotNull(reference.location).line)
    }

    @Test
    fun `rejects an activity alias without a target activity`(@TempDir projectRoot: Path) {
        val manifest = projectRoot.resolve("AndroidManifest.xml")
        manifest.writeText(
            """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                  <application>
                    <activity-alias android:name=".LauncherAlias" />
                  </application>
                </manifest>
            """.trimIndent(),
        )

        val error = kotlin.test.assertFailsWith<AndroidResourceScanningException> {
            AndroidManifestScanner(projectRoot).scan(manifest, "dev.fixture")
        }

        assertEquals(
            "activity-alias is missing android:targetActivity at AndroidManifest.xml:3",
            error.message,
        )
        kotlin.test.assertFalse(error.message.orEmpty().contains(projectRoot.toString()))
    }

    @Test
    fun `retains nested component binary names`(@TempDir projectRoot: Path) {
        val manifest = projectRoot.resolve("AndroidManifest.xml")
        manifest.writeText(
            """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                  <application android:name=".Outer${'$'}NestedApplication" />
                </manifest>
            """.trimIndent(),
        )

        val reference = AndroidManifestScanner(projectRoot).scan(manifest, "dev.fixture").single()

        assertEquals("class:dev/fixture/Outer${'$'}NestedApplication", reference.nodeId.value)
    }
}
