import java.io.File

// AA는 compiler-embeddable과 classloader를 공유하지 않는 별도 실험 runtime이다.
plugins { java }
repositories { mavenCentral() }
val semanticRuntime by configurations.creating

dependencies {
    semanticRuntime("dev.detekt:detekt-kotlin-analysis-api:2.0.0-alpha.6")
    semanticRuntime("dev.detekt:detekt-kotlin-analysis-api-standalone:2.0.0-alpha.6")
    semanticRuntime("org.jetbrains.kotlin:kotlin-compiler:2.4.10")
    semanticRuntime("org.jetbrains.kotlin:kotlin-stdlib:2.4.10")
    semanticRuntime("org.jetbrains.kotlin:kotlin-scripting-jvm:2.4.10")
    semanticRuntime("org.jetbrains:annotations:23.0.0")
}

tasks.register("writeSemanticClasspath") {
    doLast {
        val files = semanticRuntime.resolve().sortedBy { it.name }
        require(files.none { "compiler-embeddable" in it.name }) { "Relocated compiler must not enter AA runtime" }
        layout.buildDirectory.file("semantic-classpath.txt").get().asFile.apply {
            parentFile.mkdirs()
            writeText(files.joinToString(File.pathSeparator) { it.absolutePath } + "\n")
        }
    }
}
