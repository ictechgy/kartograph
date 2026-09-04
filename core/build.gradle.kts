plugins {
    `java-library`
    kotlin("jvm")
}

val generatedVersionResources = layout.buildDirectory.dir("generated/version")
val generateVersionResource = tasks.register<WriteProperties>("generateVersionResource") {
    destinationFile = generatedVersionResources.map { directory ->
        directory.file("dev/kartograph/version.properties")
    }
    property("version", project.version.toString())
}

dependencies {
    testImplementation(kotlin("test"))
}

sourceSets.main {
    resources.srcDir(generatedVersionResources)
}

tasks.processResources {
    dependsOn(generateVersionResource)
}
