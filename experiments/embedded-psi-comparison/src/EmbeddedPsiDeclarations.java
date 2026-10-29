import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles;
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment;
import org.jetbrains.kotlin.config.CompilerConfiguration;
import org.jetbrains.kotlin.com.intellij.lang.java.JavaLanguage;
import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.com.intellij.psi.*;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.kotlin.psi.*;

/** Kotlin/Java PSI의 명시적 함수 선언과 위치만 읽는 독립 smoke adapter이다. */
public final class EmbeddedPsiDeclarations {
    private record Declaration(String file, String language, String owner, String name,
                               List<String> parameters, int start, int end, int nameStart,
                               int line, int column) {}

    private static String quote(String text) {
        StringBuilder result = new StringBuilder("\"");
        for (char ch : text.toCharArray()) {
            if (ch == '\\' || ch == '"') result.append('\\').append(ch);
            else if (ch < 32) result.append(String.format("\\u%04x", (int) ch));
            else result.append(ch);
        }
        return result.append('"').toString();
    }

    private static Declaration declaration(String relative, String language, String owner,
            String name, List<String> parameters, PsiElement element, PsiElement identifier, String source) {
        if (identifier == null) throw new IllegalArgumentException("Missing declaration identifier: " + relative);
        int offset = identifier.getTextRange().getStartOffset();
        int line = 1, column = 1;
        for (int index = 0; index < offset; index++) {
            if (source.charAt(index) == '\n') { line++; column = 1; } else column++;
        }
        return new Declaration(relative, language, owner, name, parameters,
            element.getTextRange().getStartOffset(), element.getTextRange().getEndOffset(), offset, line, column);
    }

    private static String json(Declaration row) {
        return "{\"file\":" + quote(row.file()) + ",\"language\":" + quote(row.language())
            + ",\"owner\":" + quote(row.owner()) + ",\"name\":" + quote(row.name())
            + ",\"parameterTypeText\":[" + String.join(",", row.parameters().stream().map(EmbeddedPsiDeclarations::quote).toList())
            + "],\"declarationStartUtf16\":" + row.start() + ",\"declarationEndUtf16Exclusive\":" + row.end()
            + ",\"nameStartUtf16\":" + row.nameStart() + ",\"nameLine\":" + row.line()
            + ",\"nameColumnUtf16\":" + row.column() + ",\"semanticResolution\":false}";
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: EmbeddedPsiDeclarations <source-root> <file-list>");
        Path root = Path.of(args[0]).toRealPath();
        List<String> files = Files.readAllLines(Path.of(args[1]), StandardCharsets.UTF_8);
        if (files.isEmpty() || files.stream().distinct().count() != files.size())
            throw new IllegalArgumentException("Source manifest must be nonempty and unique");
        List<Declaration> declarations = new ArrayList<>();
        Disposable disposable = Disposer.newDisposable();
        try {
            KotlinCoreEnvironment environment = KotlinCoreEnvironment.createForProduction(disposable,
                new CompilerConfiguration(), EnvironmentConfigFiles.JVM_CONFIG_FILES);
            KtPsiFactory kotlin = new KtPsiFactory(environment.getProject(), false);
            PsiFileFactory java = PsiFileFactory.getInstance(environment.getProject());
            for (String relative : files) {
                Path path = root.resolve(relative).toRealPath();
                if (!path.startsWith(root)) throw new IllegalArgumentException("Source escapes input root");
                String source = Files.readString(path, StandardCharsets.UTF_8);
                PsiFile file;
                if (relative.endsWith(".kt")) file = kotlin.createFile(path.getFileName().toString(), source);
                else if (relative.endsWith(".java"))
                    file = java.createFileFromText(path.getFileName().toString(), JavaLanguage.INSTANCE, source);
                else throw new IllegalArgumentException("Unsupported source extension: " + relative);
                if (!PsiTreeUtil.collectElementsOfType(file, PsiErrorElement.class).isEmpty())
                    throw new IllegalArgumentException("PSI parse error: " + relative);
                if (file instanceof KtFile ktFile) {
                    for (KtNamedFunction function : PsiTreeUtil.collectElementsOfType(file, KtNamedFunction.class)) {
                        List<String> owners = new ArrayList<>();
                        for (PsiElement parent = function.getParent(); parent != null; parent = parent.getParent()) {
                            if (parent instanceof KtNamedFunction) throw new IllegalArgumentException("Local function outside fixture contract");
                            if (parent instanceof KtClassOrObject owner) {
                                if (owner.getName() == null) throw new IllegalArgumentException("Anonymous owner outside fixture contract");
                                owners.add(0, owner.getName());
                            }
                        }
                        List<String> parameters = function.getValueParameters().stream().map(parameter -> {
                            if (parameter.getTypeReference() == null) throw new IllegalArgumentException("Missing explicit parameter type");
                            return parameter.getTypeReference().getText();
                        }).toList();
                        String owner = ktFile.getPackageFqName().asString();
                        if (!owners.isEmpty()) owner += "." + String.join(".", owners);
                        declarations.add(declaration(relative, "kotlin", owner, function.getName(), parameters,
                            function, function.getNameIdentifier(), source));
                    }
                } else if (file instanceof PsiJavaFile) {
                    for (PsiMethod method : PsiTreeUtil.collectElementsOfType(file, PsiMethod.class)) {
                        if (method.isConstructor()) continue;
                        PsiClass owner = method.getContainingClass();
                        if (owner == null || owner.getQualifiedName() == null)
                            throw new IllegalArgumentException("Anonymous Java owner outside fixture contract");
                        List<String> parameters = new ArrayList<>();
                        for (PsiParameter parameter : method.getParameterList().getParameters()) {
                            if (parameter.getTypeElement() == null) throw new IllegalArgumentException("Missing Java parameter type");
                            parameters.add(parameter.getTypeElement().getText());
                        }
                        declarations.add(declaration(relative, "java", owner.getQualifiedName(), method.getName(), parameters,
                            method, method.getNameIdentifier(), source));
                    }
                } else throw new IllegalStateException("Java parser did not produce PsiJavaFile");
            }
        } finally { Disposer.dispose(disposable); }
        declarations.sort(Comparator.comparing(Declaration::file).thenComparingInt(Declaration::start));
        for (Declaration declaration : declarations) System.out.println(json(declaration));
    }
}
