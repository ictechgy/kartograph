# Phase 5 architecture analysis validation

Measured on 2026-09-04 from all six production JVM module class roots, including the Gradle plugin.
The input contained 2,246 nodes and 8,942 normalized graph edges.

| Query | Result | Wall time |
|---|---:|---:|
| `dead --keep-rules .kartograph-self.pro --strict` | 0 findings | 0.26 s |
| `cycles` on module/package units | 0 findings | 0.23 s |
| `rules --config .kartograph.yml` | 0 findings, 0 unassigned | 0.29 s |
| `metrics` on module/package units | 6 rows | 0.25 s |

All four include class-file indexing and remain below the PRD's 10 second public-project target.
The timings are local measurements, not a guarantee for other machines. The rule configuration
encodes the dependency directions in `AGENTS.md`; a rule violation reports the actual usage edge,
its kind and weight, and the source location when bytecode preserved one.

Cycle analysis first collapses declarations to their explicit module or JVM package. This avoids
presenting class/member implementation mechanics as architecture cycles. Martin coupling uses the
same `EdgeKind.impliesUsage` predicate as reachability and query, deduplicated by source/target unit.
