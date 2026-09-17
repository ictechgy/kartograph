plugins { kotlin("jvm") version "2.4.20" }
repositories { mavenCentral() }
dependencies { compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.20") }
kotlin { jvmToolchain(17) }
