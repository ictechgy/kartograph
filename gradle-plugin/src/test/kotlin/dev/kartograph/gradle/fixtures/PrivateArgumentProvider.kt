package dev.kartograph.gradle.fixtures

import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider
import java.io.File

/** 다른 패키지의 비공개 구현도 Gradle이 일반 compiler 인자로 사용할 수 있다. */
object PrivateArgumentProvider {
    fun create(metadata: File): CommandLineArgumentProvider = Arguments(metadata)
    fun capture(project: org.gradle.api.Project, compiler: org.gradle.api.tasks.compile.JavaCompile): Any =
        dev.kartograph.gradle.AndroidCompilerInputs.argumentProviderInputs(project, compiler)
}

private class Arguments(@get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) val metadata: File) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = emptyList()
}
