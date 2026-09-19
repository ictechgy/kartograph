package dev.kartograph.cli

import dev.kartograph.analysis.DependencyFindings
import dev.kartograph.export.DependencyListCodec
import dev.kartograph.export.DependencyReporter
import dev.kartograph.export.ReportFormat
import dev.kartograph.index.ClassIndexingException
import dev.kartograph.index.DependencyArtifactScanner
import dev.kartograph.index.ExternalReferenceScanner
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * 선언된 dependency와 색인된 bytecode 참조를 대조해 unused 후보를 보고한다.
 * 판정은 참조 부재의 측정이며 dependency 삭제 승인이 아니다.
 */
internal object DependenciesCommand {
    fun run(arguments: List<String>, output: PrintStream, error: PrintStream): Int {
        if (arguments == listOf("--help") || arguments == listOf("-h")) {
            output.print(HELP)
            return ExitStatus.SUCCESS.code
        }
        val options = try {
            parseOptions(arguments, error)
        } catch (pathError: InvalidPathException) {
            error.println("error: invalid path")
            null
        } ?: return ExitStatus.USAGE.code
        if (!Files.isDirectory(options.projectRoot)) {
            return toolFailure(error, "project root does not exist")
        }
        return try {
            execute(options, output, error)
        } catch (indexingError: ClassIndexingException) {
            toolFailure(error, indexingError.message ?: "class indexing failed")
        } catch (fileError: IOException) {
            toolFailure(error, "unable to read the dependency list")
        } catch (listError: IllegalArgumentException) {
            toolFailure(error, listError.message ?: "invalid dependency list")
        }
    }

    private fun execute(options: DependenciesOptions, output: PrintStream, error: PrintStream): Int {
        val declared = DependencyListCodec.parse(Files.readString(options.dependenciesFile))
        if (declared.isEmpty()) {
            return toolFailure(error, "dependency list is empty")
        }
        val includeTestScopes = options.testClassRoots.isNotEmpty()
        val judgedScopes = DependencyFindings.judgedScopes(includeTestScopes)
        val referencedClasses = ExternalReferenceScanner().scan(options.classRoots + options.testClassRoots)
        val scanner = DependencyArtifactScanner()
        val artifactClasses = linkedMapOf<String, Set<String>>()
        declared.forEach { dependency ->
            if (dependency.scope !in judgedScopes || dependency.artifact in artifactClasses) return@forEach
            val resolved = try {
                resolveProjectPath(options.projectRoot, dependency.artifact)
            } catch (pathError: InvalidPathException) {
                return toolFailure(error, "dependency artifact for ${dependency.coordinate} has an invalid path")
            }
            if (!Files.isDirectory(resolved) && !Files.isRegularFile(resolved)) {
                return toolFailure(error, "dependency artifact for ${dependency.coordinate} does not exist")
            }
            val classes = try {
                scanner.scan(resolved)
            } catch (scanError: ClassIndexingException) {
                return toolFailure(error, "dependency artifact for ${dependency.coordinate} cannot be read")
            }
            artifactClasses[dependency.artifact] = classes
        }
        val result = DependencyFindings.find(declared, referencedClasses, artifactClasses, includeTestScopes)
        val limitations = buildList {
            add("only references from the supplied class roots are measured; reflection strings, runtime class loading, SOURCE-retention annotations, annotation processors and resource-driven use are not resolved")
            add("generic type arguments that appear only in signatures are erased from bytecode descriptors")
            if (!includeTestScopes) {
                add("test class roots were not supplied; test scopes are counted but not judged")
            }
            if (result.skippedCount > 0) {
                add("${result.skippedCount} declared dependencies use processor, runtime-only, or unjudged test scopes and were not judged")
            }
            if (result.withoutClassCount > 0) {
                add("${result.withoutClassCount} declared artifacts contained no class files and were not judged")
            }
        }
        output.print(DependencyReporter.render(options.reportFormat, result, limitations))
        return if (options.strict && result.findings.isNotEmpty()) ExitStatus.FINDINGS.code else ExitStatus.SUCCESS.code
    }

    private fun parseOptions(arguments: List<String>, error: PrintStream): DependenciesOptions? {
        val classRoots = mutableListOf<Path>()
        val testClassRoots = mutableListOf<Path>()
        var projectRoot: Path? = null
        var dependenciesValue: String? = null
        var strict = false
        var reportFormat = ReportFormat.TEXT
        var index = 0
        while (index < arguments.size) {
            val option = arguments[index]
            if (option == "--strict") {
                strict = true
                index++
                continue
            }
            val value = valueAfter(arguments, index, option, error) ?: return null
            when (option) {
                "--classes" -> classRoots.add(Path.of(value))
                "--test-classes" -> testClassRoots.add(Path.of(value))
                "--project" -> projectRoot = Path.of(value).toAbsolutePath().normalize()
                "--dependencies" -> dependenciesValue = value
                "--report-format" -> reportFormat = ReportFormat.fromOption(value) ?: run {
                    error.println("error: invalid report format: $value")
                    return null
                }
                else -> {
                    error.println("error: unknown dependencies option: $option")
                    return null
                }
            }
            index += 2
        }
        if (reportFormat != ReportFormat.TEXT && reportFormat != ReportFormat.JSON) {
            error.println("error: dependencies supports text and json report formats")
            return null
        }
        val project = projectRoot ?: return missingOption(error, "--project")
        return DependenciesOptions(
            classRoots = classRoots.takeIf(List<Path>::isNotEmpty) ?: return missingOption(error, "--classes"),
            testClassRoots = testClassRoots,
            projectRoot = project,
            dependenciesFile = resolveProjectPath(project, dependenciesValue ?: return missingOption(error, "--dependencies")),
            strict = strict,
            reportFormat = reportFormat,
        )
    }

    private fun valueAfter(arguments: List<String>, index: Int, option: String, error: PrintStream): String? {
        val value = arguments.getOrNull(index + 1)
        if (value == null || value.startsWith('-')) {
            error.println("error: missing value for $option")
            return null
        }
        return value
    }

    private fun missingOption(error: PrintStream, option: String): Nothing? {
        error.println("error: missing required $option")
        return null
    }

    private fun resolveProjectPath(projectRoot: Path, value: String): Path {
        val path = Path.of(value)
        return if (path.isAbsolute) path.normalize() else projectRoot.resolve(path).normalize()
    }

    private fun toolFailure(error: PrintStream, message: String): Int {
        error.println("error: $message")
        return ExitStatus.FAILURE.code
    }

    private data class DependenciesOptions(
        val classRoots: List<Path>,
        val testClassRoots: List<Path>,
        val projectRoot: Path,
        val dependenciesFile: Path,
        val strict: Boolean,
        val reportFormat: ReportFormat,
    )

    private val HELP = """
        Find declared dependencies that the indexed bytecode never references.

        Usage:
          kartograph dependencies --classes <directory> [--classes <directory>]... --project <directory> \
            --dependencies <file> [--test-classes <directory-or-jar>]... \
            [--strict] [--report-format text|json]

        <file> is a TSV list: coordinate<TAB>scope<TAB>artifact. Blank lines and # comments are allowed,
        artifact paths resolve against --project. api/implementation/compileOnly entries are judged;
        testImplementation/testCompileOnly entries are judged only with --test-classes. Processor,
        runtime-only and unjudged test scopes are counted but not judged.

        This command measures bytecode references only. A dependency reported unused may still be used
        through reflection, resources, annotation processors or class roots that were not supplied;
        it is not proof that the dependency can be removed.
    """.trimIndent() + "\n"
}
