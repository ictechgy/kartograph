@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

import com.intellij.openapi.util.Disposer
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.KaSuccessCallInfo
import org.jetbrains.kotlin.analysis.api.resolution.singleFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtLibraryModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSdkModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.*
import java.nio.file.Path

/** AA와 Java PSI가 직접 해석한 call target의 source anchor만 내보낸다. */
fun main(args: Array<String>) {
    require(args.size == 2) { "Usage: PsiResolvedCallsKt <source-root> <fixture-stdlib-jar>" }
    val root = Path.of(args[0]).toRealPath()
    val stdlib = Path.of(args[1]).toRealPath()
    val disposable = Disposer.newDisposable()
    val output = mutableListOf<String>()
    try {
        val session = buildStandaloneAnalysisAPISession(disposable) {
            buildKtModuleProvider {
                platform = JvmPlatforms.defaultJvmPlatform
                val sdk = buildKtSdkModule {
                    addBinaryRootsFromJdkHome(Path.of(System.getProperty("java.home")), isJre = false)
                    platform = JvmPlatforms.defaultJvmPlatform
                    libraryName = "jdk"
                }
                val library = buildKtLibraryModule {
                    addBinaryRoot(stdlib)
                    platform = JvmPlatforms.defaultJvmPlatform
                    libraryName = "stdlib"
                }
                addModule(buildKtSourceModule {
                    addSourceRoot(root)
                    addRegularDependency(sdk)
                    addRegularDependency(library)
                    platform = JvmPlatforms.defaultJvmPlatform
                    moduleName = "same-two-source-files"
                })
            }
        }
        session.application.runReadAction(Runnable {
            val files = session.modulesWithFiles.values.flatten().distinctBy { it.virtualFile.path }
            require(files.count { it.name.endsWith(".kt") } == 1 && files.count { it.name.endsWith(".java") } == 1) {
                "Expected exactly the two fixture source files"
            }
            for (file in files.sortedBy { it.virtualFile.path }) {
                require(PsiTreeUtil.collectElementsOfType(file, PsiErrorElement::class.java).isEmpty()) {
                    "PSI parse errors in ${file.name}"
                }
                when (file) {
                    is KtFile -> for (call in PsiTreeUtil.collectElementsOfType(file, KtCallExpression::class.java)) {
                        val caller = PsiTreeUtil.getParentOfType(call, KtNamedFunction::class.java)
                        val target = analyze(call) {
                            val info = call.resolveToCall()
                            if (info is KaSuccessCallInfo) info.singleFunctionCallOrNull()?.symbol?.psi else null
                        }
                        output += callRow(root, "kotlin-aa", call, caller, target, "method")
                    }
                    is PsiJavaFile -> {
                        for (call in PsiTreeUtil.collectElementsOfType(file, PsiMethodCallExpression::class.java)) {
                            output += callRow(root, "java-psi", call,
                                PsiTreeUtil.getParentOfType(call, PsiMethod::class.java), call.resolveMethod(), "method")
                        }
                        for (call in PsiTreeUtil.collectElementsOfType(file, PsiNewExpression::class.java)) {
                            output += callRow(root, "java-psi", call,
                                PsiTreeUtil.getParentOfType(call, PsiMethod::class.java), call.resolveConstructor(), "constructor")
                        }
                    }
                }
            }
        })
    } finally { Disposer.dispose(disposable) }
    output.sorted().forEach(::println)
    System.out.flush()
    check(!System.out.checkError()) { "Failed to flush PSI output" }
    // 분석·dispose·출력 성공 뒤에만 격리된 CLI를 끝낸다. 라이브러리 종료 보장은 아니다.
    kotlin.system.exitProcess(0)
}

private fun quote(text: String): String = buildString {
    append('"')
    for (character in text) when {
        character == '"' || character == '\\' -> { append('\\'); append(character) }
        character.code < 32 -> append("\\u%04x".format(character.code))
        else -> append(character)
    }
    append('"')
}

private fun anchor(root: Path, raw: PsiElement?): String {
    if (raw == null) return "null"
    val target = raw.navigationElement
    val file = target.containingFile?.virtualFile ?: return "null"
    val path = Path.of(file.path).toAbsolutePath().normalize()
    if (!path.startsWith(root)) return "null"
    val identifier = when (target) {
        is KtNamedDeclaration -> target.nameIdentifier
        is PsiNameIdentifierOwner -> target.nameIdentifier
        else -> null
    } ?: return "null"
    return "{\"file\":${quote(root.relativize(path).toString().replace('\\', '/'))}," +
        "\"nameStartUtf16\":${identifier.textRange.startOffset},\"name\":${quote(identifier.text)}}"
}

private fun callRow(root: Path, engine: String, call: PsiElement, caller: PsiElement?, target: PsiElement?, kind: String): String {
    val source = root.relativize(Path.of(call.containingFile.virtualFile.path).toAbsolutePath().normalize()).toString().replace('\\', '/')
    return "{\"engine\":${quote(engine)},\"file\":${quote(source)},\"callStartUtf16\":${call.textRange.startOffset}," +
        "\"callEndUtf16Exclusive\":${call.textRange.endOffset},\"kind\":${quote(kind)}," +
        "\"caller\":${anchor(root, caller)},\"target\":${anchor(root, target)}," +
        "\"resolved\":${target != null}}"
}
