---
name: kartograph
description: Investigate Kotlin and Android dependency-graph findings with kartograph before changing allegedly unused declarations or cross-platform bridge handlers.
---

# kartograph

Use `kartograph query <symbol> --classes <compiled-root> --project <root>` before changing a reported
declaration. Read `usedBy`, `dependsOn`, `members`, `reachability`, `truncated`, and every
`limitations` entry together.

An `unreachable` state means only that no configured retention root reaches the declaration in the
compiled graph. It is evidence for human review and never deletion approval. Verify runtime
registration, reflection, JNI, resources, build variants, generated code, and callers in other
languages before making a change. A public API is not a retention root: public visibility alone
neither proves a caller nor proves that no external caller exists.

If `truncated` is true, raise `--limit` or narrow the query before drawing a conclusion. If
`suppressedByBaseline` is true, treat it as an existing team decision rather than a new finding.
When the query is `ambiguous`, repeat it with a candidate `usr`; do not choose by name.

After reviewing the evidence, run the affected application's tests and build, then make the
smallest reviewable change. Rebuild before re-running kartograph so `index-staleness` is absent.
R8 removing code is not proof that code is unused; reflection can keep behavior alive outside this
graph.

For Flutter MethodChannel and React Native NativeModule boundaries, export
`kartograph bridges --project <root>` and join it with the other platform through isthmus. Dynamic
or unattributed bridge facts are limitations, not proof that the other side is absent.
