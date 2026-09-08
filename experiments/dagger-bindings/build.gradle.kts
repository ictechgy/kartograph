plugins { java }
repositories { mavenCentral() }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }
val daggerVersion = file("dagger-version.txt").readText().trim()
dependencies {
    implementation("com.google.dagger:dagger-spi:$daggerVersion")
    implementation("com.google.dagger:dagger:$daggerVersion")
}
val compiler by configurations.creating
dependencies { compiler("com.google.dagger:dagger-compiler:$daggerVersion") }
tasks.register<Sync>("dependenciesForFixture") {
    from(configurations.runtimeClasspath, compiler)
    into(layout.buildDirectory.dir("fixture-dependencies"))
}
