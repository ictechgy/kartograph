import java.util.Properties

plugins { java }
repositories { mavenCentral() }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }
val versions = Properties().apply { file("versions.properties").inputStream().use { load(it) } }
val soot by configurations.creating
val wala by configurations.creating
configurations.compileClasspath { extendsFrom(soot, wala) }
dependencies {
    soot("org.soot-oss:sootup.callgraph:${versions.getProperty("sootup")}")
    soot("org.soot-oss:sootup.java.bytecode.frontend:${versions.getProperty("sootup")}")
    wala("com.ibm.wala:com.ibm.wala.core:${versions.getProperty("wala")}")
}
tasks.register<Sync>("sootDependencies") { from(soot); into(layout.buildDirectory.dir("libraries/soot")) }
tasks.register<Sync>("walaDependencies") { from(wala); into(layout.buildDirectory.dir("libraries/wala")) }

tasks.withType<AbstractArchiveTask>().configureEach { isPreserveFileTimestamps = false; isReproducibleFileOrder = true }
