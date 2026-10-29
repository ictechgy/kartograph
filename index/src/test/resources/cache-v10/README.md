# Frozen ClassFacts v10 attribute records

These fixtures were emitted by the pre-SafeArgs codec at source commit
`33f8ce1b15af5be6dc01573e1209017cb1999f1d` (local equivalent
`9b9082bdd6692a7f32b896e30e01defed9f583a4`), using JDK 17 and the retained
compiled distribution. Its core JAR SHA-256 is
`76a7967aa4e0e5d0a196ffc372f5d0400b272ca60826893ee0635b3004311d02`;
index JAR SHA-256 is
`18fae47ec45a39844ecbdd97d7478e5a5164b3341d177bdc2b55fd4425f7e639`.

- `old-attributes.cix` is a codec-only synthetic field containing all ten
  pre-existing `NodeAttribute` values. It is not a claim that a compiler produces
  that combination. Engine and content labels are `legacy-v10-attributes` and
  `fixture`. SHA-256:
  `632552936a307e1b15200a74bdd6fe3bcdf02bb00984cc4afe50a51afc929fbf`.
- `jvm-modifier.cix` is the original cache entry for the project's actual
  Kotlin 2.4.20 compiled `JvmModifier` enum. Its class SHA-256 is
  `34079b5214ef4882709da7d85db4dfa879de647dd286fe4f576531a714ae908d`.
  Cache SHA-256:
  `818f3b6a7762d90c801e3b0d53cb09b59c15a28a3d7f8b949c1b77fbf66f9681`.

The tests use the recorded engine labels solely to isolate the binary schema.
Production cache lookup separately binds the current implementation artifacts;
this is not permission to reuse a cache across different engines. New attributes
must not renumber existing v10 meanings. The new attribute also has a separate
current-encoder round-trip test. The full raw payload comparison remains strict.
