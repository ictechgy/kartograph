package dev.kartograph.gradle

import dev.kartograph.core.BuildWitness
import dev.kartograph.core.InputFingerprint
import dev.kartograph.export.BuildWitnessCodec
import dev.kartograph.index.ContentFingerprint
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import java.io.File
import java.io.Serializable
import java.nio.file.Files

/**
 * AGP application의 processResources 산출(R.jar)을 resource producer witness로 덮는다(후보 A).
 *
 * compiler witness와 같은 fail-closed 원칙을 유지한다: 입력은 실행 시점에 관측해 기록하고, task가
 * 실패하면 witness를 남기지 않으며, witness가 커버한 R.jar의 내용이 바뀌면 snapshot capture가
 * stale로 거부한다. producer의 원인 입력은 res·merged manifest·namespace·플랫폼 boot classpath다.
 */
internal object ResourceProcessWitnesses {
    /** processResources task(AGP가 늦게 생성한다)에 witness 기록을 붙이고 witness 파일 provider를 반환한다. */
    fun automaticProcessResources(
        project: Project,
        processTaskName: String,
        scope: String,
        namespace: Provider<String>,
        resDirectories: FileCollection,
        mergedManifest: Provider<RegularFile>,
        buildInputs: FileCollection,
        bootClasspath: FileCollection,
        rJar: Provider<File>,
    ): Provider<RegularFile> {
        val witnessDirectory = project.layout.buildDirectory.dir("kartograph/witnesses/$processTaskName")
        val witness = witnessDirectory.map { it.file("witness.json") }
        val taskIdentity = if (project.path == ":") ":$processTaskName" else "${project.path}:$processTaskName"
        val spec = Spec(project.layout.projectDirectory.asFile, scope, taskIdentity, namespace,
            resDirectories, mergedManifest, buildInputs, bootClasspath, rJar, witness)
        project.tasks.matching { task -> task.name == processTaskName }.configureEach { task ->
            task.outputs.dir(witnessDirectory).withPropertyName("kartographResourceWitness")
            task.doFirst(BeginResourceWitness(spec))
            task.doLast(CompleteResourceWitness(spec))
        }
        return witness
    }

    /** 실행 시점에 관측한 producer 입력과 R.jar 출력이다. providers는 실행 시점에 resolve한다. */
    internal data class Spec(
        val project: File,
        val scope: String,
        val taskIdentity: String,
        val namespace: Provider<String>,
        val resDirectories: FileCollection,
        val mergedManifest: Provider<RegularFile>,
        val buildInputs: FileCollection,
        val bootClasspath: FileCollection,
        val rJar: Provider<File>,
        val witness: Provider<RegularFile>,
    ) : Serializable {
        fun observe(): List<InputFingerprint> {
            val files = buildList {
                addAll(resDirectories.files.filter { file -> Files.exists(file.toPath()) }.map { "sources" to it })
                mergedManifest.get().asFile.takeIf(File::isFile)?.let { add("sources" to it) }
                addAll(buildInputs.files.map { "buildConfig" to it })
                addAll(bootClasspath.files.map { "compiler" to it })
            }
            val projectPath = project.toPath()
            // external slot 식별자에 ':'를 넣지 않는다(InputFingerprint 이동성 계약).
            val slotBase = taskIdentity.removePrefix(":").replace(':', '-')
            val fingerprints = files.mapIndexed { index, (role, file) ->
                try {
                    ContentFingerprint.capture(projectPath, file.toPath(), role, "$slotBase-$role-$index")
                } catch (error: IllegalArgumentException) {
                    // 절대경로는 노출하지 않고 role·파일명과 원인 메시지를 함께 남긴다.
                    throw IllegalArgumentException(
                        "resource witness fingerprint input failed ($role: ${file.name}): ${error.message}", error)
                }
            }
            return fingerprints + InputFingerprint(
                "options", "$slotBase-options",
                ContentFingerprint.values(listOf("namespace=${namespace.get()}")),
            )
        }

        fun output(): InputFingerprint {
            // slot은 프로젝트 내 경로에만 사용되지만 이동성 계약을 위해 ':'를 제거한다.
            val slot = taskIdentity.removePrefix(":").replace(':', '-')
            return ContentFingerprint.capture(project.toPath(), rJar.get().toPath(), "classes", "$slot-classes")
        }

        fun witness(): BuildWitness =
            BuildWitness(scope, "agp-process-resources", taskIdentity, observe(), listOf(output()))

        fun witnessFile(): File = witness.get().asFile
    }

    internal class BeginResourceWitness(private val spec: Spec) : Action<Task>, Serializable {
        override fun execute(task: Task) {
            // task가 실패하면 doLast가 실행되지 않고, 이전 성공 기록도 남지 않는다.
            Files.deleteIfExists(spec.witnessFile().toPath())
        }
    }

    internal class CompleteResourceWitness(private val spec: Spec) : Action<Task>, Serializable {
        override fun execute(task: Task) {
            require(spec.rJar.get().isFile) { "resource producer did not emit the R.jar output" }
            val witnessFile = spec.witnessFile()
            Files.createDirectories(witnessFile.toPath().parent)
            Files.writeString(witnessFile.toPath(), BuildWitnessCodec.render(spec.witness()))
        }
    }
}
