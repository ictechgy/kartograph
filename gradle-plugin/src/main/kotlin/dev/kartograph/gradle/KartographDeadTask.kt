package dev.kartograph.gradle

import dev.kartograph.analysis.DefaultRetention
import dev.kartograph.analysis.DeadFindings
import dev.kartograph.analysis.ReachabilityAnalyzer
import dev.kartograph.core.AnalysisLimitation
import dev.kartograph.core.Finding
import dev.kartograph.core.GraphNode
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

    /** Android framework 상속 규칙도 dependency hierarchy에 포함한다. */
    @get:Classpath
    public abstract val platformClasspath: ConfigurableFileCollection

    init {
        includePrivateMembers.convention(false)
    }
    @get:Classpath
    public abstract val projectJars: ListProperty<RegularFile>

    @get:Classpath
    public abstract val projectDirectories: ListProperty<Directory>

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

    @get:OutputFile
    public abstract val reportFile: RegularFileProperty

    @TaskAction
    public fun analyze() {
        val projectRoot = projectDirectory.get().asFile.toPath()
        val classRoots = buildList {
            addAll(projectDirectories.get().map { directory -> directory.asFile.toPath() })
            addAll(projectJars.get().map { jar -> jar.asFile.toPath() })
        }
        val graph = ClassFileIndexer().index(classRoots)
        val classpath = buildList {
            addAll(classpathDirectories.get().map { directory -> directory.asFile.toPath() })
            addAll(classpathJars.get().map { jar -> jar.asFile.toPath() })
            addAll(platformClasspath.files.sorted().map { it.toPath() })
        }
        val hierarchy = ClassHierarchyIndexer().index(
            classpath,
            graph.nodes.values.flatMap(GraphNode::supertypes),
        )
        val evidence = retentionEvidence(projectRoot, graph, hierarchy)
        val result = ReachabilityAnalyzer.analyze(graph, evidence)
        val allFindings = DeadFindings.collect(graph, result, includePrivateMembers.get())
        if (baselineWriteFile.isPresent) {
            val path = baselineWriteFile.get().asFile.toPath()
            path.parent?.let { parent -> Files.createDirectories(parent) }
            Files.writeString(path, BaselineCodec.render(allFindings))
            writeReport(allFindings, 0)
            logger.lifecycle("kartograph ${variantName.get()}: captured ${allFindings.size} unreachable declarations")
            return
        }
        val baseline = baselineFile.orNull?.asFile?.toPath()?.let { path -> BaselineCodec.parse(Files.readString(path)) }.orEmpty()
        val findings = allFindings.filterNot { finding -> finding.fingerprint in baseline }
        writeReport(findings, allFindings.size - findings.size)
        logger.lifecycle("kartograph ${variantName.get()}: ${findings.size} unreachable declarations")
        if (strict.get() && findings.isNotEmpty()) {
            throw GradleException("kartograph found ${findings.size} unreachable declarations; see the task report")
        }
    }

    private fun retentionEvidence(
        projectRoot: Path,
        graph: dev.kartograph.core.CodeGraph,
        hierarchy: dev.kartograph.core.ClassHierarchy,
    ): List<RetentionEvidence> {
        val inputEvidence = buildList {
            addAll(AndroidManifestScanner(projectRoot).scan(manifest.get().asFile.toPath(), namespace.get()))
            resourceDirectories.files.filter(java.io.File::isDirectory).sorted().forEach { resourceRoot ->
                addAll(AndroidXmlScanner(projectRoot).scan(resourceRoot.toPath()))
            }
        }
        val keepRules = KeepRuleScanner(projectRoot, includePrivateMembers.get())
            .scan(keepRuleFiles.files.sorted().map(java.io.File::toPath))
        return DefaultRetention.find(graph, inputEvidence, keepRules, hierarchy,
            includePrivateMembers = includePrivateMembers.get())
    }

    private fun writeReport(findings: List<Finding>, suppressedCount: Int) {
        val report = reportFile.get().asFile.toPath()
        Files.createDirectories(requireNotNull(report.parent))
        val format = ReportFormat.fromOption(reportFormat.get())
            ?: throw GradleException("invalid kartograph report format: ${reportFormat.get()}")
        val content = AdoptionReporter.render(format, findings, AnalysisLimitation.entries, suppressedCount)
        Files.writeString(report, content)
    }

}
