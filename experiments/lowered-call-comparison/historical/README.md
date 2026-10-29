# Tiny lowered-call comparison: source-only proposal

Not executed. This directory contains 26 authored source lines in exactly one Kotlin file and one Java file. There are 14 explicit methods, ten fixture-internal source method-call sites, two external SAM-invocation boundary sites, and no explicit constructor expressions. No production source has been changed.

The source-call target expectations are frozen before compilation in `source-call-expectations.json`. `fixture-manifest.json` seals the source, expectation, proposal and runtime-pin bytes. The published semantic adapter is reused unchanged from main `254c8ad40e08de6fa899a9ffd8bd3c606b8aad1b`, at `experiments/embedded-psi-comparison/semantic/PsiResolvedCalls.kt`; its source hash is also sealed. The established run-02 adapter/runtime is a reusable execution input, not evidence that this new fixture has run.

## Authored source-call targets

In the following list, K is `lowered.LoweredKotlin` and J is `lowered.LoweredJava`:

1. K.inlineStep -> J.inlineLeaf: the body of the inline declaration.
2. K.withDefault -> J.defaultSeed: the default-value expression, attributed lexically to K.withDefault.
3. K.kotlinInline -> K.inlineStep: the Kotlin call that can disappear during inlining.
4. K.kotlinDefault -> K.withDefault: the omitted-argument call that can invoke a default bridge in bytecode.
5. K.kotlinToJavaLambda -> J.acceptKotlinLambda: Kotlin passes a SAM lambda to Java.
6. K.kotlinToJavaLambda -> J.lambdaLeaf: a direct target inside that lambda; the enclosing named function is the adapter's lexical caller.
7. J.javaInline -> K.inlineStep: Java-call control for the same Kotlin inline declaration.
8. J.javaExplicitDefault -> K.withDefault: explicit-argument Java-call control for the same Kotlin defaulted declaration.
9. J.javaToKotlinLambda -> K.acceptJavaLambda: Java passes a SAM lambda to Kotlin.
10. J.javaToKotlinLambda -> J.lambdaLeaf: a direct target inside that lambda; the enclosing Java method is the adapter's lexical caller.

The two `operation.applyAsInt(7)` sites are external boundaries. The unchanged adapter should report `resolved=true` with `target=null` because it only exports target anchors inside the fixture root. This does not identify the exact external declaration, so those rows cannot qualify an exact-target comparison. Their source coordinates and enclosing callers are still frozen. An unresolved boundary is not accepted as resolved. Implicit compiler constructors are outside the source-call gate.

K.unused is an explicit negative control: its declaration must exist, but no authored source call or observed raw CALL may target it. Additional source-target negatives prevent the inlined body, default expression, or lambda callback from being substituted for the selected source callee.

## Lowering distinctions to observe

`lowering-expectations.json` records hypotheses separately from source-target expectations. These are not observed results and must not be silently changed to fit a run.

- The inline declaration body should retain its J.inlineLeaf call, while K.kotlinInline's source call to K.inlineStep can be erased and its compiled body can call J.inlineLeaf directly. The Java control should still call K.inlineStep.
- K.kotlinDefault should call `withDefault$default`, whose bytecode calls both J.defaultSeed and K.withDefault. K.withDefault itself should not call J.defaultSeed. Inspect the existing `kotlinDefaultBridge` association independently; it must target K.withDefault, not the default-expression callee. This association is already a product feature, not a newly discovered gap.
- Both lambda bodies can live in synthetic methods and be connected through invokedynamic/bootstrap method handles. Preserve those raw method owners and bootstrap evidence. An enclosing source function's lexical ownership is not a direct JVM invocation. Do not manufacture source call-site lines on handle edges or infer that a callback is actually executed from lambda creation alone.
- The five uncomplicated preserved pairs are the inline declaration body, both Java-to-Kotlin control calls, and the two calls accepting a lambda. Their exact JVM target matches are useful controls. The other five source rows require an explicit lowering classification, not an undifferentiated failure/accuracy score.

The first useful product check is whether ordinary preserved calls, exact bridge association, and actual synthetic lambda-body calls survive with correct identities and provenance. Missing evidence here could justify a narrowly scoped product investigation. Source calls erased by inlining are a known bytecode limitation and do not, by themselves, justify a bug fix. Likewise, lexical lambda/default-expression ownership must not be mistaken for a runtime-call precision defect. No product bug is claimed before the real comparison.

## Limits

This does not test Kotlin function types, captures, inline lambda parameters, default constructors, `@JvmOverloads`, suspend, value classes, virtual dispatch completeness, reachability/deletion safety, performance, or whole-project precision/recall. SAM callbacks are deliberately noncapturing `IntUnaryOperator` lambdas in both directions. Runtime execution of fixture methods is not required to compare static source and compiled call facts.

See `RUN-PLAN.md` for the minimal, serialized actual run. Do not launch it until the parent assigns the compiler/JVM lane and reviews the frozen inputs.
