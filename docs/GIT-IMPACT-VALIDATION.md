# Git impact validation

This bounded self-change run checks that a real Kotlin patch reaches review candidates through compiled before/current inputs. It is not a labeled application-wide recall/precision benchmark, a prediction of failing tests, or permission to skip tests.

## Exact inputs

- Before: `bb13a7d8b8e48a2d61ed090e4c8dfe761685bdb1`.
- Current/tool implementation: `50324b1f941648dc7746ea7aa70f5fd203585356` (PR #199).
- Windows, Temurin JDK 17.0.20.1, Gradle 9.7.1; both snapshots use tool version label `0.25.0` and scope `kartograph:jvm-review`.
- Compiled roots: `core`, `analysis`, `export`, `index`, `cli` Kotlin main outputs plus CLI Kotlin test outputs. Gradle plugin, Android, other test modules and runtime observations are outside this run.
- Before snapshot SHA-256: `272bb9e73f3dbca457f85232ea93a78fe62849ff958b95e5d515fccb520c69c3`.
- Current snapshot SHA-256: `47a1ab2cdf1d41b3224ca92e60271ff8da1158ea3196827d9f3b628237ea4432`.

Build each checkout first. Capture each using `snapshot --include-paths --compact --project <checkout> --revision <exact-commit> --scope kartograph:jvm-review`, with each selected `--classes <module>/build/classes/kotlin/main` and matching `--source-root <module>/src/main/kotlin`, plus the CLI test root pair. Then run:

```sh
kartograph impact --git-base bb13a7d8b8e48a2d61ed090e4c8dfe761685bdb1 \
  --project <current-checkout> --base-project <before-checkout> \
  --base-graph before.json --graph-file current.json \
  --limit 100 --path-limit 10000 --summary-limit 100
# Repeat with --test-status test for static test candidates.
```

## Observations and limits

The query observed 3,572 affected graph nodes; 594 were tagged as test candidates. These are graph nodes, not 594 independently runnable test cases. Each requested page returned 100 nodes. The 10,000-edge path budget omitted 6,078 path edges; `truncated.budget` and `truncated.results` were true, `truncated.depth` false. The 100,000 visit budget was not reached (3,714 before and 3,774 current visits). Both queries returned `partial`, exit 64, with four unmapped documentation paths.

For example, `CallDeclarationCliTest.graph declaration snapshots and CLI MCP queries preserve independent observable calls` and `DirectInterfaceDeclarationCliTest.CLI exports and MCP replay preserve direct interface proof without changing queries` had base-revision bytecode reference paths to `McpTestClient`. That class shares a changed test file; it was not itself edited. This is concrete over-selection from whole-file seeds, not a claim those tests will fail. The result preserves the historical path and separately records omitted current paths. Narrower symbol selection remains available through the existing mode.

Manual capture had no registered build witness. Both sides therefore remained `unverified` with `missing-build-witness` and `unwitnessed-class-root`; equal revision labels were not promoted to compiler freshness. Preparation included a baseline compile/test attempt (72 seconds) and current incremental focused compile/test/install (28 seconds). Snapshot capture took 6.75/7.13 seconds, impact 6.66 seconds, test-filtered impact 7.13 seconds. These single-run times exclude initial JDK/dependency downloads and are not a performance guarantee.

Five native Git regression tests additionally cover compiled rename mapping, deletion with historical callers, stale body edits/untracked files, per-call MCP rehashing, and actual javac input/output witness matching including null working-tree revision and explicit scope mismatch. Fifteen unrelated Windows full-suite failures were reproduced on the exact before commit (newline/path/symlink and CRLF fixture behavior). Linux CI remains the complete test/coverage/Android/plugin gate.
