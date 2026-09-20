package dev.kartograph.gradle

import dev.kartograph.analysis.DependencyConfigurationAnalysis
import dev.kartograph.analysis.DependencyLimitations
import dev.kartograph.analysis.DependencyReview
import dev.kartograph.export.BaselineCodec
import dev.kartograph.export.SuppressCodec
import dev.kartograph.core.DependencyScope
import dev.kartograph.export.DependencyListCodec
import dev.kartograph.export.DependencyReporter
import dev.kartograph.export.ReportFormat
import dev.kartograph.index.DependencyInputScanner
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
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
    @get:Input public abstract val declarations: ListProperty<String>
    @get:Input public abstract val resolved: ListProperty<String>
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

    init {
        declarations.convention(emptyList())
        resolved.convention(emptyList())
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
        val main = (classRoots.files + projectJars.get().map { it.asFile } + projectDirectories.get().map { it.asFile })
            .filter { it.exists() }.map { it.toPath() }
        val tests = (testClassRoots.files + testProjectJars.get().map { it.asFile } + testProjectDirectories.get().map { it.asFile })
            .filter { it.exists() }.map { it.toPath() }
        // manual task 설정이 파일 입력에 없는 artifact를 읽어 up-to-date/cache를 우회하지 못하게 한다.
        val tracked = dependencyArtifacts.files.map { it.toPath().toAbsolutePath().normalize() }.toSet()
        val ignored = setOf(DependencyScope.RUNTIME_ONLY, DependencyScope.TEST_RUNTIME_ONLY,
            DependencyScope.KAPT, DependencyScope.KSP, DependencyScope.ANNOTATION_PROCESSOR)
        (declared + classpath).filter { it.scope !in ignored }.forEach { dependency ->
            val path = projectRoot.resolve(dependency.artifact).toAbsolutePath().normalize()
            if (path !in tracked) throw GradleException("Dependency artifacts must be declared task inputs")
        }
        val inputs = DependencyInputScanner.scan(projectRoot, main, tests, declared, classpath)
        val observed = DependencyConfigurationAnalysis.analyze(declared, inputs.main, inputs.test, inputs.artifactClasses,
            classpath, apiAdvice = library.get())
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
}
