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
