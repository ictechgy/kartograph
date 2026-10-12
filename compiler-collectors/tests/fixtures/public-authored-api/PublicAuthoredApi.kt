package external.consumer

import dev.kartograph.analysis.AuthoredCallerScope
import dev.kartograph.analysis.AuthoredCallUniverse
import dev.kartograph.analysis.AuthoredUniverseStatus
import dev.kartograph.core.*

// 설치된 공개 JAR만 소비하는 별도 Kotlin module이다. 내부 접근이나 friend 경로를 사용하지 않는다.
fun main() {
    val method = NodeId("method:Calls#zero()V")
    val file = CompilerEvidenceSource("src/Calls.java", "a".repeat(64))
    val row = CompilerAuthoredCaller(method, file, 0, 20,
        CompilerSourceCoordinateBasis.JAVAC_UTF16_CHAR_SEQUENCE, "17.0.20", 1, 0,
        AuthoredCallCoverage.COMPLETE, emptyList(), emptyList(), "c".repeat(64), "d".repeat(64), "f".repeat(64))
    val inventory = CompilerCallerInventory("library", "compileJava", "classes", listOf(file), listOf(
        CompilerSourceBody("b0", file, 0, 20, CompilerSourceBodyKind.ORDINARY_METHOD, true,
            "JAVAC_EXPLICIT", listOf(method))), listOf(CompilerInventoryOutput("Calls.class", "b".repeat(64), "Calls")),
        listOf(CompilerInventoryMethod(method, 9, true, CompilerBackendOrigin.SOURCE_BOUND, "b0", "JAVAC_EXPLICIT")),
        row.coordinateBasis, row.compilerVersion, row.inputToken, row.collectorArtifactSha256, "e".repeat(64), row.evidenceSha256)
    val base = CodeGraph(listOf(GraphNode(NodeId("class:Calls"), "Calls", NodeKind.CLASS),
        GraphNode(method, "zero", NodeKind.METHOD, jvmModifiers = setOf(JvmModifier.STATIC))), emptyList())
        .withCompilerCallerInventories(listOf(inventory))
    val files = mutableSetOf(file.path)
    val modules = mutableMapOf("library" to files as Set<String>)
    val kinds = mutableSetOf(CompilerSourceBodyKind.ORDINARY_METHOD)
    val scope = AuthoredCallerScope(modules, kinds)
    val complete = AuthoredCallUniverse.examine(base.withCompilerAuthoredCalls(listOf(row)), scope)
    check(complete.status == AuthoredUniverseStatus.COMPLETE)
    check(complete.callers == setOf(method) && complete.calls == mapOf(method to emptyList<CompilerAuthoredCall>()))
    check(complete.sourceBodies == 1 && complete.limitations.isEmpty())
    val incomplete = AuthoredCallUniverse.examine(base.withCompilerAuthoredCalls(listOf(row.copy(
        coverage = AuthoredCallCoverage.INCOMPLETE, reasons = listOf(AuthoredCallReason.UNSUPPORTED_SYNTAX)))), scope)
    check(incomplete.status == AuthoredUniverseStatus.INCOMPLETE)
    check("incomplete-caller-coverage" in incomplete.limitations)
    val missing = AuthoredCallUniverse.examine(base, scope)
    check(missing.status == AuthoredUniverseStatus.UNKNOWN)
    check("missing-or-mismatched-caller-coverage" in missing.limitations)
    check(runCatching { scope.copy(wholeProject = true) }.exceptionOrNull() is IllegalArgumentException)
    files.clear(); modules.clear(); kinds.clear()
    check(runCatching { AuthoredCallUniverse.examine(base, scope) }.exceptionOrNull() is IllegalArgumentException)
    check(complete.scope.filesByModule == mapOf("library" to setOf(file.path)))
    check(complete.scope.bodyKinds == setOf(CompilerSourceBodyKind.ORDINARY_METHOD))
    check(runCatching { (complete.scope.bodyKinds as MutableSet<CompilerSourceBodyKind>).clear() }
        .exceptionOrNull() is UnsupportedOperationException)
    println("PUBLIC_AUTHORED_API complete=COMPLETE incomplete=INCOMPLETE missing=UNKNOWN invalid=REJECTED immutable=YES")
}
