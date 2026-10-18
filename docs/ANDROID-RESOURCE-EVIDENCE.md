# Android resource observations

The optional `androidResources` query-snapshot extension records unverified layout observations. Its presence never admits XML-to-R mapping, marks provider inventory complete, or adds impact seeds. Existing snapshots omit it; an explicitly captured empty channel is distinct. Explicit JSON null is invalid.

Version 1 stores:
- namespace/type/name resource keys, independent of XML configuration definitions;
- declared roots with `android-resources` fingerprints;
- project-relative layout definitions with root identity, relative path, qualifier, and immutable XML SHA-256;
- distinct include sites identified by source definition and authored traversal ordinal. Literal targets name resource keys, including references whose target is absent. Dynamic, external-namespace, and unsupported observations remain explicit.

All qualifier definitions are preserved. No overlay winner, device configuration, runtime inflation, generated binding, custom-view resolution, or inlined resource access is inferred. External roots can be recorded, but external layout-file definitions are currently unsupported. Root rows do not prove that an AGP provider supplied its complete inventory or that an output belongs to those inputs.

The only admission value is `unverified`. The trusted application adapter separately establishes complete main-provider inventory, producer-bound bytes and exact selected output ownership in [androidResourceBindings](ANDROID-RESOURCE-BINDINGS.md). Nonfinal R-field bindings remain absent from this facts record itself; its presence alone cannot seed impact.

The strict codec rejects unknown/missing fields, altered identities, noncanonical row order, duplicate root/file/site identities, invalid references, mismatched scopes, and aggregate snapshots. Snapshot merge refuses even captured-empty channels rather than silently losing observations. Plain and compact encodings preserve the same facts independently of graph views.

Resource observations are capped at 512 roots, 50,000 definitions, 100,000 include sites, and 16 MiB aggregate authored text. Individual project paths are at most 4,096 characters, namespaces 255, names 128, and qualifiers 256. The existing overall snapshot byte limit also applies. These are hard admission bounds, with no new CLI options.

The model/codec layer itself reads no XML or filesystem paths. The separate byte parser is described below; the separate [trusted capture adapter](ANDROID-RESOURCE-CAPTURE.md) refuses observed unsafe paths and symlinks. Dynamic class names remain data.

Compatibility: older readers may ignore this optional channel and drop it when reserializing. Keep the original snapshot when using older tools. Embedded JVM API clients must rebuild against the appended QuerySnapshot constructor field; this addition is source-defaulted, not a binary-compatibility promise.

## Bounded byte parser

AndroidLayoutXmlParser copies the supplied byte array, computes its SHA-256, and parses that same owned copy. It opens no paths and does not resolve include targets. The caller-supplied root ID remains unverified; parsing cannot establish a filesystem/root/producer association.

Each call accepts at most 2 MiB XML, depth 128, 100,000 elements, 10,000 total attributes plus namespace declarations, and 10,000 includes. Exact boundaries are tested. Excess input throws, with no truncated result. DTD and entity-reference events are prohibited; external resolution is disabled and a fail-closed resolver is retained. Comments and XML encoding remain in the source hash. Literal include cycles remain finite facts.

The trusted filesystem adapter bounds aggregate bytes, reads descriptor-relative with no-follow semantics where supported (or explicitly reports unavailable), and derives root hashes from retained XML bytes plus all other member hashes. Provider inventory/output ownership is checked separately by the binding adapter. Before/after fingerprints alone must not be described as protection against directory-swap races.

The existing Linux JDK 17/21 compatibility jobs run an owned-temporary-directory capability experiment. It requires a secure directory descriptor, exact descriptor-relative bytes, and refusal of a symlink contained entirely in that temporary fixture. Its bounded JSON log reports those three observations and the Java feature version. macOS/Windows availability is unmeasured; this probe alone does not establish producer admission, atomic capture or runtime completeness.
