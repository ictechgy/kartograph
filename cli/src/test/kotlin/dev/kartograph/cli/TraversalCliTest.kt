package dev.kartograph.cli

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.GraphNode
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.export.LanguageTraversalCodec
import dev.kartograph.export.McpJsonCodec
import dev.kartograph.export.QuerySnapshot
import dev.kartograph.export.QuerySnapshotCodec
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir

/** `impact --format language-traversal`과 `reach`의 교환 문서·옵션·호환 계약이다. */
class TraversalCliTest {
    private data class Execution(val status: Int, val output: String, val error: String)

    private fun run(vararg arguments: String): Execution {
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val status = KartographCli.run(arguments, PrintStream(output), PrintStream(error))
        return Execution(status, output.toString(), error.toString())
    }

    /**
     * 합성 Java 프로젝트다. Http.get(root)을 RealApi.call이 부르고, 화면 Screen.render 안의 익명 Runnable이
     * Api.call(구현 하나)을 부른다. 공통 Ui.button은 Runnable.run으로 모든 익명 Runnable에 dispatch한다.
     */
    private fun capture(root: Path): Path {
        val source = Files.createDirectories(root.resolve("src/p"))
        mapOf(
            "Api.java" to "package p; interface Api { void call(); }",
            "RealApi.java" to "package p; class RealApi implements Api { public void call() { Http.get(); } }",
            "Http.java" to "package p; class Http { static void get() {} }",
            "Screen.java" to "package p; class Screen { void render(final Api api) { Runnable r = new Runnable() { public void run() { api.call(); } }; Ui.button(r); } }",
            "Ui.java" to "package p; class Ui { static void button(Runnable r) { r.run(); } }",
            "Other.java" to "package p; class Other { void show() { Ui.button(new Runnable() { public void run() {} }); } }",
            "Main.java" to "package p; public class Main { public static void main(String[] a) { new Screen().render(new RealApi()); } }",
        ).forEach { (name, text) -> source.resolve(name).toFile().writeText(text) }
        val classes = Files.createDirectories(root.resolve("classes"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-g", "-d", classes.toString(),
            *source.toFile().listFiles()!!.map { it.path }.toTypedArray()))
        val snapshot = run("snapshot", "--classes", classes.toString(), "--project", root.toString(), "--include-paths")
        assertEquals(0, snapshot.status, snapshot.error)
        return root.resolve("graph.json").also { Files.writeString(it, snapshot.output) }
    }

    private fun document(execution: Execution): Map<*, *> = McpJsonCodec.parse(execution.output) as Map<*, *>

    private fun reached(document: Map<*, *>): Map<String, Map<*, *>> = (document["reached"] as List<*>).map { it as Map<*, *> }
        .associateBy { ((it["symbol"] as Map<*, *>)["usr"] as String) }

    @Test
    fun `reverse traversal follows lexical containment and labels bound dispatch`(@TempDir root: Path) {
        val graph = capture(root)
        val execution = run("impact", "method:p/Http#get()V", "--format", "language-traversal", "--graph-file", graph.toString(),
            "--project", root.toString(), "--generated-at", "2026-09-27T00:00:00Z")
        assertEquals(0, execution.status, execution.error)
        val document = document(execution)
        assertEquals("language-traversal", document["format"])
        assertEquals("dependents", document["direction"])
        assertEquals("candidates", document["dispatch"])
        assertEquals(root.toRealPath().toString(), document["project"])
        assertEquals("2026-09-27T00:00:00.000Z", document["generatedAt"])
        val rows = reached(document)
        assertEquals("direct", rows.getValue("method:p/RealApi#call()V")["evidence"])
        assertEquals("bound", rows.getValue("method:p/Api#call()V")["evidence"])
        val render = rows.getValue("method:p/Screen#render(Lp/Api;)V")
        assertEquals(listOf("contains"), render["relationships"])
        assertEquals("bound", render["evidence"])
        assertTrue("method:p/Main#main([Ljava/lang/String;)V" in rows)
        // Ui.button은 Screen이 넘긴 익명 Runnable을 실행하므로 콜백 흐름으로 닿는다. fan-out이 아니므로 Ui.button을
        // 부르는 다른 화면(Other.show)으로는 퍼지지 않는다.
        val button = rows.getValue("method:p/Ui#button(Ljava/lang/Runnable;)V")
        assertEquals(listOf("callback"), button["relationships"])
        assertEquals("bound", button["evidence"])
        assertFalse("method:p/Other#show()V" in rows, "callback callers must not spread to their other callers")
        assertTrue((document["limitations"] as List<*>).any { (it as String).startsWith("callback-flow: 2 bound") })
        assertTrue((document["limitations"] as List<*>).any { (it as String).startsWith("lambda-dispatch-excluded:") })
        val again = run("impact", "method:p/Http#get()V", "--format", "language-traversal", "--graph-file", graph.toString(),
            "--project", root.toString(), "--generated-at", "2026-09-27T00:00:00Z")
        assertEquals(execution.output, again.output)
    }

    @Test
    fun `dispatch modes nest and all follows callback fan-out`(@TempDir root: Path) {
        val graph = capture(root)
        fun count(mode: String) = reached(document(run("impact", "method:p/Http#get()V", "--format", "language-traversal",
            "--graph-file", graph.toString(), "--project", root.toString(), "--dispatch", mode))).keys
        val direct = count("direct")
        val bound = count("bound")
        val candidates = count("candidates")
        val all = count("all")
        assertTrue(direct.containsAll(listOf("method:p/RealApi#call()V")) && "method:p/Api#call()V" !in direct)
        assertTrue(bound.containsAll(direct) && candidates.containsAll(bound) && all.containsAll(candidates))
        assertTrue("method:p/Other#show()V" in all)
    }

    @Test
    fun `reach emits dependencies with multi-root attribution`(@TempDir root: Path) {
        val graph = capture(root)
        val execution = run("reach", "method:p/Main#main([Ljava/lang/String;)V", "--symbol", "method:p/Screen#render(Lp/Api;)V",
            "--graph-file", graph.toString(), "--project", root.toString())
        assertEquals(0, execution.status, execution.error)
        val document = document(execution)
        assertEquals("dependencies", document["direction"])
        val rows = reached(document)
        val render = rows.getValue("method:p/Screen#render(Lp/Api;)V")
        assertEquals(listOf(0L), render["roots"], "a root reached from another root lists only the other root")
        assertEquals(listOf(0L, 1L), rows.getValue("method:p/Http#get()V")["roots"])
    }

    @Test
    fun `roots come from bridge facts and unknown roots stay listed`(@TempDir root: Path) {
        val graph = capture(root)
        val facts = root.resolve("routes.json").also { Files.writeString(it, """{"format": "bridge-facts", "facts": [
            {"kind": "route-call", "symbol": {"qualifiedName": "p.Http.get", "usr": "method:p/Http#get()V"}},
            {"kind": "route-call", "symbol": {"qualifiedName": "p.Gone.run", "usr": "method:p/Gone#run()V"}},
            {"kind": "route-call"}]}""") }
        val execution = run("impact", "--format", "language-traversal", "--roots-from", facts.toString(), "--graph-file", graph.toString(),
            "--project", root.toString())
        assertEquals(64, execution.status)
        val document = document(execution)
        val roots = (document["roots"] as List<*>).map { it as Map<*, *> }
        assertEquals(listOf("method:p/Http#get()V", "method:p/Gone#run()V"), roots.map { it["id"] })
        assertFalse("symbol" in roots[1])
        assertEquals(true, document["truncated"])
        assertEquals(listOf("root-not-found"), document["truncationReasons"])
    }

    @Test
    fun `snapshots without enclosure facts fall back to candidate lambda edges`(@TempDir root: Path) {
        val graph = capture(root)
        val legacy = root.resolve("legacy.json").also {
            Files.writeString(it, Files.readString(graph).replace(Regex(", \"enclosures\": \\[[^\\]]*\\]"), ""))
        }
        assertFalse(Files.readString(legacy).contains("\"enclosures\""))
        val execution = run("impact", "method:p/Http#get()V", "--format", "language-traversal", "--graph-file", legacy.toString(),
            "--project", root.toString())
        assertEquals(0, execution.status, execution.error)
        assertContains(execution.output, "lexical-enclosures-unavailable:")
        assertTrue("method:p/Ui#button(Ljava/lang/Runnable;)V" in reached(document(execution)))
    }

    @Test
    fun `default impact output is unchanged by enclosure facts`(@TempDir root: Path) {
        val graph = capture(root)
        val legacy = root.resolve("legacy.json").also {
            Files.writeString(it, Files.readString(graph).replace(Regex(", \"enclosures\": \\[[^\\]]*\\]"), ""))
        }
        val current = run("impact", "method:p/Http#get()V", "--graph-file", graph.toString(), "--all")
        val old = run("impact", "method:p/Http#get()V", "--graph-file", legacy.toString(), "--all")
        assertEquals(0, current.status, current.error)
        assertEquals(old.output, current.output)
        assertEquals(current.output, run("impact", "method:p/Http#get()V", "--graph-file", graph.toString(), "--all", "--format", "json").output)
        assertContains(current.output, "\"format\": \"kartograph-impact\"")
    }

    private fun git(root: Path, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-C", root.toString(), "-c", "user.name=Fixture", "-c", "user.email=fixture@example.com",
            "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null") + arguments).redirectErrorStream(true).start()
        val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        assertEquals(0, process.waitFor(), output)
        return output.trim()
    }

    private fun traverse(graph: Path, root: Path, vararg extra: String): Execution = run("impact", "method:p/Http#get()V",
        "--format", "language-traversal", "--graph-file", graph.toString(), "--project", root.toString(),
        "--generated-at", "2026-09-27T00:00:00Z", *extra)

    /** git 격리 때문에 revision을 확인하지 못했다는 한계 문구다. */
    private fun revisionLimitations(document: Map<*, *>): List<String> =
        (document["limitations"] as List<*>).map { it as String }.filter { it.startsWith("revision-unverified:") }

    @Test
    fun `revision comes from the option or the snapshot label and conflicting labels fail`(@TempDir root: Path) {
        val graph = capture(root)
        assertFalse("revision" in document(traverse(graph, root)), "no repository and no label leaves revision unknown")
        assertEquals(emptyList(), revisionLimitations(document(traverse(graph, root))), "a directory outside git is not a limitation")
        val given = traverse(graph, root, "--revision", "release-1.2")
        assertEquals(0, given.status, given.error)
        assertEquals("release-1.2", document(given)["revision"])
        val label = "0123456789abcdef0123456789abcdef01234567"
        val labeled = root.resolve("labeled.json").also {
            val snapshot = run("snapshot", "--classes", root.resolve("classes").toString(), "--project", root.toString(),
                "--include-paths", "--revision", label)
            assertEquals(0, snapshot.status, snapshot.error)
            Files.writeString(it, snapshot.output)
        }
        assertEquals(label, document(traverse(labeled, root))["revision"])
        assertEquals(label, document(traverse(labeled, root, "--revision", label))["revision"])
        val conflict = traverse(labeled, root, "--revision", "release-1.2")
        assertEquals(2, conflict.status)
        assertContains(conflict.error, "does not match the snapshot's revision label")
    }

    @Test
    fun `a clean git HEAD becomes the revision and a dirty project omits it`(@TempDir root: Path) {
        val graph = capture(root)
        Files.writeString(root.resolve(".gitignore"), "classes/\n*.json\n")
        git(root, "init", "-q")
        git(root, "add", "-A")
        git(root, "commit", "-q", "-m", "fixture")
        val head = git(root, "rev-parse", "HEAD")
        val clean = traverse(graph, root)
        assertEquals(0, clean.status, clean.error)
        assertEquals(head, document(clean)["revision"])
        assertEquals("pinned", document(traverse(graph, root, "--revision", "pinned"))["revision"], "--revision wins over HEAD")
        Files.writeString(root.resolve("notes.txt"), "untracked")
        assertFalse("revision" in document(traverse(graph, root)), "an untracked file makes HEAD unreliable")
        // 작업 트리가 더러워 뺀 revision은 문서화된 생략이라 한계를 남기지 않는다.
        assertEquals(emptyList(), revisionLimitations(document(traverse(graph, root))))
        Files.delete(root.resolve("notes.txt"))
        root.resolve("src/p/Http.java").toFile().appendText("\n// edited\n")
        assertFalse("revision" in document(traverse(graph, root)), "an uncommitted edit makes HEAD unreliable")
        git(root, "checkout", "-q", "--", "src/p/Http.java")
        assertEquals(head, document(traverse(graph, root))["revision"])
        val nested = Files.createDirectories(root.resolve("sub"))
        Files.writeString(nested.resolve("Keep.txt"), "tracked")
        git(root, "add", "sub/Keep.txt")
        git(root, "commit", "-q", "-m", "sub")
        root.resolve("src/p/Http.java").toFile().appendText("\n// outside sub\n")
        assertEquals(git(root, "rev-parse", "HEAD"), document(traverse(graph, nested))["revision"],
            "changes outside the project directory do not affect its revision")
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "the fsmonitor marker hook is a POSIX shell script")
    fun `reach reads a clean HEAD without running the repository fsmonitor command`(@TempDir root: Path) {
        val project = Files.createDirectories(root.resolve("project"))
        val graph = capture(project)
        Files.writeString(project.resolve(".gitignore"), "classes/\n*.json\n")
        git(project, "init", "-q")
        git(project, "add", "-A")
        git(project, "commit", "-q", "-m", "fixture")
        val head = git(project, "rev-parse", "HEAD")
        // 압축본으로 받은 저장소의 .git/config에 실린 명령을 흉내 낸다. revision 확인의 git status가 index를 읽을 때 실행된다.
        val probe = FsmonitorHookProbe(root, project)

        val forward = run("reach", "method:p/Main#main([Ljava/lang/String;)V", "--graph-file", graph.toString(),
            "--project", project.toString(), "--generated-at", "2026-09-27T00:00:00Z")

        assertEquals(0, forward.status, forward.error)
        assertEquals(head, document(forward)["revision"])
        assertFalse(probe.hookRan, "kartograph must not run the analyzed repository's core.fsmonitor command")
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "the filter driver marker is a POSIX shell script")
    fun `reach reads a clean HEAD without running filter drivers configured by the repository`(@TempDir root: Path) {
        val project = Files.createDirectories(root.resolve("project"))
        val graph = capture(project)
        Files.writeString(project.resolve(".gitignore"), "classes/\n*.json\n")
        Files.writeString(project.resolve(".gitattributes"), "* filter=evil\n")
        GitFixture.git(project, "init", "-q")
        GitFixture.git(project, "add", "-A")
        GitFixture.git(project, "commit", "-q", "-m", "fixture")
        val head = GitFixture.git(project, "rev-parse", "HEAD")
        // 압축본으로 받은 저장소의 .git/config에 실린 드라이버를 흉내 낸다. revision 확인의 git status가 stat이 낡은 파일을
        // 다시 읽을 때 실행된다.
        val probe = FilterDriverProbe(root, project, "evil", stale = listOf(project.resolve("src/p/Http.java")))

        val forward = run("reach", "method:p/Main#main([Ljava/lang/String;)V", "--graph-file", graph.toString(),
            "--project", project.toString(), "--generated-at", "2026-09-27T00:00:00Z")

        assertEquals(0, forward.status, forward.error)
        assertEquals(head, document(forward)["revision"], "unchanged content stays clean without the driver")
        assertFalse(probe.ran, "kartograph must not run the analyzed repository's filter driver")
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "the filter driver marker is a POSIX shell script")
    fun `revision checks run no repository filter driver and keep unchanged content clean`(@TempDir root: Path) {
        // .git/info/attributes와 커밋된 .gitattributes, 점이 든 이름, process 키가 모두 같은 경계를 지난다.
        listOf(Triple("evil", "clean", true), Triple("evil.name", "clean", false), Triple("evil", "process", true))
            .forEachIndexed { index, (name, key, infoAttributes) ->
                val project = root.resolve("project-$index")
                val attributes = if (infoAttributes) emptyMap() else mapOf(".gitattributes" to "* filter=$name\n")
                GitFixture.commit(project, mapOf("src/A.kt" to "class A\n") + attributes)
                val head = GitFixture.git(project, "rev-parse", "HEAD")
                val probe = FilterDriverProbe(root, project, name, key, infoAttributes, stale = listOf(project.resolve("src/A.kt")))

                assertEquals(head, GitRevision.cleanHead(project.toRealPath()), "$name.$key")
                assertFalse(probe.ran, "kartograph must not run the analyzed repository's filter driver ($name.$key)")
            }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "the filter driver marker is a POSIX shell script")
    fun `a required filter driver keeps the revision readable without running it`(@TempDir root: Path) {
        // git-lfs는 required = true를 둔다. 명령만 비우면 git이 그 파일에서 실패해 revision을 잃는다.
        val project = root.resolve("project")
        GitFixture.commit(project, mapOf("src/A.kt" to "class A\n"))
        val head = GitFixture.git(project, "rev-parse", "HEAD")
        val probe = FilterDriverProbe(root, project, "evil", infoAttributes = true, stale = listOf(project.resolve("src/A.kt")),
            required = true)

        assertEquals(head, GitRevision.cleanHead(project.toRealPath()))
        assertFalse(probe.ran, "kartograph must not run the analyzed repository's filter driver")
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "the normalizing filter is a POSIX shell command")
    fun `a filter-normalized file with a stale stat omits the revision`(@TempDir root: Path) {
        val project = root.resolve("project")
        // git-lfs처럼 저장소가 믿는 정규화 filter다. 커밋할 때 돌아서 blob은 대문자로 저장된다.
        GitFixture.commit(project, mapOf(".gitattributes" to "*.txt filter=upper\n", "notes.txt" to "hello\n"),
            "filter.upper.clean" to "tr a-z A-Z")
        val head = GitFixture.git(project, "rev-parse", "HEAD")
        assertEquals(head, GitRevision.cleanHead(project.toRealPath()), "an unchanged stat needs no content read")

        GitFixture.stale(listOf(project.resolve("notes.txt")), GitFixture.STALE_TIME)

        // filter를 돌리지 않으면 내용이 blob과 달라 보인다. 깨끗하다고 주장하지 않고 revision을 뺀다(fail-safe).
        // 실제로 고친 파일과 구분할 수 없으므로 더러운 작업 트리처럼 한계 없이 뺀다.
        assertEquals(GitRevision.Inspection(null, null), GitRevision.inspect(project.toRealPath()))
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "the filter driver marker is a POSIX shell script")
    fun `a filter driver name that cannot be overridden omits the revision without running it`(@TempDir root: Path) {
        // git은 -c 값을 첫 '='에서 나누므로 "a=b"는 덮을 수 없다. 비ASCII 이름은 JVM argv 인코딩이 로캘에 따라 바꾼다.
        listOf("a=b", "évil").forEachIndexed { index, name ->
            val project = root.resolve("project-$index")
            GitFixture.commit(project, mapOf("src/A.kt" to "class A\n"))
            val probe = FilterDriverProbe(root, project, name, infoAttributes = true, stale = listOf(project.resolve("src/A.kt")))

            val inspection = GitRevision.inspect(project.toRealPath())
            assertNull(inspection.revision, name)
            // 막은 사실을 한계로 알린다. 신뢰하지 않는 드라이버 이름은 싣지 않는다.
            assertContains(inspection.limitation.orEmpty(), "filter driver whose name cannot be safely overridden", message = name)
            assertFalse(inspection.limitation.orEmpty().contains(name), name)
            assertFalse(probe.ran, "kartograph must not run git while it cannot override the driver ($name)")
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "the filter driver marker is a POSIX shell script")
    fun `a submodule under the project omits the revision without running git inside it`(@TempDir root: Path) {
        val library = root.resolve("library")
        GitFixture.commit(library, mapOf("inner.txt" to "inner\n"))
        val repository = root.resolve("repository")
        GitFixture.commit(repository, mapOf("app/src/A.kt" to "class A\n", "tools/B.kt" to "class B\n"))
        GitFixture.git(repository, "submodule", "--quiet", "add", library.toString(), "app/sub")
        GitFixture.git(repository, "commit", "-q", "-m", "submodule")
        val head = GitFixture.git(repository, "rev-parse", "HEAD")
        // 서브모듈 저장소 자신의 config에 건 드라이버다. 상위 저장소의 status가 서브모듈 안에서 git을 띄우면 실행된다.
        val submodule = repository.resolve("app/sub")
        val probe = FilterDriverProbe(root, submodule, "evil", infoAttributes = true, stale = listOf(submodule.resolve("inner.txt")))

        // 서브모듈 작업 트리가 깨끗한지는 서브모듈 저장소의 설정을 따르는 git으로만 알 수 있어 깨끗함을 증명하지 못한다.
        assertNull(GitRevision.cleanHead(repository.resolve("app").toRealPath()))
        assertNull(GitRevision.cleanHead(repository.toRealPath()))
        assertEquals(head, GitRevision.cleanHead(repository.resolve("tools").toRealPath()), "a project without submodules keeps HEAD")
        assertContains(GitRevision.inspect(repository.toRealPath()).limitation.orEmpty(), "submodules")
        assertNull(GitRevision.inspect(repository.resolve("tools").toRealPath()).limitation)
        assertFalse(probe.ran, "kartograph must not run git inside the submodule")
    }

    @Test
    fun `a revision the git boundary cannot verify leaves a limitation instead of a silent omission`(@TempDir root: Path) {
        val library = root.resolve("library")
        GitFixture.commit(library, mapOf("inner.txt" to "inner\n"))
        val project = root.resolve("project")
        val graph = capture(project)
        GitFixture.commit(project, mapOf(".gitignore" to "classes/\n*.json\n"))
        GitFixture.git(project, "submodule", "--quiet", "add", library.toString(), "sub")
        GitFixture.git(project, "commit", "-q", "-m", "submodule")

        val traversal = traverse(graph, project)

        assertEquals(0, traversal.status, traversal.error)
        val document = document(traversal)
        assertFalse("revision" in document, "a submodule keeps the project from proving a clean HEAD")
        // 작업 트리가 더러워 뺀 경우와 구분되도록, git 격리 때문에 확인하지 못했다는 사실과 해결 방향을 한계로 남긴다.
        val limitation = revisionLimitations(document).single()
        assertContains(limitation, "submodule")
        assertContains(limitation, "--revision")
        assertFalse(limitation.contains(project.toString()), "the limitation carries no absolute path")
        // --revision을 주면 git으로 확인하지 않으므로 한계도 없다.
        assertEquals(emptyList(), revisionLimitations(document(traverse(graph, project, "--revision", "pinned"))))
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "the upload-pack marker is a POSIX shell script")
    fun `revision checks do not fetch objects a partial clone lacks through the repository remote`(@TempDir root: Path) {
        val source = root.resolve("source")
        val lines = (1..200).joinToString("\n", postfix = "\n")
        GitFixture.commit(source, mapOf("src/Old.kt" to lines))
        val base = GitFixture.git(source, "rev-parse", "HEAD")
        GitFixture.git(source, "mv", "src/Old.kt", "src/New.kt")
        Files.writeString(source.resolve("src/New.kt"), lines + "tail\n")
        GitFixture.git(source, "commit", "-q", "-am", "rename")
        val clone = root.resolve("clone")
        // HEAD만 기준 커밋으로 옮겨(--soft) index에 staged rename을 남긴다. 일반 git status는 rename을 판정하려고 기준 쪽의
        // 빠진 blob을 lazy fetch하며 clone config의 upload-pack을 실행한다.
        val probe = LazyFetchProbe(root, source, clone, "blob:none", listOf("--no-optional-locks", "status", "--porcelain")) { target ->
            GitFixture.git(target, "reset", "-q", "--soft", base)
        }

        assertNull(GitRevision.cleanHead(clone.toRealPath()))
        assertFalse(probe.ran, "kartograph must not fetch missing objects through the analyzed repository's remote")
    }

    @Test
    fun `graph revision is shared by both directions and ignores snapshot layout`(@TempDir root: Path) {
        val graph = capture(root)
        val reverse = document(traverse(graph, root))["graphRevision"] as String
        assertTrue(Regex("sha256:[0-9a-f]{64}").matches(reverse), reverse)
        // 순회가 조립한 간선을 해시에 넘겨도 snapshot 그래프만으로 다시 낸 해시와 같다.
        val snapshot = QuerySnapshotCodec.parse(Files.readString(graph))
        assertEquals(LanguageTraversalCodec.graphRevision(snapshot.graph, snapshot.enclosuresCaptured, snapshot.callbackFactsCaptured), reverse)
        val forward = run("reach", "method:p/Main#main([Ljava/lang/String;)V", "--graph-file", graph.toString(), "--project", root.toString())
        assertEquals(0, forward.status, forward.error)
        assertEquals(reverse, document(forward)["graphRevision"])
        listOf(listOf("--compact"), emptyList()).forEach { layout ->
            val other = run("snapshot", "--classes", root.resolve("classes").toString(), "--project", root.toString(), *layout.toTypedArray())
            assertEquals(0, other.status, other.error)
            val file = root.resolve("layout.json").also { Files.writeString(it, other.output) }
            assertNotEquals(Files.readString(graph), other.output)
            assertEquals(reverse, document(traverse(file, root))["graphRevision"], "layout $layout")
        }
        val legacy = root.resolve("legacy.json").also {
            Files.writeString(it, Files.readString(graph).replace(Regex(", \"enclosures\": \\[[^\\]]*\\]"), ""))
        }
        assertNotEquals(reverse, document(traverse(legacy, root))["graphRevision"], "legacy snapshots traverse a different graph")
    }

    @Test
    fun `traversal usage errors return 64`(@TempDir root: Path) {
        val graph = root.resolve("graph.json").also { Files.writeString(it, QuerySnapshotCodec.render(QuerySnapshot(
            CodeGraph(listOf(GraphNode(NodeId("method:p/A#run()V"), "run", NodeKind.METHOD)), emptyList()), emptyList(), emptyList()))) }
        val base = arrayOf("impact", "method:p/A#run()V", "--format", "language-traversal", "--graph-file", graph.toString())
        val project = arrayOf("--project", root.toString())
        listOf(
            arrayOf(*base) to "missing required --project",
            arrayOf(*base, *project, "--base-graph", graph.toString()) to "not supported with --format language-traversal",
            arrayOf(*base, *project, "--all") to "not supported with --format language-traversal",
            arrayOf(*base, *project, "--dispatch", "maybe") to "dispatch must be",
            arrayOf(*base, *project, "--class-hops", "some") to "class-hops must be all or member-only",
            arrayOf(*base, *project, "--depth", "129") to "depth must be 1..128",
            arrayOf(*base, *project, "--generated-at", "yesterday") to "--generated-at",
            arrayOf(*base, *project, "--revision", "") to "--revision must be non-empty",
            arrayOf(*base, *project, "--revision", "abc\u0001") to "--revision must be non-empty",
            arrayOf(*base, *project, "--revision", "abc\u0085") to "--revision must be non-empty",
            arrayOf(*base, *project, "--revision", "abc\u2028") to "--revision must be non-empty",
            arrayOf(*base, *project, "--symbol", "method:p/B#run()V\u2029") to "invalid symbol",
            arrayOf(*base, *project, "--symbol", "method:p/B#run()V\u007f") to "invalid symbol",
            arrayOf("impact", "method:p/A#run()V", "--format", "text", "--graph-file", graph.toString()) to "invalid impact format",
            arrayOf("reach", "method:p/A#run()V", "--format", "json", "--graph-file", graph.toString(), *project) to "supports only",
        ).forEach { (arguments, message) ->
            val execution = run(*arguments)
            assertEquals(64, execution.status, arguments.joinToString(" "))
            assertContains(execution.error, message)
        }
        assertEquals(2, run("reach", "method:p/A#run()V", "--graph-file", root.resolve("absent.json").toString(), *project).status)
        assertContains(run("reach", "--help").output, "Dispatch modes")
        assertContains(run("reach", "--help").output, "Class hops")
        assertContains(run("--help").output, "kartograph reach")
    }

    /**
     * `impact --help`가 안내하는 `impact --format language-traversal --help`가 순회 도움말을 내고 0으로 끝난다.
     * 전에는 인자 전체가 `--help` 하나일 때만 도움말이라 `unknown traversal option: --help`와 64였다(회귀 방지).
     */
    @Test
    fun `help after the traversal format prints traversal help`() {
        assertContains(run("impact", "--help").output, "impact --format language-traversal --help")
        listOf(
            arrayOf("impact", "--format", "language-traversal", "--help") to "kartograph impact --format language-traversal <usr>",
            arrayOf("impact", "--format", "language-traversal", "-h") to "kartograph impact --format language-traversal <usr>",
            arrayOf("impact", "--help", "--format", "language-traversal") to "kartograph impact --format language-traversal <usr>",
            arrayOf("reach", "--format", "language-traversal", "--help") to "kartograph reach <usr>",
            arrayOf("reach", "-h") to "kartograph reach <usr>",
        ).forEach { (arguments, usage) ->
            val execution = run(*arguments)
            assertEquals(0, execution.status, arguments.joinToString(" "))
            assertContains(execution.output, usage)
            assertContains(execution.output, "Dispatch modes")
            assertEquals("", execution.error)
        }
        // 도움말 말고 다른 인자가 섞이면 이전처럼 사용 오류다.
        assertEquals(64, run("impact", "--format", "language-traversal", "--help", "method:p/A#run()V").status)
    }

    /**
     * ViewModel 모양의 합성 Java 프로젝트다. `androidx.lifecycle.ViewModel`은 콜백 모델만 켜는 빈 대역이다.
     * JobsViewModel.load가 Http.get을 부르고, JobsScreen은 load를 부른다. Formatter·Holder·SpecialUser는 이름만 적고,
     * Factory는 인스턴스를 만들며, SpecialViewModel은 상속한다.
     */
    private fun captureViewModel(root: Path): Path {
        val sources = root.resolve("src")
        mapOf(
            "androidx/lifecycle/ViewModel.java" to "package androidx.lifecycle; public abstract class ViewModel { protected void onCleared() {} }",
            "p/Http.java" to "package p; public class Http { public static void get() {} }",
            "p/JobsViewModel.java" to "package p; public class JobsViewModel extends androidx.lifecycle.ViewModel { public void load() { Http.get(); } public void other() {} }",
            "p/JobsScreen.java" to "package p; class JobsScreen { void render(JobsViewModel vm) { vm.load(); } }",
            "p/Formatter.java" to "package p; class Formatter { static String label(JobsViewModel vm) { return \"jobs\"; } }",
            "p/Printer.java" to "package p; class Printer { void print() { Formatter.label(null); } }",
            "p/Holder.java" to "package p; class Holder { JobsViewModel vm; }",
            "p/Factory.java" to "package p; class Factory { JobsViewModel create() { return new JobsViewModel(); } }",
            "p/SpecialViewModel.java" to "package p; class SpecialViewModel extends JobsViewModel {}",
            "p/SpecialUser.java" to "package p; class SpecialUser { void show(SpecialViewModel vm) {} }",
        ).forEach { (name, text) -> sources.resolve(name).also { Files.createDirectories(it.parent) }.toFile().writeText(text) }
        val classes = Files.createDirectories(root.resolve("classes"))
        val files = sources.toFile().walkTopDown().filter { it.isFile }.map { it.path }.sorted().toList()
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-g", "-d", classes.toString(), *files.toTypedArray()))
        val snapshot = run("snapshot", "--classes", classes.toString(), "--project", root.toString(), "--include-paths")
        assertEquals(0, snapshot.status, snapshot.error)
        return root.resolve("graph.json").also { Files.writeString(it, snapshot.output) }
    }

    @Test
    fun `member-only class hops drop type-reference spillover but keep screens factories and subclasses`(@TempDir root: Path) {
        val graph = captureViewModel(root)
        fun impact(vararg extra: String) = run("impact", "method:p/Http#get()V", "--format", "language-traversal", "--graph-file", graph.toString(),
            "--project", root.toString(), "--generated-at", "2026-09-28T00:00:00Z", *extra).also { assertEquals(0, it.status, it.error) }
        val wide = document(impact())
        val narrowExecution = impact("--class-hops", "member-only")
        val narrow = document(narrowExecution)
        val kept = listOf("method:p/JobsViewModel#load()V", "method:p/JobsScreen#render(Lp/JobsViewModel;)V", "class:p/JobsViewModel",
            "method:p/Factory#create()Lp/JobsViewModel;", "class:p/SpecialViewModel")
        val spill = listOf("method:p/Formatter#label(Lp/JobsViewModel;)Ljava/lang/String;", "method:p/Printer#print()V",
            "field:p/Holder#vm:Lp/JobsViewModel;", "method:p/SpecialUser#show(Lp/SpecialViewModel;)V")
        assertTrue(reached(wide).keys.containsAll(kept + spill), reached(wide).keys.toString())
        assertTrue(reached(narrow).keys.containsAll(kept), reached(narrow).keys.toString())
        assertTrue(spill.none { it in reached(narrow) }, reached(narrow).keys.toString())
        assertTrue(reached(wide).keys.containsAll(reached(narrow).keys))
        assertEquals(wide["graphRevision"], narrow["graphRevision"], "the graph is the same; only the traversal is narrowed")
        val limitations = narrow["limitations"] as List<*>
        assertTrue(limitations.any { (it as String).startsWith("class-hops-narrowed: ") && !it.startsWith("class-hops-narrowed: 0 ") }, limitations.toString())
        assertTrue((wide["limitations"] as List<*>).none { (it as String).startsWith("class-hops-narrowed:") })
        assertEquals(narrowExecution.output, impact("--class-hops", "member-only").output, "narrow output is byte-stable")
        assertEquals(impact().output, impact("--class-hops", "all").output, "all is the default")
    }

    @Test
    fun `member-only reach stops at a named-only class but follows instantiation`(@TempDir root: Path) {
        val graph = captureViewModel(root)
        fun reach(symbol: String, vararg extra: String) = reached(document(run("reach", symbol, "--graph-file", graph.toString(),
            "--project", root.toString(), *extra).also { assertEquals(0, it.status, it.error) })).keys
        val label = "method:p/Formatter#label(Lp/JobsViewModel;)Ljava/lang/String;"
        assertTrue("method:p/Http#get()V" in reach(label))
        val narrow = reach(label, "--class-hops", "member-only")
        assertTrue("class:p/JobsViewModel" in narrow && "method:p/Http#get()V" !in narrow, narrow.toString())
        assertTrue("method:p/Http#get()V" in reach("method:p/Factory#create()Lp/JobsViewModel;", "--class-hops", "member-only"))
    }
}
