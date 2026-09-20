# Kartograph compiler collectors @VERSION@

This optional distribution contains `lib/kartograph-compiler-collectors.jar` and
the standalone `processor_output_witness.py` runner and `processor_output_cache.gradle`
adapter. It is separate from the CLI
and Gradle plugin runtime. The version is also recorded in `VERSION` and the JAR
manifest.

Download this ZIP and `SHA256SUMS` from the same
[GitHub release](https://github.com/ictechgy/kartograph/releases/tag/v@VERSION@).
Verify the ZIP's SHA-256 against its entry before extracting it. The archive
contains no compiler, Kotlin runtime, KSP API, or annotation processor dependency;
use the compiler and processor dependencies declared by your build.

The collectors target JDK 17, Kotlin/KAPT 2.4.10, KSP 2.3.12 and Dagger 2.59.
These are tested integration versions, not a claim of compatibility with every
compiler or processor version. Compiler plugins can depend on version-specific
APIs.

Use the JAR on the selected compiler/processor path. For the output collector,
select `dev.kartograph.collectors.OutputRecordingProcessor` with javac/KAPT or
`dev.kartograph.collectors.RecordingSymbolProcessorProvider` with KSP, and select
one delegate processor. The build must supply the pending token and output
options. Do not run the delegate separately through service auto-discovery.

See the versioned [collector setup](https://github.com/ictechgy/kartograph/blob/v@VERSION@/compiler-collectors/README.md)
and [processor output contract](https://github.com/ictechgy/kartograph/blob/v@VERSION@/docs/PROCESSOR-GENERATION.md)
for Gradle registration, configuration, snapshot support and exact limitations.

For the standalone runner (Python 3.9 or newer), provide an explicit local JSON
configuration containing the authorized command, inputs and output roots:

```sh
python3 processor_output_witness.py record --config /path/to/local-config.json
python3 processor_output_witness.py verify --config /path/to/local-config.json
```

The runner's v2 receipt fingerprints the configured command and explicit inputs. It
does not prove that all compiler inputs were supplied. Raw collector observations
alone do not prove build success. API outputs and callback-scoped direct writes
remain distinct, and neither is a deletion approval or runtime completeness claim.

The runner can still verify older v1 receipts, but snapshot import requires a new
v2 capture. A successful v2 run leaves the stable token file for subsequent Gradle
cache keys; only the validated completed receipt proves the configured command
finished successfully. Failed runs remove both the token and previous receipt.

To restore raw observations with the native Gradle processor task, apply the
provided script and call `registerProcessorOutputCache('kspKotlin', file('config.json'))`
(use `kaptKotlin` or `compileJava` for the corresponding compiler). Declare direct
write **files** in `cacheOutputs`; the adapter retains the native task's existing
output directories and adds only those files and the raw sidecar. List the adapter
script in the runner's `inputs`, disable incremental processing, and enable the
task's normal build cache. Keep unrelated handwritten files outside `cacheOutputs`.

After recording, import with `kartograph snapshot --project <project> --classes <classes>
--scope <same-scope> --processor-output-config <config.json>`, or configure
`processorOutputConfigs.from('config.json')` on the Gradle snapshot task. The
snapshot rechecks the declared inputs and all observed output bytes; it never
executes the configuration's command.
