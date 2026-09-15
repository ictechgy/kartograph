package dev.kartograph.gradle

import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.ScopedArtifacts
import com.android.build.api.variant.UnitTest
import com.android.build.api.variant.Variant
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.toolchain.JavaLauncher

/** 선택한 Android main/unit-test artifacts와 compiler provider를 같은 snapshot scope로 연결한다. */
internal object AndroidSnapshotTasks {
    fun register(project: Project, extension: KartographExtension, variant: Variant) {
        val unitTest = variant.nestedComponents.filterIsInstance<UnitTest>().singleOrNull()
        val components = listOfNotNull(variant, unitTest)
        val scope = "${project.path}:${variant.name}"
        val roots = components.associate { component -> component.name to project.files(component.sources.java?.all, component.sources.kotlin?.all) }
        val configuration = SnapshotBuildInputs.collect(project, extension.snapshotBuildInputs)
        val buildInputs = configuration.files
        val resDirs = project.files()
        val task = project.tasks.register("kartographSnapshot${variant.name.replaceFirstChar(Char::titlecase)}",
            KartographAndroidSnapshotTask::class.java) { snapshot ->
            snapshot.group = "verification"
            snapshot.description = "Captures compiled Android ${variant.name} main and unit-test inputs for impact queries."
            snapshot.scope.set(scope)
            snapshot.revision.set(extension.snapshotRevision)
            snapshot.projectDirectory.set(project.layout.projectDirectory)
            snapshot.buildDirectory.set(project.layout.buildDirectory)
            snapshot.includeSourcePaths.set(extension.includeSourcePaths)
            snapshot.includePrivateMembers.set(extension.includePrivateMembers)
            snapshot.snapshotMaxMiB.set(extension.snapshotMaxMiB)
            snapshot.indexCacheEnabled.set(extension.snapshotIndexCacheEnabled)
            snapshot.indexCacheDirectory.set(extension.snapshotIndexCacheDirectory)
            snapshot.baselineFile.set(extension.baseline)
            snapshot.keepRuleFiles.from(extension.keepRules)
            snapshot.generatedKeepRuleFiles.from(variant.proguardFiles)
            snapshot.generatedClassRoots.from(extension.generatedClassRoots)
            snapshot.namespace.set(variant.namespace)
            snapshot.manifestFile.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            snapshot.buildInputFiles.from(buildInputs)
            snapshot.buildFileWatches.from(configuration.watchedFiles)
            snapshot.buildDirectoryWatches.from(configuration.watchedDirectories)
            snapshot.buildLogicWatches.from(configuration.buildLogicRoots)
            snapshot.testJars.convention(emptyList())
            snapshot.testDirectories.convention(emptyList())
            snapshot.testClasspathJars.convention(emptyList())
            snapshot.testClasspathDirectories.convention(emptyList())
            snapshot.compilations.convention(emptyList())
            snapshot.classRoots.from(snapshot.testJars, snapshot.testDirectories, snapshot.mainJars, snapshot.mainDirectories)
            snapshot.dependencyClasspath.from(snapshot.testClasspathJars, snapshot.testClasspathDirectories,
                snapshot.mainClasspathJars, snapshot.mainClasspathDirectories,
                project.extensions.getByType(AndroidComponentsExtension::class.java).sdkComponents.bootClasspath)
        components.forEach { component ->
            snapshot.sourceDirectories.from(roots.getValue(component.name))
            component.sources.res?.all?.let { layers ->
                resDirs.from(layers.map { it.flatten() })
                snapshot.androidResourceDirectories.from(layers.map { it.flatten() })
            }
            component.sources.resources?.all?.let { resources ->
                snapshot.serviceResourceRoots.from(resources)
                snapshot.resourceDirectories.from(resources)
            }
        }
            snapshot.snapshotFile.set(project.layout.buildDirectory.file("reports/kartograph/${variant.name}-snapshot.json"))
            snapshot.localBindingsFile.set(project.layout.buildDirectory.file("kartograph/${variant.name}-input-bindings.json"))
            snapshot.outputs.upToDateWhen { false }
        }
        variant.artifacts.forScope(ScopedArtifacts.Scope.PROJECT).use(task)
            .toGet(ScopedArtifact.CLASSES, KartographAndroidSnapshotTask::mainJars, KartographAndroidSnapshotTask::mainDirectories)
        variant.artifacts.forScope(ScopedArtifacts.Scope.ALL).use(task)
            .toGet(ScopedArtifact.CLASSES, KartographAndroidSnapshotTask::mainClasspathJars, KartographAndroidSnapshotTask::mainClasspathDirectories)
        unitTest?.artifacts?.forScope(ScopedArtifacts.Scope.PROJECT)?.use(task)
            ?.toGet(ScopedArtifact.CLASSES, KartographAndroidSnapshotTask::testJars, KartographAndroidSnapshotTask::testDirectories)
        unitTest?.artifacts?.forScope(ScopedArtifacts.Scope.ALL)?.use(task)
            ?.toGet(ScopedArtifact.CLASSES, KartographAndroidSnapshotTask::testClasspathJars, KartographAndroidSnapshotTask::testClasspathDirectories)

        val optionalOutputs = project.files()
        // AGP application의 processResources 산출 R.jar를 resource producer witness로 덮는다(후보 A).
        if (variant is com.android.build.api.variant.ApplicationVariant) {
            val processTaskName = "process${variant.name.replaceFirstChar(Char::titlecase)}Resources"
            val rJar = project.provider {
                val processTask = project.tasks.getByName(processTaskName)
                requireNotNull(processTask.outputs.files.firstOrNull { file -> file.name == "R.jar" }) {
                    "process resources task does not declare the R.jar output"
                }
            }
            val resourceWitness = ResourceProcessWitnesses.automaticProcessResources(
                project, processTaskName, scope, variant.namespace, resDirs,
                variant.artifacts.get(SingleArtifact.MERGED_MANIFEST), buildInputs,
                project.files(project.extensions.getByType(AndroidComponentsExtension::class.java)
                    .sdkComponents.bootClasspath),
                rJar,
            )
            task.configure { snapshot -> snapshot.buildWitnessFiles.from(resourceWitness) }
        }
        val javaInputs = components.associate { component ->
            val input = project.objects.newInstance(SnapshotCompilation::class.java)
            input.compiler.set("javac")
            val compilerInputs = project.files()
            task.configure { snapshot ->
                snapshot.compilations.add(input)
                snapshot.sourceFiles.from(input.primarySources)
                snapshot.buildWitnessFiles.from(input.witnessFiles)
                snapshot.compilerInputFiles.from(compilerInputs)
            }
            component.name to (input to compilerInputs)
        }
        // AGP callback은 onVariants 중 등록해야 compiler task 생성에 반영된다.
        AndroidCompilerInputs.javaCompilers(project, components) { component, compiler ->
            val additionalInputs = project.files(AndroidCompilerInputs.systemImages(project, compiler),
                AndroidCompilerInputs.metadataInputs(project, compiler))
            val witness = CompilerWitnesses.automaticJavaCompile(project, compiler, scope,
                roots.getValue(component.name).filter { it.isDirectory }, buildInputs, optionalOutputs, additionalInputs)
            val (input, compilerInputs) = javaInputs.getValue(component.name)
            input.identity.set(compiler.path)
            input.primarySources.from(compiler.source)
            input.classDirectories.from(compiler.destinationDirectory)
            input.witnessFiles.from(witness)
            compilerInputs.from(additionalInputs, compiler.classpath, compiler.javaCompiler.map { it.metadata.installationPath.file("lib/modules") })
        }
        project.afterEvaluate {
            val kotlin = KotlinJvmInputs.androidCompilations(project, roots)
            if (kotlin.isNotEmpty()) {
                val runtime = KotlinJvmInputs.compilerRuntime(project)
                val jdk = extension.snapshotKotlinToolchain.orElse(project.providers.provider<JavaLauncher> {
                    throw IllegalArgumentException("Kotlin snapshots require snapshotKotlinToolchain bound to the intended Gradle toolchain")
                })
                kotlin.values.forEach { compilation ->
                    val compiler = compilation.compiler
                    val witness = KotlinCompilerWitnesses.automaticCompile(project, compiler, scope,
                        compilation.roots.filter { it.isDirectory }, buildInputs, runtime, optionalOutputs, jdk)
                    attach(project, task, compiler, witness, "kotlin", compiler.map {
                        KotlinCompilerWitnesses.sourceFiles(it).filter { file -> file.extension == "kt" }
                    }, compiler.map { KotlinCompilerWitnesses.destination(it) })
                    task.configure { snapshot ->
                        snapshot.sourceDirectories.from(compilation.roots)
                        snapshot.sourceFiles.from(compiler.map { KotlinCompilerWitnesses.sourceFiles(it) })
                        snapshot.compilerInputFiles.from(runtime, compiler.map { KotlinCompilerWitnesses.byteInputs(it) },
                            jdk.map { it.metadata.installationPath.file("lib/modules") })
                    }
                }
            }
        }
    }

    private fun attach(project: Project, snapshot: TaskProvider<KartographAndroidSnapshotTask>, compiler: TaskProvider<out Task>,
        witness: Provider<RegularFile>, kind: String, sources: Provider<out FileCollection>, destination: Provider<*>) {
        snapshot.configure { task ->
            val input = project.objects.newInstance(SnapshotCompilation::class.java)
            input.identity.set(compiler.map { it.path })
            input.compiler.set(kind)
            input.primarySources.from(sources)
            input.classDirectories.from(destination)
            input.witnessFiles.from(witness)
            task.compilations.add(input)
            task.buildWitnessFiles.from(witness)
            task.sourceFiles.from(sources)
        }
    }
}
