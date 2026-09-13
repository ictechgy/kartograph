package dev.kartograph.gradle

import com.android.build.api.variant.Component
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.compile.JavaCompile
import java.io.File

/** AGP 공개 compiler callback 또는 이전 공개 variant provider로 compiler를 선택한다. */
internal object AndroidCompilerInputs {
    /** javac의 선언된 입력 생산자에서 AGP metadata task를 선택하며 task/output 이름을 만들지 않는다. */
    fun metadataInputs(project: Project, compiler: JavaCompile): FileCollection {
        val api = Class.forName("com.android.build.gradle.tasks.JavaPreCompileTask", false, Component::class.java.classLoader)
        val producers = compiler.inputs.files.buildDependencies.getDependencies(compiler).filter(api::isInstance)
        require(producers.size == 1) { "Android compiler metadata producer is missing or ambiguous" }
        return project.files(producers.single().outputs.files)
    }

    /** AGP의 공개 JdkImageInput provider를 통해 실제 image와 선언된 module 파일을 lazy 연결한다. */
    fun systemImages(project: Project, compiler: JavaCompile): FileCollection {
        try {
            val api = Class.forName("com.android.build.gradle.tasks.JdkImageInput", false, Component::class.java.classLoader)
            val inputs = compiler.options.compilerArgumentProviders.filter(api::isInstance).flatMap { argumentProvider ->
                @Suppress("UNCHECKED_CAST")
                val modules = api.getMethod("getGeneratedModuleFile").invoke(argumentProvider) as Provider<File>
                val runtime = api.getMethod("getJrtFsJar").invoke(argumentProvider)
                val image = modules.map {
                    val arguments = argumentProvider.asArguments().toList()
                    val index = arguments.indexOf("--system")
                    require(index >= 0) { "Android JDK image provider omitted its system argument" }
                    val value = requireNotNull(arguments.getOrNull(index + 1)) { "Android compiler system image argument is missing" }
                    File(value).also { require(it.isDirectory) { "Android compiler system image is unavailable" } }
                }
                listOf(modules, runtime, image)
            }
            return project.files(inputs)
        } catch (error: ReflectiveOperationException) {
            throw IllegalArgumentException("supported Android JDK image input API is unavailable", error)
        }
    }

    fun javaCompilers(project: Project, components: List<Component>, register: (Component, JavaCompile) -> Unit) {
        val callback = Component::class.java.methods.singleOrNull { it.name == "configureJavaCompileTask" }
        if (callback != null) {
            components.forEach { component ->
                val action: (JavaCompile) -> Unit = { compiler ->
                    register(component, compiler)
                }
                callback.invoke(component, action)
            }
            return
        }
        project.afterEvaluate { legacyJavaCompilers(project, components, register) }
    }

    private fun legacyJavaCompilers(project: Project, components: List<Component>, register: (Component, JavaCompile) -> Unit) {
        // AGP 8의 새 Component API에는 compiler provider가 없으므로 문서화된 legacy 공개 API만 연결한다.
        val extension = project.extensions.getByName("android")
        val loader = extension.javaClass.classLoader
        try {
            val application = project.pluginManager.hasPlugin("com.android.application")
            val api = Class.forName(if (application) "com.android.build.gradle.AbstractAppExtension"
                else "com.android.build.gradle.LibraryExtension", false, loader)
            val base = Class.forName("com.android.build.gradle.api.BaseVariant", false, loader)
            val main = api.getMethod(if (application) "getApplicationVariants" else "getLibraryVariants").invoke(extension) as Iterable<*>
            val tests = api.getMethod("getUnitTestVariants").invoke(extension) as Iterable<*>
            val byName = (main + tests).filterNotNull().associateBy { base.getMethod("getName").invoke(it) as String }
            components.forEach { component ->
                val variant = requireNotNull(byName[component.name]) { "Android compiler variant is unavailable: ${component.name}" }
                @Suppress("UNCHECKED_CAST")
                val compiler = base.getMethod("getJavaCompileProvider").invoke(variant) as TaskProvider<JavaCompile>
                compiler.configure { register(component, it) }
            }
        } catch (error: ReflectiveOperationException) {
            throw IllegalArgumentException("supported Android compiler provider API is unavailable", error)
        }
    }
}
