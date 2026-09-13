package dev.kartograph.gradle

import org.gradle.api.Project
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.JavaCompile

/** JVM source set의 provider와 실제 compiler destination을 snapshot 입력으로 연결한다. */
internal object JvmSnapshotTasks {
    fun register(project: Project, extension: KartographExtension) {
        val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME)
        val tests = sourceSets.getByName(SourceSet.TEST_SOURCE_SET_NAME)
        val kotlin = KotlinJvmInputs.compilations(project, listOf(main, tests))
        val scope = "${project.path}:jvm"
        // NO-SOURCE 출력은 비어 있을 수 있다. producer별 필수 출력 검사는 snapshot에서 별도로 수행한다.
        // 경로 정책에는 output collection의 compiler 의존성을 되먹이지 않는다.
        val declaredClassOutputs = project.files(main.output.classesDirs, tests.output.classesDirs)
        val optionalOutputs = project.files(listOfNotNull(main.output.resourcesDir, tests.output.resourcesDir),
            project.providers.provider { declaredClassOutputs.files })
        val buildInputs = project.files(project.buildFile, project.rootProject.file("settings.gradle"),
            project.rootProject.file("settings.gradle.kts"), project.rootProject.file("gradle.properties"))
            .filter { it.isFile }
        val compilers = listOf(main, tests).map { sourceSet ->
            val compiler = project.tasks.named(sourceSet.compileJavaTaskName, JavaCompile::class.java)
            val witness = CompilerWitnesses.automaticJavaCompile(project, compiler, scope,
                (kotlin[sourceSet.name]?.roots ?: sourceSet.java.sourceDirectories).filter { it.isDirectory }, buildInputs, optionalOutputs)
            compiler to witness
        }
        val snapshot = project.tasks.register("kartographSnapshot", KartographSnapshotTask::class.java) { task ->
            task.group = "verification"
            task.description = "Captures compiled JVM main and test inputs for change-impact queries."
            task.scope.set(scope)
            task.revision.set(extension.snapshotRevision)
            task.projectDirectory.set(project.layout.projectDirectory)
            task.includeSourcePaths.set(extension.includeSourcePaths)
            task.includePrivateMembers.set(extension.includePrivateMembers)
            task.keepRuleFiles.from(extension.keepRules)
            task.generatedClassRoots.from(extension.generatedClassRoots)
            // test runtime의 test-before-main 순서를 그래프의 첫 root 우선 정책에도 유지한다.
            task.classRoots.from(tests.output.classesDirs, main.output.classesDirs)
            task.dependencyClasspath.from(tests.runtimeClasspath)
            task.serviceResourceRoots.from(tests.output.minus(tests.output.classesDirs), main.output.minus(main.output.classesDirs))
            task.sourceDirectories.from(main.java.sourceDirectories, tests.java.sourceDirectories)
            task.resourceDirectories.from(main.resources.sourceDirectories, tests.resources.sourceDirectories)
            task.buildInputFiles.from(buildInputs)
            compilers.forEach { (compiler, witness) ->
                val compilation = project.objects.newInstance(SnapshotCompilation::class.java)
                compilation.identity.set(compiler.map { it.path })
                compilation.compiler.set("javac")
                compilation.primarySources.from(compiler.map { it.source })
                compilation.classDirectories.from(compiler.flatMap { it.destinationDirectory })
                compilation.witnessFiles.from(witness)
                task.compilations.add(compilation)
                task.sourceFiles.from(compiler.map { it.source })
                task.buildWitnessFiles.from(witness)
                task.compilerInputFiles.from(compiler.map { it.classpath }, compiler.flatMap { it.javaCompiler }.map {
                    it.metadata.installationPath.file("lib/modules")
                })
            }
            task.snapshotFile.set(project.layout.buildDirectory.file("reports/kartograph/jvm-snapshot.json"))
            task.localBindingsFile.set(project.layout.buildDirectory.file("kartograph/jvm-input-bindings.json"))
            // 재귀 keep 입력과 시각 관측의 캐시 계약을 검증하기 전에는 snapshot을 다시 캡처한다.
            task.outputs.upToDateWhen { false }
        }
        if (kotlin.isNotEmpty()) {
            val runtime = KotlinJvmInputs.compilerRuntime(project)
            val jdk = extension.snapshotKotlinToolchain.orElse(project.providers.provider<org.gradle.jvm.toolchain.JavaLauncher> {
                throw IllegalArgumentException("Kotlin snapshots require snapshotKotlinToolchain bound to the intended Gradle toolchain")
            })
            kotlin.values.forEach { compilation ->
                val compiler = compilation.compiler
                val witness = KotlinCompilerWitnesses.automaticCompile(project, compiler, scope,
                    compilation.roots.filter { it.isDirectory }, buildInputs, runtime, optionalOutputs, jdk)
                snapshot.configure { task ->
                    val input = project.objects.newInstance(SnapshotCompilation::class.java)
                    input.identity.set(compiler.map { it.path })
                    input.compiler.set("kotlin")
                    input.primarySources.from(compiler.map { KotlinCompilerWitnesses.sourceFiles(it).filter { file -> file.extension == "kt" } })
                    input.classDirectories.from(compiler.map { KotlinCompilerWitnesses.destination(it) })
                    input.witnessFiles.from(witness)
                    task.compilations.add(input)
                    task.buildWitnessFiles.from(witness)
                    task.sourceDirectories.from(compilation.roots)
                    task.sourceFiles.from(compiler.map { KotlinCompilerWitnesses.sourceFiles(it) })
                    task.compilerInputFiles.from(runtime, compiler.map { KotlinCompilerWitnesses.byteInputs(it) },
                        jdk.map { it.metadata.installationPath.file("lib/modules") })
                }
            }
        }
    }
}
