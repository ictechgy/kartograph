# Actual lowered-call comparison result

The one actual run completed on 2026-10-13 with all ten stages returning 0. The original source-only README, RUN-PLAN and fixture manifest are preserved as the authored pre-execution oracle; this file records the later outcome.

- Product: clean Kartograph main `254c8ad40e08de6fa899a9ffd8bd3c606b8aad1b`.
- Adapter: unchanged published embedded PSI adapter, Kotlin Analysis API plus Java PSI. Runtime remains pinned to the original 18 JARs.
- Source qualification: all 12 exact authored rows match with multiplicity, including 10 internal targets and two explicitly target-null external SAM boundaries.
- Product qualification: all 14 declared methods exist. Five direct pairs are preserved. The other five source sites have the predicted inline erasure, default-bridge redirect, default-expression relocation or lambda-body relocation, corroborated by actual javap and raw graph data.
- The existing Kotlin default bridge points to the correct target. The unused method has no incoming raw CALL.
- Both lambda handle edges contain no callSiteLines. The actual generated-body calls retain lines 10 and 12. Kotlin's compiler-generated body is PRIVATE|STATIC|FINAL without ACC_SYNTHETIC; Java's includes ACC_SYNTHETIC. Generated spelling or flags alone were not used to infer body identity.
- Source, adapter/runtime, installed product and compiled fixture hashes are equal at both endpoints. Every stage's original output and exit is retained under run-01.

This small fixture found no resolution defect. Expected lowering is not an accuracy failure. The comparison does not establish callback execution, whole-project completeness, performance ranking or general source-coordinate fidelity. In particular, inline debug line/SMAP interpretation needs its own field-contract analysis before a defect can be claimed.

The original fixture manifest SHA-256 is `ffef91dd0974160fa4c360e4453cd43965250b48dd48e8b99b9cfe7535c6dc1e`; executed implementation manifest is `0116440c3ca99b0301900928db55f83a8b1ed444de973b7c3b9d233070370e23`.
