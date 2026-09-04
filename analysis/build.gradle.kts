plugins {
    `java-library`
    kotlin("jvm")
}

dependencies {
    api(project(":core"))

    testImplementation(kotlin("test"))
}
