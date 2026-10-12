package dev.kartograph.collectors;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import javax.lang.model.element.*;
import javax.lang.model.util.Elements;

/** javac source Trees와 semantic origin을 보존하고 실제 출력과 교차 확인한다. */
final class JavacCallerInventory {
    private final EvidenceProtocol.Options options;
    private final Trees trees;
    private final Elements elements;
    private final Function<Element, String> identity;
    private final List<CallerInventoryProtocol.Body> bodies = new ArrayList<>();
    private final Set<Tree> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<String, String> origins = new TreeMap<>();
    private long nodes;
    private long retainedBytes;
    JavacCallerInventory(EvidenceProtocol.Options options, Trees trees, Elements elements, Function<Element, String> identity) {
        this.options = options; this.trees = trees; this.elements = elements; this.identity = identity;
    }
    void observe(TreePath declaration, EvidenceProtocol.Source source) {
        if (source == null) throw new IllegalArgumentException("caller inventory source is missing");
        new TreePathScanner<Void, Void>() {
            int depth;
            @Override public Void scan(Tree tree, Void unused) {
                if (tree == null || !visited.add(tree)) return null;
                if (++nodes > EvidenceProtocol.MAX_ROWS || ++depth > AuthoredCallProtocol.MAX_DEPTH) throw new IllegalArgumentException("caller inventory source limit exceeded");
                try { return super.scan(tree, unused); } finally { depth--; }
            }
            @Override public Void visitMethod(MethodTree method, Void unused) {
                Element element = trees.getElement(getCurrentPath());
                if (!(element instanceof ExecutableElement executable)) throw new IllegalArgumentException("caller inventory semantic method is missing");
                String origin = elements.getOrigin(element).name();
                String kind;
                if (executable.getKind() == ElementKind.CONSTRUCTOR) kind = origin.equals("EXPLICIT") ? "CONSTRUCTOR" : "IMPLICIT_CONSTRUCTOR";
                else if (executable.getModifiers().contains(Modifier.ABSTRACT)) kind = "ABSTRACT_METHOD";
                else if (executable.getModifiers().contains(Modifier.NATIVE)) kind = "NATIVE_METHOD";
                else if (executable.getEnclosingElement() instanceof TypeElement owner && owner.getNestingKind() == NestingKind.LOCAL) kind = "LOCAL_METHOD";
                else if (executable.getEnclosingElement() instanceof TypeElement owner && owner.getNestingKind() == NestingKind.ANONYMOUS) kind = "ANONYMOUS_METHOD";
                else kind = "ORDINARY_METHOD";
                String id = identity.apply(executable);
                add(getCurrentPath(), source, kind, method.getBody() != null, "JAVAC_" + origin, id);
                if (id != null) origins.put(id, "JAVAC_" + origin);
                if (method.getDefaultValue() != null)
                    add(new TreePath(getCurrentPath(), method.getDefaultValue()), source, "UNSUPPORTED", true, "JAVAC_ANNOTATION_DEFAULT", null);
                return super.visitMethod(method, unused);
            }
            @Override public Void visitVariable(VariableTree variable, Void unused) {
                if (getCurrentPath().getParentPath().getLeaf() instanceof ClassTree && variable.getInitializer() != null)
                    add(getCurrentPath(), source, "PROPERTY_INITIALIZER", true, "JAVAC_TREE_FIELD", null);
                return super.visitVariable(variable, unused);
            }
            @Override public Void visitBlock(BlockTree block, Void unused) {
                if (getCurrentPath().getParentPath().getLeaf() instanceof ClassTree)
                    add(getCurrentPath(), source, block.isStatic() ? "STATIC_INITIALIZER" : "INSTANCE_INITIALIZER", true, "JAVAC_TREE_BLOCK", null);
                return super.visitBlock(block, unused);
            }
            @Override public Void visitLambdaExpression(LambdaExpressionTree lambda, Void unused) {
                add(getCurrentPath(), source, "LAMBDA", true, "JAVAC_TREE_LAMBDA", null);
                return super.visitLambdaExpression(lambda, unused);
            }
        }.scan(declaration, null);
    }
    private void add(TreePath path, EvidenceProtocol.Source source, String kind, boolean present, String origin, String method) {
        if (bodies.size() >= EvidenceProtocol.MAX_ROWS) throw new IllegalArgumentException("caller inventory body limit exceeded");
        long start = trees.getSourcePositions().getStartPosition(path.getCompilationUnit(), path.getLeaf());
        long end = trees.getSourcePositions().getEndPosition(path.getCompilationUnit(), path.getLeaf());
        if (start < 0 || end <= start || end > Integer.MAX_VALUE) { start = -1; end = -1; }
        retainedBytes += 512L + 4L * (source.path().length() + (method == null ? 0 : method.length()));
        if (retainedBytes > EvidenceProtocol.MAX_ENCODED_BYTES) throw new IllegalArgumentException("caller inventory byte limit exceeded");
        bodies.add(new CallerInventoryProtocol.Body("b" + bodies.size(), source.path(), (int) start, (int) end, kind, present, origin,
            method == null ? List.of() : List.of(method)));
    }
    void finish(String compiler, String artifact, Collection<EvidenceProtocol.Source> sources, Set<String> generatedOwners) {
        List<CallerOutputHeaders.Output> outputs = CallerOutputHeaders.readDirectory(options.classOutput());
        Set<String> actualOwners = new TreeSet<>();
        Set<String> actualMethods = new TreeSet<>();
        for (var output : outputs) {
            if (!output.owner().isEmpty()) actualOwners.add(output.owner());
            for (var method : output.methods()) actualMethods.add(method.identity());
        }
        if (!actualOwners.equals(generatedOwners)) throw new IllegalArgumentException("caller inventory output differs from actual javac generation");
        List<CallerInventoryProtocol.Body> resolved = bodies.stream().map(body -> new CallerInventoryProtocol.Body(body.key(), body.path(),
            body.start(), body.end(), body.kind(), body.present(), body.origin(), body.methods().stream().filter(actualMethods::contains).toList())).toList();
        CallerInventoryProtocol.write(options, compiler, artifact, sources, resolved, outputs, origins);
    }
}
