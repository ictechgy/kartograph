package dev.kartograph.gradle

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.toolchain.JavaLauncher
import java.io.File

/** 공개 Kotlin JVM compiler task API를 통해 명시적으로 선택한 Kotlin/Android compilation을 기록한다. */
public object KotlinCompilerWitnesses {
    /** Java 소스도 Kotlin compiler 입력이므로 mixed source root를 함께 지정해야 한다. */
    @JvmOverloads
    public fun kotlinCompile(project: Project, compiler: TaskProvider<out Task>, scope: String,
        sourceRoots: FileCollection, buildInputs: FileCollection, jdk: Provider<JavaLauncher>,
        additionalInputs: FileCollection = project.files(), compilerEvidence: Boolean = false): Provider<RegularFile> {
        compiler.configure { KotlinApi(it).useToolchain(jdk) }
        return CompilerWitnesses.register(project, compiler, scope, sourceRoots, buildInputs, "kotlin", additionalInputs, jdk, compilerEvidence)
    }

    internal fun destination(task: Task): File = KotlinApi(task).destination()

    internal fun fullCompilation(task: Task) = KotlinApi(task).fullCompilation()

    internal fun byteInputs(task: Task): List<Any> = KotlinApi(task).let { api -> listOf(
        api.collection("org.jetbrains.kotlin.gradle.tasks.KotlinCompileTool", "getSources"),
        api.collection("org.jetbrains.kotlin.gradle.tasks.KotlinCompile", "getJavaSources"),
        api.collection("org.jetbrains.kotlin.gradle.tasks.KotlinCompileTool", "getLibraries"),
        api.collection("org.jetbrains.kotlin.gradle.tasks.BaseKotlinCompile", "getPluginClasspath"),
        api.collection("org.jetbrains.kotlin.gradle.tasks.BaseKotlinCompile", "getFriendPaths"),
        api.implementationArtifact(),
    ) }

    internal fun observe(task: Task, jdk: JavaLauncher, additionalInputs: FileCollection): CompilerObservation {
        val api = KotlinApi(task)
        val sources = api.files("org.jetbrains.kotlin.gradle.tasks.KotlinCompileTool", "getSources")
        val javaSources = api.files("org.jetbrains.kotlin.gradle.tasks.KotlinCompile", "getJavaSources")
        val libraries = api.files("org.jetbrains.kotlin.gradle.tasks.KotlinCompileTool", "getLibraries")
        val plugins = api.files("org.jetbrains.kotlin.gradle.tasks.BaseKotlinCompile", "getPluginClasspath")
        val friends = api.files("org.jetbrains.kotlin.gradle.tasks.BaseKotlinCompile", "getFriendPaths")
        val implementation = api.implementationArtifact()
        val known = (libraries + plugins + friends + implementation).map { it.canonicalFile }.toSet()
        // 실행 시 선언된 JAR와 명시 입력의 대응을 검증한다. task 입력 union을 provider에 저장하지 않는다.
        val compilerArtifacts = task.inputs.files.files.filter { it.isFile && it.extension == "jar" && it.canonicalFile !in known }
            .distinctBy { it.canonicalFile }
        val compilerPaths = compilerArtifacts.map { it.canonicalFile }.toSet()
        val additional = additionalInputs.files
        val supplied = additional.map { it.canonicalFile }.toSet()
        require(compilerArtifacts.isNotEmpty() && compilerArtifacts.all { it.canonicalFile in supplied }) {
            "Kotlin compiler witness requires the selected compiler runtime artifacts in additionalInputs"
        }
        require(!api.multiplatform() && api.freeArguments().none {
            it.startsWith('@') || it.startsWith("-jdk-home") || it.startsWith("-classpath") ||
                it in setOf("-d", "-version", "-help", "-X", "-script") ||
                listOf("-Xplugin", "-Xfriend-paths", "-Xjava-source-roots", "-Xcommon-sources", "-Xfragment").any(it::startsWith)
        }) {
            "Kotlin compiler witness requires declared toolchain and file input APIs"
        }
        val jdkHome = jdk.metadata.installationPath.asFile
        val files = libraries.map { "classpath" to it } + plugins.map { "processor" to it } +
            friends.map { "friend" to it } + compilerArtifacts.map { "compiler" to it } +
            additional.filter { it.canonicalFile !in known && it.canonicalFile !in compilerPaths }.map { "compilerInput" to it } +
            ("compiler" to implementation) +
            ("compiler" to File(jdkHome, "lib/modules").canonicalFile)
        return CompilerObservation(sources + javaSources, api.destination(), files.distinct(), api.effectiveArguments())
    }
}

/** KGP의 별도 classloader에서 공개 API만 조회한다. 내부 구현 클래스나 접근 제한을 우회하지 않는다. */
private class KotlinApi(private val task: Task) {
    private val loader = task.javaClass.classLoader

    init {
        require(contract("org.jetbrains.kotlin.gradle.tasks.KotlinCompile").isInstance(task)) {
            "compiler witness requires a supported Kotlin JVM compiler task"
        }
    }

    fun collection(api: String, getter: String): FileCollection = get(task, api, getter) as FileCollection

    fun files(api: String, getter: String): Set<File> = collection(api, getter).files

    fun destination(): File = (get(task, "org.jetbrains.kotlin.gradle.tasks.KotlinCompileTool", "getDestinationDirectory")
        as org.gradle.api.file.DirectoryProperty).get().asFile

    fun multiplatform(): Boolean = (get(task, "org.jetbrains.kotlin.gradle.tasks.BaseKotlinCompile", "getMultiPlatformEnabled")
        as org.gradle.api.provider.Property<*>).get() as Boolean

    fun freeArguments(): List<String> {
        val options = get(task, "org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompile", "getCompilerOptions")
        val values = get(options, "org.jetbrains.kotlin.gradle.dsl.KotlinCommonCompilerOptions", "getFreeCompilerArgs")
            as org.gradle.api.provider.ListProperty<*>
        return values.get().map { it as String }
    }

    fun implementationArtifact(): File = File(contract("org.jetbrains.kotlin.gradle.tasks.KotlinCompile")
        .protectionDomain.codeSource.location.toURI()).canonicalFile

    fun fullCompilation() {
        try {
            contract("org.jetbrains.kotlin.gradle.tasks.AbstractKotlinCompile").getMethod("setIncremental", Boolean::class.javaPrimitiveType)
                .invoke(task, false)
        } catch (error: ReflectiveOperationException) {
            throw IllegalArgumentException("supported Kotlin full compilation API is unavailable", error)
        }
    }

    // Gradle 내부 snapshot 객체를 직렬화하지 않고 KGP가 실제 구성한 compiler 인자를 기록한다.
    fun effectiveArguments(): List<String> = try {
        val contextType = contract("org.jetbrains.kotlin.gradle.plugin.KotlinCompilerArgumentsProducer${'$'}CreateCompilerArgumentsContext")
        val companion = contextType.getField("Companion").get(null)
        val context = contract("org.jetbrains.kotlin.gradle.plugin.KotlinCompilerArgumentsProducer${'$'}CreateCompilerArgumentsContext${'$'}Companion")
            .getMethod("getDefault").invoke(companion)
        val arguments = contract("org.jetbrains.kotlin.gradle.plugin.KotlinCompilerArgumentsProducer")
            .getMethod("createCompilerArguments", contextType).invoke(task, context)
        val result = contract("org.jetbrains.kotlin.compilerRunner.ArgumentUtils")
            .getMethod("convertArgumentsToStringList", contract("org.jetbrains.kotlin.cli.common.arguments.CommonToolArguments"))
            .invoke(null, arguments) as List<*>
        result.map { it as String }
    } catch (error: ReflectiveOperationException) {
        throw IllegalArgumentException("supported Kotlin compiler arguments API is unavailable", error)
    }

    fun useToolchain(jdk: Provider<JavaLauncher>) {
        val toolchain = get(task, "org.jetbrains.kotlin.gradle.tasks.UsesKotlinJavaToolchain", "getKotlinJavaToolchain")
        val setter = get(toolchain, "org.jetbrains.kotlin.gradle.tasks.KotlinJavaToolchain", "getToolchain")
        try {
            contract("org.jetbrains.kotlin.gradle.tasks.KotlinJavaToolchain${'$'}JavaToolchainSetter")
                .getMethod("use", Provider::class.java).invoke(setter, jdk)
        } catch (error: ReflectiveOperationException) {
            throw IllegalArgumentException("supported Kotlin compiler toolchain API is unavailable", error)
        }
    }

    private fun get(receiver: Any, api: String, getter: String): Any = try {
        requireNotNull(contract(api).getMethod(getter).invoke(receiver)) { "Kotlin compiler API returned no value" }
    } catch (error: ReflectiveOperationException) {
        throw IllegalArgumentException("supported Kotlin compiler input API is unavailable", error)
    }

    private fun contract(name: String): Class<*> = try {
        Class.forName(name, false, loader)
    } catch (error: ClassNotFoundException) {
        throw IllegalArgumentException("supported Kotlin compiler API is unavailable", error)
    }
}
