plugins {
    `java-library`
    kotlin("jvm")
}

dependencies {
    api(project(":core"))
    implementation("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.10")
    implementation("org.ow2.asm:asm:9.9")

    testImplementation(kotlin("test"))
}
