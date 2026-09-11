package dev.kartograph.index

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.ClassHierarchy
import dev.kartograph.core.CallResolution
import dev.kartograph.core.InvocationKind
import dev.kartograph.core.NodeAttribute
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

/** 같은 class 방문에서 얻은 그래프와 후속 runtime 질의의 관측값이다. */
public class IndexedClasses internal constructor(
    public val graph: CodeGraph,
    internal val observations: List<ClassRuntimeObservation>,
    public val hierarchy: ClassHierarchy = ClassHierarchy.EMPTY,
) {
    /** 1회 파싱한 그래프에 dependency header를 보강하며 관측값과 호출 위치는 재사용한다. */
    public fun withHierarchy(hierarchy: ClassHierarchy): IndexedClasses =
        IndexedClasses(ExternalDispatchIndexer.enrich(graph, hierarchy), observations, hierarchy)
}

/** 파일 이름과 시각은 신선도 비교에만 사용하며 절대경로를 내보내지 않는다. */
internal data class ClassRuntimeObservation(
    val sourceFile: String? = null,
    val modified: FileTime = FileTime.fromMillis(0),
    val nativeMethods: Int = 0,
    val reflectionCalls: Int = 0,
    val dynamicRegistrations: Int = 0,
    val classLoadingCalls: Int = 0,
    val reflectiveConstructions: Int = 0,
    val outsideRuntimeTargets: Int = 0,
    val valueAnalysisLimits: Int = 0,
    val serviceLoadingCalls: Int = 0,
    val reflectiveMethods: Int = 0,
    val reflectiveFields: Int = 0,
    val reflectiveMemberMisses: Int = 0,
    val modifiedPrecisionMillis: Long = 0,
)

/** 현재 산출물에서 정적 그래프가 놓칠 runtime 채널을 실제 개수로 보고한다. */
public object RuntimeLimitationScanner {
    /** 단독 호출에서도 그래프와 관측값에 같은 class 선택 규칙을 적용한다. */
    public fun scan(classRoots: List<Path>, projectRoot: Path): List<String> =
        scan(ClassFileIndexer().indexWithObservations(classRoots), projectRoot)

    /** 이미 인덱싱한 class를 다시 읽지 않고 source 신선도와 계량 한계를 반환한다. */
    public fun scan(indexed: IndexedClasses, projectRoot: Path): List<String> {
        val observations = indexed.observations
        val sources = mutableListOf<Path>()
        if (Files.isDirectory(projectRoot)) ProjectTraversal.walkSources(projectRoot) { sources.add(it) }
        val sourcesByName = sources.groupBy { it.fileName.toString() }
        val outputByName = observations.filter { it.sourceFile != null }.groupBy { it.sourceFile }
        var staleCount = 0
        var unknownCount = 0
        for (source in sources) {
            val name = source.fileName.toString()
            val outputs = outputByName[name].orEmpty()
            if (sourcesByName.getValue(name).size != 1 || outputs.isEmpty()) {
                unknownCount++
                continue
            }
            // 같은 source에서 나온 nested/facade 중 하나라도 오래됐으면 새 class가 이를 가리지 않는다.
            val sourceTime = Files.getLastModifiedTime(source)
            if (outputs.any { output ->
                    if (output.modifiedPrecisionMillis == 0L) sourceTime > output.modified
                    else sourceTime.toInstant() >= output.modified.toInstant().plusMillis(output.modifiedPrecisionMillis)
                }) {
                staleCount++
            } else if (outputs.any { sourceTime > it.modified }) {
                // ZIP 시각의 손실 구간 안에서는 새 컴파일과 이후 source 변경을 구분할 수 없다.
                unknownCount++
            }
        }
        val dynamicRegistrations = observations.sumOf { it.dynamicRegistrations }
        val nativeMethods = observations.sumOf { it.nativeMethods }
        val reflectionCalls = observations.sumOf { it.reflectionCalls }
        val calls = indexed.graph.externalCalls
        val loading = observations.sumOf { it.classLoadingCalls }
        val constructions = observations.sumOf { it.reflectiveConstructions }
        val serviceLoading = observations.sumOf { it.serviceLoadingCalls }
        val externalDispatch = calls.count { it.kind in setOf(InvocationKind.VIRTUAL, InvocationKind.INTERFACE) && it.resolution == CallResolution.UNRESOLVED }
        val modeledDispatch = calls.count { it.resolution == CallResolution.PROJECT_CANDIDATES }
        val constants = indexed.graph.nodes.values.count { NodeAttribute.COMPILE_TIME_CONSTANT in it.attributes }
        return buildList {
            val missingMembers = observations.sumOf { it.reflectiveMemberMisses }
            if (missingMembers > 0) add("runtime-member-lookup: $missingMembers known reflective lookup(s) have no project member matching lookup semantics")
            val methods = observations.sumOf { it.reflectiveMethods }
            val fields = observations.sumOf { it.reflectiveFields }
            if (methods > 0) add("reflective-method-invocation: $methods Method.invoke call(s) have no resolved member")
            if (fields > 0) add("reflective-field-access: $fields reflective field access call(s) have no resolved member")
            val outside = observations.sumOf { it.outsideRuntimeTargets }
            val bounded = observations.sumOf { it.valueAnalysisLimits }
            if (outside > 0) add("runtime-targets-outside-graph: $outside resolved runtime target site(s) have no matching project declaration")
            if (bounded > 0) add("runtime-analysis-limits: $bounded method(s) exceeded or could not complete bounded value analysis")
            if (loading > 0) add("class-loading: $loading ClassLoader.loadClass call(s) have no resolved runtime target")
            if (constructions > 0) add("reflective-construction: $constructions reflective constructor call(s) require runtime target modeling")
            if (serviceLoading > 0) add("service-loading: $serviceLoading ServiceLoader call(s) require provider registration inputs")
            if (externalDispatch > 0) add("external-dispatch: $externalDispatch external virtual call(s) have no project implementation target")
            if (modeledDispatch > 0) add("dispatch-candidates: $modeledDispatch external virtual call(s) use conservative hierarchy candidates rather than proven receivers")
            if (constants > 0) add("inlined-constant-references: $constants compile-time constant declaration(s) may have erased use sites")
            if (dynamicRegistrations > 0) add(
                "dynamic-registration: $dynamicRegistrations runtime component registration call(s) are absent from the manifest graph",
            )
            if (staleCount > 0) add(
                "index-staleness: $staleCount of ${sources.size} source file(s) changed after a matching class file",
            )
            if (unknownCount > 0) add(
                "index-freshness-unknown: $unknownCount of ${sources.size} source file(s) have uncertain compiled-source matching or timestamp precision",
            )
            if (nativeMethods > 0) add("jni-methods: $nativeMethods native method(s) may be called outside the JVM graph")
            if (reflectionCalls > 0) add("reflection-strings: $reflectionCalls Class.forName call(s) have unresolved names")
        }.sorted()
    }

    internal fun isDynamicRegistration(owner: String, name: String): Boolean =
        name in DYNAMIC_REGISTRATION_METHODS && owner in REGISTRATION_OWNERS

    private val DYNAMIC_REGISTRATION_METHODS = setOf(
        "registerReceiver", "registerComponentCallbacks", "registerActivityLifecycleCallbacks",
    )
    private val REGISTRATION_OWNERS = setOf(
        "android/app/Activity",
        "android/app/Application",
        "android/content/Context",
        "android/content/ContextWrapper",
        "androidx/core/content/ContextCompat",
    )
}
