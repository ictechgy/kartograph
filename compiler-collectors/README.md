# Kartograph compiler collectors

This is a standalone optional build for compiler-produced impact evidence. The
collector jar is not an analyzer runtime dependency and has no dependency on
Kartograph `core`, `index`, `export`, or CLI modules.

The jar contains three separately selected collectors:

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

## Build and run

Set `JAVA_HOME` to JDK 17 and run from the repository root:

```sh
./gradlew --no-daemon -p compiler-collectors integrationTest
```

The first run resolves fixed compiler dependencies using the checked-in SHA-256
verification metadata. Add `--offline` after those inputs are cached. The build
version comes from the repository `VERSION` file.

The build publishes `build/libs/kartograph-compiler-collectors.jar` and runs
the real Java, Kotlin, and Dagger fixtures in `tests/run.py`.

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
