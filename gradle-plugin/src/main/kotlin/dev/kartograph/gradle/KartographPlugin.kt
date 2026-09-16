package dev.kartograph.gradle

import dev.kartograph.export.QuerySnapshotCodec
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
        extension.snapshotsEnabled.convention(false)
        extension.snapshotMaxMiB.convention(QuerySnapshotCodec.DEFAULT_MAX_MIB)
        extension.snapshotIndexCacheEnabled.convention(project.providers.gradleProperty("kartograph.indexCache").map { value ->
            when (value) {
                "true" -> true
                "false" -> false
                else -> throw IllegalArgumentException("kartograph.indexCache must be true or false")
            }
        }.orElse(false))
        extension.snapshotIndexCacheDirectory.convention(project.layout.buildDirectory.dir("kartograph/index-cache"))
        extension.snapshotRevision.convention(project.providers.gradleProperty("kartograph.revision"))
        project.pluginManager.withPlugin("com.android.application") { AndroidTasks.configureAndroid(project, extension) }
        project.pluginManager.withPlugin("com.android.library") { AndroidTasks.configureAndroid(project, extension) }
        project.afterEvaluate {
            if (extension.snapshotsEnabled.get() && project.pluginManager.hasPlugin("java")) {
                JvmSnapshotTasks.register(project, extension)
            }
        }
    }

}
