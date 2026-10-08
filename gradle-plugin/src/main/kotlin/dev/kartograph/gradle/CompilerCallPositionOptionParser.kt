package dev.kartograph.gradle

/** 실제 compiler 인자에서 token-bound call-position marker 포함 여부만 결정한다. */
internal object CompilerCallPositionOptionParser {
    fun enabled(compilerKind: String, effectiveArguments: List<String>, compilerEvidence: Boolean): Boolean {
        if (!compilerEvidence) return false
        return when (compilerKind) {
            "javac" -> javac(effectiveArguments)
            "kotlin" -> kotlin(effectiveArguments)
            else -> throw IllegalArgumentException("unsupported compiler witness kind")
        }
    }

    fun isJavacPluginArgument(argument: String): Boolean = argument == JAVAC_PLUGIN ||
        argument.length > JAVAC_PLUGIN.length && argument.startsWith(JAVAC_PLUGIN) &&
        argument[JAVAC_PLUGIN.length] in JAVAC_WHITESPACE

    private fun javac(arguments: List<String>): Boolean {
        val configured = arguments.filter(::isJavacPluginArgument)
        if (configured.isEmpty()) return false
        require(configured.size == 1) { "compiler evidence plugin may be configured only once" }
        var collector = "javac-constants"
        var root = false
        var output = false
        var token = false
        var positions = false
        val values = configured.single().removePrefix(JAVAC_PLUGIN).trim { it in JAVAC_WHITESPACE }
            .takeIf(String::isNotEmpty)?.split(WHITESPACE).orEmpty()
        for (argument in values) {
            val separator = argument.indexOf('=')
            require(separator > 0) { "compiler evidence options must be key=value" }
            val key = argument.substring(0, separator)
            val value = argument.substring(separator + 1)
            require(value.isNotBlank()) { "compiler evidence option is empty" }
            when (key) {
                "collector" -> collector = value
                "root" -> root = true
                "output" -> output = true
                "token" -> token = true
                "callPositions" -> positions = strictBoolean(value)
                else -> throw IllegalArgumentException("unsupported compiler evidence option")
            }
        }
        require(root && output && token) { "compiler evidence root, output and token are required" }
        require(!positions || collector == "javac-constants") { "call positions require a constants collector" }
        return positions && collector == "javac-constants"
    }

    private fun kotlin(arguments: List<String>): Boolean {
        var found: Boolean? = null
        var index = 0
        while (index < arguments.size) {
            val argument = arguments[index++]
            val payload = when {
                argument == "-P" -> arguments.getOrNull(index++)
                argument.startsWith("-P") -> argument.removePrefix("-P")
                else -> null
            } ?: continue
            for (option in payload.split(',')) {
                if (option != KOTLIN_OPTION && !option.startsWith("$KOTLIN_OPTION=")) continue
                require(option.startsWith("$KOTLIN_OPTION=")) { "Kotlin call positions option requires a value" }
                require(found == null) { "Kotlin call positions option may be configured only once" }
                found = strictBoolean(option.substringAfter('='))
            }
        }
        return found ?: false
    }

    private fun strictBoolean(value: String): Boolean = when (value) {
        "true" -> true
        "false" -> false
        else -> throw IllegalArgumentException("compiler call positions option must be true or false")
    }

    private const val JAVAC_PLUGIN: String = "-Xplugin:KartographEvidence"
    private const val KOTLIN_OPTION: String = "plugin:kartograph.compiler-evidence:callPositions"
    private val JAVAC_WHITESPACE = setOf(' ', '\t', '\n', '\u000B', '\u000C', '\r')
    private val WHITESPACE = Regex("\\s+")
}
