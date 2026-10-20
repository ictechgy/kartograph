package dev.kartograph.gradle

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class DependencyResolutionTest {
    @Test
    fun `location keys drop the checkout and Gradle user home locations`(@TempDir root: Path) {
        fun key(name: String, relative: String): String =
            ArtifactLocationKeys(root.resolve("$name/checkout").toFile(), root.resolve("$name/home").toFile())
                .of(root.resolve(name).resolve(relative).toFile())
        assertEquals("{root}/app/libs/x.jar", key("first", "checkout/app/libs/x.jar"))
        assertEquals(key("first", "checkout/app/libs/x.jar"), key("second", "checkout/app/libs/x.jar"))
        val module = "home/caches/modules-2/files-2.1/g/m/1/0a1b/m-1.jar"
        assertEquals("{gradle-user-home}/caches/modules-2/files-2.1/g/m/1/0a1b/m-1.jar", key("first", module))
        assertEquals(key("first", module), key("second", module))
    }

    @Test
    fun `a Gradle user home inside the checkout keeps home relative keys`(@TempDir root: Path) {
        val checkout = root.resolve("workspace")
        val keys = ArtifactLocationKeys(checkout.toFile(), checkout.resolve("ci/gradle-home").toFile())
        assertEquals("{gradle-user-home}/caches/x.jar", keys.of(checkout.resolve("ci/gradle-home/caches/x.jar").toFile()))
        assertEquals("{root}/ci/libs/x.jar", keys.of(checkout.resolve("ci/libs/x.jar").toFile()))
    }

    @Test
    fun `same named transform outputs get distinct keys that end with the file name`(@TempDir root: Path) {
        val home = root.resolve("home")
        val keys = ArtifactLocationKeys(root.resolve("checkout").toFile(), home.toFile())
        val core = keys.of(home.resolve("caches/transforms/0a/transformed/core/jars/classes.jar").toFile())
        val appcompat = keys.of(home.resolve("caches/transforms/1b/transformed/appcompat/jars/classes.jar").toFile())
        assertNotEquals(core, appcompat)
        assertEquals(listOf("classes.jar", "classes.jar"), listOf(core, appcompat).map { it.substringAfterLast('/') })
    }

    @Test
    fun `keys inside one base keep the former absolute path order`(@TempDir root: Path) {
        val checkout = root.resolve("checkout")
        val keys = ArtifactLocationKeys(checkout.toFile(), root.resolve("home").toFile())
        // Android library의 classes.jar와 R.jar처럼 파일 이름 순서와 경로 순서가 다른 경우도 대표 선택이 그대로여야 한다.
        val artifacts = listOf("lib/build/intermediates/compile_r_class_jar/debug/R.jar",
            "lib/build/intermediates/compile_library_classes_jar/debug/classes.jar", "lib/build/classes/kotlin/main",
            "lib/build/classes/java/main", "lib-extra/build/libs/a.jar").map(checkout::resolve)
        assertEquals(artifacts.sortedBy(Path::toString).map { keys.of(it.toFile()) }, artifacts.map { keys.of(it.toFile()) }.sorted())
    }

    @Test
    fun `artifacts outside both bases use a root relative path`(@TempDir root: Path) {
        val keys = ArtifactLocationKeys(root.resolve("work/checkout").toFile(), root.resolve("home").toFile())
        assertEquals("{root}/../shared/x.jar", keys.of(root.resolve("work/shared/x.jar").toFile()))
    }

    @Test
    fun `file coordinates outside both bases follow the placement relative to the root`(@TempDir root: Path) {
        fun coordinate(checkout: String, artifact: String): String {
            val keys = ArtifactLocationKeys(root.resolve(checkout).toFile(), root.resolve("home").toFile())
            return DependencyResolution.fileCoordinate("vendor.jar", keys.of(root.resolve(artifact).toFile()))
        }
        // root와 함께 옮겨지는 배치(monorepo의 형제 디렉터리)는 checkout 위치가 달라도 같은 좌표다.
        assertEquals(coordinate("a/mono/app", "a/mono/libs/vendor.jar"), coordinate("b/c/mono/app", "b/c/mono/libs/vendor.jar"))
        // 고정 위치의 jar는 root와의 상대 배치가 checkout 깊이에 따라 달라지므로 좌표도 달라진다. 0.25.0까지는 절대 경로가
        // 같으면 같은 좌표였다. 좌표는 task 입력인 위치 키로만 만들어야 cache가 다른 배치의 결과를 복원하지 않는다
        // (docs/DEPENDENCIES.md에 적은 예외).
        assertNotEquals(coordinate("a/app", "opt/libs/vendor.jar"), coordinate("b/c/app", "opt/libs/vendor.jar"))
    }

    @Test
    fun `aliased artifact paths are matched against the real base directories`(@TempDir root: Path) {
        val home = Files.createDirectories(root.resolve("home"))
        val alias = Files.createSymbolicLink(root.resolve("home-alias"), home)
        // Gradle은 user home을 실제 경로로 넘긴다.
        val keys = ArtifactLocationKeys(root.resolve("checkout").toFile(), home.toRealPath().toFile())
        assertEquals("{gradle-user-home}/fixture/x.jar", keys.of(alias.resolve("fixture/x.jar").toFile()))
    }

    @Test
    fun `a linked artifact file keeps its own name instead of its target`(@TempDir root: Path) {
        val target = root.resolve("checkout/libs/a.jar")
        Files.createDirectories(target.parent)
        Files.writeString(target, "jar")
        val link = Files.createSymbolicLink(Files.createDirectories(root.resolve("elsewhere")).resolve("b.jar"), target)
        val keys = ArtifactLocationKeys(root.resolve("checkout").toFile(), root.resolve("home").toFile())
        assertEquals("{root}/../elsewhere/b.jar", keys.of(link.toFile()))
        assertEquals("{root}/libs/a.jar", keys.of(target.toFile()))
    }

    @Test
    fun `file coordinates hash the location key and keep their shape`() {
        val coordinate = DependencyResolution.fileCoordinate("my lib.jar", "{root}/libs/my lib.jar")
        assertTrue(Regex("file:my\\+lib\\.jar#[0-9a-f]{12}").matches(coordinate), coordinate)
        assertEquals(coordinate, DependencyResolution.fileCoordinate("my lib.jar", "{root}/libs/my lib.jar"))
        assertNotEquals(coordinate, DependencyResolution.fileCoordinate("my lib.jar", "{root}/other/my lib.jar"))
    }
}
