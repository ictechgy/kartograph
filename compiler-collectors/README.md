# Kartograph compiler collectors

This is a standalone optional build for compiler-produced impact evidence. The
collector jar is not an analyzer runtime dependency and has no dependency on
Kartograph `core`, `index`, `export`, or CLI modules.

The jar contains four separately selected collectors:

- `javac-constants`: a `javac` plugin that records resolved references to
  compile-time constant fields.
- `kotlin-constants`: a Kotlin 2.4.10 K2 compiler plugin. FIR supplies the
  resolved constant reference and the JVM backend supplies the emitted method
  and field identities. This is what handles file `JvmName`, objects,
  companions, function `JvmName`, and overload descriptors; no fixture mapping
  is used.
- `dagger-bindings`: a Dagger 2.59 `BindingGraphPlugin` that records only
  compiler-selected dependency edges. Unselected `@Provides` bindings and
  missing-binding graphs are not published as complete evidence.
- `javac-processors`: records source files created and closed through the selected
  JSR-269 processor's Filer, with the generating processor artifact fingerprint.
  It uses the separate v2 evidence envelope and preserves attribution in snapshot
  `processorGenerations`; it does not alter reachability or dependency findings.
  See [setup and limits](../docs/PROCESSOR-GENERATION.md).

The optional distribution also contains `OutputRecordingProcessor` (javac/KAPT) and
`RecordingSymbolProcessorProvider` (KSP 2.3.12). These produce a separate output
sidecar for source, class and resource API outputs, plus byte changes in an
explicit callback-scoped direct-write directory. `processor_output_witness.py`
binds that sidecar to a successful configured command and declared inputs. The
v2 receipt can be imported as snapshot `processorOutputs` metadata without
changing graph edges or replacing compiler witnesses.
See the [output workflow](../docs/PROCESSOR-GENERATION.md#output-collector-kaptksp와-여러-출력-종류).

## Build and run

Set `JAVA_HOME` to JDK 17 and run from the repository root:

```sh
./gradlew --no-daemon -p compiler-collectors integrationTest
```

The first run resolves fixed compiler dependencies using the checked-in SHA-256
verification metadata. Add `--offline` after those inputs are cached. The build
version comes from the repository `VERSION` file.

`./gradlew --no-daemon -p compiler-collectors distZip` builds the optional
`build/distributions/kartograph-compiler-collectors-<version>.zip`. It contains the
collector JAR, standalone Python runner, Gradle cache adapter, versioned installation guide and license
notices. It does not bundle compiler or processor dependencies. The release
readiness gate checks this ZIP in both reproducibility builds and runs an
independent javac consumer against the extracted JAR and runner. The ZIP is a
separate GitHub release asset covered by `SHA256SUMS`; it is not added to the
Gradle plugin runtime.

The build publishes `build/libs/kartograph-compiler-collectors.jar` and runs
the real Java, Kotlin, and Dagger fixtures in `tests/run.py`.
It also runs `tests/output_attribution.py` for real javac/KAPT/KSP output and
failure controls. The latter restores the native javac/KAPT/KSP task from the
Gradle build cache, reuses configuration cache and validates restored output
bytes. Set `KARTOGRAPH_SNAPSHOT_CLI` to a built CLI launcher to additionally check
snapshot metadata, unchanged graph/retention and rejection of changed outputs.
It does not claim coverage of asynchronous or concurrent writers.

For product verification, register the real Gradle compiler with
`compilerEvidence = true` and use `CompilerWitnesses.inputTokenFile` and
`CompilerWitnesses.evidenceDirectory`. The producer creates the pending input
token before compilation and records completed evidence receipts afterward.
Manual tokens are only suitable for collector experiments; they are not build proof. The caller passes the project root, output file or directory, and
that token to the collector. The collector reads source bytes from the
compiler's actual units, verifies them again after successful code generation,
and publishes raw facts after its supported completion callbacks. Syntax-error,
missing-binding and changed-input cases invalidate old outputs. Only the Gradle
producer's successful task result can turn raw facts into completed receipts;
raw output alone is not a compiler exit-status oracle.

For `javac`, pass one quoted plugin argument so `javac` keeps the plugin
options together:

```text
javac -Xplugin:"KartographEvidence collector=javac-constants root=/project output=/evidence token=/pending-token" ...
```

For Dagger, use the Dagger compiler and this plugin on the processor path.
The paired `KartographEvidence` javac plugin is required to publish a result only
after compilation completes. Pass the same root, output and token to both:

```sh
javac -processorpath /path/kartograph-compiler-collectors.jar:/path/dagger-compiler.jar \
  -processor dagger.internal.codegen.ComponentProcessor \
  -Xplugin:"KartographEvidence collector=dagger-bindings root=/project output=/evidence token=/pending-token" \
  -Akartograph.evidence.root=/project \
  -Akartograph.evidence.output=/evidence \
  -Akartograph.evidence.token=/pending-token ...
```

The SPI uses bounded temporary staging so Gradle's separate processor and compiler
classloaders do not need to share an implementation object. Temporary files are
not final compiler evidence. Missing pairing produces a compiler error.

For Kotlin 2.4.10, pass the collector jar and plugin configuration to the K2
compiler:

```text
java -cp <kotlin-2.4.10-compiler-classpath> org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -Xplugin=/path/kartograph-compiler-collectors.jar \
  -P plugin:kartograph.compiler-evidence:root=/project \
  -P plugin:kartograph.compiler-evidence:output=/evidence \
  -P plugin:kartograph.compiler-evidence:token=/pending-token ...
```

The output path may be a `.tsv` file, an existing directory, or a directory
path that will be created. An exploded collector class directory is rejected:
the artifact identity must be the packaged jar.

## Raw interchange

The UTF-8 sidecar is deterministic TSV with this header:

```text
format	kartograph-compiler-evidence	1
```

It then contains singleton `collector`, `compiler`, `token`, `artifact`, and
`unmapped` rows, one `source` row per compiler source unit, and `edge` rows.
Source paths and JVM node identities are base64url without padding. Source
rows contain the raw source SHA-256. `artifact` is the same content-fingerprint
shape used by Kartograph for a packaged file. No source bytes, constant values,
qualifier strings, timestamps, random values, or absolute paths are emitted.

`unmapped` is explicit incomplete evidence, not a best-effort success signal.
The producer never walks a source directory to manufacture compiler coverage,
and hand-authored TSV is not compiler proof.
