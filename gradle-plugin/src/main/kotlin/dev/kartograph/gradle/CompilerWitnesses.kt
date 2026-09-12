package dev.kartograph.gradle

import dev.kartograph.core.BuildWitness
import dev.kartograph.core.InputFingerprint
import dev.kartograph.export.BuildWitnessCodec
import dev.kartograph.index.ContentFingerprint
import dev.kartograph.index.CompilerEvidenceReceipts
import dev.kartograph.index.CompilerEvidenceToken
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.compile.JavaCompile
import java.io.File
import java.io.Serializable
import java.nio.file.Files
import java.nio.file.Path
import org.gradle.api.specs.Spec

/** 명시적으로 선택한 Java compiler task와 source/build 입력을 성공한 산출물에 연결한다. */
public object CompilerWitnesses {
    /** main/test/generated root를 호출자가 선택한다. 실제 destination provider만 사용한다. */
    @JvmOverloads
    public fun javaCompile(project: Project, compiler: TaskProvider<JavaCompile>, scope: String,
        sourceRoots: FileCollection, buildInputs: FileCollection, additionalInputs: FileCollection = project.files(),
        compilerEvidence: Boolean = false): Provider<RegularFile> =
        register(project, compiler, scope, sourceRoots, buildInputs, "javac", additionalInputs, compilerEvidence = compilerEvidence)

    /** 수집기는 compiler action 중에만 이 요청 토큰을 읽는다. 성공 증거나 cache output은 아니다. */
    public fun inputTokenFile(project: Project, compiler: TaskProvider<out Task>): Provider<RegularFile> =
        project.layout.buildDirectory.file("kartograph/witnesses/${compiler.name}.pending")

    /** 수집기 출력 전용 경로다. 활성화된 compiler가 실행되기 전에 이전 파일을 제거한다. */
    public fun evidenceDirectory(project: Project, compiler: TaskProvider<out Task>): Provider<Directory> =
        project.layout.buildDirectory.dir("kartograph/compiler-evidence/${compiler.name}")

    internal fun register(project: Project, compiler: TaskProvider<out Task>, scope: String,
        sourceRoots: FileCollection, buildInputs: FileCollection, kind: String,
        additionalInputs: FileCollection,
        kotlinJdk: Provider<org.gradle.jvm.toolchain.JavaLauncher>? = null,
        compilerEvidence: Boolean = false): Provider<RegularFile> {
        val witnessDirectory = project.layout.buildDirectory.dir("kartograph/witnesses/${compiler.name}")
        val witness = witnessDirectory.map { it.file("witness.json") }
        val taskIdentity = if (project.path == ":") ":${compiler.name}" else "${project.path}:${compiler.name}"
        val byteInputs = project.objects.fileCollection().from(sourceRoots, buildInputs, additionalInputs)
        val spec = WitnessSpec(project.layout.projectDirectory.asFile, scope, compiler.name, taskIdentity, kind, sourceRoots, buildInputs, byteInputs, additionalInputs, witness, kotlinJdk, compilerEvidence)
        compiler.configure { task ->
            task.inputs.files(sourceRoots).withPropertyName("kartographSourceRoots").withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
            task.inputs.files(buildInputs).withPropertyName("kartographBuildInputs").withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
            task.inputs.property("kartographScope", scope)
            task.inputs.property("kartographCompilerEvidenceEnabled", compilerEvidence)
            if (compilerEvidence) {
                task.outputs.dir(evidenceDirectory(project, compiler)).withPropertyName("kartographCompilerEvidence")
                if (task is JavaCompile) task.options.isIncremental = false
                else KotlinCompilerWitnesses.fullCompilation(task)
            }
            // ABI 정규화와 별개로 바이트를 키에 넣는다. task.inputs.files를 재사용하면 설정 캐시가 자기 참조한다.
            if (task is JavaCompile) {
                byteInputs.from(project.providers.provider {
                    listOfNotNull(task.classpath, task.options.annotationProcessorPath, task.options.bootstrapClasspath, task.options.sourcepath)
                }, task.javaCompiler.map { it.metadata.installationPath.file("lib/modules") })
            } else {
                byteInputs.from(KotlinCompilerWitnesses.byteInputs(task), requireNotNull(kotlinJdk).map {
                    it.metadata.installationPath.file("lib/modules")
                })
            }
            task.inputs.files(byteInputs).withPropertyName("kartographByteInputs").withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
            // KGP는 compiler output을 디렉터리로 준비하므로 증거 전용 디렉터리를 선언한다.
            task.outputs.dir(witnessDirectory).withPropertyName("kartographBuildWitness")
            task.outputs.upToDateWhen(MatchingWitness(spec))
            val listener = project.gradle.sharedServices.registerIfAbsent("kartographWitness${ContentFingerprint.values(listOf(task.path))}", FailedWitness::class.java) {
                it.parameters.witness.set(witness)
            }
            project.objects.newInstance(WitnessEvents::class.java).registry.onTaskCompletion(listener)
            // 이전 성공 기록은 재컴파일가 시작되기 전에 제거한다. 실패하면 완료 action은 실행되지 않는다.
            task.doFirst(BeginWitness(spec))
            task.doLast(CompleteWitness(spec))
        }
        return compiler.flatMap { witness }
    }
}

/** 공개 task 완료 이벤트로 뒤에 추가된 action 실패도 이전 성공 기록을 남기지 않게 한다. */
public abstract class FailedWitness : org.gradle.api.services.BuildService<FailedWitness.Parameters>, org.gradle.tooling.events.OperationCompletionListener, AutoCloseable {
    /** 완료 이벤트와 해당 compiler의 증거 output을 연결하는 값이다. */
    public interface Parameters : org.gradle.api.services.BuildServiceParameters {
        public val witness: org.gradle.api.file.RegularFileProperty
    }
    @Volatile private var failed = false
    override fun onFinish(event: org.gradle.tooling.events.FinishEvent) {
        if (event is org.gradle.tooling.events.task.TaskFinishEvent && event.result is org.gradle.tooling.events.task.TaskFailureResult) {
            failed = true
            Files.deleteIfExists(parameters.witness.get().asFile.toPath())
        }
    }
    override fun close() {
        // --continue에서 다른 task가 뒤늦게 성공해도 실패한 build가 증거를 남기지 않는다.
        if (failed) Files.deleteIfExists(parameters.witness.get().asFile.toPath())
    }
}

internal abstract class WitnessEvents @javax.inject.Inject constructor(val registry: org.gradle.build.event.BuildEventsListenerRegistry)

internal class MatchingWitness(private val spec: WitnessSpec) : Spec<Task>, Serializable {
    override fun isSatisfiedBy(task: Task): Boolean = try {
        // output이 없으면 Gradle의 정상 cache 복원을 허용한다. 복원 문서도 소비 시 내용 검증한다.
        if (!spec.witness.get().asFile.exists()) true else {
            val witness = BuildWitnessCodec.parse(Files.readString(spec.witness.get().asFile.toPath()))
            val inputs = spec.observe(task)
            witness.scope == spec.scope && witness.compiler == spec.kind && witness.artifact == spec.taskIdentity &&
                witness.inputs == inputs && witness.outputs == listOf(spec.output(task)) &&
                if (spec.compilerEvidence) witness.compilerEvidence == spec.receipts(task, inputs) && CompilerEvidenceToken.matches(witness)
                else witness.compilerEvidence.isEmpty()
        }
    } catch (_: java.io.IOException) { false }
    catch (_: IllegalArgumentException) { false }
}

internal data class CompilerObservation(val sources: Set<File>, val destination: File, val files: List<Pair<String, File>>, val options: List<String>)

internal data class WitnessSpec(val project: File, val scope: String, val artifact: String, val taskIdentity: String, val kind: String,
    val sourceRoots: FileCollection, val buildInputs: FileCollection, val byteInputs: FileCollection,
    val additionalInputs: FileCollection, val witness: Provider<RegularFile>,
    val kotlinJdk: Provider<org.gradle.jvm.toolchain.JavaLauncher>?, val compilerEvidence: Boolean = false) : Serializable {
    fun observe(task: Task): List<InputFingerprint> {
        val observed = if (kind == "javac") javaObservation(task as JavaCompile)
            else KotlinCompilerWitnesses.observe(task, requireNotNull(kotlinJdk).get(), additionalInputs)
        val roots = sourceRoots.files.toList()
        require(roots.isNotEmpty() && buildInputs.files.isNotEmpty()) { "compiler witness requires explicit source roots and build configuration inputs" }
        val eligible = roots.flatMap { root ->
            require(root.isDirectory) { "compiler witness source root is missing" }
            Files.walk(root.toPath()).use { stream -> stream.filter { Files.isRegularFile(it) &&
                (it.toString().endsWith(".java") || kind == "kotlin" && it.toString().endsWith(".kt")) }.map { it.toFile().canonicalFile }.toList() }
        }.toSet()
        require(eligible == observed.sources.map { it.canonicalFile }.toSet()) { "declared source roots do not match compiler sources; include generated and test inputs explicitly" }
        val files = roots.map { "sources" to it } + buildInputs.files.map { "buildConfig" to it } + observed.files
        val covered = byteInputs.files.map { it.canonicalFile }
        require(files.all { (_, file) ->
            val current = file.canonicalFile
            covered.any { root -> current == root || root.isDirectory && current.toPath().startsWith(root.toPath()) }
        }) { "compiler witness byte inputs omit declared compiler files; supply additionalInputs explicitly" }
        val projectPath = project.toPath()
        val fingerprints = files.mapIndexed { index, (role, file) ->
            ContentFingerprint.capture(projectPath, file.toPath(), role, "$artifact-$role-$index")
        }
        val replacements = (files.zip(fingerprints).flatMap { (entry, fingerprint) ->
            listOf(entry.second.absolutePath, entry.second.toURI().toASCIIString(), entry.second.toPath().toAbsolutePath().toUri().toASCIIString())
                .map { it to fingerprint.path }
        } + listOf(project.absolutePath, project.toURI().toASCIIString().trimEnd('/'), project.toPath().toAbsolutePath().toUri().toASCIIString().trimEnd('/'))
            .map { it to "project" }).sortedByDescending { it.first.length }
        val normalized = observed.options.map { value -> replacements.fold(value) { text, (path, identity) -> text.replace(path, identity) } }
        return fingerprints + InputFingerprint("options", "$artifact-options", ContentFingerprint.values(normalized))
    }

    fun output(task: Task): InputFingerprint {
        val destination = if (kind == "javac") (task as JavaCompile).destinationDirectory.get().asFile else KotlinCompilerWitnesses.destination(task)
        return ContentFingerprint.capture(project.toPath(), destination.toPath(), "classes", "$artifact-classes")
    }

    fun pending(): Path = witness.get().asFile.toPath().parent.parent.resolve("$artifact.pending")

    fun evidenceDirectory(): Path = witness.get().asFile.toPath().parent.parent.parent.resolve("compiler-evidence").resolve(artifact)

    fun token(inputs: List<InputFingerprint>): String = CompilerEvidenceToken.create(scope, kind, taskIdentity, inputs)

    fun receipts(task: Task, inputs: List<InputFingerprint>): List<InputFingerprint> {
        val directory = evidenceDirectory()
        require(Files.isDirectory(directory) && !Files.isSymbolicLink(directory)) { "compiler evidence output directory is missing" }
        val documents = Files.list(directory).use { it.sorted().toList() }
        require(documents.all { Files.isRegularFile(it) && it.fileName.toString().endsWith(".tsv") }) { "compiler evidence output contains unsupported entries" }
        val observed = if (kind == "javac") javaObservation(task as JavaCompile)
            else KotlinCompilerWitnesses.observe(task, requireNotNull(kotlinJdk).get(), additionalInputs)
        val expected = observed.sources.filter { it.extension == if (kind == "javac") "java" else "kt" }.map { it.toPath() }.toSet()
        val sourcePaths = sourceRoots.files + if (task is JavaCompile) task.options.sourcepath?.files.orEmpty() else emptySet()
        val generated = listOf(observed.destination) + if (task is JavaCompile)
            listOfNotNull(task.options.generatedSourceOutputDirectory.orNull?.asFile) else emptyList()
        return CompilerEvidenceReceipts.validate(project.toPath(), kind, token(inputs), inputs, documents, expected,
            sourcePaths.map { it.toPath() }, generated.map { it.toPath() }, artifact)
    }

    private fun javaObservation(task: JavaCompile): CompilerObservation {
        require(task.options.isFailOnError) { "compiler witness requires failOnError=true" }
        val installation = task.javaCompiler.get().metadata.installationPath.asFile.toPath().toRealPath()
        val declared = task.inputs.files.files
        val files = task.classpath.files.map { "classpath" to it } +
            task.options.annotationProcessorPath?.files.orEmpty().map { "processor" to it } +
            task.options.bootstrapClasspath?.files.orEmpty().map { "bootClasspath" to it } +
            task.options.sourcepath?.files.orEmpty().map { "sources" to it } +
            ("compiler" to installation.resolve("lib/modules").toFile())
        require(task.options.forkOptions.executable == null && task.options.forkOptions.jvmArgs.orEmpty().isEmpty()) {
            "compiler witness requires the declared Java toolchain without a custom launcher or JVM arguments"
        }
        require(task.options.extensionDirs.isNullOrEmpty()) { "compiler witness requires declared javac file input APIs" }
        val arguments = task.options.allCompilerArgs
        validateJavaArguments(arguments, declared)
        val known = (files.map { it.second } + task.source.files + sourceRoots.files + buildInputs.files).map { it.canonicalFile }.toSet()
        val extraInputs = declared.filter { it.canonicalFile !in known }.map { "compilerInput" to it }
        return CompilerObservation(task.source.files, task.destinationDirectory.get().asFile, files + extraInputs,
            listOf(task.sourceCompatibility, task.targetCompatibility, task.options.release.orNull?.toString().orEmpty(),
                task.options.encoding.orEmpty(), task.options.isDebug.toString(), task.options.debugOptions.debugLevel.orEmpty(),
                task.options.isWarnings.toString(), task.options.isDeprecation.toString(), task.options.javaModuleVersion.orNull.orEmpty(),
                task.options.javaModuleMainClass.orNull.orEmpty()) + arguments)
    }

    private fun validateJavaArguments(arguments: List<String>, declared: Set<File>) {
        val paths = declared.map { it.canonicalFile }.toSet()
        var index = 0
        while (index < arguments.size) {
            val argument = arguments[index++]
            if (argument in setOf("--system", "--module-path", "--upgrade-module-path", "--patch-module", "-classpath", "--class-path", "-cp", "-sourcepath", "--source-path", "-processorpath", "--processor-path")) {
                val value = arguments.getOrNull(index++) ?: throw IllegalArgumentException("missing declared compiler argument input")
                val pathValue = if (argument == "--patch-module") value.substringAfter('=', "") else value
                require(pathValue.isNotEmpty() && pathValue.split(File.pathSeparator).all { File(it).canonicalFile in paths }) {
                    "compiler argument file inputs must be declared to Gradle"
                }
            } else require(argument in setOf("-parameters", "-Werror", "-Xlint", "-g", "-proc:none", "-proc:full", "--enable-preview", "-XDstringConcat=inline") ||
                argument.startsWith("-Xlint:") || argument.startsWith("-g:") || argument.startsWith("-A") || argument.startsWith("-Xdiags:") ||
                compilerEvidence && argument.startsWith("-Xplugin:KartographEvidence ")) {
                "unsupported javac option for compiler witness"
            }
        }
    }
}

internal class BeginWitness(private val spec: WitnessSpec) : Action<Task>, Serializable {
    override fun execute(task: Task) {
        Files.deleteIfExists(spec.witness.get().asFile.toPath())
        Files.deleteIfExists(spec.pending())
        if (spec.compilerEvidence && Files.exists(spec.evidenceDirectory())) {
            require(Files.isDirectory(spec.evidenceDirectory()) && !Files.isSymbolicLink(spec.evidenceDirectory())) { "invalid compiler evidence output directory" }
            Files.list(spec.evidenceDirectory()).use { entries -> entries.forEach {
                require(Files.isRegularFile(it) && !Files.isSymbolicLink(it) &&
                    (it.fileName.toString().endsWith(".tsv") || it.fileName.toString().endsWith(".tmp"))) { "unsupported compiler evidence output entry" }
                Files.delete(it)
            } }
        }
        val inputs = spec.observe(task)
        // pending은 성공 증거 형식을 갖지 않으며 compiler output/cache에 포함하지 않는다.
        Files.createDirectories(spec.pending().parent)
        Files.writeString(spec.pending(), spec.token(inputs))
    }
}

internal class CompleteWitness(private val spec: WitnessSpec) : Action<Task>, Serializable {
    override fun execute(task: Task) {
        val inputs = spec.observe(task)
        require(Files.isRegularFile(spec.pending()) && Files.readString(spec.pending()) == spec.token(inputs)) {
            "compiler inputs changed during compilation; rebuild before capturing a snapshot"
        }
        val receipts = if (spec.compilerEvidence) spec.receipts(task, inputs) else emptyList()
        val witness = BuildWitness(spec.scope, spec.kind, spec.taskIdentity, inputs, listOf(spec.output(task)), receipts,
            if (spec.compilerEvidence) spec.token(inputs) else null)
        Files.createDirectories(spec.witness.get().asFile.toPath().parent)
        Files.writeString(spec.witness.get().asFile.toPath(), BuildWitnessCodec.render(witness))
        Files.delete(spec.pending())
    }
}
