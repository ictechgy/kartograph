package dev.kartograph.gradle

import dev.kartograph.analysis.DefaultRetention
import dev.kartograph.analysis.DeadFindings
import dev.kartograph.analysis.InputHints
import dev.kartograph.analysis.KeepRuleRetention
import dev.kartograph.analysis.ReachabilityAnalyzer
import dev.kartograph.core.AnalysisLimitation
import dev.kartograph.core.Finding
import dev.kartograph.core.GraphNode
import dev.kartograph.core.InputHint
import dev.kartograph.core.KeepRule
import dev.kartograph.core.RetentionEvidence
import dev.kartograph.export.AdoptionReporter
import dev.kartograph.export.BaselineCodec
import dev.kartograph.export.ReportFormat
import dev.kartograph.index.AndroidManifestScanner
import dev.kartograph.index.AndroidXmlScanner
import dev.kartograph.index.ClassFileIndexer
import dev.kartograph.index.ClassHierarchyIndexer
import dev.kartograph.index.KeepRuleScanner
import java.nio.file.Files
import java.nio.file.Path
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/** Android variant artifact를 직접 받아 CLI와 독립적으로 dead report를 생성한다. */
@DisableCachingByDefault(because = "Keep-rule include files are discovered while the task executes")
public abstract class KartographDeadTask : DefaultTask() {
    /** Member 진단 opt-in도 task 입력으로 기록해 보고 범위를 명확히 한다. */
    @get:Input
    public abstract val includePrivateMembers: Property<Boolean>
    @get:Input public abstract val keepRuleMode: Property<String>
    @get:Input public abstract val useVariantKeepRules: Property<Boolean>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val excludedKeepRuleFiles: ConfigurableFileCollection
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val variantKeepRuleFiles: ConfigurableFileCollection

    /** Android framework 상속 규칙도 dependency hierarchy에 포함한다. */
    @get:Classpath
    public abstract val platformClasspath: ConfigurableFileCollection

    init {
        includePrivateMembers.convention(false)
        keepRuleMode.convention("strict")
        useVariantKeepRules.convention(true)
    }
    @get:Classpath
    public abstract val projectJars: ListProperty<RegularFile>

    @get:Classpath
    public abstract val projectDirectories: ListProperty<Directory>

    /** 생성 전용 입력을 이름 추측 없이 구분한다. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val generatedClassRoots: ConfigurableFileCollection

    @get:Classpath
    public abstract val classpathJars: ListProperty<RegularFile>

    @get:Classpath
    public abstract val classpathDirectories: ListProperty<Directory>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val manifest: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val resourceDirectories: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val keepRuleFiles: ConfigurableFileCollection

    /** variant의 Java resource root다. 기본 source set 중 미생성 디렉터리는 실행 시 제외한다. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val serviceResourceDirectories: ConfigurableFileCollection

    @get:Input
    public abstract val namespace: Property<String>

    @get:Input
    public abstract val variantName: Property<String>

    @get:Input
    public abstract val strict: Property<Boolean>

    @get:Input
    public abstract val reportFormat: Property<String>

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val baselineFile: RegularFileProperty

    /** 명시적으로 요청한 task 테스트/도구가 현재 finding을 baseline으로 캡처한다. */
    @get:OutputFile
    @get:Optional
    public abstract val baselineWriteFile: RegularFileProperty

    @get:Internal
    public abstract val projectDirectory: DirectoryProperty

    /** 빌드 출력 기준은 실행 시점에 project를 만질 수 없으므로 입력으로 받는다. */
    @get:Internal
    public abstract val buildDirectory: DirectoryProperty

    @get:OutputFile
    public abstract val reportFile: RegularFileProperty

    @TaskAction
    public fun analyze() {
        val projectRoot = projectDirectory.get().asFile.toPath()
        val classRoots = buildList {
            addAll(projectDirectories.get().map { directory -> directory.asFile.toPath() })
            addAll(projectJars.get().map { jar -> jar.asFile.toPath() })
        }
        val classpath = buildList {
            addAll(classpathDirectories.get().map { directory -> directory.asFile.toPath() })
            addAll(classpathJars.get().map { jar -> jar.asFile.toPath() })
            addAll(platformClasspath.files.sorted().map { it.toPath() })
        }
        val indexed = ClassFileIndexer().indexWithObservations(classRoots, classpath,
            serviceResourceDirectories.files.filter(java.io.File::isDirectory).sorted().map(java.io.File::toPath),
            generatedClassRoots.files.sorted().map(java.io.File::toPath))
        val sourcePaths = dev.kartograph.index.SourcePathIndex.resolve(indexed.graph, projectRoot)
        val graph = dev.kartograph.index.SourceDeclarationLocations.enrich(indexed.graph, projectRoot, sourcePaths.byNodeId, false)
        val hierarchy = indexed.hierarchy
        val retention = retentionEvidence(projectRoot, graph, hierarchy)
        val result = ReachabilityAnalyzer.analyze(graph, retention.evidence)
        val allFindings = DeadFindings.collect(graph, result, includePrivateMembers.get())
        val inputHints = InputHints.detect(
            keepRuleInputs = retention.keepRuleInputCount,
            classpathInputs = classpath.size,
            manifestEvidenceCount = retention.manifestEvidenceCount,
            graph = graph,
        ) + if (retention.evidence.any { it.reason == dev.kartograph.core.RetentionReason.UNKNOWN_KEEP_RULE }) listOf(InputHint.UNKNOWN_KEEP_RULES) else emptyList()
        val unknown = InputHint.UNKNOWN_KEEP_RULES in inputHints
        val bindingReview = dev.kartograph.analysis.AndroidBindingReview.analyze(graph, hierarchy,
            dev.kartograph.index.AndroidBindingScanner.scan(graph, classRoots,
                resourceDirectories.files.filter { it.isDirectory }.map { it.toPath() }, projectRoot,
                buildRoot = buildDirectory.get().asFile.toPath()), result)
        val diagnostics = retention.diagnostics + sourcePaths.limitations + bindingReview.diagnostics
        fun report(findings: List<Finding>, suppressed: Int) = writeReport(findings.map { finding ->
            val declaration = graph.node(finding.nodeId)?.sourceDeclaration
            val path = declaration?.path ?: sourcePaths.byNodeId[finding.nodeId]
            finding.copy(location = path?.let { dev.kartograph.core.SourceLocation(it, declaration?.line ?: finding.location?.line,
                declaration?.column ?: finding.location?.column) } ?: finding.location)
        }, suppressed, retention.unmatchedKeepRules, inputHints, diagnostics,
            (bindingReview.unresolvedAdapters + bindingReview.unresolvedOwners).associateWith { dev.kartograph.export.FindingConfidence.REVIEW })
        if (baselineWriteFile.isPresent) {
            if (unknown) throw GradleException("cannot write a baseline from unknown keep rules")
            val path = baselineWriteFile.get().asFile.toPath()
            path.parent?.let { parent -> Files.createDirectories(parent) }
            Files.writeString(path, BaselineCodec.render(allFindings))
            report(allFindings, 0)
            logger.lifecycle("kartograph ${variantName.get()}: captured ${allFindings.size} unreachable declarations")
            return
        }
        val baseline = baselineFile.orNull?.asFile?.toPath()?.let { path -> BaselineCodec.parse(Files.readString(path)) }.orEmpty()
        val findings = allFindings.filterNot { finding -> finding.fingerprint in baseline }
        report(findings, allFindings.size - findings.size)
        logger.lifecycle("kartograph ${variantName.get()}: ${findings.size} unreachable declarations")
        if (strict.get() && unknown) throw GradleException("keep-rule analysis is unknown; strict check cannot pass; see the task report")
        if (strict.get() && findings.isNotEmpty()) {
            throw GradleException("kartograph found ${findings.size} unreachable declarations; see the task report")
        }
    }

    private fun retentionEvidence(
        projectRoot: Path,
        graph: dev.kartograph.core.CodeGraph,
        hierarchy: dev.kartograph.core.ClassHierarchy,
    ): RetentionOutcome {
        // project 밖으로 옮긴 build 디렉터리의 merged manifest·생성 resource도 같은 project의 build 출력으로 읽는다.
        val buildRoot = buildDirectory.get().asFile.toPath()
        val manifestEvidence = AndroidManifestScanner(projectRoot, buildRoot).scan(manifest.get().asFile.toPath(), namespace.get())
        val inputEvidence = buildList {
            addAll(manifestEvidence)
            resourceDirectories.files.filter(java.io.File::isDirectory).sorted().forEach { resourceRoot ->
                addAll(AndroidXmlScanner(projectRoot, buildRoot).scan(resourceRoot.toPath()))
            }
        }
        require(keepRuleMode.get() in setOf("strict", "lenient")) { "keepRuleMode must be strict or lenient" }
        val excluded = excludedKeepRuleFiles.files.mapTo(mutableSetOf()) { it.canonicalFile }
        val selected = keepRuleFiles.files + if (useVariantKeepRules.get()) variantKeepRuleFiles.files else emptySet()
        val ruleFiles = existingRuleFiles(selected.filter { it.canonicalFile !in excluded }.sorted().map(java.io.File::toPath))
        val scanner = KeepRuleScanner(projectRoot, includePrivateMembers.get(), keepRuleMode.get() == "lenient")
        val keepRules = scanner.scan(ruleFiles)
        val evidence = DefaultRetention.find(graph, inputEvidence, keepRules, hierarchy,
            includePrivateMembers = includePrivateMembers.get(), lenientKeepRules = keepRuleMode.get() == "lenient")
        return RetentionOutcome(
            evidence,
            KeepRuleRetention.unmatched(keepRules, evidence),
            ruleFiles.size,
            manifestEvidence.size,
            scanner.diagnostics.map { "unknown-keep-rule: $it" } +
                if (!useVariantKeepRules.get() || excluded.isNotEmpty()) listOf("keep-rule-inputs-excluded: explicitly selected subset; omitted shrinker inputs are not assessed") else emptyList(),
        )
    }

    /**
     * AGP가 variant.proguardFiles로 넘긴 중간 산출물은 minify를 켜기 전까지 없을 수 있다
     * (AGP 8의 default_proguard_files). 빌드 출력 디렉터리 아래의 누락만 건너뛰고,
     * 소스 트리 경로의 누락은 스캐너가 기존대로 실패로 둔다.
     */
    private fun existingRuleFiles(files: List<Path>): List<Path> {
        val existing = AndroidKeepRules.existing(files, buildDirectory.get().asFile.toPath())
        val skipped = files.size - existing.size
        if (skipped > 0) logger.lifecycle("kartograph ${variantName.get()}: skipped $skipped missing generated keep rule file(s) under the build directory")
        return existing
    }

    private fun writeReport(
        findings: List<Finding>,
        suppressedCount: Int,
        unmatchedKeepRules: List<KeepRule>,
        inputHints: List<InputHint>,
        diagnostics: List<String>,
        confidence: Map<dev.kartograph.core.NodeId, dev.kartograph.export.FindingConfidence>,
    ) {
        val report = reportFile.get().asFile.toPath()
        Files.createDirectories(requireNotNull(report.parent))
        val format = ReportFormat.fromOption(reportFormat.get())
            ?: throw GradleException("invalid kartograph report format: ${reportFormat.get()}")
        val content = AdoptionReporter.render(
            format,
            findings,
            AnalysisLimitation.entries,
            suppressedCount,
            confidence = confidence,
            expiredSuppressions = 0,
            unmatchedKeepRules = unmatchedKeepRules,
            inputHints = inputHints,
            externalRetentionContexts = emptyList(),
            diagnostics = diagnostics,
        )
        Files.writeString(report, content)
    }

    private data class RetentionOutcome(
        val evidence: List<RetentionEvidence>,
        val unmatchedKeepRules: List<KeepRule>,
        val keepRuleInputCount: Int,
        val manifestEvidenceCount: Int,
        val diagnostics: List<String>,
    )

}
