# Incremental class parsing

Snapshot capture can reuse the parsed facts of unchanged classfiles and dependency
JAR headers. The cache is opt-in and local to the selected directory. Every capture still reads current
class bytes, selects the current ordered roots, and rebuilds global analysis,
retention, source locations, limitations and freshness evidence.

```sh
kartograph snapshot --classes build/classes/kotlin/main --project . \
  --index-cache build/kartograph/index-cache --compact --timings > snapshot.json
```

Relative cache paths resolve against `--project`. The option is accepted by
`snapshot`; saved `query` and `impact` continue to consume the resulting document.
Cache configuration and timing statistics are excluded from the snapshot's
semantic contents and provenance options.

For automatically configured JVM and Android snapshots:

```kotlin
kartograph {
    snapshotsEnabled.set(true)
    snapshotIndexCacheEnabled.set(true)
    // Optional: the default is build/kartograph/index-cache.
    snapshotIndexCacheDirectory.set(layout.buildDirectory.dir("kartograph/index-cache"))
}
```

The same opt-in is available as `-Pkartograph.indexCache=true`. `false` disables
reuse, and other values are rejected when the snapshot task evaluates the option.
Existing Kotlin toolchain and compiler-witness requirements still apply; see
[automatic capture](IMPACT.md#jvm-빌드에서-자동-캡처).

The snapshot task still executes on every request. This setting reuses class
parsing work within that execution; it does not reuse a prior snapshot output or
skip a compilation required by Gradle. The cache directory is task local state,
separate from the public snapshot and checkout-specific input bindings.

## Correctness and invalidation

Entries bind the exact class bytes to the cache schema and the parser's actual
implementation dependencies. Changing a class while preserving its size or
modification time invalidates that entry. Engine changes invalidate prior facts
even if the product version string is unchanged. Filesystem and JAR timestamps
are observed again when a cached entry is used.

Root additions, deletions and ordering are read again. Generated-root markers,
resource inputs, dependency hierarchy, overrides, runtime analysis and retention
are applied to the current inputs. A matching cache entry does not establish
that a source file was compiled successfully; compiler witnesses and
`verify-snapshot` retain that responsibility.

Dependency JARs are keyed from their current bytes. A cache miss is parsed from
that exact byte snapshot, and multi-release selection and current input ordering
are retained. Direct in-memory header capture is bounded by the smaller of 256 MiB
and one eighth of the JVM maximum heap (64 MiB with `-Xmx512m`). Larger JARs use
uncached parsing when an eligible captured spool is unavailable. Directory
headers and the running JDK's hierarchy expansion continue to be read directly.

Input fingerprints digest files with at most four workers; the combined digest is
assembled on the calling thread in input order, so the recorded values equal a
sequential computation and the before/after comparison is unchanged. Class and
dependency-header inputs are processed in ordered batches with at most
four workers. Global analysis starts after those jobs finish. During initial
header-cache population, a verified capture streams the JAR bytes it fingerprints
to owned temporary files, bounded to 128 MiB per JAR and 256 MiB in total. This
avoids retaining whole JARs in the heap. Larger inputs use the direct-read fallback.
Both captures still read all current inputs, and output is released only after
their provenance matches. Temporary files are released after use or capture
failure. The local population marker controls this optimization; it never
validates an input or replaces its content hash.

Corrupt or unsupported entries fall back to authoritative class parsing. Cache
write failures do not authorize a partial graph, and malformed original classes
still fail. Cache statistics distinguish parsing, hits, invalid entries and write
failures. Cache entries may contain source-derived symbols and constants; keep
them as local build data rather than uploading them as public report artifacts.

## Measuring the result

`--timings` writes fingerprint, parsing/cache, global-index and snapshot-render
measurements to stderr. Class-stage timings measure elapsed batch time; concurrent
worker durations are not added together. `indexDispatchNanos` covers external
dispatch enrichment, and `indexTotalNanos` includes that final index step.
`indexCacheHits` counts reused class parse facts and
`indexParsedClasses` counts actual class parsing. These counts describe class
inputs, not the number of declarations or proven runtime paths.
`hierarchyCacheHits` and `hierarchyParsedJars` report dependency JAR header reuse.
Unavailable engine/input cache states are counted separately.

For Gradle, `--info` includes the `kartograph index:` counts. Compare cache-disabled,
cold and warm captures on the same compiled inputs, and check snapshot equality
before interpreting timing differences. Source build time and complete CI wall
time must be recorded separately. The following final local JDK 17 measurements, after the instance-helper
and JSON output changes, used seven alternating full/cold/warm comparisons per workload, with identical snapshot bytes. The one-class change
measurements used seven separate comparisons and confirmed exactly one parse
with all unchanged classes reused.

| Frozen input | Warm/full median | Cold/full median | One-class change/full median |
|---|---:|---:|---:|
| kartograph 0.8.0 production classes (392 classes) | 0.786 | 0.993 | 0.859 |
| Now in Android (22 roots, 227 classpath JARs including SDK) | 0.841 | 1.060 | 0.835 |

These are process wall-time ratios on one local machine, including input
fingerprints, analysis and output. The final self one-class result missed the internal
15% reduction target: its unrounded ratio was 0.858569, or about 14.1% faster.
Two preceding final runs also missed that target (0.851411 and 0.851048); those failures
were retained. Earlier development measurements had passed, so the final result does
not establish a reliable 15% reduction for this workload. The warm-cache and cold-cache
targets passed on both inputs, and the one-class Android target passed. These results do not promise
the same reduction on another machine or in a complete CI build. Now in Android
was pinned at `12f80da6518e161ed16a06a68e71fb8a873576d6`.

After the parallel input fingerprint (0.10.1 development, three seven-run comparisons
against the same main baseline on one machine, unchanged runners and inputs), the
Now in Android one-class ratios were 0.781, 0.782 and 0.821 against baseline
0.840, 0.828 and 0.835, and its warm ratios 0.787, 0.772 and 0.756 against
0.827, 0.832 and 0.832. The kartograph one-class ratios stayed inside the
measurement band (0.851, 0.821, 0.838 against 0.859, 0.846, 0.838), so the
internal 15% target for that small input is still not established. One candidate run
missed the warm target (0.857) while every mode of that run was uniformly slower
under host load; it is retained with the passing runs. The investigation was
closed there: the small input's ratio is bounded by capture costs shared by both
modes (JVM start, class loading, JIT warm-up, rendering and the input
fingerprint, which the parallel digest reduced but did not remove), a C1-only
JIT experiment made the ratio worse, and reusing global analysis across captures
was not attempted because it would risk the cache-on/off equality contract for
an estimated upper bound of about 0.18 s (about 0.1 s for the part that closes
per class). Design record: `ONE-CLASS-CHANGE-DESIGN.md`.

Default-heap peak resident memory on the same Android input was about 765 MB for
full capture, 746 MB for cold cache population and 667 MB for warm capture.
All three modes also completed with `-Xmx512m` and produced the same snapshot.
Resident memory includes JVM memory outside the configured Java heap.
