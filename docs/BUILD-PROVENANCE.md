# Build provenance and snapshot freshness

`verify-snapshot` compares bytes and membership, not timestamps or commit labels. A
snapshot carries ordered analysis class roots separately from hierarchy classpaths,
explicit source/config/retention inputs (including recursive keep-rule includes),
and optional compiler-task witnesses. Saved `query` and `impact` continue to use the
captured graph and retention facts offline; comparison never replaces those facts.

A verification report has `matched`, `stale`, or `unverified` status. Exit codes are
0 for matched evidence, 1 for stale/unverified, 2 for invalid/unreadable documents,
and 64 for invalid options. Legacy snapshots remain readable and are explicitly
unverified. Capturing hashes after `true`, a shell build command, or an arbitrary
caller revision cannot produce compilation evidence.

[Compiler-produced references](COMPILER-EVIDENCE.md) describe the optional
collector path. Enabling `compilerEvidence` on a supported producer records
version 2 receipts; raw reference files are imported only through explicit
`snapshot --compiler-evidence` inputs with matching compiler evidence.

## Explicit Gradle producer

Apply the kartograph plugin and register the selected **compiler task**. Registration
is opt-in; it does not discover main/test/generated roots or choose an Android variant.
For Java (Kotlin DSL):

```kotlin
import dev.kartograph.gradle.CompilerWitnesses
import org.gradle.api.tasks.compile.JavaCompile

val witness = CompilerWitnesses.javaCompile(
    project, tasks.named<JavaCompile>("compileJava"), "sample:main",
    files("src/main/java"), files("build.gradle.kts", "settings.gradle.kts"),
)
```

For Kotlin JVM / a selected Android Kotlin compiler task with KGP 2.4.10:

```kotlin
import dev.kartograph.gradle.KotlinCompilerWitnesses
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion

val witness = KotlinCompilerWitnesses.kotlinCompile(
    project, tasks.named<KotlinCompile>("compileKotlin"), "sample:main",
    files("src/main/kotlin", "src/main/java"),
    files("build.gradle.kts", "settings.gradle.kts"),
    javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) },
    additionalInputs = files(configurations.named("kotlinBuildToolsApiClasspath")),
)
```

Only include existing source roots. Supply generated-source providers with their
producer dependencies and register test compiler tasks separately with their actual
source roots. The selected roots must cover the compiler's declared source set
exactly (Kotlin also consumes Java sources). Supply all relevant build scripts,
version catalogs and additional safe generator/config files explicitly. Do not
supply environment files, authentication files, credential stores or a whole home
or Gradle cache directory. No raw option values or absolute paths are serialized.
The tool hashes all files in explicitly selected non-source roots locally, including
public certificate resources; it does not infer confidentiality from a filename.
Choose those roots accordingly. Symbolic file inputs are rejected.
The Kotlin adapter configures the supplied JDK through the public toolchain API.
Keep the Java and Kotlin bytecode targets aligned explicitly when that toolchain differs
from an Android variant's default; the JDK used to compile and the bytecode target are separate settings.
Public KGP APIs are resolved through the selected task's classloader, including effective
compiler arguments, so an isolated Kotlin plugin classloader is supported.
The adapter is tested against KGP 2.4.10; it also uses that version's compiler argument
serialization API. Availability on other KGP versions is not a compatibility guarantee.
The example selects KGP 2.4.10's default Build Tools API runtime configuration. If
the build explicitly disables `kotlin.compiler.runViaBuildToolsApi`, select
`kotlinCompilerClasspath` instead. These configuration names are version-specific
caller choices; supplying an unrelated runtime does not pass artifact validation.

The compiler's public destination provider is the artifact root. Android users must
select the compiler task through their supported variant/build API and pass its
actual destination provider to their capture invocation. PROJECT graph roots and
ALL hierarchy inputs remain distinct. A transformed AGP JAR is not automatically
attributed to an earlier compiler directory: an unmatched root remains unverified.
Automatic variant/task/root capture belongs to the later CI automation goal.

The compiler records inputs before its action, removes the previous witness before
an attempted compile, and records matching post-action inputs and class outputs only
on success. A public Gradle completion listener also removes evidence if a later task
action or dependency fails; registered witnesses are invalidated at build completion
when any task failed, including `--continue` builds. The witness is a declared compiler output, so a matching up-to-date
execution or build-cache restoration can reuse it. Separate native Gradle file
inputs include the witnessed bytes in the task's cache key, so ABI-identical dependency changes cannot restore
an earlier witness after `clean`. A separate byte comparison is
still required before accepting a snapshot. Javac custom launchers and unsupported
options are rejected. File arguments from providers (such as `--system`) must also
be declared Gradle file inputs; use declared compiler APIs.
Kotlin records declared compiler artifact inputs and effective compiler arguments through the KGP public API; an
unavailable artifact/property is an error, not a fabricated success record.
Each compiler owns a dedicated witness output directory. The pending marker lives outside
that directory, and only the completed JSON is a reusable build result.
Additional declared compiler files outside the adapter's public input collections
must be supplied through `additionalInputs`. Compilation fails before recording
evidence if any observed file is missing from the byte-sensitive input roots.
Do not derive this collection from the compiler task's aggregate `inputs.files`:
that creates a reference back to the same task during configuration-cache restore.
Kotlin evidence uses public source/library/plugin/friend collections and the explicit
compiler runtime/configuration inputs. Private incremental-cache snapshot files are
derived build state and are not claimed as supported compiler evidence inputs.
Java rejects omitted declared files; Kotlin validates runtime JAR coverage but does
not automatically distinguish custom non-JAR plugin/argument files from private
cache state. Include those custom files in `additionalInputs`; omitting them leaves
their changes outside verification. `matched` covers the recorded input contract.
The Android witness experiment covers the selected Kotlin task. Android JavaCompile
tasks with additional AGP/processor file inputs require explicit enumeration and
have not been validated by that experiment.

## Capture and compare

After the compiler task succeeds, pass its returned witness file and destination
provider values to the existing CLI. The example uses illustrative paths; it does
not define any AGP output layout:

```sh
kartograph snapshot --project . --classes "$CLASS_OUTPUT" \
  --scope sample:main --build-witness "$WITNESS_FILE" \
  --source-root src/main/java --include-paths --timings > snapshot.json
kartograph verify-snapshot --project . --graph-file snapshot.json --scope sample:main
```

Use the same project directory for registration and CLI capture. All supplied class,
classpath, retention, source, build-config and witness file contents are captured
before and after graph construction; changes abort capture. Source roots include
Java/Kotlin filenames and bytes, so additions and deletions are visible. Full class
roots include all output entries. `--classes` and `--classpath` may also be repeated
on `verify-snapshot` to compare a newly supplied ordered input selection.
As with capture, `--classes` resolves from the working directory; `--classpath`
resolves from the project directory. Prefer absolute values when these differ.

External inputs (including the compiler/JDK artifact) appear as `external/...`
slots. Bind each slot to a local file/directory with repeated
`--input external/slot=/local/path`. Bindings are supplied at comparison time and are
not serialized. Missing bindings are `unverified`; proven byte changes are `stale`.
Moving an equivalent checkout
preserves project-relative identities and content digests. The document includes
compiler kind, compiler task artifact identity and project/variant scope; changed
witness files or a different requested scope fail comparison.
Use repeated `--artifact :project:compileTask` and `--compiler javac|kotlin` to
compare the intended compiler selection in witness order. Artifact identities come
from Gradle task paths. Scope is the registration's explicit variant label; the
producer does not infer a variant from a task name or a directory layout.

`check-impact.py` runs comparison for the current snapshot and, when supplied,
`--base-project` for the base checkout. It accepts repeated `--input` / `--base-input`
bindings. Report-only mode writes freshness diagnostics and returns 0; `--strict`
returns 1 for stale/unverified evidence as well as incomplete impact selection.
Without a base checkout, base freshness is explicitly unverified. Tool/document
failures remain exit 2 in either mode.

## Evidence and cost

`--timings` writes `captureHashNanos` to stderr separately from indexing/capture;
`verify-snapshot` reports `hashNanos` separately from offline query time. These are
wall-clock observations for the supplied inputs, not an incremental-build speedup
claim. Compiler pre/post hashing is additional build cost, particularly for JDK
modules and dependency JARs.

Fingerprints provide integrity evidence and supported compiler lifecycle
correspondence. They do not authenticate a malicious build producer, prove that a
revision label describes an entire checkout, cover undeclared processor inputs, or
prove arbitrary runtime completeness. Input consistency is compared at the recorded
pre/post boundaries. Producer integration tests exercise real Gradle compiler tasks;
unit witness fixtures alone are not evidence that a build ran.
