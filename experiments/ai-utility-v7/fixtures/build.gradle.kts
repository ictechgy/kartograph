import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins { kotlin("jvm") version "2.4.20" apply false }
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    repositories { mavenCentral() }
    extensions.configure<KotlinJvmProjectExtension> { jvmToolchain(17) }
    dependencies { "testImplementation"("junit:junit:4.13.2") }
    tasks.withType<Test>().configureEach { useJUnit() }
}
