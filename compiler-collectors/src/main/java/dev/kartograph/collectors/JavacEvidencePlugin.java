package dev.kartograph.collectors;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ErroneousTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import com.sun.source.util.SourcePositions;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.WeakHashMap;
import java.util.Collections;
import java.util.IdentityHashMap;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.JavaFileObject;

/** Javac 의미 분석 결과와 실제 task lifecycle을 연결하는 optional compiler plugin이다. */
public final class JavacEvidencePlugin implements Plugin {
    private static final Map<JavacTask, JavacEvidenceSession> sessions = new WeakHashMap<>();

    @Override public String getName() { return "KartographEvidence"; }

    @Override public void init(JavacTask task, String... arguments) {
        EvidenceProtocol.Options options = EvidenceProtocol.parse("javac-constants", arguments);
        EvidenceProtocol.invalidateOutput(options);
        install(task, options);
    }

    static synchronized JavacEvidenceSession install(JavacTask task, EvidenceProtocol.Options options) {
        JavacEvidenceSession existing = sessions.get(task);
        if (existing != null) {
            if (!existing.options().equals(options)) throw new IllegalArgumentException("compiler evidence plugin was configured twice");
            return existing;
        }
        JavacEvidenceSession session = new JavacEvidenceSession(task, options);
        sessions.put(task, session);
        task.addTaskListener(session);
        return session;
    }

    static synchronized void clear(JavacEvidenceSession session) {
        sessions.entrySet().removeIf(entry -> entry.getValue() == session);
    }
}

/** 한 javac task의 실제 source unit, compiler semantic edge와 성공 완료를 모은다. */
final class JavacEvidenceSession implements TaskListener {
    private record PendingCall(EvidenceProtocol.CallPosition position, String targetOwner, String targetSource, long budgetBytes) {}

    private final EvidenceProtocol.Options options;
    private final Trees trees;
    private final Elements elements;
    private final Types types;
    private final String initialToken;
    private final String artifact;
    private final Map<String, EvidenceProtocol.Source> sources = new TreeMap<>();
    private final Set<ClassTree> parsedClasses = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<ClassTree> analyzedClasses = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<TypeElement> analyzedTypes = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<String> analyzedOwners = new TreeSet<>();
    private final Set<TypeElement> generatedTypes = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<ClassTree> scanned = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<CompilationUnitTree, CharSequence> sourceContents = new IdentityHashMap<>();
    private final Map<CompilationUnitTree, Integer> remainingTopLevelScans = new IdentityHashMap<>();
    private final Map<TypeElement, String> targetSources = new IdentityHashMap<>();
    private final EvidenceProtocol.CallPositionBuffer callPositionBuffer = new EvidenceProtocol.CallPositionBuffer();
    private final ArrayDeque<PendingCall> pendingCalls = new ArrayDeque<>();
    private final Set<EvidenceProtocol.Edge> edges = new TreeSet<>((left, right) -> {
        int source = left.source().compareTo(right.source());
        if (source != 0) return source;
        int target = left.target().compareTo(right.target());
        return target != 0 ? target : left.kind().compareTo(right.kind());
    });
    private int unmapped;
    private boolean invalid;
    private String invalidReason;
    private boolean completed;

    JavacEvidenceSession(JavacTask task, EvidenceProtocol.Options options) {
        this.options = options;
        this.trees = Trees.instance(task);
        this.elements = task.getElements();
        this.types = task.getTypes();
        this.initialToken = EvidenceProtocol.readToken(options);
        this.artifact = EvidenceProtocol.artifactFingerprint(JavacEvidencePlugin.class);
        if (options.collector().equals("dagger-bindings")) DaggerEvidenceFiles.begin(options, initialToken, artifact);
        if (options.collector().equals("javac-processors")) ProcessorEvidenceFiles.begin(options, artifact);
    }

    EvidenceProtocol.Options options() { return options; }

    @Override public void started(TaskEvent event) {
        if (event.getKind() == TaskEvent.Kind.PARSE) observeSource(event.getSourceFile());
    }

    @Override public void finished(TaskEvent event) {
        switch (event.getKind()) {
            case PARSE -> {
                observeSource(event.getSourceFile());
                CompilationUnitTree unit = event.getCompilationUnit();
                if (unit != null) {
                    int topLevelClasses = 0;
                    for (var declaration : unit.getTypeDecls()) {
                        if (declaration instanceof ClassTree type) {
                            parsedClasses.add(type);
                            topLevelClasses++;
                        }
                    }
                    if (options.callPositions() && topLevelClasses > 0) remainingTopLevelScans.put(unit, topLevelClasses);
                    new TreeScanner<Void, Void>() {
                        @Override public Void visitErroneous(ErroneousTree tree, Void unused) {
                            fail("javac source contains a syntax error");
                            return null;
                        }
                    }.scan(unit, null);
                }
            }
            case ANALYZE -> {
                String source = recordSource(event.getSourceFile());
                TypeElement type = event.getTypeElement();
                if (source != null && type != null && !type.getSimpleName().contentEquals("package-info")) {
                    TreePath path = trees.getPath(type);
                    if (path == null && event.getCompilationUnit() != null) {
                        TreePath[] found = new TreePath[1];
                        new TreePathScanner<Void, Void>() {
                            @Override public Void visitClass(ClassTree tree, Void unused) {
                                if (trees.getElement(getCurrentPath()) == type) found[0] = getCurrentPath();
                                return super.visitClass(tree, unused);
                            }
                        }.scan(event.getCompilationUnit(), null);
                        path = found[0];
                    }
                    if (path == null && event.getCompilationUnit() != null && event.getCompilationUnit().getTypeDecls().stream().noneMatch(ClassTree.class::isInstance)) {
                        // package-info 등은 javac의 익명 분석 정점을 만들 수 있지만 class 출력이 필요하지 않다.
                        return;
                    }
                    if (path == null || !(path.getLeaf() instanceof ClassTree tree)) fail("javac attributed class is unavailable");
                    else {
                        analyzedTypes.add(type);
                        if (options.callPositions()) {
                            String analyzedOwner = owner(type);
                            if (analyzedOwner != null) analyzedOwners.add(analyzedOwner);
                            cacheTargetSource(type, source);
                        }
                        analyzedClasses.add(tree);
                        if (options.collector().equals("javac-constants") && scanned.add(tree)) scan(path);
                    }
                }
            }
            case GENERATE -> {
                if (event.getTypeElement() != null) generatedTypes.add(event.getTypeElement());
            }
            case COMPILATION -> {
                try { complete(); }
                finally {
                    if (options.collector().equals("dagger-bindings")) DaggerEvidenceFiles.cleanup(options);
                    if (options.collector().equals("javac-processors")) ProcessorEvidenceFiles.cleanup(options);
                    sourceContents.clear();
                    remainingTopLevelScans.clear();
                    targetSources.clear();
                    JavacEvidencePlugin.clear(this);
                }
            }
            default -> { }
        }
    }

    private void observeSource(JavaFileObject sourceFile) {
        recordSourcePath(sourcePath(sourceFile));
    }

    private String recordSource(JavaFileObject sourceFile) {
        String source = sourcePath(sourceFile);
        recordSourcePath(source);
        return source;
    }

    private void recordSourcePath(String relative) {
        if (relative == null) return;
        try {
            Path file = options.root().toAbsolutePath().normalize().resolve(relative);
            EvidenceProtocol.Source value = EvidenceProtocol.source(options.root(), file);
            EvidenceProtocol.Source previous = sources.putIfAbsent(relative, value);
            if (previous != null && !previous.equals(value)) fail("source changed during javac analysis");
        } catch (RuntimeException error) {
            fail("javac source inventory is unavailable");
        }
    }

    private String sourcePath(JavaFileObject sourceFile) {
        if (sourceFile == null) return null;
        try {
            URI uri = sourceFile.toUri();
            if (!"file".equalsIgnoreCase(uri.getScheme())) {
                fail("javac compiler unit is not a file");
                return null;
            }
            Path file = Path.of(uri).toRealPath();
            Path root = options.root().toAbsolutePath().normalize();
            if (!file.startsWith(root)) {
                fail("javac compiler unit is outside the project root");
                return null;
            }
            return root.relativize(file).toString().replace('\\', '/');
        } catch (RuntimeException | java.io.IOException error) {
            fail("javac compiler unit path is unavailable");
            return null;
        }
    }

    private void scan(TreePath path) {
        if (path == null) {
            fail("javac semantic unit is unavailable");
            return;
        }
        try {
        new TreePathScanner<Void, Void>() {
            private ExecutableElement caller;
            private ExecutableElement callCaller;

            @Override public Void visitClass(ClassTree tree, Void unused) {
                ExecutableElement previousCallCaller = callCaller;
                if (options.callPositions()) {
                    callCaller = null;
                    Element element = trees.getElement(getCurrentPath());
                    if (element instanceof TypeElement type) {
                        String analyzedOwner = owner(type);
                        if (analyzedOwner != null) analyzedOwners.add(analyzedOwner);
                        CompilationUnitTree unit = getCurrentPath().getCompilationUnit();
                        cacheTargetSource(type, unit == null ? null : sourcePath(unit.getSourceFile()));
                    }
                }
                try {
                    return super.visitClass(tree, unused);
                } finally {
                    callCaller = previousCallCaller;
                }
            }

            @Override public Void visitMethod(MethodTree tree, Void unused) {
                ExecutableElement previous = caller;
                ExecutableElement previousCallCaller = callCaller;
                Element element = trees.getElement(getCurrentPath());
                if (element == null) { unmapped++; return null; }
                caller = element instanceof ExecutableElement executable ? executable : null;
                if (options.callPositions()) callCaller = caller;
                try {
                    super.visitMethod(tree, unused);
                    return null;
                } finally {
                    caller = previous;
                    callCaller = previousCallCaller;
                }
            }

            private void reference() {
                Element element = trees.getElement(getCurrentPath());
                if (element == null) { unmapped++; return; }
                if (!(element instanceof VariableElement field) || field.getKind() != ElementKind.FIELD || field.getConstantValue() == null) return;
                if (caller == null) {
                    unmapped++;
                    return;
                }
                String source = identity(caller);
                String target = identity(field);
                if (source == null || target == null) unmapped++;
                else edges.add(new EvidenceProtocol.Edge(source, target, "constant"));
            }

            @Override public Void visitMethodInvocation(MethodInvocationTree tree, Void unused) {
                if (options.callPositions()) recordCall(getCurrentPath(), tree, callCaller);
                return super.visitMethodInvocation(tree, unused);
            }

            @Override public Void visitLambdaExpression(LambdaExpressionTree tree, Void unused) {
                if (!options.callPositions()) return super.visitLambdaExpression(tree, unused);
                ExecutableElement previousCallCaller = callCaller;
                callCaller = null;
                try {
                    return super.visitLambdaExpression(tree, unused);
                } finally {
                    callCaller = previousCallCaller;
                }
            }

            @Override public Void visitIdentifier(IdentifierTree tree, Void unused) {
                reference();
                return super.visitIdentifier(tree, unused);
            }

            @Override public Void visitMemberSelect(MemberSelectTree tree, Void unused) {
                reference();
                return super.visitMemberSelect(tree, unused);
            }
        }.scan(path, null);
        } finally {
            releaseSourceContent(path);
        }
    }

    private void releaseSourceContent(TreePath path) {
        if (!options.callPositions() || !(path.getLeaf() instanceof ClassTree type) || !parsedClasses.contains(type)) return;
        CompilationUnitTree unit = path.getCompilationUnit();
        Integer remaining = remainingTopLevelScans.get(unit);
        if (remaining == null) return;
        if (remaining <= 1) {
            remainingTopLevelScans.remove(unit);
            sourceContents.remove(unit);
        } else {
            remainingTopLevelScans.put(unit, remaining - 1);
        }
    }

    private String identity(Element element) {
        if (element instanceof TypeElement type) {
            String owner = owner(type);
            return owner == null ? null : "class:" + owner;
        }
        if (element instanceof VariableElement field && field.getKind() == ElementKind.FIELD) {
            String owner = owner((TypeElement) field.getEnclosingElement());
            String descriptor = descriptor(field.asType());
            return owner == null || descriptor == null ? null : "field:" + owner + "#" + field.getSimpleName() + ":" + descriptor;
        }
        if (element instanceof ExecutableElement method && method.getEnclosingElement() instanceof TypeElement type) {
            if (method.getKind() == ElementKind.CONSTRUCTOR &&
                (type.getKind() == ElementKind.ENUM || type.getNestingKind() == NestingKind.LOCAL || type.getNestingKind() == NestingKind.ANONYMOUS ||
                 type.getNestingKind() == NestingKind.MEMBER && !type.getModifiers().contains(Modifier.STATIC))) return null;
            String owner = owner(type);
            if (owner == null) return null;
            String name = method.getKind() == ElementKind.CONSTRUCTOR ? "<init>" : method.getSimpleName().toString();
            List<String> arguments = new ArrayList<>();
            for (VariableElement parameter : method.getParameters()) {
                String descriptor = descriptor(parameter.asType());
                if (descriptor == null) return null;
                arguments.add(descriptor);
            }
            String returns = descriptor(method.getReturnType());
            return returns == null ? null : "method:" + owner(type) + "#" + name + "(" + String.join("", arguments) + ")" + returns;
        }
        return null;
    }

    private void recordCall(TreePath path, MethodInvocationTree invocation, ExecutableElement caller) {
        if (invalid) return;
        try {
            callPositionBuffer.observe();
            if (path == null || caller == null) {
                callPositionBuffer.unmapped();
                return;
            }
            CompilationUnitTree unit = path.getCompilationUnit();
            String sourcePath = unit == null ? null : sourcePath(unit.getSourceFile());
            String sourceId = identity(caller);
            Element element = trees.getElement(path);
            ExecutableElement target = element instanceof ExecutableElement executable ? executable : null;
            TypeElement targetType = target != null && target.getEnclosingElement() instanceof TypeElement type ? type : null;
            String targetId = target == null ? null : identity(target);
            String targetOwner = targetType == null ? null : owner(targetType);
            String targetSource = targetType == null ? null : targetSource(targetType);
            EvidenceProtocol.Source source = sourcePath == null ? null : sources.get(sourcePath);
            if (unit == null || sourceId == null || targetId == null || targetOwner == null || source == null ||
                targetSource == null || !sources.containsKey(targetSource)) {
                callPositionBuffer.unmapped();
                return;
            }
            long[] span = selectorSpan(unit, invocation);
            if (span == null || unit.getLineMap() == null) {
                callPositionBuffer.unmapped();
                return;
            }
            long line = unit.getLineMap().getLineNumber(span[0]);
            long column = unit.getLineMap().getColumnNumber(span[0]);
            if (line < 1 || line > Integer.MAX_VALUE || column < 1 || column > Integer.MAX_VALUE) {
                callPositionBuffer.unmapped();
                return;
            }
            var position = new EvidenceProtocol.CallPosition(sourceId, targetId, source,
                (int) span[0], (int) span[1], (int) line, (int) column);
            long budgetBytes = EvidenceProtocol.pendingCallBytes(position, targetOwner, targetSource);
            callPositionBuffer.retainPending(budgetBytes);
            pendingCalls.addLast(new PendingCall(position, targetOwner, targetSource, budgetBytes));
        } catch (EvidenceProtocol.CallPositionLimitException error) {
            pendingCalls.clear();
            fail("javac call position collection exceeded its resource limit");
            throw error;
        } catch (RuntimeException error) {
            pendingCalls.clear();
            fail("invalid javac compiler call position collection");
            throw error;
        }
    }

    private String targetSource(TypeElement type) {
        TypeElement outermost = outermostType(type);
        if (targetSources.containsKey(outermost)) return targetSources.get(outermost);
        TreePath declaration = trees.getPath(outermost);
        CompilationUnitTree unit = declaration == null ? null : declaration.getCompilationUnit();
        String source = unit == null ? null : sourcePath(unit.getSourceFile());
        targetSources.put(outermost, source);
        return source;
    }

    private void cacheTargetSource(TypeElement type, String source) {
        if (source != null) targetSources.putIfAbsent(outermostType(type), source);
    }

    private TypeElement outermostType(TypeElement type) {
        TypeElement outermost = type;
        Element current = type;
        while (current.getEnclosingElement() != null) {
            current = current.getEnclosingElement();
            if (current instanceof TypeElement enclosingType) outermost = enclosingType;
        }
        return outermost;
    }

    private void finalizeCalls() {
        try {
            while (!pendingCalls.isEmpty()) {
                PendingCall pending = pendingCalls.removeFirst();
                if (!analyzedOwners.contains(pending.targetOwner()) || !sources.containsKey(pending.targetSource())) {
                    callPositionBuffer.dropPending(pending.budgetBytes());
                    callPositionBuffer.unmapped();
                } else {
                    callPositionBuffer.replacePending(pending.budgetBytes(), pending.position());
                }
            }
        } catch (EvidenceProtocol.CallPositionLimitException error) {
            pendingCalls.clear();
            fail("javac call position collection exceeded its resource limit");
            throw error;
        } catch (RuntimeException error) {
            pendingCalls.clear();
            fail("invalid javac compiler call position collection");
            throw error;
        }
    }

    private long[] selectorSpan(CompilationUnitTree unit, MethodInvocationTree invocation) {
        Tree selector = invocation.getMethodSelect();
        SourcePositions positions = trees.getSourcePositions();
        long start = positions.getStartPosition(unit, selector);
        long end = positions.getEndPosition(unit, selector);
        String name;
        if (selector instanceof MemberSelectTree member) {
            name = member.getIdentifier().toString();
            if (end < 0 || name.isEmpty()) return null;
            start = end - name.length();
        } else if (selector instanceof IdentifierTree identifier) {
            name = identifier.getName().toString();
        } else {
            return null;
        }
        if (name.isEmpty() || start < 0 || end <= start || end > Integer.MAX_VALUE) return null;
        CharSequence content = sourceContent(unit);
        return content != null && end <= content.length() &&
            content.subSequence((int) start, (int) end).toString().equals(name) ? new long[]{start, end} : null;
    }

    private CharSequence sourceContent(CompilationUnitTree unit) {
        if (sourceContents.containsKey(unit)) return sourceContents.get(unit);
        try {
            CharSequence content = unit.getSourceFile().getCharContent(false);
            sourceContents.put(unit, content);
            return content;
        } catch (java.io.IOException | RuntimeException error) {
            sourceContents.put(unit, null);
            fail("javac compiler source content is unavailable");
            return null;
        }
    }

    private String owner(TypeElement type) {
        try {
            String value = elements.getBinaryName(type).toString().replace('.', '/');
            return value.isBlank() ? null : value;
        } catch (RuntimeException error) {
            return null;
        }
    }

    private String descriptor(TypeMirror type) {
        if (type == null) return null;
        return switch (type.getKind()) {
            case BOOLEAN -> "Z";
            case BYTE -> "B";
            case SHORT -> "S";
            case INT -> "I";
            case LONG -> "J";
            case CHAR -> "C";
            case FLOAT -> "F";
            case DOUBLE -> "D";
            case VOID -> "V";
            case ARRAY -> {
                String component = descriptor(((ArrayType) type).getComponentType());
                yield component == null ? null : "[" + component;
            }
            case DECLARED, ERROR -> {
                if (!(((DeclaredType) type).asElement() instanceof TypeElement element)) yield null;
                String owner = owner(element);
                yield owner == null ? null : "L" + owner + ";";
            }
            case TYPEVAR, INTERSECTION -> descriptor(types.erasure(type));
            default -> null;
        };
    }

    private synchronized void complete() {
        if (completed) {
            JavacEvidencePlugin.clear(this);
            return;
        }
        if (invalid) {
            JavacEvidencePlugin.clear(this);
            return;
        }
        try {
        if (sources.isEmpty() || !analyzedClasses.containsAll(parsedClasses) || !generatedTypes.containsAll(analyzedTypes)) {
                fail("javac did not complete every observed source unit");
                return;
            }
            if (!EvidenceProtocol.readToken(options).equals(initialToken)) {
                fail("compiler evidence token changed during javac");
                return;
            }
            for (EvidenceProtocol.Source source : sources.values()) {
                EvidenceProtocol.Source current = EvidenceProtocol.source(options.root(), options.root().resolve(source.path()));
                if (!current.equals(source)) {
                    fail("source changed during javac");
                    return;
                }
            }
            if (options.collector().equals("javac-constants")) {
                if (options.callPositions()) {
                    finalizeCalls();
                    EvidenceProtocol.write(options, Runtime.version().toString(), artifact, sources.values(), unmapped, edges, null,
                        callPositionBuffer.positionsList(), callPositionBuffer.observedCount(), callPositionBuffer.unmappedCount(), callPositionBuffer.ambiguousCount());
                } else {
                    EvidenceProtocol.write(options, Runtime.version().toString(), artifact, sources.values(), unmapped, edges);
                }
            } else if (options.collector().equals("javac-processors")) {
                if (!ProcessorEvidenceFiles.hasStage(options)) {
                    fail("recording processor did not publish a round; select RecordingProcessor and enable annotation processing");
                    return;
                }
                var generation = ProcessorEvidenceFiles.read(options, artifact);
                EvidenceProtocol.write(options, Runtime.version().toString(), artifact, sources.values(), 0, java.util.List.of(), generation);
            } else {
                DaggerEvidenceFiles.Stage staged = DaggerEvidenceFiles.read(options, artifact);
                if (staged.incomplete()) { fail("Dagger did not publish a complete binding graph"); return; }
                EvidenceProtocol.write(options, staged.compiler(), artifact, sources.values(), staged.unmapped(), staged.edges());
            }
            completed = true;
            if (unmapped > 0) diagnostic("unmapped javac compiler references");
        } catch (RuntimeException error) {
            fail("compiler evidence could not be published");
            throw error;
        } finally {
            JavacEvidencePlugin.clear(this);
        }
    }

    private void fail(String reason) {
        invalid = true;
        if (invalidReason == null) invalidReason = reason;
        diagnostic(reason);
    }

    private void diagnostic(String reason) {
        System.err.println("Kartograph compiler evidence incomplete: " + reason);
    }

}
