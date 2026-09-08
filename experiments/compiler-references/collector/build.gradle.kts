plugins { kotlin("jvm") version "2.4.10" }
repositories { mavenCentral() }
dependencies { compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.10") }
kotlin { jvmToolchain(17) }
