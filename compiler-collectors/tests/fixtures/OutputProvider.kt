package fixture

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.KSAnnotated
import java.nio.file.Files
import java.nio.file.Path

class OutputProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor = object : SymbolProcessor {
        private var emitted = false
        override fun process(resolver: Resolver): List<KSAnnotated> {
            if (emitted) return emptyList()
            emitted = true
            val generator = environment.codeGenerator
            val mode = environment.options["fixture.mode"] ?: "normal"
            val output = generator.createNewFile(Dependencies.ALL_FILES, "fixture", "Generated", "kt")
            output.write((if (mode == "broken") "invalid Kotlin !!!" else "package fixture\nobject Generated { fun value() = 7 }").toByteArray())
            if (mode != "unclosed") output.close()
            generator.createNewFileByPath(Dependencies.ALL_FILES, "fixture/proof", "txt").use { it.write("processor-resource".toByteArray()) }
            generator.createNewFile(Dependencies.ALL_FILES, "fixture", "OutputBinary", "class").use { stream ->
                javaClass.getResourceAsStream("/fixture/OutputBinary.class")!!.use { it.copyTo(stream) }
            }
            Files.writeString(Path.of(environment.options.getValue("kartograph.outputs.directRoot"), "direct.txt"), "processor-direct")
            return emptyList()
        }
    }
}
