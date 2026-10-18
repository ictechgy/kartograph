# Bounded Android resource-impact experiment

This experiment establishes the input evidence for a future resource-identity mapping.
It does not add XML resource mapping to the product.

## Scope

The existing `agp-minimum` hosted job supplies JDK 17, Gradle 8.10.2 and Android platform 35.
`Scripts/verify-agp-8-resource-impact.py` copies the tracked AGP 8.7.3/KGP 2.0.21 application
fixture into an owned temporary sibling directory. It excludes build/cache/local-properties
outputs and leaves the original compatibility/dead-code fixture unchanged. The copied build
uses the existing Google/Maven Central repositories and adds pinned JUnit 4.13.2 from Maven Central.
Non-final and non-transitive R are explicit experiment inputs.

The fixture contains framework-only screen/child/unrelated layouts and a parent layout with a
literal include of child. Java accessors read the exact generated R.layout fields. Four JUnit
methods call those accessors. The tests execute ordinary JVM methods, not layout inflation.

## Required evidence

Both before and after an XML-only child text change must have:
- A successful actual AGP build, successful unit-test execution with all four expected methods,
  and a snapshot whose captured inputs verify as matched.
- The exact generated R field selected by impact, with bytecode FIELD_ACCESS from the accessor
  and CALL then FIELD_ACCESS from the compiled test.
- Explicit unmappedFile output for each XML file. An empty or missing report is rejected.
- Identical code/test source hashes and changed child XML bytes, with other XML bytes unchanged.

The old snapshot must become stale after the XML edit and before rebuilding. The experiment
does not compare a stale snapshot as if current. No inferred resource relationship is inserted.
The parent include is recorded as an input fact, while include-to-code traversal remains absent.

Only the bounded result.json is uploaded. Raw logs and checkout-specific input bindings are
not public artifacts. The result records the source revision, CLI library hashes, snapshot hashes,
source/XML hashes, exact graph witnesses, executed test inventory and explicit limitations.

## Interpretation and next slice

`evidenceEstablishedWithKnownMappingGap` means the compiler/test prerequisites were observed
and XML file selection was explicitly unmapped. It is not passing resource-impact coverage.
When a supported mapping is implemented, deliberately replace the unmapped expectation with
positive resource-to-code/test witnesses and negative unrelated-resource checks.
Do not weaken the prerequisite evidence to get a green result.

Final/inlined resource IDs, other AGP versions, overlays, navigation, data/view binding and
Android UI behavior are outside this experiment. Missing support remains unknown rather than
blocking this one-toolchain measurement or claiming universal coverage.

The subprocess budget is ten minutes per command; the hosted experiment step has a 25-minute
limit within the existing AGP job's explicit 45-minute budget. Timeouts fail; there is no skip path.
