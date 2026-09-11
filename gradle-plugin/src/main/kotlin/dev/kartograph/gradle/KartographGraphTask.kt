package dev.kartograph.gradle

import dev.kartograph.core.KartographVersion
import dev.kartograph.export.GraphJsonRenderer
import dev.kartograph.index.ClassFileIndexer
import dev.kartograph.index.SourcePathIndex
import dev.kartograph.index.SourcePathResolution
import java.nio.file.Files
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Android variant의 project class artifact에서 교환용 그래프 JSON을 만든다.
 *
 * 다른 도구가 심볼과 파일을 함께 소비할 수 있게 하는 보고 task이며, 삭제 판정이나 도달성 정책을 담지 않는다.
 */
@DisableCachingByDefault(because = "Opting into source path resolution reads project sources that cannot be declared as inputs")
public abstract class KartographGraphTask : DefaultTask() {
    @get:Classpath
    public abstract val projectJars: ListProperty<RegularFile>

    @get:Classpath
    public abstract val projectDirectories: ListProperty<Directory>

    /** 선택된 project class root 중 생성 전용 산출물의 출처다. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val generatedClassRoots: ConfigurableFileCollection

    /** 같은 바이트의 root 사이에서 생성 표시를 옮겨도 task 입력의 의미가 달라짐을 기록한다. */
    @get:Input
    public val generatedClassRootPositions: List<Int>
        get() {
            val generated = generatedClassRoots.files.mapTo(mutableSetOf()) { it.canonicalFile }
            return inputRoots().mapIndexedNotNull { index, root -> index.takeIf { root.canonicalFile in generated } }
        }

    /** 보고 범위를 명확히 하도록 경로 해석 여부도 task 입력으로 기록한다. */
    @get:Input
    public abstract val includeSourcePaths: Property<Boolean>

    @get:Input
    public abstract val variantName: Property<String>

    @get:Internal
    public abstract val projectDirectory: DirectoryProperty

    /** 선택한 variant의 Java resource에서 service 등록 사실을 읽는다. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val serviceResourceDirectories: ConfigurableFileCollection

    @get:OutputFile
    public abstract val graphFile: RegularFileProperty

    init {
        includeSourcePaths.convention(false)
    }

    @TaskAction
    public fun renderGraph() {
        val classRoots = inputRoots().map(java.io.File::toPath)
        val graph = ClassFileIndexer().indexWithObservations(classRoots, null,
            serviceResourceDirectories.files.filter(java.io.File::isDirectory).sorted().map(java.io.File::toPath),
            generatedClassRoots.files.sorted().map(java.io.File::toPath)).graph
        // 경로 해석은 opt-in이다. CLI와 같은 기본값을 유지해 요청하지 않은 경로 노출을 만들지 않는다.
        val paths = if (includeSourcePaths.get()) {
            SourcePathIndex.resolve(graph, projectDirectory.get().asFile.toPath())
        } else {
            // 경로 해석을 요청하지 않아도 계량 가능한 한계는 숨기지 않는다.
            SourcePathResolution(limitations = SourcePathIndex.missingSourcePaths(graph))
        }
        val target = graphFile.get().asFile.toPath()
        Files.createDirectories(requireNotNull(target.parent))
        Files.writeString(
            target,
            GraphJsonRenderer.render(graph, KartographVersion.current, paths.byNodeId, paths.limitations),
        )
        logger.lifecycle(
            "kartograph ${variantName.get()}: ${graph.nodeCount} nodes and ${graph.edgeCount} edges",
        )
    }

    private fun inputRoots(): List<java.io.File> = projectDirectories.get().map { it.asFile } + projectJars.get().map { it.asFile }
}
