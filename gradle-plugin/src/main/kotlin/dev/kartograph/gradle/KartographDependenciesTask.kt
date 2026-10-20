package dev.kartograph.gradle

import dev.kartograph.analysis.DependencyConfigurationAnalysis
import dev.kartograph.analysis.DependencyLimitations
import dev.kartograph.analysis.DependencyReview
import dev.kartograph.export.BaselineCodec
import dev.kartograph.export.SuppressCodec
import dev.kartograph.core.DeclaredDependency
import dev.kartograph.core.DependencyScope
import dev.kartograph.export.DependencyListCodec
import dev.kartograph.export.DependencyReporter
import dev.kartograph.export.ReportFormat
import dev.kartograph.index.DependencyInputScanner
import java.nio.file.Path
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/** 해석된 Gradle 입력을 공통 dependency 분석에 전달하고 report를 쓴 뒤 strict 실패를 결정한다. */
@CacheableTask
public abstract class KartographDependenciesTask : DefaultTask() {
    @get:Classpath public abstract val classRoots: ConfigurableFileCollection
    @get:Classpath public abstract val testClassRoots: ConfigurableFileCollection
    @get:Classpath public abstract val dependencyArtifacts: ConfigurableFileCollection
    @get:Classpath public abstract val projectJars: ListProperty<RegularFile>
    @get:Classpath public abstract val projectDirectories: ListProperty<Directory>
    @get:Classpath public abstract val testProjectJars: ListProperty<RegularFile>
    @get:Classpath public abstract val testProjectDirectories: ListProperty<Directory>
    /** 직접 선언 목록이다. `coordinate<TAB>scope<TAB>artifact` 줄이며 artifact는 [artifactLocations]의 위치 키이거나 [projectDirectory] 기준 경로다. */
    @get:Input public abstract val declarations: ListProperty<String>
    /** 선택된 compile classpath 전체 목록이다. 형식은 [declarations]와 같다. */
    @get:Input public abstract val resolved: ListProperty<String>
    /**
     * declarations·resolved의 artifact 열이 위치 키일 때 그 키가 가리키는 실제 파일 경로다.
     * plugin 배선은 checkout·Gradle user home 위치를 뺀 키만 task 입력에 두고 위치는 여기에만 둔다. 파일 내용은
     * [dependencyArtifacts]가, 키와 파일의 대응은 [artifactLocationPositions]가 입력으로 추적한다. 항목이 없는 artifact 열은 경로로 읽는다.
     */
    @get:Internal public abstract val artifactLocations: MapProperty<String, String>
    @get:Input public abstract val inputLimitations: ListProperty<String>
    @get:Input public abstract val library: Property<Boolean>
    @get:Input public abstract val strict: Property<Boolean>
    @get:Input public abstract val reportFormat: Property<String>
    @get:Internal public abstract val projectDirectory: DirectoryProperty
    @get:OutputFile public abstract val reportFile: RegularFileProperty
    @get:Optional @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    public abstract val baselineFile: RegularFileProperty
    @get:Optional @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    public abstract val suppressFile: RegularFileProperty
    /** 지정한 출력 파일에 필터 적용 전의 모든 관찰 지문을 저장한다. */
    @get:Optional @get:OutputFile public abstract val baselineOutput: RegularFileProperty
    /** 날짜가 바뀌면 configuration cache 재사용 시에도 만료를 다시 평가한다. */
    @get:Input public val suppressionDate: String
        get() = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()
    /**
     * 위치 키마다 [dependencyArtifacts]의 몇 번째 파일인지 기록한다. dependencyArtifacts는 파일 내용과 순서만 추적하므로,
     * 같은 바이트의 artifact 사이에서 키를 바꿔 붙여도 task 입력의 의미가 달라지게 한다. 대응하는 파일이 없으면 -1이다.
     */
    @get:Input public val artifactLocationPositions: List<String>
        get() {
            val locations = artifactLocations.get()
            if (locations.isEmpty()) return emptyList()
            val projectRoot = projectDirectory.get().asFile.toPath()
            val positions = mutableMapOf<Path, Int>()
            dependencyArtifacts.files.forEachIndexed { index, file -> positions.putIfAbsent(file.toPath().toAbsolutePath().normalize(), index) }
            return locations.toSortedMap().map { (key, location) -> "$key\t${positions[artifactPath(projectRoot, location)] ?: -1}" }
        }

    init {
        declarations.convention(emptyList())
        resolved.convention(emptyList())
        artifactLocations.convention(emptyMap())
        inputLimitations.convention(emptyList())
        library.convention(false)
        strict.convention(false)
        reportFormat.convention("gradle")
        projectJars.convention(emptyList())
        projectDirectories.convention(emptyList())
        testProjectJars.convention(emptyList())
        testProjectDirectories.convention(emptyList())
    }

    @TaskAction
    public fun analyzeDependencies() {
        val format = ReportFormat.fromOption(reportFormat.get()) ?: throw GradleException("Invalid dependency report format")
        val declared = DependencyListCodec.parse(declarations.get().distinct().sorted().joinToString("\n"))
        val classpath = DependencyListCodec.parse(resolved.get().distinct().sorted().joinToString("\n"))
        val projectRoot = projectDirectory.get().asFile.toPath()
        // plugin 배선은 artifact 열에 위치 키를 쓰고 실제 파일은 artifactLocations로 찾는다. 수동 설정의 경로 열은 그대로 쓴다.
        val locations = artifactLocations.get()
        fun location(artifact: String): String = locations[artifact] ?: artifact
        val main = (classRoots.files + projectJars.get().map { it.asFile } + projectDirectories.get().map { it.asFile })
            .filter { it.exists() }.map { it.toPath() }
        val tests = (testClassRoots.files + testProjectJars.get().map { it.asFile } + testProjectDirectories.get().map { it.asFile })
            .filter { it.exists() }.map { it.toPath() }
        // manual task 설정이 파일 입력에 없는 artifact를 읽어 up-to-date/cache를 우회하지 못하게 한다.
        val tracked = dependencyArtifacts.files.map { it.toPath().toAbsolutePath().normalize() }.toSet()
        val ignored = setOf(DependencyScope.RUNTIME_ONLY, DependencyScope.TEST_RUNTIME_ONLY,
            DependencyScope.KAPT, DependencyScope.KSP, DependencyScope.ANNOTATION_PROCESSOR)
        (declared + classpath).filter { it.scope !in ignored }.forEach { dependency ->
            val path = artifactPath(projectRoot, location(dependency.artifact))
            if (path !in tracked) throw GradleException("Dependency artifacts must be declared task inputs")
        }
        fun located(dependencies: List<DeclaredDependency>) = dependencies.map { it.copy(artifact = location(it.artifact)) }
        val inputs = DependencyInputScanner.scan(projectRoot, main, tests, located(declared), located(classpath))
        // 대표 artifact 선택과 정렬은 위치와 무관한 키로 한다.
        val artifactClasses = (declared + classpath).mapNotNull { dependency ->
            inputs.artifactClasses[location(dependency.artifact)]?.let { dependency.artifact to it }
        }.toMap()
        val analysis = DependencyConfigurationAnalysis.analyze(declared, inputs.main, inputs.test, artifactClasses,
            classpath, apiAdvice = library.get())
        // 보고서에는 예전처럼 실제 파일 이름만 실린다. 키의 마지막 이름이 파일 이름이라 결과가 위치에 따라 달라지지 않는다.
        val observed = analysis.copy(findings = analysis.findings.map { it.copy(artifact = location(it.artifact)) },
            advice = analysis.advice.map { it.copy(artifact = location(it.artifact)) })
        val baseline = baselineFile.orNull?.asFile?.let { BaselineCodec.parse(it.readText()) }.orEmpty()
        val suppressions = suppressFile.orNull?.asFile?.let { SuppressCodec.parse(it.readText()) }.orEmpty()
        baselineOutput.orNull?.asFile?.let { target ->
            target.parentFile.mkdirs()
            target.writeText(BaselineCodec.renderFingerprints(DependencyReview.fingerprints(observed)))
        }
        val today = java.time.LocalDate.parse(suppressionDate)
        val result = DependencyReview.apply(observed, baseline,
            suppressions.filter { it.expires >= today }.mapTo(mutableSetOf()) { it.fingerprint },
            suppressions.count { it.expires < today })
        val gaps = DependencyLimitations.describe(inputs.main, inputs.test, observed, resolvedProvided = true) + inputLimitations.get()
        val file = reportFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText(DependencyReporter.render(format, result, gaps))
        if (strict.get() && (result.findings.isNotEmpty() || result.advice.isNotEmpty())) {
            throw GradleException("Dependency findings exceeded the strict threshold; inspect the written report")
        }
    }

    /** artifact 경로는 [projectDirectory] 기준으로 읽는다. 절대경로는 그대로다. */
    private fun artifactPath(projectRoot: Path, artifact: String): Path = projectRoot.resolve(artifact).toAbsolutePath().normalize()
}
