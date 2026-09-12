# Compiler evidence product verification

2026-09-12, the completed build-provenance baseline is PR38 (`a3a2cd8`).
The optional collectors are now consumed through completed compiler receipts by
product snapshots and impact queries. The input and lifecycle contract is in
[COMPILER-EVIDENCE](../../docs/COMPILER-EVIDENCE.md).

| Compiler fixture | Product references | Controls / lifecycle evidence |
|---|---:|---|
| javac constants | 1 | Bytecode-only impact misses the reader; compiler evidence finds it; equal-value unused constant stays unreferenced. |
| Kotlin 2.4.10 | 6 | File/function `JvmName`, object, companion field relocation and Int/String overloads; unused and shadowing controls. |
| Dagger 2.59 | 3 | Selected binds/constructor dependency graph, actual selected service output, missing-binding rejection. |

The portable Gradle verifier uses temporary projects whose paths include spaces.
Each compiler is run with the real collector JAR. It checks completed version2
receipts, current snapshot verification, impact paths, configuration-cache reuse,
clean/build-cache restoration, wrong scope and changed collector files. The Java
case also changes source bytes while preserving size and mtime, attempts to replay
an old sidecar, rejects partial inventory and checks source deletion. The Dagger
case checks that a missing binding removes the witness and invalidates the prior
snapshot. Generated processor source outputs are fingerprinted separately.

The standalone collector suite also checks multi-top-level Java classes,
inventory-only `package-info.java`, constructor shapes, an empty Dagger module,
failed compilations with prior output present, and concurrent/malformed Dagger
staging requests. Unsupported implicit constructor JVM parameters are counted as
unmapped. A Kotlin external top-level constant must not be guessed to be a
same-name nested project field; pre-lowering IR symbol identity replaces that
heuristic.

```sh
./gradlew --no-daemon :cli:installDist :gradle-plugin:jar
./gradlew --no-daemon -p experiments/dagger-bindings dependenciesForFixture
./gradlew --no-daemon -p compiler-collectors integrationTest
python3 Scripts/verify-compiler-evidence.py
```

[Recorded results](results-2026-09-12.json) contain artifact hashes and measured
stage times. They exclude source/build logs and local input bindings. The times
include compiler/build work where the stage says so; this is not an incremental
collector performance claim. Collector-enabled tasks currently perform full
compiler analysis, while Gradle up-to-date and build-cache reuse remain available.

These controlled fixtures establish the listed behavior. They do not establish
arbitrary runtime completeness, safe deletion, or a general advantage over all
competing tools. The planned held-out public-change and actual AI repair
comparison remains a separate delivery goal.
