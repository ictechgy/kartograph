plugins {
    `java-library`
    kotlin("jvm")
}

dependencies {
    api(project(":analysis"))
    api(project(":core"))

    testImplementation(kotlin("test"))
}
