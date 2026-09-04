package dev.kartograph.analysis

/** 레이어 YAML을 부분 적용하지 못하게 하는 검증 오류다. */
public class LayerConfigurationException(message: String) : IllegalArgumentException(message)

/** 외부 객체 생성을 허용하지 않는 작은 선언형 YAML subset parser다. */
public object LayerRuleYaml {
    /** 검증이 끝난 뒤에만 노출되는 원자적 설정 값이다. */
    public data class Configuration(val layers: List<LayerDefinition>, val rules: List<LayerRule>)

    /** 안전한 scalar와 inline list만 받아 YAML tag/object 생성을 원천 차단한다. */
    public fun parse(text: String): Configuration {
        val sections = mutableMapOf<String, MutableList<Map<String, String>>>()
        val declaredSections = mutableSetOf<String>()
        var section: String? = null
        var current: MutableMap<String, String>? = null
        text.lineSequence().forEachIndexed { index, raw ->
            val line = raw.substringBefore('#').trimEnd()
            if (line.isBlank()) return@forEachIndexed
            if (!line.startsWith(' ')) {
                val key = line.removeSuffix(":")
                if (line != "$key:" || key !in setOf("layers", "rules")) fail(index, "unknown root key")
                if (!declaredSections.add(key)) fail(index, "duplicate root key")
                section = key
                current = null
            } else {
                val active = section ?: fail(index, "entry appears before a section")
                val trimmed = line.trimStart()
                if (trimmed.startsWith("- ")) {
                    current = linkedMapOf()
                    sections.getOrPut(active) { mutableListOf() } += current
                    put(current, trimmed.removePrefix("- "), index)
                } else {
                    put(current ?: fail(index, "property appears before a list item"), trimmed, index)
                }
            }
        }
        val layers = sections["layers"].orEmpty().mapIndexed { index, values ->
            unknown(values, setOf("name", "match"), "layers[$index]")
            LayerDefinition(required(values, "name", "layers[$index]"), list(values, "match", "layers[$index]"))
        }
        if (layers.isEmpty()) throw LayerConfigurationException("configuration requires at least one layer")
        val rules = sections["rules"].orEmpty().mapIndexed { index, values ->
            unknown(values, setOf("name", "from", "allow", "deny"), "rules[$index]")
            val from = required(values, "from", "rules[$index]")
            val allow = values["allow"]?.let(::parseList)
            val deny = values["deny"]?.let(::parseList)
            try {
                LayerRule(values["name"] ?: "$from dependency rule", from, allow, deny)
            } catch (error: IllegalArgumentException) {
                throw LayerConfigurationException("rules[$index]: ${error.message}")
            }
        }
        val names = layers.map { it.name }
        if (names.size != names.toSet().size) throw LayerConfigurationException("layer names must be unique")
        val ruleNames = rules.map { it.name }
        if (ruleNames.size != ruleNames.toSet().size) throw LayerConfigurationException("rule names must be unique")
        rules.forEachIndexed { index, rule ->
            val references = listOf(rule.from) + rule.allow.orEmpty() + rule.deny.orEmpty()
            val missing = references.firstOrNull { it !in names }
            if (missing != null) throw LayerConfigurationException("rules[$index] references unknown layer '$missing'")
        }
        return Configuration(layers, rules)
    }

    private fun put(target: MutableMap<String, String>, entry: String, line: Int) {
        val key = entry.substringBefore(':', missingDelimiterValue = "")
        val value = entry.substringAfter(':', missingDelimiterValue = "").trim()
        if (key.isBlank() || !entry.contains(':') || key in target) fail(line, "invalid or duplicate property")
        target[key] = value
    }

    private fun required(values: Map<String, String>, key: String, scope: String): String =
        values[key]?.takeIf { it.isNotBlank() } ?: throw LayerConfigurationException("$scope requires '$key'")

    private fun list(values: Map<String, String>, key: String, scope: String): List<String> =
        values[key]?.let(::parseList)?.takeIf { it.isNotEmpty() }
            ?: throw LayerConfigurationException("$scope requires a non-empty '$key'")

    private fun parseList(value: String): List<String> {
        if (!value.startsWith('[') || !value.endsWith(']')) throw LayerConfigurationException("lists must use [value, ...] syntax")
        val inside = value.substring(1, value.length - 1).trim()
        if (inside.isEmpty()) return emptyList()
        return inside.split(',').map { it.trim().removeSurrounding("\"").removeSurrounding("'") }
            .also { if (it.any(String::isBlank)) throw LayerConfigurationException("list entries must not be blank") }
    }

    private fun unknown(values: Map<String, String>, allowed: Set<String>, scope: String) {
        values.keys.firstOrNull { it !in allowed }?.let { throw LayerConfigurationException("$scope has unknown key '$it'") }
    }

    private fun fail(line: Int, message: String): Nothing = throw LayerConfigurationException("line ${line + 1}: $message")
}
