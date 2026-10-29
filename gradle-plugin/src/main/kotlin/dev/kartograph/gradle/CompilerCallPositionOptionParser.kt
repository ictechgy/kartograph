package dev.kartograph.gradle

/** 실제 compiler 인자에서 token-bound call-position marker 포함 여부만 결정한다. */
internal object CompilerCallPositionOptionParser {
    fun enabled(compilerKind: String, effectiveArguments: List<String>, compilerEvidence: Boolean): Boolean {
        return enabledOption(compilerKind, effectiveArguments, compilerEvidence, "callPositions")
    }

    fun enumDecisionsEnabled(compilerKind: String, effectiveArguments: List<String>, compilerEvidence: Boolean): Boolean =
        enabledOption(compilerKind, effectiveArguments, compilerEvidence, "enumDecisions")

    private fun enabledOption(compilerKind: String, effectiveArguments: List<String>, compilerEvidence: Boolean, option: String): Boolean {
        if (!compilerEvidence) return false
        return when (compilerKind) {
            "javac" -> javac(effectiveArguments, option)
            "kotlin" -> kotlin(effectiveArguments, option)
            else -> throw IllegalArgumentException("unsupported compiler witness kind")
        }
    }

    fun authoredEnabled(compilerKind: String, arguments: List<String>, compilerEvidence: Boolean): Boolean =
        compilerEvidence && when (compilerKind) {
            "javac" -> javac(arguments, "authoredCalls")
            "kotlin" -> kotlin(arguments, "authoredCalls")
            else -> throw IllegalArgumentException("unsupported compiler witness kind")
        }

    fun inventoryEnabled(compilerKind: String, arguments: List<String>, compilerEvidence: Boolean): Boolean =
        compilerEvidence && when (compilerKind) {
            "javac" -> javac(arguments, "callerInventory")
            "kotlin" -> kotlin(arguments, "callerInventory")
            else -> throw IllegalArgumentException("unsupported compiler witness kind")
        }

    fun inventoryOutput(compilerKind: String, arguments: List<String>): java.io.File {
        val values = if (compilerKind == "javac") arguments.filter(::isJavacPluginArgument).flatMap {
            it.removePrefix(JAVAC_PLUGIN).trim().split(WHITESPACE).filter { value -> value.startsWith("classOutput=") }.map { value -> value.substringAfter('=') }
        } else arguments.flatMap { it.removePrefix("-P").split(',') }.filter {
            it.startsWith("plugin:kartograph.compiler-evidence:classOutput=")
        }.map { it.substringAfter("classOutput=") }
        require(values.size == 1 && values.single().isNotBlank()) { "caller inventory requires one classOutput option" }
        val value = values.single()
        return (if (value.startsWith("file:")) java.io.File(java.net.URI.create(value)) else java.io.File(value)).canonicalFile
    }

    fun isJavacPluginArgument(argument: String): Boolean = argument == JAVAC_PLUGIN ||
        argument.length > JAVAC_PLUGIN.length && argument.startsWith(JAVAC_PLUGIN) &&
        argument[JAVAC_PLUGIN.length] in JAVAC_WHITESPACE

    private fun javac(arguments: List<String>, option: String): Boolean {
        val configured = arguments.filter(::isJavacPluginArgument)
        if (configured.isEmpty()) return false
        require(configured.size == 1) { "compiler evidence plugin may be configured only once" }
        var collector = "javac-constants"
        var root = false
        var output = false
        var token = false
        var positions = false
        var authoredSeen = false
        var inventorySeen = false
        var classOutputSeen = false
        var decisions = false
        var decisionsSeen = false
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
                "classOutput" -> { require(!classOutputSeen) { "classOutput may be configured only once" }; classOutputSeen = true }
                "callPositions", "authoredCalls", "callerInventory" -> {
                    if (key == "callerInventory") {
                        require(!inventorySeen) { "callerInventory may be configured only once" }
                        inventorySeen = true
                    }
                    if (key == "authoredCalls") {
                        require(!authoredSeen) { "authoredCalls may be configured only once" }
                        authoredSeen = true
                    }
                    val enabled = strictBoolean(value)
                    if (key == option) positions = enabled
                }
                "enumDecisions" -> {
                    require(!decisionsSeen) { "compiler enum decisions option may be configured only once" }
                    decisionsSeen = true
                    decisions = strictBoolean(value)
                }
                else -> throw IllegalArgumentException("unsupported compiler evidence option")
            }
        }
        require(root && output && token) { "compiler evidence root, output and token are required" }
        require(!positions || collector == "javac-constants") { "call positions require a constants collector" }
        require(!decisions || collector == "javac-constants") { "enum decisions require a constants collector" }
        return (if (option == "enumDecisions") decisions else positions) && collector == "javac-constants"
    }

    private fun kotlin(arguments: List<String>, optionName: String): Boolean {
        val optionKey = "plugin:kartograph.compiler-evidence:$optionName"
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
                if (option != optionKey && !option.startsWith("$optionKey=")) continue
                require(option.startsWith("$optionKey=")) { "Kotlin call positions option requires a value" }
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
    private val JAVAC_WHITESPACE = setOf(' ', '\t', '\n', '\u000B', '\u000C', '\r')
    private val WHITESPACE = Regex("\\s+")
}
