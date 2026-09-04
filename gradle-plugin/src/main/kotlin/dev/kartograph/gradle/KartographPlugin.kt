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
        extension.reportFormat.convention("gradle")
        project.pluginManager.withPlugin("com.android.application") { configureAndroid(project, extension) }
        project.pluginManager.withPlugin("com.android.library") { configureAndroid(project, extension) }
    }

    private fun configureAndroid(project: Project, extension: KartographExtension) {
        val androidComponents = project.extensions.getByType(AndroidComponentsExtension::class.java)
        androidComponents.onVariants { variant -> registerVariantTask(project, extension, variant) }
    }

    private fun registerVariantTask(project: Project, extension: KartographExtension, variant: Variant) {
        val taskName = "kartographDead${variant.name.replaceFirstChar(Char::titlecase)}"
        val task = project.tasks.register(taskName, KartographDeadTask::class.java) { deadTask ->
            deadTask.group = "verification"
            deadTask.description = "Reports declarations unreachable in the ${variant.name} Android variant."
            deadTask.variantName.set(variant.name)
            deadTask.namespace.set(variant.namespace)
            deadTask.strict.set(extension.strict)
            deadTask.reportFormat.set(extension.reportFormat)
            deadTask.baselineFile.set(extension.baseline)
            deadTask.projectDirectory.set(project.layout.projectDirectory)
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
}
