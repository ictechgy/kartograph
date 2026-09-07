package dev.kartograph.index

import dev.kartograph.core.CodeGraph
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

/** 같은 class 방문에서 얻은 그래프와 후속 runtime 질의의 관측값이다. */
public class IndexedClasses internal constructor(
    public val graph: CodeGraph,
    internal val observations: List<ClassRuntimeObservation>,
)

/** 파일 이름과 시각은 신선도 비교에만 사용하며 절대경로를 내보내지 않는다. */
internal data class ClassRuntimeObservation(
    val sourceFile: String? = null,
    val modified: FileTime = FileTime.fromMillis(0),
    val nativeMethods: Int = 0,
    val reflectionCalls: Int = 0,
    val dynamicRegistrations: Int = 0,
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
            val oldestOutput = outputs.minOf { it.modified }
            if (Files.getLastModifiedTime(source) > oldestOutput) staleCount++
        }
        val dynamicRegistrations = observations.sumOf { it.dynamicRegistrations }
        val nativeMethods = observations.sumOf { it.nativeMethods }
        val reflectionCalls = observations.sumOf { it.reflectionCalls }
        return buildList {
            if (dynamicRegistrations > 0) add(
                "dynamic-registration: $dynamicRegistrations runtime component registration call(s) are absent from the manifest graph",
            )
            if (staleCount > 0) add(
                "index-staleness: $staleCount of ${sources.size} source file(s) changed after a matching class file",
            )
            if (unknownCount > 0) add(
                "index-freshness-unknown: $unknownCount of ${sources.size} source file(s) could not be matched unambiguously to compiled source metadata",
            )
            if (nativeMethods > 0) add("jni-methods: $nativeMethods native method(s) may be called outside the JVM graph")
            if (reflectionCalls > 0) add("reflection-strings: $reflectionCalls Class.forName call(s) use runtime names")
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
