package dev.kartograph.gradle

import dev.kartograph.export.QuerySnapshotCodec
import org.gradle.api.Plugin
import org.gradle.api.Project

/** Android Variant API의 lazy artifact provider를 kartograph task 입력에 연결한다. */
public class KartographPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("kartograph", KartographExtension::class.java)
        extension.strict.convention(false)
        extension.dependencyIncludeTests.convention(false)
        extension.includePrivateMembers.convention(false)
        extension.reportFormat.convention("gradle")
        extension.includeSourcePaths.convention(false)
        extension.snapshotsEnabled.convention(false)
        extension.snapshotMaxMiB.convention(QuerySnapshotCodec.DEFAULT_MAX_MIB)
        extension.snapshotIndexCacheEnabled.convention(booleanProperty(project, "kartograph.indexCache").orElse(false))
        extension.snapshotIncludeUnitTests.convention(booleanProperty(project, "kartograph.snapshotIncludeUnitTests").orElse(true))
        extension.snapshotIndexCacheDirectory.convention(project.layout.buildDirectory.dir("kartograph/index-cache"))
        extension.snapshotRevision.convention(project.providers.gradleProperty("kartograph.revision"))
        project.pluginManager.withPlugin("com.android.application") { AndroidTasks.configureAndroid(project, extension) }
        project.pluginManager.withPlugin("com.android.library") { AndroidTasks.configureAndroid(project, extension) }
        project.afterEvaluate {
            if (project.pluginManager.hasPlugin("java")) {
                DependencyTasks.registerJvm(project, extension)
                if (extension.snapshotsEnabled.get()) JvmSnapshotTasks.register(project, extension)
            }
        }
    }

    /** `true`/`false`만 받는 Gradle property다. 오타를 기본값으로 조용히 바꾸지 않고 설정 오류로 알린다. */
    private fun booleanProperty(project: Project, name: String) = project.providers.gradleProperty(name).map { value ->
        when (value) {
            "true" -> true
            "false" -> false
            else -> throw IllegalArgumentException("$name must be true or false")
        }
    }
}
