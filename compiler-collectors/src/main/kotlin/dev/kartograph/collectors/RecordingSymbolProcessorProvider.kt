package dev.kartograph.collectors

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import java.io.OutputStream

/** 실제 KSP provider와 CodeGenerator를 위임하고 정상 close 및 finish를 관찰한다. */
public class RecordingSymbolProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        val identity = requireNotNull(environment.options["kartograph.processor"]) { "select one delegate KSP provider" }
        require(identity != javaClass.name) { "recursive KSP provider" }
        val provider = Class.forName(identity).getDeclaredConstructor().newInstance() as SymbolProcessorProvider
        val session = ProcessorOutputSession(environment.options, "ksp", provider.javaClass, javaClass)
        val original = environment.codeGenerator
        val generator = object : CodeGenerator by original {
            private fun create(extension: String, action: () -> OutputStream): OutputStream {
                val before = original.generatedFile.map { it.canonicalFile }.toSet()
                val output = action()
                val paths = original.generatedFile.map { it.canonicalFile }.toSet() - before
                if (paths.size != 1) {
                    output.close()
                    error("KSP CodeGenerator did not expose exactly one new output")
                }
                val path = paths.single().toPath()
                session.opened(path)
                val kind = when (extension) { "java", "kt" -> "source"; "class" -> "class"; else -> "resource" }
                return session.stream(output, path, kind)
            }
            override fun createNewFile(dependencies: Dependencies, packageName: String, fileName: String, extensionName: String): OutputStream =
                create(extensionName) { original.createNewFile(dependencies, packageName, fileName, extensionName) }
            override fun createNewFileByPath(dependencies: Dependencies, path: String, extensionName: String): OutputStream =
                create(extensionName) { original.createNewFileByPath(dependencies, path, extensionName) }
        }
        var featureRegistration: SymbolProcessor? = null
        session.beginCallback()
        val delegate = provider.create(SymbolProcessorEnvironment(environment.options, environment.kotlinVersion, generator,
            environment.logger, environment.apiVersion, environment.compilerVersion, environment.platforms, environment.kspVersion) { processor ->
                require(featureRegistration == null) { "KSP feature registration was repeated" }
                featureRegistration = processor
            })
        session.endCallback()
        val wrapper = object : SymbolProcessor {
            override fun process(resolver: Resolver): List<KSAnnotated> {
                session.beginCallback()
                val result = delegate.process(resolver)
                session.endCallback()
                return result
            }
            override fun finish() {
                session.beginCallback()
                delegate.finish()
                session.endCallback()
                session.finish()
            }
            override fun onError() { delegate.onError() }
        }
        featureRegistration?.let {
            require(it === delegate) { "KSP feature registration selected a different processor" }
            environment.registerProcessorForNewFeatures(wrapper)
        }
        return wrapper
    }
}
