package dev.kartograph.gradle

import dev.kartograph.core.DependencyScope
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.FileCollectionDependency
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider

/** 의존성 해석은 provider에서만 수행하고 class 산출물은 public source set/variant API로 연결한다. */
internal object DependencyTasks {
    fun registerJvm(project: Project, extension: KartographExtension) {
        val sets = project.extensions.getByType(SourceSetContainer::class.java)
        val main = sets.getByName(SourceSet.MAIN_SOURCE_SET_NAME)
        val test = sets.getByName(SourceSet.TEST_SOURCE_SET_NAME)
        val task = task(project, extension, "kartographDependencies", "jvm", project.pluginManager.hasPlugin("java-library"))
        task.configure { it.classRoots.from(main.output.classesDirs) }
        val requests = requests(project, listOf(""))
        wire(project.configurations.getByName(main.compileClasspathConfigurationName), task, requests, false)
        if (extension.dependencyIncludeTests.get()) {
            task.configure { it.testClassRoots.from(test.output.classesDirs) }
            val testRequests = requests(project, listOf(""), listOf("test"))
            wire(project.configurations.getByName(test.compileClasspathConfigurationName), task, testRequests, true)
        }
    }


    fun task(project: Project, extension: KartographExtension, name: String, variant: String, library: Boolean): TaskProvider<KartographDependenciesTask> =
        project.tasks.register(name, KartographDependenciesTask::class.java) { task ->
            task.group = "verification"
            task.description = "Reviews dependency declarations and compiled usage for $variant."
            task.projectDirectory.set(project.layout.projectDirectory)
            task.strict.set(extension.strict)
            task.library.set(library)
            task.reportFormat.set(extension.reportFormat)
            task.reportFile.set(project.layout.buildDirectory.file("reports/kartograph/$variant-dependencies.txt"))
        }

    fun wire(configuration: Configuration, task: TaskProvider<KartographDependenciesTask>, requests: ListProperty<String>, test: Boolean) {
        val rootComponent = configuration.incoming.resolutionResult.rootComponent
        val ownComponent = rootComponent.map { it.id }
        // Android unit-test classpath의 tested variant는 의존성이 아니라 별도 main class 입력이다.
        // public component filter로 제외해 classes/lint secondary artifact를 임의 선택하지 않는다.
        val artifacts = configuration.incoming.artifactView { view ->
            view.componentFilter { id -> id != ownComponent.get() }
        }.artifacts
        val result = rootComponent.zip(artifacts.resolvedArtifacts) { root, files -> root to files }
            .zip(requests) { (root, files), declarations -> DependencyResolution.capture(root, files, declarations, test) }
        task.configure {
            it.dependencyArtifacts.from(artifacts.artifactFiles)
            it.declarations.addAll(result.map(DependencyResolution::declared))
            it.resolved.addAll(result.map(DependencyResolution::resolved))
            it.inputLimitations.addAll(result.map(DependencyResolution::limitations))
        }
    }

    fun requests(project: Project, prefixes: List<String>, testPrefixes: List<String> = emptyList()): ListProperty<String> {
        val result = project.objects.listProperty(String::class.java).convention(emptyList())
        for (scope in DependencyScope.entries) {
            val names = when {
                scope == DependencyScope.KAPT || scope == DependencyScope.KSP ->
                    (prefixes + testPrefixes).map { scope.option + it.replaceFirstChar(Char::titlecase) }.toSet()
                scope.option.startsWith("test") -> testPrefixes.map { it + scope.option.removePrefix("test") }.toSet()
                else -> prefixes.map { prefix -> if (prefix.isEmpty()) scope.option else prefix + scope.option.replaceFirstChar(Char::titlecase) }.toSet()
            }
            project.configurations.matching { it.name in names }.all { configuration ->
                // DependencySet.all은 predicate가 아니라 현재·이후 추가되는 선언을 관찰하는 Action이다.
                configuration.allDependencies.all { dependency ->
                    when (dependency) {
                        is ProjectDependency -> result.add("${scope.option}\tproject:${projectPath(dependency)}")
                        is FileCollectionDependency -> result.addAll(dependency.files.elements.map { files ->
                            files.map { "${scope.option}\tfile:${it.asFile.absolutePath}" }
                        })
                        is ModuleDependency -> result.add("${scope.option}\tmodule:${dependency.group}:${dependency.name}")
                    }
                }
            }
        }
        return result
    }

    private fun projectPath(dependency: ProjectDependency): String {
        // getPath는 8.11부터다. 최소 Gradle 8.10.2에서는 당시 public getDependencyProject를 사용한다.
        val api = ProjectDependency::class.java
        val modern = api.methods.firstOrNull { it.name == "getPath" && it.parameterCount == 0 }
        if (modern != null) return modern.invoke(dependency) as String
        val legacy = api.getMethod("getDependencyProject").invoke(dependency) as Project
        return legacy.path
    }
}
