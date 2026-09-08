import com.sun.source.tree.*;
import com.sun.source.util.*;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.lang.model.util.*;
import javax.tools.*;
import java.nio.file.*;
import java.util.*;

/** javac 의미 해석 후 inline 전 FIELD 참조를 수집하는 독립 실험이다. */
public final class JavaReferenceCollector {
    public static void main(String[] args) throws Exception {
        Path source = Path.of(args[0]), classes = Path.of(args[1]), output = Path.of(args[2]);
        Files.createDirectories(classes);
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var files = compiler.getStandardFileManager(diagnostics, Locale.ROOT, java.nio.charset.StandardCharsets.UTF_8)) {
            var task = (JavacTask) compiler.getTask(null, files, diagnostics,
                List.of("-g", "-d", classes.toString()), null, files.getJavaFileObjects(source.toFile()));
            var units = new ArrayList<CompilationUnitTree>();
            task.parse().forEach(units::add);
            task.analyze();
            if (diagnostics.getDiagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR))
                throw new IllegalStateException("fixture semantic analysis failed");
            var trees = Trees.instance(task);
            var rows = new TreeSet<String>();
            for (var unit : units) new TreePathScanner<Void, Void>() {
                ExecutableElement caller;
                @Override public Void visitMethod(MethodTree tree, Void unused) {
                    var previous = caller;
                    caller = (ExecutableElement) trees.getElement(getCurrentPath());
                    super.visitMethod(tree, unused);
                    caller = previous;
                    return null;
                }
                void reference(Tree tree) {
                    var element = trees.getElement(getCurrentPath());
                    if (caller == null || !(element instanceof VariableElement field)
                        || field.getKind() != ElementKind.FIELD || field.getConstantValue() == null) return;
                    var owner = (TypeElement) field.getEnclosingElement();
                    var callerOwner = (TypeElement) caller.getEnclosingElement();
                    String callerId = "method:" + task.getElements().getBinaryName(callerOwner).toString().replace('.', '/')
                        + "#" + caller.getSimpleName() + "(" + caller.getParameters().stream()
                            .map(param -> descriptor(param.asType(), task.getTypes(), task.getElements())).reduce("", String::concat)
                        + ")" + descriptor(caller.getReturnType(), task.getTypes(), task.getElements());
                    String targetId = "field:" + task.getElements().getBinaryName(owner).toString().replace('.', '/')
                        + "#" + field.getSimpleName() + ":" + descriptor(field.asType(), task.getTypes(), task.getElements());
                    rows.add("javac\t" + callerId + "\t" + targetId + "\t" + trees.getSourcePositions().getStartPosition(unit, tree));
                }
                @Override public Void visitIdentifier(IdentifierTree tree, Void unused) { reference(tree); return super.visitIdentifier(tree, unused); }
                @Override public Void visitMemberSelect(MemberSelectTree tree, Void unused) { reference(tree); return super.visitMemberSelect(tree, unused); }
            }.scan(unit, null);
            task.generate();
            Files.write(output, rows);
        }
    }
    private static String descriptor(TypeMirror type, Types types, Elements elements) {
        return switch (type.getKind()) {
            case BOOLEAN -> "Z"; case BYTE -> "B"; case SHORT -> "S"; case INT -> "I";
            case LONG -> "J"; case CHAR -> "C"; case FLOAT -> "F"; case DOUBLE -> "D"; case VOID -> "V";
            case ARRAY -> "[" + descriptor(((ArrayType) type).getComponentType(), types, elements);
            case DECLARED -> "L" + elements.getBinaryName((TypeElement) ((DeclaredType) type).asElement()).toString().replace('.', '/') + ";";
            case TYPEVAR -> descriptor(types.erasure(type), types, elements);
            default -> throw new IllegalArgumentException("unsupported fixture JVM type");
        };
    }
}
