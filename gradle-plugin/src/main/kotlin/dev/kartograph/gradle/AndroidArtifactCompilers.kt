package dev.kartograph.gradle

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.compile.JavaCompile

/** 선택한 공개 artifact의 task 의존 관계에서 추가 compiler 기여자를 찾는다. */
internal object AndroidArtifactCompilers {
    fun attach(project: Project, snapshot: KartographAndroidSnapshotTask, scope: String,
        buildInputs: FileCollection, optionalOutputs: FileCollection) {
        val artifacts = project.files(snapshot.mainJars, snapshot.mainDirectories, snapshot.testJars, snapshot.testDirectories)
        val compilers = contributors(project, artifacts)
        // 의존 관계를 읽으면 기본 compiler 구성 callback이 먼저 실행된다.
        val known = snapshot.compilations.get().map { it.identity.get() }.toSet()
        compilers.filter { it.path !in known }.forEach { compiler ->
            val priorScope = compiler.inputs.properties["kartographScope"]
            require(priorScope == null || priorScope == scope) { "additional compiler belongs to more than one snapshot scope" }
            val arguments = AndroidCompilerInputs.argumentProviderInputs(project, compiler)
            val additional = project.files(AndroidCompilerInputs.systemImages(project, compiler), arguments.files)
            val witness = CompilerWitnesses.automaticJavaCompile(project, compiler, scope,
                project.files(compiler.source), buildInputs, optionalOutputs, additional, arguments.rejection)
            val input = project.objects.newInstance(SnapshotCompilation::class.java)
            input.compiler.set("javac")
            input.identity.set(compiler.path)
            input.primarySources.from(compiler.source)
            input.classDirectories.from(compiler.destinationDirectory)
            input.witnessFiles.from(witness)
            snapshot.compilations.add(input)
            snapshot.sourceFiles.from(compiler.source)
            snapshot.buildWitnessFiles.from(witness)
            snapshot.compilerInputFiles.from(AndroidCompilerInputs.snapshotInputs(project, compiler, additional))
        }
    }

    /** task 이름이나 생성 경로만으로 허용하지 않고 선택 artifact의 의존 관계만 따른다. */
    internal fun contributors(project: Project, artifacts: FileCollection): List<JavaCompile> {
        val pending = ArrayDeque<Task>()
        pending.addAll(artifacts.buildDependencies.getDependencies(null))
        val seen = linkedSetOf<Task>()
        while (pending.isNotEmpty()) {
            val task = pending.removeFirst()
            if (!seen.add(task)) continue
            require(seen.size <= 10_000) { "selected artifact producer graph exceeds capture budget" }
            if (task.project != project) continue
            pending.addAll(task.taskDependencies.getDependencies(task))
        }
        return seen.filterIsInstance<JavaCompile>().filter { it.project == project }.sortedBy { it.path }
    }
}
