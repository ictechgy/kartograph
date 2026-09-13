package dev.kartograph.gradle

import org.gradle.api.Project
import org.gradle.api.file.FileCollection

/** 하위 프로젝트를 구성하는 상위 build script와 해당 프로젝트의 설정 파일도 compiler 입력으로 연결한다. */
internal object SnapshotBuildInputs {
    data class Selection(val files: FileCollection, val watchedFiles: FileCollection, val watchedDirectories: FileCollection)

    fun collect(project: Project, additional: FileCollection): Selection {
        val ancestors = generateSequence(project) { it.parent }.toList().asReversed()
        val buildLogic = listOf(project.rootProject.file("buildSrc")) + project.gradle.includedBuilds.map { it.projectDir }
        val directories = (ancestors.map { it.projectDir } + buildLogic).distinct()
        val declared = project.files(ancestors.map { it.buildFile }, directories.flatMap { directory ->
            listOf("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts", "gradle.properties")
                .map { directory.resolve(it) }
        })
        // gradle에는 catalog/wrapper/applied script, build logic의 src에는 실행 구현과 resource가 있다.
        // build/.gradle 출력은 수집하지 않고, 사용자 정의 위치는 추가 입력으로 받는다.
        val watchedDirectories = project.files(directories.map { it.resolve("gradle") }, buildLogic.map { it.resolve("src") })
        val files = project.files(declared.filter { it.isFile }, watchedDirectories.filter { it.isDirectory }, additional)
        return Selection(files, declared, watchedDirectories)
    }
}
