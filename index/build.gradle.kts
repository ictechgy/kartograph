plugins {
    `java-library`
    kotlin("jvm")
}

dependencies {
    api(project(":core"))
    implementation("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.21")
    implementation("org.ow2.asm:asm:9.11.1")
    implementation("org.ow2.asm:asm-tree:9.11.1")
    implementation("org.ow2.asm:asm-analysis:9.11.1")

    testImplementation(kotlin("test"))
    // 콜백 fixture의 실제 bytecode를 순회까지 이어 검증한다. 테스트 전용이며 main 의존 방향은 바꾸지 않는다.
    testImplementation(project(":analysis"))
}

// Gradle plugin은 host build(AGP 등)의 classloader에 먼저 올라온 ASM 위에서 돌 수 있다. 9.11 이전 ASM(계산 한도 API 없음)에서도
// 인덱싱이 끝나는지 격리된 classloader로 확인하도록, 테스트에만 9.10.1 jar를 넘긴다. 배포 의존성에는 들어가지 않는다.
val legacyAsm = configurations.create("legacyAsmTestRuntime") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
dependencies {
    legacyAsm("org.ow2.asm:asm:9.10.1")
    legacyAsm("org.ow2.asm:asm-tree:9.10.1")
    legacyAsm("org.ow2.asm:asm-analysis:9.11.1")
}
tasks.test {
    inputs.files(legacyAsm).withPropertyName("legacyAsm").withNormalizer(ClasspathNormalizer::class.java)
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Dkartograph.test.legacyAsm=" + legacyAsm.files.joinToString(File.pathSeparator))
    })
}
