package dev.kartograph.collectors;

import com.sun.source.tree.*;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;

/** 타입이 확인된 사용자 작성 분기만 기록한다. 실행이나 도달 가능성을 뜻하지 않는다. */
final class JavacEnumDecisionScanner extends TreePathScanner<Void, Void> {
    private final Trees trees;
    private final Elements elements;
    private final EvidenceProtocol.Source source;
    private final EvidenceProtocol.EnumDecisionBuffer buffer;
    private final Set<Tree> observed;
    private String lexicalClass;
    private boolean lexicalLambda;

    JavacEnumDecisionScanner(Trees trees, Elements elements, EvidenceProtocol.Source source,
        EvidenceProtocol.EnumDecisionBuffer buffer, Set<Tree> observed) {
        this.trees = trees;
        this.elements = elements;
        this.source = source;
        this.buffer = buffer;
        this.observed = observed;
    }

    @Override public Void visitClass(ClassTree tree, Void unused) {
        String previous = lexicalClass;
        Element element = trees.getElement(getCurrentPath());
        lexicalClass = element instanceof TypeElement type ? classId(type) : null;
        try { return super.visitClass(tree, unused); }
        finally { lexicalClass = previous; }
    }

    @Override public Void visitLambdaExpression(LambdaExpressionTree tree, Void unused) {
        boolean previous = lexicalLambda;
        lexicalLambda = true;
        try { return super.visitLambdaExpression(tree, unused); }
        finally { lexicalLambda = previous; }
    }

    @Override public Void visitSwitch(SwitchTree tree, Void unused) {
        fallback(tree, tree.getExpression(), tree.getCases());
        return super.visitSwitch(tree, unused);
    }

    @Override public Void visitSwitchExpression(SwitchExpressionTree tree, Void unused) {
        fallback(tree, tree.getExpression(), tree.getCases());
        return super.visitSwitchExpression(tree, unused);
    }

    @SuppressWarnings("preview")
    private void fallback(Tree tree, ExpressionTree expression, List<? extends CaseTree> cases) {
        TypeElement type = enumType(new TreePath(getCurrentPath(), expression));
        if (type == null || !observed.add(tree)) return;
        buffer.observe();
        String enumClass = classId(type);
        TreeSet<String> constants = new TreeSet<>();
        boolean hasDefault = false;
        boolean supported = enumClass != null;
        for (CaseTree branch : cases) {
            // Java 17은 상수 식을 직접 노출하고 이후 javac은 CONSTANT_CASE_LABEL로 감싼다.
            // 빈 식 목록을 작성된 default로 추정하지 않고 패턴을 거부한다.
            // default 자체에도 원본 소스 범위가 있어야 한다.
            for (Tree label : branch.getLabels()) {
                String labelKind = label.getKind().name();
                if (labelKind.equals("DEFAULT_CASE_LABEL")) {
                    long start = trees.getSourcePositions().getStartPosition(getCurrentPath().getCompilationUnit(), label);
                    long end = trees.getSourcePositions().getEndPosition(getCurrentPath().getCompilationUnit(), label);
                    if (start < 0 || end <= start) supported = false;
                    else hasDefault = true;
                } else if (!(label instanceof ExpressionTree) && !labelKind.equals("CONSTANT_CASE_LABEL")) {
                    supported = false;
                }
            }
            for (ExpressionTree value : branch.getExpressions()) {
                TreePath valuePath = TreePath.getPath(getCurrentPath().getCompilationUnit(), value);
                ExpressionTree bare = unparenthesized(value);
                if (bare.getKind() == Tree.Kind.NULL_LITERAL) continue;
                String constant = valuePath == null ? null : enumConstant(valuePath, enumClass);
                if (constant == null) supported = false;
                else constants.add(constant);
            }
        }
        if (!supported) { buffer.unsupported(); return; }
        if (!hasDefault) { buffer.ignored(); return; }
        emit(tree, enumClass, "EXPLICIT_FALLBACK", constants);
    }

    @Override public Void visitMethodInvocation(MethodInvocationTree tree, Void unused) {
        membership(tree);
        return super.visitMethodInvocation(tree, unused);
    }

    private void membership(MethodInvocationTree tree) {
        if (!(tree.getMethodSelect() instanceof MemberSelectTree selection) ||
            !selection.getIdentifier().contentEquals("contains") || tree.getArguments().size() != 1) return;
        TreePath argument = new TreePath(getCurrentPath(), tree.getArguments().get(0));
        TypeElement type = enumType(argument);
        if (type == null || !observed.add(tree)) return;
        buffer.observe();
        String enumClass = classId(type);
        Element target = trees.getElement(new TreePath(getCurrentPath(), tree.getMethodSelect()));
        if (enumClass == null || !(target instanceof ExecutableElement method) ||
            !(method.getEnclosingElement() instanceof TypeElement owner) ||
            method.getReturnType().getKind() != TypeKind.BOOLEAN || method.getParameters().size() != 1 ||
            !method.getParameters().get(0).asType().toString().equals("java.lang.Object") ||
            !Set.of("java.util.Collection", "java.util.List", "java.util.Set",
                "java.util.AbstractCollection", "java.util.EnumSet").contains(owner.getQualifiedName().toString())) {
            buffer.unsupported(); return;
        }
        ExpressionTree receiver = unparenthesized(selection.getExpression());
        if (!(receiver instanceof MethodInvocationTree factory)) { buffer.unsupported(); return; }
        TreePath factoryPath = TreePath.getPath(getCurrentPath().getCompilationUnit(), factory);
        if (factoryPath == null) { buffer.unsupported(); return; }
        Element factoryTarget = trees.getElement(new TreePath(factoryPath, factory.getMethodSelect()));
        if (!(factoryTarget instanceof ExecutableElement factoryMethod) ||
            !(factoryMethod.getEnclosingElement() instanceof TypeElement factoryOwner) ||
            !factoryMethod.getModifiers().contains(Modifier.STATIC) ||
            !Set.of("java.util.Set#of", "java.util.List#of", "java.util.Arrays#asList", "java.util.EnumSet#of")
                .contains(factoryOwner.getQualifiedName() + "#" + factoryMethod.getSimpleName())) {
            buffer.unsupported(); return;
        }
        TreeSet<String> constants = new TreeSet<>();
        for (ExpressionTree value : factory.getArguments()) {
            String constant = enumConstant(new TreePath(factoryPath, value), enumClass);
            if (constant == null) { buffer.unsupported(); return; }
            constants.add(constant);
        }
        boolean negated = false;
        TreePath span = getCurrentPath();
        for (TreePath parent = span.getParentPath(); parent != null; parent = span.getParentPath()) {
            if (parent.getLeaf() instanceof ParenthesizedTree parentheses && parentheses.getExpression() == span.getLeaf()) {
                span = parent;
            } else if (parent.getLeaf() instanceof UnaryTree unary &&
                unary.getKind() == Tree.Kind.LOGICAL_COMPLEMENT && unary.getExpression() == span.getLeaf()) {
                negated = !negated;
                span = parent;
            } else break;
        }
        emit(span.getLeaf(), enumClass, negated ? "NEGATED_FINITE_MEMBERSHIP" : "FINITE_MEMBERSHIP", constants);
    }

    private void emit(Tree tree, String enumClass, String kind, Set<String> constants) {
        CompilationUnitTree unit = getCurrentPath().getCompilationUnit();
        long start = trees.getSourcePositions().getStartPosition(unit, tree);
        long end = trees.getSourcePositions().getEndPosition(unit, tree);
        if (start < 0 || end <= start || end > Integer.MAX_VALUE || unit.getLineMap() == null) {
            buffer.unsupported(); return;
        }
        long line = unit.getLineMap().getLineNumber(start);
        long column = unit.getLineMap().getColumnNumber(start);
        if (line < 1 || column < 1 || line > Integer.MAX_VALUE || column > Integer.MAX_VALUE) {
            buffer.unsupported(); return;
        }
        buffer.add(new EvidenceProtocol.EnumDecision(source, (int) start, (int) end, (int) line, (int) column,
            lexicalClass, enumClass, kind, lexicalLambda, List.copyOf(constants)));
    }

    private TypeElement enumType(TreePath path) {
        TypeMirror type = trees.getTypeMirror(path);
        return type instanceof DeclaredType declared && declared.asElement() instanceof TypeElement element &&
            element.getKind() == ElementKind.ENUM ? element : null;
    }

    private String enumConstant(TreePath path, String enumClass) {
        while (path.getLeaf() instanceof ParenthesizedTree parentheses) path = new TreePath(path, parentheses.getExpression());
        Element value = trees.getElement(path);
        if (!(value instanceof VariableElement field) || field.getKind() != ElementKind.ENUM_CONSTANT ||
            !(field.getEnclosingElement() instanceof TypeElement owner)) return null;
        String ownerId = classId(owner);
        if (ownerId == null || !ownerId.equals(enumClass)) return null;
        String binary = ownerId.substring("class:".length());
        return "field:" + binary + "#" + field.getSimpleName() + ":L" + binary + ";";
    }

    private String classId(TypeElement type) {
        String binary = elements.getBinaryName(type).toString().replace('.', '/');
        return binary.isBlank() ? null : "class:" + binary;
    }

    private static ExpressionTree unparenthesized(ExpressionTree tree) {
        while (tree instanceof ParenthesizedTree parentheses) tree = parentheses.getExpression();
        return tree;
    }

    static Set<Tree> observedSet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }
}
