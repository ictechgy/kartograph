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
    data class ArgumentInputs(val files: FileCollection, val rejection: WitnessRejection? = null)

    /** argument provider가 Gradle에 선언한 파일 입력만 연결한다. processor 출력은 입력으로 재분류하지 않는다. */
    fun argumentProviderInputs(project: Project, compiler: JavaCompile, metadataProviderType: Class<*>? = null): ArgumentInputs = try {
        ArgumentInputs(readArgumentProviderInputs(project, compiler, metadataProviderType))
    } catch (_: ReflectiveOperationException) {
        // 공개 getter라도 구현 클래스에 접근할 수 없으면 compiler 설정은 보존하고 witness만 거부한다.
        ArgumentInputs(project.files(), WitnessRejection.UNSUPPORTED_ARGUMENT_PROVIDER)
    }

    private fun readArgumentProviderInputs(project: Project, compiler: JavaCompile, metadataProviderType: Class<*>?): FileCollection {
        val inputs = mutableListOf<Any?>()
        val outputs = mutableListOf<Any?>()
        for (argument in compiler.options.compilerArgumentProviders) {
            // AGP Room helper의 단일 입력은 기존 공개 JavaPreCompileTask output adapter가 이미 연결한다.
            // 이 예외 뒤에도 WitnessSpec의 실제 task 입력 전체 byte-coverage 검사는 생략하지 않는다.
            if (isCoveredMetadataProvider(argument.javaClass, metadataProviderType)) continue
            // Gradle의 decorated getter는 annotation을 복제하지 않을 수 있으므로 공개 원래 선언도 읽는다.
            val pending = ArrayDeque<Class<*>>()
            val seen = mutableSetOf<Class<*>>()
            pending += argument.javaClass
            while (pending.isNotEmpty()) {
                val type = pending.removeFirst()
                if (!seen.add(type)) continue
                require(seen.size <= 64) { "compiler argument provider hierarchy exceeds capture budget" }
                type.superclass?.let(pending::add)
                pending.addAll(type.interfaces)
                for (method in type.declaredMethods.filter { java.lang.reflect.Modifier.isPublic(it.modifiers) && it.parameterCount == 0 }) {
                    val annotations = method.annotations.map { it.annotationClass.java.name }.toSet()
                    val input = annotations.any(FILE_INPUT_ANNOTATIONS::contains)
                    val output = annotations.any(FILE_OUTPUT_ANNOTATIONS::contains)
                    require(!input || !output) { "compiler argument provider cannot declare the same property as input and output" }
                    val optional = "org.gradle.api.tasks.Optional" in annotations
                    if (input) inputs += fileValue(method.invoke(argument), optional)
                    if (output) outputs += outputValue(project, method.invoke(argument), optional)
                }
            }
        }
        val selected = project.files(inputs)
        val destinations = project.files(outputs, outputValue(project, compiler.destinationDirectory, false),
            outputValue(project, compiler.options.generatedSourceOutputDirectory, true),
            outputValue(project, compiler.options.headerOutputDirectory, true))
        return selected.filter { input ->
            val path = input.canonicalFile.toPath()
            require(destinations.none { output -> val target = output.canonicalFile.toPath()
                path.startsWith(target) || target.startsWith(path)
            }) { "compiler argument provider input overlaps compiler outputs" }
            true
        }
    }

    private fun fileValue(value: Any?, optional: Boolean): Any? =
        if (optional && value is Provider<*>) value.map { listOf(it) }.orElse(emptyList()) else value

    // 출력 내용이나 producer dependency를 입력에 연결하지 않고 위치만 읽어 중첩을 거부한다.
    private fun outputValue(project: Project, value: Any?, optional: Boolean): Any? =
        if (value is Provider<*>) project.providers.provider { listOfNotNull(if (optional) value.orNull else value.get()) } else value

    private val FILE_INPUT_ANNOTATIONS = setOf(
        "org.gradle.api.tasks.InputFile", "org.gradle.api.tasks.InputFiles", "org.gradle.api.tasks.InputDirectory",
        "org.gradle.api.tasks.Classpath", "org.gradle.api.tasks.CompileClasspath",
    )
    private val FILE_OUTPUT_ANNOTATIONS = setOf(
        "org.gradle.api.tasks.OutputFile", "org.gradle.api.tasks.OutputFiles", "org.gradle.api.tasks.OutputDirectory",
        "org.gradle.api.tasks.OutputDirectories",
    )

    /** Component와 같은 AGP classloader의 정확한 타입만 기존 metadata adapter에 위임한다. */
    fun metadataProviderType(): Class<*>? = try {
        Class.forName("com.android.build.gradle.tasks.JavaCompileOptionsForRoom", false, Component::class.java.classLoader)
    } catch (_: ClassNotFoundException) { null }

    private fun isCoveredMetadataProvider(type: Class<*>, expected: Class<*>?): Boolean {
        if (expected == null || type != expected || type.superclass != Any::class.java ||
            type.interfaces.toList() != listOf(org.gradle.process.CommandLineArgumentProvider::class.java)) return false
        val getters = type.declaredMethods.filter { java.lang.reflect.Modifier.isPublic(it.modifiers) && it.parameterCount == 0 }
        val inputs = getters.filter { method -> method.annotations.any { it.annotationClass.java.name in FILE_INPUT_ANNOTATIONS } }
        if (inputs.size != 1 || inputs.single().name != "getAnnotationProcessorListFile" ||
            inputs.single().returnType != Provider::class.java ||
            inputs.single().annotations.none { it.annotationClass.java.name == "org.gradle.api.tasks.InputFile" }) return false
        return getters.none { method -> method.annotations.any { it.annotationClass.java.name in FILE_OUTPUT_ANNOTATIONS } }
    }

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
