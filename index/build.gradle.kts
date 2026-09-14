plugins {
    `java-library`
    kotlin("jvm")
}

dependencies {
    api(project(":core"))
    implementation("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.10")
    implementation("org.ow2.asm:asm:9.10.1")
    implementation("org.ow2.asm:asm-tree:9.10.1")
    implementation("org.ow2.asm:asm-analysis:9.10.1")

    testImplementation(kotlin("test"))
}
