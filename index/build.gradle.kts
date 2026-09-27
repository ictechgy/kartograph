plugins {
    `java-library`
    kotlin("jvm")
}

dependencies {
    api(project(":core"))
    implementation("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.20")
    implementation("org.ow2.asm:asm:9.10.1")
    implementation("org.ow2.asm:asm-tree:9.10.1")
    implementation("org.ow2.asm:asm-analysis:9.10.1")

    testImplementation(kotlin("test"))
    // 콜백 fixture의 실제 bytecode를 순회까지 이어 검증한다. 테스트 전용이며 main 의존 방향은 바꾸지 않는다.
    testImplementation(project(":analysis"))
}
