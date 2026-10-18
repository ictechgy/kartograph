# Android layout field ownership evidence

This optional channel records the narrow relationship between captured main-component layout keys and exact retained, static, non-final integer R.layout fields. It does not yet enable XML file impact selection.

## Capture boundary

Only the existing trusted Gradle application snapshot task wires capture. It uses the same complete main-component resource provider as processResources, separately from the combined main/unit-test resource list used by retention. No new user flag is introduced. Ordinary CLI scans do not invoke trusted capture.

All declared main roots, including absent roots, must match the copied facts inventory and the unique same-scope processResources witness's byte-complete android-resources rows. Namespace options and the single witnessed R.jar output must match. The output must occur exactly once among the inputs actually indexed in that capture.

The capture adapter retains its trusted, quiescent build precondition and its documented caps. Descriptor-relative no-follow checks do not make hostile concurrent mutation safe or establish an atomic snapshot. Unsupported descriptor providers, external roots, observed symlinks/special files, limits, invalid XML, incomplete inventories and unavailable producer evidence leave the optional channel unavailable with a bounded reason. Ordinary snapshot behavior remains available where its existing checks pass. Existing compiler freshness checks still reject stale producer inputs and changed outputs.

If the optional facts/bindings exceed the existing snapshotMaxMiB cap, the task drops both records, adds a fixed snapshot-size-limit reason, restores the producer inventory-unverified limitation, and renders the ordinary snapshot under the same cap. An ordinary snapshot that still exceeds the cap fails as before. No size failure is treated as an empty resource inventory.

## Recorded structure

androidResources remains the original unverified facts channel. Its presence alone does not admit mapping.

The separately nullable androidResourceBindings version1 record contains:

- Exact scope and namespace.
- A digest of the complete canonical facts payload, including every root, definition, qualifier, include kind, ordinal and target.
- The exact producer identity and digest of its ordered input/output/receipt payload.
- Namespace options, complete main-root hashes and the selected output identity.
- Exactly one outcome for each distinct defined resource key. Each outcome is either the exact JVM field identity or an explicit unavailable reason.

The binder uses the same IndexedClasses result that supplied the graph. Selected-root indices, declaration membership, MEMBER ownership edges and precise STATIC/non-FINAL integer signatures must agree. A duplicate FQCN respects the existing classpath winner. A field selected from another output does not become a resource binding merely because another R.jar also declares it.

Namespace distinguishes resource keys. Gradle scope distinguishes captures even when namespaces, paths or local fact IDs coincide. A namespace is not a globally unique module identity. Aggregate snapshot merging continues to refuse resource-bearing snapshots.

## Compatibility and queries

Absent bindings in legacy or facts-only snapshots mean this capability is unavailable. Old readers may ignore and drop the optional channel on reserialization. Embedded clients must rebuild for the appended QuerySnapshot constructor field.

The codec validates recorded cross-field consistency only. It performs no live filesystem verification and authenticates neither producers nor saved JSON. Current freshness remains a separate operation.

Canonical snapshots reject dangling bound fields or lost required flags. Clients constructing a pruned graph view must explicitly remove the entire binding record and retain an unavailable diagnostic, rather than trim per-key outcomes. Harmless source-location enrichment preserves bindings.

The first real gate is the existing AGP8.7.3/Gradle8.10.2/KGP2.0.21 application fixture with non-final R fields, Java17 and SDK35. It explicitly registers a main resource directory that remains absent and requires its row in facts, bindings and producer inputs in both phases. The AGP SourceDirectories.Layered.all contract supplies registered source folders; the real fixture must confirm absent-root preservation for this supported toolchain. It checks four exact layout fields, caller/test witnesses, unrelated exclusions, literal include facts, unchanged code/test sources and XML-only stale→rebuild→matched behavior. XML impact remains explicitly unmapped at this stage.

Final/inlined IDs, configuration/overlay winners, custom-view links, resource IDs, navigation, data binding/view binding and Android inflation are not covered by this channel.
