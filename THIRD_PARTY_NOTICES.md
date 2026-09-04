# Third-party notices

The CLI and Gradle plugin distributions embed the following runtime dependencies:

| Component | Version | License |
|---|---:|---|
| ASM | 9.9 | [BSD 3-Clause](LICENSES/BSD-3-Clause.txt) |
| JetBrains annotations | 13.0 | [Apache License 2.0](LICENSES/Apache-2.0.txt) |
| Kotlin standard library | 2.4.10 | [Apache License 2.0](LICENSES/Apache-2.0.txt) |
| Kotlin Metadata JVM | 2.4.10 | [Apache License 2.0](LICENSES/Apache-2.0.txt) |

The license texts are reproduced in `LICENSES/`. Project dependencies can also be inspected with
`./gradlew :gradle-plugin:dependencies --configuration runtimeClasspath`.
