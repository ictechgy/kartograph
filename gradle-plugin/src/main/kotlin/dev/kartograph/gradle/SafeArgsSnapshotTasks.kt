package dev.kartograph.gradle

import dev.kartograph.core.GenerationReceipt
import dev.kartograph.core.GenerationSource
import dev.kartograph.export.GenerationReceiptCodec
import org.gradle.api.Project
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskProvider

/** 지원되는 SafeArgs public task를 현재 실행에 연결한다. source 이름으로 task나 컴파일 성공을 추정하지 않는다. */
internal object SafeArgsSnapshotTasks {
    fun register(project: Project, variant: String, scope: String, snapshot: TaskProvider<KartographAndroidSnapshotTask>) {
        project.pluginManager.withPlugin("androidx.navigation.safeargs") {
            val suffix = variant.replaceFirstChar(Char::titlecase)
            // 복원된 action 객체의 소유권은 아직 검증하지 않았다. 일반 빌드/cache 정책은 그대로 둔다.
            if (project.gradle.startParameter.isConfigurationCacheRequested) {
                snapshot.configure { it.captureLimitations.add("generation-unverified: configuration-cache hook identity is not supported") }
                project.tasks.named("kartographGraph$suffix", KartographGraphTask::class.java).configure {
                    it.safeArgsUnavailable = "generation-live-unverified: configuration-cache hook identity is not supported"
                }
                project.tasks.named("kartographDead$suffix", KartographDeadTask::class.java).configure {
                    it.safeArgsUnavailable = "generation-live-unverified: configuration-cache hook identity is not supported"
                }
                return@withPlugin
            }
            val taskName = "generateSafeArgs$suffix"
            var lifecycle: SafeArgsGenerationLifecycle? = null
            var unavailable = "generation-task-unavailable"
            val current = {
                lifecycle?.complete() ?: SafeArgsGenerationLifecycle.Result(null, unavailable, false)
            }
            val completion = project.tasks.register("kartographVerifySafeArgs$suffix") { task ->
                task.group = "verification"
                task.description = "Revalidates current SafeArgs generation evidence without changing generator inputs."
                task.dependsOn(taskName)
                task.outputs.upToDateWhen { false }
                task.doLast { current().reason?.let { task.logger.info("kartograph generation unverified: $it") } }
            }
            snapshot.configure { task ->
                task.dependsOn(completion)
                task.safeArgsCapture = current
                task.inputs.files(project.provider { current().files.map { it.second.toFile() } })
                    .withPropertyName("kartographSafeArgsGenerationInputs").withPathSensitivity(PathSensitivity.NONE)
            }
            val live = SafeArgsLiveCapture(project.layout.projectDirectory.asFile.toPath(), scope,
                { snapshot.get().snapshotFile.get().asFile.toPath() }, { snapshot.get().localBindingsFile.get().asFile.toPath() },
                { dev.kartograph.export.QuerySnapshotCodec.maximumBytes(snapshot.get().snapshotMaxMiB.get()) }, current)
            fun inputs(task: org.gradle.api.Task) {
                task.dependsOn(snapshot)
                task.inputs.files(snapshot.flatMap { it.snapshotFile }, snapshot.flatMap { it.localBindingsFile })
                    .withPropertyName("kartographSafeArgsCompilerProof").withPathSensitivity(PathSensitivity.NONE)
                task.outputs.upToDateWhen { false }
            }
            project.tasks.named("kartographGraph$suffix", KartographGraphTask::class.java).configure {
                inputs(it); it.safeArgsLive = live
            }
            project.tasks.named("kartographDead$suffix", KartographDeadTask::class.java).configure {
                inputs(it); it.safeArgsLive = live
            }
            project.tasks.matching { it.name == taskName }.configureEach { generator ->
                if (generateSequence(generator.javaClass as Class<*>?) { it.superclass }.none { it.name == SafeArgsTaskBytes.TASK_CLASS }) {
                    unavailable = "unsupported SafeArgs task type"
                    return@configureEach
                }
                val observed = SafeArgsGenerationLifecycle(project.layout.projectDirectory.asFile.toPath(), generator,
                    project.layout.buildDirectory.dir("kartograph/generation/$variant").get().asFile.toPath(), variant) { nonce, input, output, sources ->
                    GenerationReceiptCodec.render(GenerationReceipt(scope, generator.path, GenerationReceipt.POLICY, nonce,
                        input.outputRoot, input.files, requireNotNull(output.mapping), output.files,
                        sources.map { GenerationSource(it.input, it.path, it.qualifiedName, it.sha256) }))
                }
                observed.install()
                lifecycle = observed
            }
        }
    }
}
