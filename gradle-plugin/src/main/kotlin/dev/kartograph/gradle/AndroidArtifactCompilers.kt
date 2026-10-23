package dev.kartograph.gradle

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.compile.JavaCompile

/** Additional compiler contributors are discovered through the selected public artifact DAG. */
internal object AndroidArtifactCompilers {
    fun attach(project: Project, snapshot: KartographAndroidSnapshotTask, scope: String,
        buildInputs: FileCollection, optionalOutputs: FileCollection) {
        val artifacts = project.files(snapshot.mainJars, snapshot.mainDirectories, snapshot.testJars, snapshot.testDirectories)
        val compilers = contributors(project, artifacts)
        // Resolving the DAG runs the primary compiler configuration callbacks first.
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

    /** Names and generated-looking paths grant no authority; only selected artifact dependencies do. */
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
