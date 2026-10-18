package dev.kartograph.core

/** 리소스 키는 구성별 XML 정의와 독립적이며 특정 기기의 선택 결과가 아니다. */
public data class AndroidResourceKey(val namespace: String, val type: String, val name: String) {
    init {
        require(namespace.length in 1..255 && Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*").matches(namespace)) { "invalid Android resource namespace" }
        require(type == "layout" && name.length in 1..128 && Regex("[a-z_][a-z0-9_]*").matches(name)) { "unsupported Android resource key" }
    }
    internal val fields: List<String> get() = listOf(namespace, type, name)
}

/** 선언된 root의 내용 지문이다. 생산자 대응이나 전체 provider inventory admission은 아직 없다. */
public data class AndroidResourceRoot(val input: InputFingerprint) {
    public val id: String
    init {
        SourceUseCanonical.input(input, "android-resources")
        require(input.path.length <= 4_096) { "Android resource root path exceeds limit" }
        id = SourceUseCanonical.hash(sequenceOf("kartograph-android-resource-root-v1") + SourceUseCanonical.fields(input))
    }
}

/** 같은 키의 모든 qualifier 정의를 보존하며 overlay winner를 추정하지 않는다. */
public data class AndroidLayoutDefinition(
    val key: AndroidResourceKey,
    val rootId: String,
    val file: CompilerEvidenceSource,
    val relativePath: String,
    val qualifier: String = "",
) {
    public val id: String
    init {
        SourceUseCanonical.hashText(rootId)
        SourceUseCanonical.input(InputFingerprint("sourceFile", file.path, file.sha256), "sourceFile")
        require(file.path.length <= 4_096 && !file.path.startsWith("external/")) { "layout definition needs a portable project path" }
        require(qualifier.length <= 256 && (qualifier.isEmpty() || Regex("[A-Za-z0-9+_-]+").matches(qualifier))) { "invalid layout qualifier" }
        val directory = if (qualifier.isEmpty()) "layout" else "layout-$qualifier"
        require(relativePath == "$directory/${key.name}.xml") { "layout key does not match its relative XML path" }
        id = SourceUseCanonical.hash(sequenceOf("kartograph-android-layout-definition-v1") + key.fields +
            listOf(rootId, file.path, file.sha256, relativePath, qualifier))
    }
}

/** literal 키 참조도 inflate 성공이나 실제 구성 선택을 증명하지 않는다. */
public enum class AndroidLayoutIncludeKind { LITERAL, DYNAMIC, EXTERNAL_NAMESPACE, UNSUPPORTED }

/** ordinal은 한 XML 안의 include 관측 순서다. 같은 target의 중복 include도 별도 사실로 보존한다. */
public data class AndroidLayoutInclude(
    val sourceDefinitionId: String,
    val ordinal: Int,
    val kind: AndroidLayoutIncludeKind,
    val target: AndroidResourceKey? = null,
) {
    public val id: String
    init {
        SourceUseCanonical.hashText(sourceDefinitionId)
        require(ordinal in 0 until AndroidResourceEvidence.MAX_INCLUDES) { "invalid include ordinal" }
        require((kind in setOf(AndroidLayoutIncludeKind.LITERAL, AndroidLayoutIncludeKind.EXTERNAL_NAMESPACE)) == (target != null)) {
            "include target does not match its observation kind"
        }
        id = SourceUseCanonical.hash(sequenceOf("kartograph-android-layout-include-v1", sourceDefinitionId,
            ordinal.toString(), kind.name) + target?.fields.orEmpty())
    }
}

/**
 * 선택적 미승인 리소스 관측 채널이다. 존재만으로 XML→R 또는 영향 seed를 만들 수 없다.
 * null 채널과 검사한 빈 목록은 구분하며, 원본 사실은 graph view에서도 삭제하지 않는다.
 */
public class AndroidResourceEvidence(
    public val scope: String,
    public val namespace: String,
    roots: Iterable<AndroidResourceRoot>,
    definitions: Iterable<AndroidLayoutDefinition>,
    includes: Iterable<AndroidLayoutInclude>,
) {
    public val admission: String get() = "unverified"
    public val roots: List<AndroidResourceRoot> = SourceUseCanonical.list(roots, MAX_ROOTS).sortedBy { it.id }.let(SourceUseCanonical::freeze)
    public val definitions: List<AndroidLayoutDefinition> = SourceUseCanonical.list(definitions, MAX_DEFINITIONS).sortedBy { it.id }.let(SourceUseCanonical::freeze)
    public val includes: List<AndroidLayoutInclude> = SourceUseCanonical.list(includes, MAX_INCLUDES).sortedBy { it.id }.let(SourceUseCanonical::freeze)

    init {
        require(scope.length in 1..200 && Regex("[A-Za-z0-9_.:-]+").matches(scope)) { "invalid resource scope" }
        AndroidResourceKey(namespace, "layout", "validation")
        require(this.roots.map { it.input.path }.toSet().size == this.roots.size) { "duplicate resource root identity" }
        require(this.definitions.map { it.file.path }.toSet().size == this.definitions.size) { "duplicate layout file definition" }
        require(this.includes.map { it.sourceDefinitionId to it.ordinal }.toSet().size == this.includes.size) { "duplicate include site" }
        val byRoot = this.roots.associateBy { it.id }
        val byDefinition = this.definitions.associateBy { it.id }
        this.definitions.forEach { definition ->
            val root = requireNotNull(byRoot[definition.rootId]) { "layout root is absent" }
            require(definition.key.namespace == namespace && !root.input.path.startsWith("external/") &&
                definition.file.path == root.input.path + "/" + definition.relativePath) { "layout definition is outside its declared root or namespace" }
        }
        this.includes.forEach { include ->
            require(include.sourceDefinitionId in byDefinition) { "include source definition is absent" }
            include.target?.let { target ->
                require((include.kind == AndroidLayoutIncludeKind.LITERAL) == (target.namespace == namespace)) { "include namespace classification mismatch" }
            }
        }
        var bytes = 0L
        fun count(values: Iterable<String>) {
            values.forEach { value ->
                SourceUseCanonical.text(value)
                bytes += value.toByteArray(Charsets.UTF_8).size
                require(bytes <= MAX_TEXT_BYTES) { "Android resource evidence exceeds aggregate text limit" }
            }
        }
        count(listOf(scope, namespace))
        this.roots.forEach { count(SourceUseCanonical.fields(it.input)) }
        this.definitions.forEach { count(it.key.fields + listOf(it.rootId, it.file.path, it.file.sha256, it.relativePath, it.qualifier)) }
        this.includes.forEach { count(listOf(it.sourceDefinitionId) + it.target?.fields.orEmpty()) }
    }

    override fun equals(other: Any?): Boolean = other is AndroidResourceEvidence && scope == other.scope && namespace == other.namespace &&
        roots == other.roots && definitions == other.definitions && includes == other.includes
    override fun hashCode(): Int = listOf(scope, namespace, roots, definitions, includes).hashCode()

    public companion object {
        public const val MAX_ROOTS: Int = 512
        public const val MAX_DEFINITIONS: Int = 50_000
        public const val MAX_INCLUDES: Int = 100_000
        public const val MAX_TEXT_BYTES: Int = 16 * 1024 * 1024
    }
}
