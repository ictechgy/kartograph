package dev.kartograph.gradle

import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.work.DisableCachingByDefault

/** AGP PROJECT/ALL artifact provider의 main/unit-test 입력을 공통 snapshot task에 전달한다. */
@DisableCachingByDefault(because = "Snapshot capture validates transitive keep inputs and compiler provenance each time")
public abstract class KartographAndroidSnapshotTask : KartographSnapshotTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val mainJars: ListProperty<RegularFile>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val mainDirectories: ListProperty<Directory>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val testJars: ListProperty<RegularFile>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val testDirectories: ListProperty<Directory>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val mainClasspathJars: ListProperty<RegularFile>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val mainClasspathDirectories: ListProperty<Directory>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val testClasspathJars: ListProperty<RegularFile>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val testClasspathDirectories: ListProperty<Directory>
}
