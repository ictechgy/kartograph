# Corroborated overload source declarations

This product change adds independent `sourceDeclaration` anchors for a narrow
subset of same-name, same-arity Java/Kotlin method overloads. It does not recover
identifier columns from bytecode and does not replace the JVM graph source.

## Matching boundary

A whole overload group must have a one-to-one match between JVM argument
descriptors and plain source parameter types. Supported spellings are Java
primitives and `String` / `java.lang.String`, and nonnullable Kotlin primitives
and `String` with optional `kotlin.` qualification. Every member must also have
an existing compiler line within its matched declaration's header/body extent;
those extents must occupy disjoint lines. The source text supplies the identifier
UTF-16 offset, line, column and source hash. The original compiler location is
preserved, including its absent column.

Unknown types, duplicate descriptors, ambiguous or same-line extents, absent or
out-of-extent compiler lines, conflicting source paths, observed builtin-name
shadowing, imports that could hide a supported name, typealiases, generic
functions/owners, inherited owner types and unsupported lowerings remain
unmatched. Constructors and the existing unique-name/arity path are unchanged.
The checks do not claim whole-project name resolution: a separately compiled
Kotlin fixture with cross-file aliases swapping `Int` and `String` verifies that
the compiler-line check rejects a misleading descriptor permutation.

## Measured integration result, 2026-10-13

The measured product was integration base
`ab056981a016e1b4452de1071bcf209818526849` plus this five-file implementation/test
patch. The tested `SourceDeclarationLocations.kt` SHA-256 was
`bf9ce9dd84f16ce61179c74b95e9379e7245c370236dee2be39e53674067863b`.

- 29 focused tests passed: `SourceDeclarationLocationsTest` (21),
  `SourceDeclarationOverloadsTest` (6), `CompilerSourcePreservationTest` (2).
  `:cli:installDist` also passed.
- The separate embedded-PSI comparison proposal reran its authored two-file
  fixture through Kotlin Analysis API / Java PSI and the built product.
- The frozen eight-method `sourceDeclaration` check passed 8/8 exact anchors,
  versus the baseline's 4/8. It compared `origin=sourceHeader`, project-relative
  path, source SHA-256, `coordinateBasis=rawDecodedUtf16`, identifier offset,
  line and column.
- Added anchors were Kotlin `leaf(Int)` at offset 41, line 4, column 9 and
  `leaf(String)` at 79, 5, 9; Java `leaf(int)` at 61, 4, 23 and
  `leaf(String)` at 120, 5, 26.
- All eight bytecode locations were unchanged. The same four primary direct-call
  edges matched PSI exactly, with opposite-overload and unused-method negatives.

Fixture source SHA-256 values:

- `probe/PsiKotlin.kt`: `09e2da7b473e7661b7b374571b1fb80074821023747661db33a328770d4f8a8b`
- `probe/PsiJava.java`: `4084c63c440e76c2b27cab74fee27f32c0f2038540ef469a816a81a04ec6ea6c`

The separate [PSI experiment proposal (PR #161)](https://github.com/ictechgy/kartograph/pull/161),
head `9960c0edc0c2d9354c99961c0471e72096785fa5`, retains the comparator, exact
source-header checker, raw graphs and execution receipts. This product patch does
not duplicate that framework or claim general precision, completeness or
performance from the small fixture.

## Main-based payload and verification

This payload is based on main
`2cf3cb6058111906071084f403d73f3574db3c96`. It applies only the overload change and
its four test/fixture files. Main's existing compiler-call-position return is
preserved; the integration-only compiler-source-facts API is not imported.
The tested main-based `SourceDeclarationLocations.kt` SHA-256 is
`fc33288d48666b4afe727591f8ed8f28973b5f14871b5838a5512d5126bee826`.

On 2026-10-13, full `:index:test` and `:cli:installDist` passed using JDK 17 and
Gradle 9.7.1. XML records 715 tests in 72 suites: 711 passed, zero failures/errors,
and four pre-existing optional fixtures skipped. Two Kotlin inline-relay tests
require `KARTOGRAPH_KOTLIN_COMPILER_JAR`; the packaged javac collector test
requires `KARTOGRAPH_COLLECTOR_JAR`; the packaged Kotlin collector test requires
its pinned collector, Kotlin 2.4.10 classpath and JDK 17 environment inputs.

The actual main-based CLI then ran against the retained compiled two-file fixture
and PSI oracle. It exited zero, passed all eight exact source-header anchors and
the same four primary-edge comparisons, and preserved the entire edge array and
all node fields other than `sourceDeclaration`. Source, compiled-fixture and
installed-product hashes were unchanged across the product run. This is a fresh
main-based product execution against the retained oracle, distinct from the full
integration-based PSI rerun above.

Module/CLI verification command (configure the selected JDK/Gradle as needed):

```sh
./gradlew --no-daemon :index:test :cli:installDist
```

The broader compiler corpus, repository-wide tests/coverage and remote product CI
remain separate gates. These module and fixture results are not a full CI pass.
