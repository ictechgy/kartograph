package dev.kartograph.gradle

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import java.io.File
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity

/** 각 compiler가 실제 생성하는 언어의 소스와 산출물·성공 증거를 함께 검증한다. */
public abstract class SnapshotCompilation {
    @get:Input public abstract val identity: Property<String>
    @get:Input public abstract val compiler: Property<String>

    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val primarySources: ConfigurableFileCollection

    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val classDirectories: ConfigurableFileCollection

    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val witnessFiles: ConfigurableFileCollection

    /** 실패 사유도 선언된 입력으로 읽는다. 성공 증거 형식과 별도 파일이다. */
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    public val rejectionFiles: Provider<List<File>>
        get() = witnessFiles.elements.map { files -> files.map { it.asFile.parentFile.resolve(WitnessRejection.FILE_NAME) } }
}
