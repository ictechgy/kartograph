package dev.kartograph.index

import dev.kartograph.core.InputFingerprint
import java.nio.file.Files
import java.nio.file.Path

/** 완료된 compiler 출력의 수집 파일을 검사한 뒤 재사용 가능한 지문을 만든다. */
public object CompilerEvidenceReceipts {
    /** 부분 compilation의 inventory와 이전 실행의 결과를 완료 증거로 승격하지 않는다. */
    public fun validate(project: Path, compiler: String, token: String, inputs: List<InputFingerprint>,
        documents: List<Path>, expectedSources: Set<Path>, sourceRoots: List<Path>, generatedRoots: List<Path>,
        externalPrefix: String): List<InputFingerprint> {
        require(compiler in setOf("javac", "kotlin")) { "unsupported evidence compiler" }
        require(documents.isNotEmpty() && documents.size <= 256) { "compiler evidence output is missing or exceeds the document limit" }
        require(documents.sumOf { Files.size(it) } <= 64L * 1024 * 1024) { "compiler evidence exceeds the total byte limit" }
        val root = project.toRealPath()
        val expected = expectedSources.map { it.toRealPath() }.toSet()
        val declared = sourceRoots.filter { Files.exists(it) }.map { it.toRealPath() }
        val generated = generatedRoots.filter { Files.exists(it) }.map { it.toRealPath() }
        val observed = linkedSetOf<Path>()
        val derived = linkedSetOf<Path>()
        val receipts = mutableListOf<InputFingerprint>()
        documents.sortedBy { it.toAbsolutePath().normalize().toString() }.forEachIndexed { index, file ->
            val before = ContentFingerprint.hash(file)
            val document = CompilerEvidenceReader.read(file)
            require(document.inputToken == token) { "compiler evidence belongs to a different compilation" }
            require(document.collector in if (compiler == "javac") setOf("javac-constants", "dagger-bindings") else setOf("kotlin-constants")) {
                "compiler evidence collector does not match the compiler"
            }
            require(inputs.any { it.role in setOf("processor", "compiler") && it.sha256 == document.artifactSha256 }) {
                "compiler evidence collector artifact is not a declared compiler input"
            }
            document.sources.forEach { source ->
                val path = root.resolve(source.path).toRealPath()
                require(path.startsWith(root) && path.toString().let { it.endsWith(".java") || it.endsWith(".kt") }) {
                    "compiler evidence source is outside the supported project inputs"
                }
                require(CompilerEvidenceIndexer.sourceHash(path) == source.sha256) { "compiler evidence source changed during compilation" }
                if (path !in expected && declared.none { path == it || Files.isDirectory(it) && path.startsWith(it) }) {
                    require(generated.any { path == it || path.startsWith(it) }) { "compiler evidence contains an undeclared source" }
                    derived.add(path)
                }
                observed.add(path)
            }
            val receipt = ContentFingerprint.capture(root, file, "compilerEvidence", "$externalPrefix-evidence-$index")
            require(receipt.sha256 == before) { "compiler evidence changed while recording its receipt" }
            receipts += receipt
        }
        require(observed.containsAll(expected)) { "compiler evidence is partial; run a full compilation" }
        derived.sorted().forEachIndexed { index, path ->
            receipts += ContentFingerprint.capture(root, path, "compilerGeneratedSource", "$externalPrefix-generated-source-$index")
        }
        return receipts
    }
}
