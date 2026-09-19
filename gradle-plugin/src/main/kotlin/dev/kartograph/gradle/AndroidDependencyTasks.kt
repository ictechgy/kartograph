package dev.kartograph.gradle

import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.ScopedArtifacts
import com.android.build.api.variant.Variant
import com.android.build.api.variant.UnitTest
import org.gradle.api.Project

/** JVM configuration-cache 역직렬화가 AGP 타입을 로드하지 않도록 Android 배선을 분리한다. */
internal object AndroidDependencyTasks {
    fun register(project: Project, extension: KartographExtension, variant: Variant) {
        val task = DependencyTasks.task(project, extension, "kartographDependencies${variant.name.replaceFirstChar(Char::titlecase)}",
            variant.name, project.pluginManager.hasPlugin("com.android.library"))
        variant.artifacts.forScope(ScopedArtifacts.Scope.PROJECT).use(task)
            .toGet(ScopedArtifact.CLASSES, KartographDependenciesTask::projectJars, KartographDependenciesTask::projectDirectories)
        val prefixes = (listOf("", variant.name) + listOfNotNull(variant.buildType, variant.flavorName) + variant.productFlavors.map { it.second }).distinct()
        DependencyTasks.wire(variant.compileConfiguration, task, DependencyTasks.requests(project, prefixes), false)
        if (extension.dependencyIncludeTests.get()) {
            val units = variant.nestedComponents.filterIsInstance<UnitTest>()
            val unit = units.singleOrNull()
            if (unit == null) {
                task.configure { it.inputLimitations.add("${units.size} unit-test components are available for this variant; test inputs were not bound") }
                return
            }
            unit.artifacts.forScope(ScopedArtifacts.Scope.PROJECT).use(task)
                .toGet(ScopedArtifact.CLASSES, KartographDependenciesTask::testProjectJars, KartographDependenciesTask::testProjectDirectories)
            val testPrefixes = (listOf("test", unit.name) + prefixes.filter(String::isNotEmpty).map { "test" + it.replaceFirstChar(Char::titlecase) }).distinct()
            DependencyTasks.wire(unit.compileConfiguration, task, DependencyTasks.requests(project, prefixes, testPrefixes), true)
        }
    }

}
