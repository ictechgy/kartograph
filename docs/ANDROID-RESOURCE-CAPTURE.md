# Trusted Android resource capture adapter

AndroidResourceCapture is an isolated, optional adapter for callers already operating inside an explicitly trusted, quiescent build-execution boundary. It does not run from the ordinary scanner, CLI, Gradle plugin, snapshot, or query path. Existing query behavior is unchanged.

The default context is UNTRUSTED_SCAN and immediately returns Unavailable(UNTRUSTED_CONTEXT), before filesystem access. A caller must deliberately supply TRUSTED_QUIESCENT_BUILD after independently establishing that the project and its declared inputs are trusted and not being modified. Setting the enum does not establish trust, acquire a lock, or make an untrusted project safe. “Build” describes that execution context; resource roots can be declarations such as src/main/res, not only paths beneath a directory named build.

## Input and result

The API takes an absolute, normalized project Path, a scope and namespace, and an explicit list of portable project-relative resource-root strings. It does not infer roots from source paths, generated output locations, filenames, or AGP internals. Absolute/external roots, the reserved external identity prefix, noncanonical paths, duplicate roots, and overlapping roots are unavailable. External layout definitions remain unsupported by the existing model. No external identity is invented.

The result is either Captured(AndroidResourceEvidence) or Unavailable with a bounded reason enum. Only successful, explicitly requested capture can produce a captured-empty channel. An unavailable result contains no roots, definitions, includes, exception text, or partial evidence. Input/parser/model rejection, unsupported facilities, an observed unsafe entry, I/O failure, interruption, changed observations, and budget exhaustion all withhold the channel. Interruption is preserved. Limits are validated when their configuration is constructed.

A successful channel always has admission unverified. Capture does not establish complete AGP provider inventory, a successful producer invocation, producer/output ownership, resource-to-R bindings, impact seeds, runtime inflation, an overlay winner, or device configuration selection.

## Descriptor-relative observations

Each of two observations starts with a directory stream on the filesystem root and requires SecureDirectoryStream. The adapter opens every absolute-project segment, declared-root segment, and traversed member directory descriptor-relative with NOFOLLOW_LINKS. It checks attributes descriptor-relative without following links and reads regular files through descriptor-relative byte channels with NOFOLLOW_LINKS. It never falls back to opening full member paths. Observed symlinks, dangling links, special files, non-directory root ancestors, and nonportable member names are rejected.

Both observations enumerate the full declared trees, including empty directories, and hash every regular file, including binary files and non-layout XML. The comparison contains sorted relative names, entry types, file SHA-256 values, and each root's observed missing/present state. The two observations must match in full. An absent root becoming an empty directory is a change even though their producer digests are equal.

For a successful capture, each android-resources root digest follows the existing ContentFingerprint.hashInput contract exactly: the length-prefixed UTF-8 string "directory", followed by each regular member's relative name and byte SHA-256. Members are ordered using Kotlin/Java String natural order (UTF-16), including non-BMP names; directories do not contribute to this legacy digest. Missing and empty roots both use ContentFingerprint.values(listOf("directory")). Directory membership still participates in the separate two-observation comparison.

Only direct layout/name.xml and layout-qualifier/name.xml files supply layout definitions. Noncanonical names or qualifiers in those XML positions reject the channel. Nested files and other resource kinds still contribute their complete bytes to the root digest; they are not inferred layout definitions. The first observation retains the layout XML byte arrays while hashing those same bytes. After the second observation agrees, AndroidLayoutXmlParser copies and hashes each retained array and parses that same owned copy. The adapter also checks the parser's source digest against the retained file digest. Parsing never reopens the source file.

## Hard budgets

AndroidResourceCaptureLimits exposes positive, lower-only limits with these defaults and ceilings:

- 512 declared roots
- 100,000 observed entries across both observations, including each existing resource root, every member directory, and every regular file
- Depth 128 below each declared resource root; the root itself is depth zero
- 256 MiB total bytes streamed across both observations and all regular files
- 64 MiB retained layout XML bytes across the first observation
- 2 MiB per layout XML, also bounded by AndroidLayoutXmlParser.MAX_XML_BYTES

Absolute-project and declared-root descriptor chains also have fixed 128-segment bounds. Portable captured member paths are limited to 4,096 characters. Existing model bounds (50,000 definitions, 100,000 includes, 16 MiB authored text) and parser bounds remain enforced. The entry ceiling counts both passes, so it cannot be read as permission for 100,000 distinct files.

The adapter rejects an already observed file size exceeding its remaining byte budgets before opening a byte channel. Each read is restricted to the remaining budget. At an exact exhausted byte boundary it checks position and size on the same open channel, rather than consuming an additional sentinel byte. It never truncates a file or emits a successful prefix. Budget accounting is for streamed/retained input bytes, not a claim about total JVM heap usage, parser allocation, elapsed time, or all operating-system activity.

## Trust and race limitations

These are two sequential observations, not an atomic snapshot or a race-proof defense. A malicious or concurrent writer can change and restore bytes or swap entries between checks. SecureDirectoryStream reduces pathname re-resolution and refuses observed/no-follow symlinks, but does not prove that every read came from a single filesystem instant.

In particular, Java cannot guarantee a nonblocking open if a checked regular file is replaced with a FIFO before the descriptor-relative open. This adapter therefore requires the existing trusted, quiescent build boundary. It makes no hostile-filesystem time-bound claim and creates no timeout worker threads or per-file subprocesses. Do not invoke it for ordinary untrusted scanning. Tests never manufacture a regular-to-FIFO race.

## Validation status and gates

The candidate is source-prepared only. Neither compilation, focused tests, aggregate coverage, nor runtime capability has been established for this capture adapter. The existing Linux JDK 17/21 SecureDirectoryStream probe is still a required runtime gate; its runtime result is not established here. macOS and Windows capability remain unknown. A declared API or test source is not execution evidence.

The focused suite compares real directory digests with ContentFingerprint.hashInput for missing, empty, nested, binary, XML and non-BMP ordering cases. Deterministic internal hooks test missing-to-present, directory membership and byte changes, and prove parsing uses retained bytes even after source replacement. Other controls cover every relevant symlink position, unsupported ZIP providers, root/ancestor regular files, malformed XML, include overflow, interruption, and exact lowered-budget boundaries plus hard-cap configuration validation. Linux requires descriptor support and a real Unix-domain socket rejection test; that socket test is explicitly Linux-only. Other platforms report an explicit JUnit capability skip when SecureDirectoryStream is unavailable, not a passing capability claim. The existing parser suite tests its full 2 MiB boundary.

Before any integration/admission claim, run the focused index tests, the existing Linux 17/21 capability probe, and the repository's aggregate test/coverage/regression gates on the exact reviewed commit. Provider admission, R-field binding and impact mapping require separate reviewed work. See [Android resource observations](ANDROID-RESOURCE-EVIDENCE.md) for the unchanged core/codec/parser contracts.
