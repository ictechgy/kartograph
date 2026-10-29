package dev.kartograph.gradle

import org.gradle.api.Project
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.SourceSet

/** 프로젝트 variant가 선언한 class 디렉터리만 부재까지 추적하며 임의 파일 의존성은 허용하지 않는다. */
internal object JvmClasspathDirectories {
    fun collect(project: Project, sourceSet: SourceSet): FileCollection {
        val artifacts = project.configurations.getByName(sourceSet.compileClasspathConfigurationName).incoming.artifactView { view ->
            view.componentFilter { it is ProjectComponentIdentifier }
        }.artifacts
        // 입력 경로를 실행 시 해석한다. 출력의 build dependency를 compiler에 되먹이지 않는다.
        return project.files(project.providers.provider {
            artifacts.artifacts.filter { artifact ->
                artifact.variant.attributes.getAttribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE) ==
                    ArtifactTypeDefinition.JVM_CLASS_DIRECTORY
            }.map { it.file }
        })
    }
}
