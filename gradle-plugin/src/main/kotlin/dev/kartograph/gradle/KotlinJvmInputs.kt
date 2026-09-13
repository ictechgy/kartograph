package dev.kartograph.gradle

import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.FileCollection
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.TaskProvider

/** KGP 공개 compilation provider에서 실제 task와 source set을 가져온다. task/output 이름을 추측하지 않는다. */
internal object KotlinJvmInputs {
    data class Compilation(val compiler: TaskProvider<out Task>, val roots: FileCollection)

    fun compilations(project: Project, sourceSets: List<SourceSet>): Map<String, Compilation> {
        return compilations(project, sourceSets.associate { it.name to it.java.sourceDirectories },
            "org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension")
    }

    fun androidCompilations(project: Project, roots: Map<String, FileCollection>): Map<String, Compilation> =
        compilations(project, roots, "org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension")

    private fun compilations(project: Project, sourceRoots: Map<String, FileCollection>, extensionApi: String): Map<String, Compilation> {
        val extension = project.extensions.findByName("kotlin") ?: return emptyMap()
        val loader = extension.javaClass.classLoader
        fun get(receiver: Any, contract: String, method: String): Any = try {
            requireNotNull(Class.forName(contract, false, loader).getMethod(method).invoke(receiver))
        } catch (error: ReflectiveOperationException) {
            throw IllegalArgumentException("supported Kotlin JVM compilation API is unavailable", error)
        }
        val target = get(extension, extensionApi, "getTarget")
        val compilations = get(target, "org.jetbrains.kotlin.gradle.plugin.KotlinTarget", "getCompilations")
            as NamedDomainObjectContainer<*>
        return sourceRoots.mapValues { (name, additionalRoots) ->
            val compilation = compilations.getByName(name)
            @Suppress("UNCHECKED_CAST")
            val compiler = get(compilation, "org.jetbrains.kotlin.gradle.plugin.KotlinCompilation", "getCompileTaskProvider")
                as TaskProvider<out Task>
            val kotlinSets = get(compilation, "org.jetbrains.kotlin.gradle.plugin.KotlinCompilation", "getAllKotlinSourceSets")
                as Set<*>
            val roots = kotlinSets.map { kotlinSet ->
                (get(requireNotNull(kotlinSet), "org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet", "getKotlin")
                    as SourceDirectorySet).sourceDirectories
            }
            Compilation(compiler, project.files(roots, additionalRoots))
        }
    }

    /** KGP가 선언한 compiler runtime configuration을 연결하고 실제 task 입력과의 대응은 witness에서 검증한다. */
    fun compilerRuntime(project: Project): FileCollection {
        val configurations = listOf("kotlinBuildToolsApiClasspath", "kotlinCompilerClasspath")
            .mapNotNull(project.configurations::findByName).filter { it.isCanBeResolved }
        require(configurations.isNotEmpty()) { "supported Kotlin compiler runtime configuration is unavailable" }
        return project.files(configurations)
    }
}
