# DOT escaping: recovered actual-CLI pilot

## Change and scope

`DotGraphRenderer.escape` now chooses the `StringBuilder.append(String)` or
`append(Char)` overload within each branch. The previous mixed String/Char
expression selected a common object-valued path. The five existing escapes,
iteration order, malformed UTF-16 handling and public API remain unchanged.
No cache, graph policy or comparator change is included.

The standalone patch targets main `34c775e70b4d8cd350f8677991df0b63c6046ab1`.
Its original renderer is byte-identical to the renderer in integration commit
`ab056981a016e1b4452de1071bcf209818526849` used below. The full integration
stack is not part of this patch.

## Actual verification (2026-10-13)

A fresh cloud checkout built both the integration baseline and the integration
baseline plus this renderer change with Gradle 9.7.1, Kotlin 2.4.20 and Temurin
JDK 17.0.20.1+1. The candidate ran the two existing renderer tests and seven new
parity tests: **9 passed, 0 failed, 0 errors, 0 skipped**. The new tests preserve
an independent copy of the old renderer, compare UTF-16 strings before UTF-8
bytes, and cover every code unit, malformed surrogates, empty/mixed strings,
fixed-seed input and representative full graphs.

These local tests and measurements used the integration-derived candidate.
They are not a claim that the main-based candidate has passed its full CI.
The normal tests, coverage and CI gates still apply.

## Startup-inclusive CLI measurement

Both actual installed CLIs received identical arguments:
`graph --classes <core> --classes <index> --classes <analysis> --classes <export>
--classes <cli> --classes <gradle-plugin> --format dot`.
All six class roots came from the unchanged integration baseline. Compilation,
dependency downloads and output checking were outside each timer. Each timer
included process startup, graph construction, rendering, output writes and exit
observation through a 1 ms poll. Both arms used `-Xmx1g`, UTF-8 and the same JDK,
working directory and environment on Linux 6.18.44 x86-64 (9 reported CPUs).
Input and installed-artifact hashes matched before and after the schedule.

One baseline and one candidate warmup were excluded. The measured order was
baseline/candidate, candidate/baseline, baseline/candidate, with no overlapping
build or test workload. These were repeated processes, not asserted cold-cache
runs. Every sample is retained here:

| Pair | Order | Baseline seconds | Candidate seconds |
|---|---|---:|---:|
| 1 | baseline, candidate | 2.880336287 | 2.576614863 |
| 2 | candidate, baseline | 2.823109434 | 2.722972645 |
| 3 | baseline, candidate | 2.911904204 | 2.854519844 |
| Median | | 2.880336287 | 2.722972645 |

The candidate median was **5.46% lower in this small descriptive pilot**.
Three pairs do not establish statistical dominance, other-command improvement,
or a general speed guarantee. No main-baseline timing comparison was run.

All eight processes, including warmups, exited 0 and produced the exact same
21,959,359-byte DOT document: **17,053 nodes and 85,279 edges**. DOT syntax,
unique node IDs and edge endpoints were checked as well as full byte parity.
The stdout SHA-256 was
`93f94f1b3943040260bf9ad4205f2f900abde6904e8708ba32cee1cc83730b0e`.

The same 137-byte stderr warning remained in every run: 1,462 call sites exceeded
256 dispatch targets, so all candidates for those sites were omitted. Its SHA-256
was `c1c92fabf12651582a3d2c0fa5af86f237734ab3ea64e4f712b1c0094c19d605`.
Output parity does not establish complete call-graph accuracy. This pilot does
not replace the prior integration CI timing failures or relax any budget.
