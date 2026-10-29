# Explicit analyzed JDK headers

`ClassFileIndexer(targetJdkHome = home, cache = cache)` and
`ClassHierarchyIndexer(targetJdkHome = home, cache = cache)` bind platform header
lookup to an existing JDK 9+ installation containing `release` and `jmods`.
The host JVM and analyzed SDK are separate inputs. Existing constructors keep
host-runtime lookup and existing no-classpath behavior. This is an index API;
CLI and Gradle-plugin defaults do not acquire an implicit SDK setting.

Explicit mode reads requested platform class headers and their ancestors from
that SDK's JMODs. Class-file indexing also requests platform invocation owners,
even without a dependency classpath, so declaration consumers can inspect their
headers. It does not import platform bodies or platform graph nodes, extract
archives, download a JDK, or fall back to host resources. Missing requested
platform headers, invalid archives and class-name/entry mismatches fail indexing.
Unreferenced class bodies are not parsed. Explicit mode reserves `java/*`, `jdk/*`
and exact classes/packages in the selected SDK inventory. Dependency additions or
overrides in that boundary fail closed, including cached headers and missing
required SDK classes offered by a dependency. Module patching is unsupported.
Third-party `javax` packages absent from the SDK inventory remain ordinary
classpath inputs; other non-platform duplicate ordering is unchanged.
Multi-release dependency JARs select entries using the analyzed
SDK's feature version instead of the host feature version.

`targetJdkFingerprint` is non-null after a successful explicit indexing operation
and resets before the next operation. It binds sorted relative JMOD names, every
JMOD byte and the complete SDK release metadata, without embedding the installation
path. Each operation rediscovers the SDK and verifies its content again before
returning, including when reusing an indexer or cache. Raw project class facts
remain SDK-independent; dependency header cache namespaces include the SDK content
identity. No assembled platform hierarchy is reused across operations.

Discovery stores bounded entry locations, then opens archives only during an
operation. Limits apply before application-level entry-map growth and class-byte
copies: 128 modules, 256 MiB/module, 1 GiB total module bytes, 500,000 archive
entries, 1,024 characters/entry name, 250,000 indexed class names, 4 MiB/requested
class, and 1 MiB/release metadata. JMOD magic, regular-file inputs, safe relative
archive paths, duplicate class names within/across modules and requested-entry
length/CRC are checked. Each module may have its own `module-info.class`.
The JVM ZIP reader may allocate its central directory internally; module-file
limits bound that exposure too. A symlink used as the JDK home is allowed, while
symlink JMOD files or `jmods` directories are rejected.

Discovery, full SDK fingerprinting, header lookup/parsing and final content
verification are all charged to `hierarchyNanos` and `totalNanos`. A comparison
must separately record the host executable/version, exact compiler and PSI SDK
homes/releases/JMOD hashes, and any shared artifact preparation. A bytecode
`--release` or Kotlin `-jvm-target` flag alone does not establish the SDK used by
a later analysis. This feature alone makes no performance or superiority claim.

## Regression execution

`TargetJdkHeadersTest` exercises synthetic archive boundaries, lazy parsing,
explicit no-host-fallback, portable/content-sensitive identity, replacement,
shared-cache separation and multi-release dependency headers. The existing
`ClassHierarchyIndexerTest` and `HierarchyIndexCacheTest` retain host-default
coverage.

`TargetJdkIntegrationTest` requires `KARTOGRAPH_JDK17_HOME` and
`KARTOGRAPH_JDK21_HOME` pointing to real installed JDKs. It compiles one Java
fixture with the actual JDK 17 `javac`, then holds project bytes and cache directory
constant across 17/21/17 cold and warm indexing. `java.util.List` must directly
extend `Collection` in 17 and `SequencedCollection` in 21. Optionally set
`KARTOGRAPH_EXPECT_HOST_FEATURE=25` while running the test JVM on JBR 25 to assert
the host/SDK separation directly. Missing environment inputs skip this dedicated
integration test; synthetic checks do not substitute for its successful execution.
