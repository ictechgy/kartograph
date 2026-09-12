# Compiler-produced references

Compiler collectors add references that compiled instructions can erase or leave
indirect. The optional collectors cover javac constants, Kotlin 2.4.10 constant
uses, and Dagger 2.59 selected bindings. Kotlin joins resolved FIR references to
names and descriptors emitted by the JVM backend; it does not use the experiment's
fixture mapping. Compiler dependencies are confined to the separate
[`compiler-collectors`](../compiler-collectors/README.md) build.

These references enter a `snapshot` explicitly. `query` and `impact` then consume
the saved graph. Default retention remains conservative, and a missing reference
does not authorize deletion.

## Build the collector

From the repository root, with `JAVA_HOME` set to JDK 17:

```sh
./gradlew --no-daemon -p compiler-collectors integrationTest
```

This produces `compiler-collectors/build/libs/kartograph-compiler-collectors.jar`.
Dependency checksums are enforced. The standalone tests execute javac, the Kotlin
compiler and the Dagger processor, including unused controls and failed builds.

## Connect a real compiler task

Apply the kartograph Gradle plugin and use its actual compiler task/output
providers. This Java example uses Groovy DSL and a local collector JAR:

```groovy
def compiler = tasks.named('compileJava', JavaCompile)
def token = dev.kartograph.gradle.CompilerWitnesses.INSTANCE.inputTokenFile(project, compiler)
def directory = dev.kartograph.gradle.CompilerWitnesses.INSTANCE.evidenceDirectory(project, compiler)
def output = directory.map { it.file('references.tsv') }
def collector = files('/path/to/kartograph-compiler-collectors.jar')
compiler.configure {
    options.annotationProcessorPath = collector
    options.compilerArgs.add('-Xplugin:KartographEvidence collector=javac-constants' +
        ' root=' + project.projectDir.toPath().toUri().toASCIIString() +
        ' output=' + output.get().asFile.toPath().toUri().toASCIIString() +
        ' token=' + token.get().asFile.toPath().toUri().toASCIIString())
}
def witness = dev.kartograph.gradle.CompilerWitnesses.INSTANCE.javaCompile(
    project, compiler, 'sample:main', files('src/main/java'),
    files('build.gradle', 'settings.gradle'), files(), true)
```

Preserve any existing annotation-processor path when adding the collector. For
Dagger, include the Dagger processor and dependencies on that path, select
`collector=dagger-bindings`, and pass the same `root`, `output`, and `token` as
`-Akartograph.evidence.root=...`, `-Akartograph.evidence.output=...`, and
`-Akartograph.evidence.token=...`. The paired javac plugin is required. The SPI and
compiler may use separate classloaders under Gradle; only the compiler completion
listener publishes the final evidence.

For a Kotlin 2.4.10 task, add the collector to the public `pluginClasspath` and
configure `-P plugin:kartograph.compiler-evidence:root=...`, `:output=...`, and
`:token=...` through `compilerOptions.freeCompilerArgs`. Register
`KotlinCompilerWitnesses.kotlinCompile` with the same source/build inputs and JDK
provider as [build provenance](BUILD-PROVENANCE.md), the selected compiler runtime
configuration in `additionalInputs`, and `compilerEvidence = true`. The default
2.4.10 runtime is `kotlinBuildToolsApiClasspath`.

Use file URIs for options so paths containing spaces remain one javac plugin
argument. Source roots must match the compiler inputs. Register each compilation
with a deliberate scope; this feature does not discover Android variants or main
and test roots. It does not attribute a transformed AGP JAR to an earlier compiler
directory automatically.

## Capture, verify and inspect impact

After compilation, use the returned witness and actual output providers:

```sh
kartograph snapshot --project . --classes "$CLASS_OUTPUT" --scope sample:main \
  --build-witness "$WITNESS" --compiler-evidence "$COLLECTOR_OUTPUT" \
  --source-root src/main/java --include-paths \
  --input external/slot=/local/input > snapshot.json
kartograph verify-snapshot --project . --graph-file snapshot.json \
  --input external/slot=/local/input
kartograph impact 'field:example/Constants#VALUE:I' --graph-file snapshot.json
```

Repeat `--compiler-evidence` and `--input` as needed. Bind **every** external slot
recorded by the witness, including collector/compiler/JDK and dependency inputs.
Slot names are portable identities; actual binding paths are not serialized.
The producer's evidence directory is managed output: prior collector files are
removed before a new compiler execution.

Witness version 2 records the input token and exact collector-file fingerprints.
Generated source outputs observed by processors receive separate fingerprints.
Older witnesses remain version 1 and readable; they do not authorize compiler
reference import. Raw TSV alone, a caller revision label, or manually generated
hashes cannot substitute for the supported producer lifecycle.

## Completeness and lifecycle

Enabled tasks perform full compiler analysis. Gradle up-to-date and build-cache
reuse remain supported; this is not a claim of incremental collector merging.
An incomplete source inventory, stale token, missing/changed sidecar, or failed
compilation cannot produce a completed receipt. Late task actions and failed
`--continue` builds invalidate the witness as described in build provenance.

Mapped references use `compilerReference` origin and `reference` edges. Dagger
bindings describe compiler selection, not a JVM call to an abstract `@Binds`
method. References outside the selected graph, shadowed source declarations and
unmapped references have separate measured counts in snapshot limitations.
Missing members of an included class are rejected. Class-root precedence is
reused from the original class parse, including declarations shadowed by an
earlier root.

The collectors cover tested compiler APIs and reference patterns. They do not
prove arbitrary runtime completeness, source transformations outside the supplied
inputs, or the honesty of a malicious producer. Custom Kotlin non-JAR plugin
inputs must be supplied explicitly under the provenance contract.

Javac scans each attributed top-level class separately, and inventory-only units
such as `package-info.java` need no class output. Constructors whose JVM parameters
include implicit outer/enum/capture arguments are currently reported as unmapped;
their descriptors are not guessed. Kotlin fields are joined through compiler IR
declaration identity captured before lowering. Unavailable external declarations
remain unmapped instead of being matched to a same-name project field.

One javac collector mode is selected per task. Raw collector completion callbacks
are not an independent compiler exit-status oracle: a completed Gradle receipt is
required before product import, including for late compiler/task failures.

## Reproduce the product integration

After building the CLI/plugin and the existing Dagger experiment dependencies:

```sh
./gradlew --no-daemon :cli:installDist :gradle-plugin:jar
./gradlew --no-daemon -p experiments/dagger-bindings dependenciesForFixture
./gradlew --no-daemon -p compiler-collectors integrationTest
python3 Scripts/verify-compiler-evidence.py
```

The validator uses temporary Java, Kotlin and Dagger projects, actual compiler
outputs and the product CLI. It checks impact traversal, unused controls,
configuration-cache reuse, wrong scopes, changed collector bytes, preserved-mtime
source changes, replayed old sidecars and missing bindings. The CI runs the same
validator and retains its report and fixture logs. These fixture results are not
the later broad evaluation of real application changes or AI repair success.
