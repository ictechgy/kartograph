import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.jetbrains.kotlin.cli.jvm.compiler.*;
import org.jetbrains.kotlin.config.CompilerConfiguration;
import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.kotlin.psi.*;
import org.jetbrains.kotlin.lexer.KtTokens;

/** 원본 Kotlin PSI의 선언 범위·source signature를 읽으며 제품 그래프는 사용하지 않는다. */
public class SourceDeclarations {
    static String quote(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '\\' || c == '"') b.append('\\').append(c);
            else if (c < 32) b.append(String.format("\\u%04x", (int)c));
            else b.append(c);
        }
        return b.append('"').toString();
    }
    static String array(List<String> list) {
        return "[" + String.join(",", list.stream().map(SourceDeclarations::quote).toList()) + "]";
    }
    static int line(String text, int offset) {
        int line = 1;
        for (int i = 0; i < Math.min(offset, text.length()); i++) if (text.charAt(i) == '\n') line++;
        return line;
    }
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Disposable disposable = Disposer.newDisposable();
        try {
            KotlinCoreEnvironment env = KotlinCoreEnvironment.createForProduction(disposable,
                new CompilerConfiguration(), EnvironmentConfigFiles.JVM_CONFIG_FILES);
            KtPsiFactory factory = new KtPsiFactory(env.getProject(), false);
            for (String relative : Files.readAllLines(Path.of(args[1]), StandardCharsets.UTF_8)) {
                String text = Files.readString(root.resolve(relative));
                KtFile file = factory.createFile(Path.of(relative).getFileName().toString(), text);
                for (KtNamedFunction fun : PsiTreeUtil.collectElementsOfType(file, KtNamedFunction.class)) {
                    if (fun.getName() == null) continue;
                    List<String> owners = new ArrayList<>();
                    boolean local = false;
                    for (PsiElement parent = fun.getParent(); parent != null; parent = parent.getParent()) {
                        if (parent instanceof KtNamedFunction) local = true;
                        if (parent instanceof KtClassOrObject) {
                            String name = ((KtClassOrObject)parent).getName();
                            if (name == null) name = "<anonymous>";
                            owners.add(0, name);
                        }
                    }
                    List<String> types = new ArrayList<>(), named = new ArrayList<>();
                    for (KtParameter parameter : fun.getValueParameters()) {
                        String type = parameter.getTypeReference() == null ? "<inferred>" : parameter.getTypeReference().getText();
                        String modifier = parameter.hasModifier(KtTokens.VARARG_KEYWORD) ? "vararg " : "";
                        types.add(modifier + type); named.add(modifier + parameter.getName() + ": " + type);
                    }
                    String receiver = fun.getReceiverTypeReference() == null ? null : fun.getReceiverTypeReference().getText();
                    System.out.println("{\"file\":" + quote(relative) + ",\"package\":" + quote(file.getPackageFqName().asString())
                        + ",\"name\":" + quote(fun.getName()) + ",\"owners\":" + array(owners)
                        + ",\"types\":" + array(types) + ",\"namedParameters\":" + array(named)
                        + ",\"receiver\":" + quote(receiver) + ",\"local\":" + local
                        + ",\"suspend\":" + fun.hasModifier(KtTokens.SUSPEND_KEYWORD)
                        + ",\"startLine\":" + line(text, fun.getTextRange().getStartOffset())
                        + ",\"endLine\":" + line(text, fun.getTextRange().getEndOffset()) + "}");
                }
            }
        } finally { Disposer.dispose(disposable); }
    }
}
