# Captured Android layout impact

The supported initial path is an application snapshot captured by the trusted Gradle adapter on the validated AGP8.7.3 / Gradle8.10.2 non-final R fixture. The query uses saved evidence and does not verify current files automatically.

## Selection and evidence

An exact portable layout XML path selects its captured namespace/type/name key and the exact retained non-final static integer R.layout field admitted for that snapshot. All captured qualifier definitions remain candidates. No filename alone supplies a namespace or module, and no device/overlay winner is inferred.

Recorded literal includes are followed backwards: changing child.xml can select a parent layout's field and therefore that field's bytecode callers and tests. Include cycles terminate. File-to-definition, include and key-to-field evidence appears in resourceSelections, separately from actual bytecode paths. selectedByRevision reports the authoritative base/current seeds. Source-edit reports use those same seeds.

The resource model's coverage is partial even when selection status is found. Dynamic, external and unsupported include sites can hide other parents; counted limitations preserve this gap. Custom views, IDs, navigation, data/view binding, final/inlined IDs and runtime inflation are outside this initial path. Test candidates do not authorize skipping tests or establish runtime behavior.

## Revisions, missing evidence and moves

Each revision validates its own complete saved resource context against its graph. A base-only binding cannot seed an identical JVM field in an unavailable current snapshot. Ordinary symbol/source/retention selection retains its existing cross-revision behavior.

Deletion uses the base's definitions and paths. Rename requests should include both old and new paths. An admitted root with no matching definition records absence. A missing legacy channel, absent binding, outside-root path or unavailable field remains explicit unresolved coverage, including when the other revision has valid candidates. Mixed incomplete selection returns the existing exit64 contract. Invalid saved consistency remains a tool/input failure.

## Bounds

Per revision the resource query accepts at most256 distinct requested paths,10000 keys,10000 complete qualifier definitions and20000 literal include sites. These fixed limits are reported; there are no new CLI flags. Exceeding a resource bound refuses that revision's entire resource seed set while preserving independent ordinary selections. It never truncates qualifier groups and calls the remainder complete.

Graph traversal retains its separate existing visit/depth/path/page budgets. The source-edit wrapper retains its16MiB serialization cap, using shared lazy resource views. Historical admission and current build freshness remain separate.

## Regression evidence

The real hosted fixture must prove XML-only child byte changes with unchanged code/test source hashes, old-snapshot stale status, rebuilt matched status, exact resource fields, independent field/call/test paths, parent include selection and unrelated exclusions. A channel-stripped saved snapshot must refuse resource selection instead of guessing from existing R fields.

Synthetic unit/CLI graphs separately exercise same-named keys in different namespaces/scopes, qualifier multiplicity, include cycles, unavailable fields, deletion/rename, and a base-only binding where current contains the same field. These tests do not claim Android inflation execution.

## 0.25.0 capture and migration

Version 0.25.0 is release preparation until its publication and package gates pass. Use CLI, plugin and
direct-library modules from the same reviewed version/source revision. Rebuild embedded JVM clients:
the appended QuerySnapshot and resource-impact report fields change public constructor/copy shapes.
GraphNode JVM modifier metadata is reused from the existing model, not added by this release.
Defaulted source parameters do not guarantee binary compatibility. Older readers can drop optional
resource channels on reserialization; retain originals and recapture lost evidence.

Rebuild and recapture both baseline and current revisions with matching tool versions, module scope and
capture settings. Do not edit a snapshot version label or infer missing channels from a matching JVM R field.
Keep resource-bearing snapshots per module: aggregate/merge refuses even captured-empty resource facts.
Namespace identifies resource keys within a capture, not a globally unique module. Duplicate JVM class names
follow the indexer's selected classpath owner; an output without that selected declaration cannot acquire its binding.

The demonstrated application combination is AGP8.7.3, Gradle8.10.2, KGP2.0.21, JDK17 and SDK35 with
non-final R IDs. Keep that existing build's trust boundary and quiescent inputs. Enable the existing snapshot task:

```kotlin
kartograph {
    snapshotsEnabled.set(true)
    includeSourcePaths.set(true)
}
```

With the matching plugin applied and the application's existing toolchain configured:

```sh
./gradlew kartographSnapshotDebug
kartograph verify-snapshot --project . \
  --graph-file build/reports/kartograph/debug-snapshot.json \
  --input-bindings build/kartograph/debug-input-bindings.json
kartograph impact --file src/main/res/layout/child.xml \
  --graph-file build/reports/kartograph/debug-snapshot.json --all
```

There is no new resource capture CLI flag. The application task uses complete main resource providers;
unit-test graph declarations are included by the existing default, but snapshot capture does not execute tests.
The input-bindings file contains checkout-specific paths and should not be published. Require the separate
verification result appropriate to the current build before treating it as fresh. The saved impact query itself
does not repeat that verification.

For a two-revision query, provide the captured baseline with --base-graph. For a rename provide both old and
new --file paths. Unsupported or absent evidence on either side remains visible even if the other side selects
code. Do not treat partial resource coverage, an empty page or a selected test list as permission to skip tests.
