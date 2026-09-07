package dev.kartograph.gradle

import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.ScopedArtifacts
import com.android.build.api.variant.Variant
import org.gradle.api.Plugin
import org.gradle.api.Project

/** Android Variant API의 lazy artifact provider를 kartograph task 입력에 연결한다. */
public class KartographPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("kartograph", KartographExtension::class.java)
        extension.strict.convention(false)
        extension.includePrivateMembers.convention(false)
        extension.reportFormat.convention("gradle")
        extension.includeSourcePaths.convention(false)
        project.pluginManager.withPlugin("com.android.application") { configureAndroid(project, extension) }
        project.pluginManager.withPlugin("com.android.library") { configureAndroid(project, extension) }
    }

    private fun configureAndroid(project: Project, extension: KartographExtension) {
        val androidComponents = project.extensions.getByType(AndroidComponentsExtension::class.java)
        androidComponents.onVariants { variant -> registerVariantTask(project, extension, variant) }
    }

    private fun registerVariantTask(project: Project, extension: KartographExtension, variant: Variant) {
        registerDeadTask(project, extension, variant)
        registerGraphTask(project, extension, variant)
    }

    private fun registerDeadTask(project: Project, extension: KartographExtension, variant: Variant) {
        val taskName = "kartographDead${variant.name.replaceFirstChar(Char::titlecase)}"
        val task = project.tasks.register(taskName, KartographDeadTask::class.java) { deadTask ->
            deadTask.group = "verification"
            deadTask.description = "Reports declarations unreachable in the ${variant.name} Android variant."
            deadTask.variantName.set(variant.name)
            deadTask.namespace.set(variant.namespace)
            deadTask.strict.set(extension.strict)
            deadTask.includePrivateMembers.set(extension.includePrivateMembers)
            deadTask.platformClasspath.from(
                project.extensions.getByType(AndroidComponentsExtension::class.java).sdkComponents.bootClasspath,
            )
            deadTask.reportFormat.set(extension.reportFormat)
            deadTask.baselineFile.set(extension.baseline)
            deadTask.projectDirectory.set(project.layout.projectDirectory)
            deadTask.buildDirectory.set(project.layout.buildDirectory)
            deadTask.manifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            variant.sources.res?.all?.let { resources ->
                deadTask.resourceDirectories.from(resources.map { layers -> layers.flatten() })
            }
            deadTask.keepRuleFiles.from(extension.keepRules)
            deadTask.keepRuleFiles.from(variant.proguardFiles)
            deadTask.reportFile.set(project.layout.buildDirectory.file("reports/kartograph/${variant.name}.txt"))
            // include 대상은 실행 중 발견되므로 누락된 file snapshot으로 report를 재사용하지 않는다.
            deadTask.outputs.upToDateWhen { false }
        }
        variant.artifacts.forScope(ScopedArtifacts.Scope.PROJECT)
            .use(task)
            .toGet(
                ScopedArtifact.CLASSES,
                KartographDeadTask::projectJars,
                KartographDeadTask::projectDirectories,
            )
        variant.artifacts.forScope(ScopedArtifacts.Scope.ALL)
            .use(task)
            .toGet(ScopedArtifact.CLASSES, KartographDeadTask::classpathJars, KartographDeadTask::classpathDirectories)
    }

    /** 그래프 문서는 dependency가 아니라 이 project가 컴파일한 선언만 담으므로 PROJECT scope만 받는다. */
    private fun registerGraphTask(project: Project, extension: KartographExtension, variant: Variant) {
        val taskName = "kartographGraph${variant.name.replaceFirstChar(Char::titlecase)}"
        val task = project.tasks.register(taskName, KartographGraphTask::class.java) { graphTask ->
            graphTask.group = "reporting"
            graphTask.description = "Writes the ${variant.name} dependency graph as a code-graph JSON document."
            graphTask.variantName.set(variant.name)
            graphTask.includeSourcePaths.set(extension.includeSourcePaths)
            graphTask.projectDirectory.set(project.layout.projectDirectory)
            graphTask.graphFile.set(
                project.layout.buildDirectory.file("reports/kartograph/${variant.name}-graph.json"),
            )
            // 경로 해석은 선언되지 않은 project source를 읽으므로 그때만 stale 문서를 재사용하지 않는다.
            graphTask.outputs.upToDateWhen { !graphTask.includeSourcePaths.get() }
        }
        variant.artifacts.forScope(ScopedArtifacts.Scope.PROJECT)
            .use(task)
            .toGet(
                ScopedArtifact.CLASSES,
                KartographGraphTask::projectJars,
                KartographGraphTask::projectDirectories,
            )
    }
}
