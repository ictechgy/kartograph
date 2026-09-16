package dev.kartograph.gradle

import dev.kartograph.analysis.DefaultRetention
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.Finding
import dev.kartograph.core.InputFingerprint
import dev.kartograph.core.SnapshotProvenance
import dev.kartograph.export.BuildWitnessCodec
import dev.kartograph.export.BaselineCodec
import dev.kartograph.export.ExternalInputBindingsCodec
import dev.kartograph.export.QuerySnapshot
import dev.kartograph.export.QuerySnapshotCodec
import dev.kartograph.index.IndexedClasses
import dev.kartograph.index.CaptureInput
import dev.kartograph.index.VerifiedCaptureScope
import dev.kartograph.index.ClassIndexCache
import dev.kartograph.index.AndroidManifestScanner
import dev.kartograph.index.AndroidXmlScanner
import dev.kartograph.index.ContentFingerprint
import dev.kartograph.index.KeepRuleScanner
import dev.kartograph.index.ProvenanceVerifier
import dev.kartograph.index.RuntimeLimitationScanner
import dev.kartograph.index.SourcePathIndex
import java.nio.file.Files
import java.nio.file.Path
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/** 성공한 compiler provider의 main/test 그래프·보존 근거·내용 지문을 저장한다. */
@DisableCachingByDefault(because = "Keep-rule includes and captured timestamp diagnostics require cache lifecycle validation")
public abstract class KartographSnapshotTask : DefaultTask() {
    @get:Nested
    public abstract val compilations: ListProperty<SnapshotCompilation>

    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val classRoots: ConfigurableFileCollection

    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val dependencyClasspath: ConfigurableFileCollection

    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val sourceDirectories: ConfigurableFileCollection

    /** 실제 선택한 compiler의 파일 집합이며 경로 해석과 runtime 관측의 읽기 경계다. */
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val sourceFiles: ConfigurableFileCollection

    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val resourceDirectories: ConfigurableFileCollection

    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val serviceResourceRoots: ConfigurableFileCollection

    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val buildInputFiles: ConfigurableFileCollection

    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val buildFileWatches: ConfigurableFileCollection

    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val buildDirectoryWatches: ConfigurableFileCollection

    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val buildLogicWatches: ConfigurableFileCollection

    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val buildWitnessFiles: ConfigurableFileCollection

    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val compilerInputFiles: ConfigurableFileCollection

    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val keepRuleFiles: ConfigurableFileCollection

    /** AGP가 선언한 rule 중 build 출력 아래의 미생성 파일만 선택적으로 처리한다. */
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val generatedKeepRuleFiles: ConfigurableFileCollection

    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val generatedClassRoots: ConfigurableFileCollection

    /** 기존 baseline은 그래프 정점을 제거하지 않고 query의 억제 상태로 보존한다. */
    @get:InputFile @get:Optional @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val baselineFile: RegularFileProperty

    /** Android adapter가 선택 variant의 merged manifest와 resource provider를 연결한다. */
    @get:InputFile @get:Optional @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val manifestFile: RegularFileProperty

    @get:Input @get:Optional public abstract val namespace: Property<String>

    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val androidResourceDirectories: ConfigurableFileCollection

    @get:Input public abstract val scope: Property<String>
    @get:Input @get:Optional public abstract val revision: Property<String>
    @get:Input public abstract val includeSourcePaths: Property<Boolean>
    @get:Input public abstract val includePrivateMembers: Property<Boolean>
    /** 저장 파일 크기만 제한하며 snapshot graph/provenance 사실에는 포함하지 않는다. */
    @get:Input public abstract val snapshotMaxMiB: Property<Int>
    /** 파싱 캐시는 결과 의미나 compiler 증거를 변경하지 않는 명시적 성능 옵션이다. */
    @get:Input public abstract val indexCacheEnabled: Property<Boolean>
    @get:LocalState public abstract val indexCacheDirectory: DirectoryProperty
    @get:Internal public abstract val projectDirectory: DirectoryProperty
    @get:Internal public abstract val buildDirectory: DirectoryProperty
    @get:OutputFile public abstract val snapshotFile: RegularFileProperty
    @get:LocalState public abstract val localBindingsFile: RegularFileProperty

    init {
        snapshotMaxMiB.convention(QuerySnapshotCodec.DEFAULT_MAX_MIB)
        indexCacheEnabled.convention(false)
        indexCacheDirectory.convention(buildDirectory.dir("kartograph/index-cache"))
    }

    /** 입력 집합이 같아도 root 순서에 따라 중복 JVM 선언의 선택이 달라진다. */
    @get:Input
    public val orderedRootIdentities: List<String>
        get() {
            val project = projectDirectory.get().asFile.canonicalFile.toPath()
            return (classRoots.files.toList() + dependencyClasspath.files.toList()).mapIndexed { index, file ->
                val path = file.canonicalFile.toPath()
                if (path.startsWith(project)) project.relativize(path).toString().replace('\\', '/')
                else "external-$index"
            }
        }

    /** local bindings는 경로를 포함하므로 snapshot과 분리하고 공개 결과에 넣지 않는다. */
    @TaskAction
    public fun captureSnapshot() {
        val snapshotMaximumBytes = QuerySnapshotCodec.maximumBytes(snapshotMaxMiB.get())
        val project = projectDirectory.get().asFile.toPath()
        val witnessPaths = buildWitnessFiles.files.filter { it.isFile }.map { it.toPath() }
        val witnesses = witnessPaths.map { path ->
            require(Files.size(path) <= QuerySnapshotCodec.MAX_BYTES) { "build witness is too large" }
            BuildWitnessCodec.parse(Files.readString(path))
        }
        val selectedRoots = classRoots.files.map { it.canonicalFile }.toSet()
        val compiledOutputs = compilations.get().filter { !it.primarySources.isEmpty }.map { compilation ->
            val identity = compilation.identity.get()
            val paths = compilation.witnessFiles.files
            require(paths.size == 1 && paths.single().isFile) {
                "missing compiler witness for $identity: ${WitnessRejection.describe(compilation.rejectionFiles.get())}; rebuild the selected compilation"
            }
            val witness = witnesses.singleOrNull { it.artifact == identity && it.compiler == compilation.compiler.get() }
            require(witness != null) { "compiler witness identity does not match $identity" }
            val outputs = compilation.classDirectories.files.map { it.canonicalFile }
            require(outputs.isNotEmpty() && outputs.all { it.isDirectory && it in selectedRoots }) {
                "missing compiler output for $identity; include and rebuild the selected compilation"
            }
            witness to outputs
        }
        val roots = classRoots.files.map { it.toPath() }.filter { path ->
            Files.exists(path) && (witnesses.any { witness -> witness.outputs.any {
                !it.path.startsWith("external/") && project.resolve(it.path).normalize() == path.toAbsolutePath().normalize()
            } } || containsClasses(path))
        }
        val classpath = dependencyClasspath.files.filter { it.exists() }.map { it.toPath() }
        val resources = serviceResourceRoots.files.filter { it.exists() }.map { it.toPath() }
        val generated = generatedClassRoots.files.map { it.toPath() }
        val scanner = KeepRuleScanner(project, includePrivateMembers.get())
        val generatedRules = generatedKeepRuleFiles.files.map { it.toPath() }
        val existingGeneratedRules = AndroidKeepRules.existing(generatedRules, buildDirectory.get().asFile.toPath())
        val missingGeneratedRules = generatedRules - existingGeneratedRules.toSet()
        val rules = scanner.scan(keepRuleFiles.files.map { it.toPath() } + existingGeneratedRules)
        val files = roots.map { "classes" to it } + classpath.map { "classpath" to it } +
            resources.map { "service-resources" to it } + generated.map { "generated-classes" to it } +
            sourceDirectories.files.map { "source-watch" to it.toPath() } +
            resourceDirectories.files.map { "directory-watch" to it.toPath() } +
            buildInputFiles.files.map { "buildConfig" to it.toPath() } +
            buildFileWatches.files.map { "file-watch" to it.toPath() } +
            buildDirectoryWatches.files.map { "directory-watch" to it.toPath() } +
            buildLogicWatches.files.map { "build-logic-watch" to it.toPath() } +
            scanner.inputFiles.map { "keepRules" to it } + witnessPaths.map { "witness" to it } +
            listOfNotNull(baselineFile.orNull?.asFile?.toPath()?.let { "baseline" to it },
                manifestFile.orNull?.asFile?.toPath()?.let { "manifest" to it }) +
            androidResourceDirectories.files.map { "directory-watch" to it.toPath() } +
            missingGeneratedRules.map { "directory-watch" to requireNotNull(it.parent) }.distinct()
        val bindings = linkedMapOf<String, Path>()
        fun capture(observation: VerifiedCaptureScope): SnapshotProvenance {
            val requested = files.mapIndexed { index, (role, path) -> CaptureInput(path, role, "$role-$index") }
            val inputs = observation.captureAll(project, requested).onEachIndexed { index, captured ->
                if (captured.path.startsWith("external/")) bindings[captured.path] = requested[index].path.toFile().canonicalFile.toPath()
            } + InputFingerprint("options", "snapshot-options", ContentFingerprint.values(listOf(
                scope.get(), includeSourcePaths.get().toString(), includePrivateMembers.get().toString(), namespace.orNull.orEmpty(),
            )))
            return SnapshotProvenance(inputs, witnesses)
        }
        val indexCache = if (indexCacheEnabled.get()) ClassIndexCache(indexCacheDirectory.get().asFile.toPath()) else null
        val prepared = ContentFingerprint.withVerifiedCapture(::capture, indexCache) { observation, before ->
            bindCompilerInputs(before, bindings)
            compiledOutputs.forEach { (witness, outputs) ->
                require(outputs.all { output -> witness.outputs.any { recorded ->
                    val path = if (recorded.path.startsWith("external/")) bindings[recorded.path] else project.resolve(recorded.path)
                    path?.toFile()?.canonicalFile == output
                } }) { "compiler witness output does not match ${witness.artifact}" }
            }
            val verified = ProvenanceVerifier.verify(before, project, scope.get(), bindings)
            require(verified.status == "matched") {
                "snapshot compiler inputs are ${verified.status}: ${verified.reasons.joinToString()}; rebuild the selected compilations; " +
                    "class roots=${before.inputs.filter { it.role == "classes" }.map { it.path }.take(10)}; " +
                    "compiler outputs=${witnesses.flatMap { it.outputs }.map { it.path }.take(10)}"
            }
            val selectedSources = sourceFiles.files.map { file ->
                require(file.isFile && !Files.isSymbolicLink(file.toPath())) { "snapshot source inventory contains an unavailable file" }
                file.canonicalFile.toPath()
            }.toSet()
            val compilerSources = witnesses.flatMap { it.inputs }.filter { it.role == "sources" }.map { input ->
                if (input.path.startsWith("external/")) bindings.getValue(input.path) else project.resolve(input.path)
            }.filter { Files.isRegularFile(it) && it.fileName.toString().let { name -> name.endsWith(".java") || name.endsWith(".kt") } }
                .map { it.toRealPath() }.toSet()
            require(selectedSources == compilerSources) { "snapshot source inventory does not match compiler units" }
            val indexed = observation.indexWithObservations(roots, classpath, resources, generated, indexCache)
            val graph = indexed.graph
            val entryPoints = buildList {
                manifestFile.orNull?.asFile?.toPath()?.let { manifest ->
                    require(namespace.isPresent) { "snapshot manifest requires its Android namespace" }
                    addAll(AndroidManifestScanner(project).scan(manifest, namespace.get()))
                }
                androidResourceDirectories.files.filter { it.isDirectory }.forEach { directory ->
                    addAll(AndroidXmlScanner(project).scan(directory.toPath()))
                }
            }
            val retention = DefaultRetention.find(graph, entryPoints, rules, indexed.hierarchy,
                includePrivateMembers = includePrivateMembers.get())
            val baseline = baselineFile.orNull?.asFile?.toPath()?.let { BaselineCodec.parse(Files.readString(it)) }.orEmpty()
            val suppressed = graph.nodes.values.filter { Finding(it.id, it.location).fingerprint in baseline }
                .mapTo(mutableSetOf()) { it.id }
            val paths = if (includeSourcePaths.get()) SourcePathIndex.resolve(graph, project, selectedSources) else null
            val located = if (paths == null) graph else CodeGraph(graph.nodes.values.map { node ->
                paths.byNodeId[node.id]?.let { path -> node.copy(location = node.location?.copy(path = path)) } ?: node
            }, graph.edges, graph.externalCalls, graph.serviceProviders)
            val snapshot = QuerySnapshot(located, retention, RuntimeLimitationScanner.scan(indexed, selectedSources) +
                paths?.limitations.orEmpty() + if (missingGeneratedRules.isEmpty()) emptyList() else
                    listOf("missing-generated-keep-files: ${missingGeneratedRules.size}"),
                suppressed = suppressed, includePrivateMembers = includePrivateMembers.get(), revision = revision.orNull,
                scope = scope.get(), provenance = before)
            val content = QuerySnapshotCodec.render(snapshot, compact = true, maximumBytes = snapshotMaximumBytes)
            SnapshotOutput(content, indexed, before)
        }
        val finalVerification = ProvenanceVerifier.verify(prepared.provenance, project, scope.get(), bindings)
        require(finalVerification.status == "matched") { "compiler inputs changed during snapshot capture" }
        val output = snapshotFile.get().asFile.toPath()
        val local = localBindingsFile.get().asFile.toPath()
        Files.createDirectories(requireNotNull(local.parent))
        Files.writeString(local, ExternalInputBindingsCodec.render(bindings.mapValues { it.value.toString() }))
        Files.createDirectories(requireNotNull(output.parent))
        Files.writeString(output, prepared.content)
        val graph = prepared.indexed.graph
        logger.lifecycle("kartograph ${scope.get()}: snapshot ${graph.nodeCount} nodes and ${graph.edgeCount} edges")
        val stats = prepared.indexed.statistics
        logger.info("kartograph index: classes=${stats.classFiles} hits=${stats.cacheHits} parsed=${stats.parsedClasses} " +
            "invalid=${stats.invalidEntries} writeFailures=${stats.writeFailures} unavailable=${stats.unavailableEntries}")
    }

    private data class SnapshotOutput(
        val content: String,
        val indexed: IndexedClasses,
        val provenance: SnapshotProvenance,
    )

    private fun containsClasses(path: Path): Boolean = if (Files.isDirectory(path)) {
        Files.walk(path).use { files -> files.anyMatch { it.fileName.toString().endsWith(".class") } }
    } else path.fileName.toString().endsWith(".jar")

    private fun bindCompilerInputs(provenance: SnapshotProvenance, bindings: MutableMap<String, Path>) {
        // 자동 producer와 snapshot은 같은 순서의 설정 입력을 공유한다. 동일한 내용의 파일도 위치로 구분한다.
        val configuration = buildInputFiles.files.map { it.canonicalFile.toPath() }
        provenance.witnesses.forEach { witness ->
            val recorded = witness.inputs.filter { it.role == "buildConfig" }
            require(recorded.size == configuration.size) { "snapshot build configuration inventory does not match compiler inputs" }
            recorded.zip(configuration).forEach { (input, path) ->
                if (input.path.startsWith("external/")) bindings[input.path] = path
                else require(projectDirectory.get().asFile.toPath().resolve(input.path).toFile().canonicalFile.toPath() == path) {
                    "snapshot build configuration order does not match compiler inputs"
                }
            }
        }
        val candidates = (compilerInputFiles.files + dependencyClasspath.files + classRoots.files + sourceFiles.files + buildInputFiles.files)
            .filter { it.exists() }.map { it.canonicalFile.toPath() }.distinct()
        val hashes = mutableMapOf<Pair<Path, Boolean>, String>()
        provenance.witnesses.flatMap { it.inputs + it.outputs + it.compilerEvidence }
            .filter { it.path.startsWith("external/") && it.role !in setOf("options", "buildConfig") }.forEach { input ->
                val matches = candidates.filter { path ->
                    hashes.getOrPut(path to (input.role == "sources")) {
                        ContentFingerprint.hash(path, input.role == "sources")
                    } == input.sha256
                }
                require(matches.size == 1) { "compiler input binding is missing or ambiguous; check selected compiler inputs" }
                bindings[input.path] = matches.single()
            }
    }
}
