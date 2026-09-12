package dev.kartograph.gradle

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
        project.pluginManager.withPlugin("com.android.application") { AndroidTasks.configureAndroid(project, extension) }
        project.pluginManager.withPlugin("com.android.library") { AndroidTasks.configureAndroid(project, extension) }
    }

}
