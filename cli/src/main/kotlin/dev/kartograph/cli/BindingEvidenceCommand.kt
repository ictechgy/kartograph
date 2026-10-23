package dev.kartograph.cli

import dev.kartograph.export.McpJsonCodec
import dev.kartograph.index.ContentFingerprint
import java.io.PrintStream
import java.nio.file.Path

/** 기존 why 입력과 도달 경로를 재사용하는 bounded DataBinding 근거 보고다. */
internal object BindingEvidenceCommand {
    fun run(arguments: List<String>, output: PrintStream, error: PrintStream): Int {
        if (arguments == listOf("--help") || arguments == listOf("-h")) {
            output.print(HELP)
            return 0
        }
        val retained = ArrayList<String>()
        val overlays = ArrayList<String>()
        var variant: String? = null
        var limit = 1_000
        var limitSeen = false
        var index = 0
        try {
            while (index < arguments.size) {
                val option = arguments[index++]
                if (option !in setOf("--resource-overlay", "--variant", "--limit")) { retained += option; continue }
                val value = arguments.getOrNull(index++) ?: return usage(error, "missing binding option value")
                when (option) {
                    "--resource-overlay" -> overlays += value
                    "--variant" -> {
                        if (variant != null || !Regex("[A-Za-z0-9_.:-]{1,200}").matches(value)) return usage(error, "invalid or duplicate variant assertion")
                        variant = value
                    }
                    "--limit" -> {
                        if (limitSeen) return usage(error, "duplicate binding limit")
                        limit = value.toIntOrNull()?.takeIf { it in 1..10_000 } ?: return usage(error, "binding limit must be between 1 and 10000")
                        limitSeen = true
                    }
                }
            }
            val parsed = WhyCommand.parseInputs(retained, error) ?: return 64
            val ordered = overlays.map { Path.of(it).let { path -> if (path.isAbsolute) path.normalize() else parsed.projectRoot.resolve(path).normalize() } }
            if (ordered.distinct().size != ordered.size || ordered.any { it !in parsed.resourceRoots }) {
                return usage(error, "overlay roots must be unique and also supplied with --resources; list lowest to highest priority")
            }
            val inputs = parsed.copy(bindingOverlayRoots = ordered, bindingLimit = limit)
            fun observe(): List<dev.kartograph.core.InputFingerprint> {
                val rules = dev.kartograph.index.KeepRuleScanner(inputs.projectRoot, inputs.includePrivateMembers, inputs.lenientKeepRules)
                rules.scan(inputs.keepRules)
                val files = inputs.classRoots.map { "classes" to it } + inputs.classpath.map { "classpath" to it } +
                    inputs.resourceRoots.map { "resources" to it } + listOf("manifest" to inputs.manifest) +
                    rules.inputFiles.map { "keepRules" to it } + inputs.serviceResources.map { "service-resources" to it }
                return files.mapIndexed { ordinal, (role, path) -> ContentFingerprint.capture(inputs.projectRoot, path, role, "$role-$ordinal") }
            }
            val before = observe()
            val analysis = RetentionPipeline.analyze(inputs)
            val after = observe()
            val stable = before == after
            val review = requireNotNull(analysis.bindingReview)
            val document = mapOf(
                "format" to "kartograph-binding-evidence", "version" to 1, "status" to if (stable) "incomplete" else "stale",
                "variant" to variant, "variantBasis" to if (variant == null) "unspecified" else "inputAssertion",
                "inputContent" to if (stable) "matchedBeforeAfter" else "changedDuringAnalysis", "buildFreshness" to "unverified",
                "inputs" to before.map { mapOf("role" to it.role, "path" to it.path, "sha256" to it.sha256) },
                "overlay" to mapOf("basis" to if (ordered.isEmpty()) "union" else "inputPriorityAssertion",
                    "roots" to ordered.map { inputs.projectRoot.relativize(it).toString().replace('\\', '/') }),
                "limits" to mapOf("rows" to limit, "pairs" to 200_000, "xmlBytes" to 2 * 1024 * 1024, "aggregateBytes" to 64 * 1024 * 1024),
                "truncated" to review.truncated, "unresolvedAdapters" to review.unresolvedAdapters.sorted().map { it.value },
                "candidates" to review.candidates.map { row -> mapOf(
                    "adapter" to row.adapter.method.value, "attributes" to row.adapter.attributes, "requireAll" to row.adapter.requireAll,
                    "valueDescriptors" to row.adapter.parameterTypes, "signatureSupported" to row.adapter.supportedSignature,
                    "layout" to mapOf("path" to row.element.location.path, "line" to row.element.location.line,
                        "element" to row.element.ordinal, "sha256" to row.element.sourceSha256, "resource" to row.element.resourcePath),
                    "boundAttributes" to row.element.boundAttributes.sorted(), "declaredValueTypes" to row.element.declaredValueTypes,
                    "attributeMatch" to row.attributeMatch, "viewMatch" to row.viewMatch, "overloadCount" to row.overloadCount,
                    "candidate" to (row.attributeMatch != "missingRequiredAttributes" &&
                        (row.viewMatch != "incompatible" || !row.adapter.supportedSignature)),
                    "runtimeSelection" to "unknown", "xmlToCompiledLink" to "unverified",
                    "compiledCallers" to row.compiledCallers.map { it.value },
                    "reachedCompiledCallers" to row.reachedCallers.map { caller -> mapOf("caller" to caller.value,
                        "path" to analysis.reachability.pathFromRootTo(caller).orEmpty().map { it.value }) },
                ) },
                "limitations" to (analysis.sourceDiagnostics + analysis.keepDiagnostics + listOf(
                    "Candidate eligibility is not an executed callback, predicted test failure, or deletion permission.",
                    "Content equality is not a compiler build witness; dependencies, configuration and conversions may remain unobserved.",
                )).distinct(),
            )
            output.println(McpJsonCodec.render(document, 16 * 1024 * 1024))
            return if (stable) 0 else 2
        } catch (_: Exception) {
            error.println("error: unable to collect binding evidence; check the bounded class, XML and retention inputs")
            return 2
        }
    }

    private fun usage(error: PrintStream, message: String): Int { error.println("error: $message"); return 64 }

    private val HELP = """
        Explain DataBinding annotation/XML candidates and independent compiled caller paths.
        Usage: kartograph bindings --project <dir> --classes <dir-or-jar> --manifest <file>
          --resources <dir> [--resources <dir>]... --namespace <name> [why input options]
          [--resource-overlay <dir>]... [--variant <assertion>] [--limit <1..10000>]

        Resource roots are a union unless explicit --resource-overlay roots (also in --resources) are
        ordered from lowest to highest priority. Only the same layout/qualifier key is overlaid.
        Variant and priority are input assertions, not proof of the compiled build or device selection.
        requireAll uses binding expressions on the same XML element. All matching overloads remain
        candidates. XML never creates retention roots; actual bytecode CALL and reached caller paths
        are independent evidence. Empty candidates are not a safety guarantee. Saved snapshot/MCP
        limitations and ordinary dead/why carry the union model; this command provides detail.
    """.trimIndent() + "\n"
}
