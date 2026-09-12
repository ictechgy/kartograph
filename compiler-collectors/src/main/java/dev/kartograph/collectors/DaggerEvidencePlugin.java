package dev.kartograph.collectors;

import dagger.spi.model.Binding;
import dagger.spi.model.BindingGraph;
import dagger.spi.model.BindingGraphPlugin;
import dagger.spi.model.DaggerElement;
import dagger.spi.model.DaggerProcessingEnv;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.annotation.processing.ProcessingEnvironment;
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

/** Dagger 2.59가 선택한 Javac binding graph를 JVM declaration edge로 수집한다. */
public final class DaggerEvidencePlugin implements BindingGraphPlugin {
    private static final String VERSION_RESOURCE = "META-INF/com.google.dagger_dagger-compiler.version";
    private ProcessingEnvironment environment;
    private Elements elements;
    private Types types;
    private EvidenceProtocol.Options options;
    private String artifact;
    private String compilerVersion;
    private final Set<EvidenceProtocol.Edge> edges = new TreeSet<>(Comparator.comparing(EvidenceProtocol.Edge::source)
        .thenComparing(EvidenceProtocol.Edge::target).thenComparing(EvidenceProtocol.Edge::kind));
    private int unmapped;
    private boolean incomplete;

    @Override public void init(DaggerProcessingEnv processingEnv, Map<String, String> options) {
        if (processingEnv.backend() != DaggerProcessingEnv.Backend.JAVAC) {
            throw new IllegalArgumentException("Dagger compiler evidence requires the Javac backend");
        }
        environment = processingEnv.javac();
        elements = environment.getElementUtils();
        types = environment.getTypeUtils();
        compilerVersion = daggerCompilerVersion();
        try {
            this.options = EvidenceProtocol.fromDaggerOptions(options);
            artifact = EvidenceProtocol.artifactFingerprint(DaggerEvidencePlugin.class);
            DaggerEvidenceFiles.requireRequest(this.options, artifact);
        } catch (IllegalArgumentException invalidRequest) {
            this.options = null;
            environment.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR, invalidRequest.getMessage());
        }
    }

    @Override public String pluginName() { return "KartographDaggerEvidence"; }

    @Override public Set<String> supportedOptions() {
        return Set.of("kartograph.evidence.root", "kartograph.evidence.output", "kartograph.evidence.token");
    }

    @Override public void visitGraph(BindingGraph graph, dagger.spi.model.DiagnosticReporter reporter) {
        if (options == null) return;
        if (graph.isFullBindingGraph() || graph.isModuleBindingGraph() || graph.isPartialBindingGraph()) return;
        if (!graph.missingBindings().isEmpty()) {
            incomplete = true;
            return;
        }
        for (BindingGraph.DependencyEdge edge : graph.dependencyEdges()) {
            var endpoints = graph.network().incidentNodes(edge);
            Element source = edge.isEntryPoint()
                ? edge.dependencyRequest().requestElement().map(DaggerElement::javac).orElse(null)
                : endpoints.source() instanceof Binding binding
                    ? binding.bindingElement().map(DaggerElement::javac).orElse(null) : null;
            Element target = endpoints.target() instanceof Binding binding
                ? binding.bindingElement().map(DaggerElement::javac).orElse(null) : null;
            String sourceId = identity(source);
            String targetId = identity(target);
            if (sourceId == null || targetId == null) {
                unmapped++;
            } else {
                edges.add(new EvidenceProtocol.Edge(sourceId, targetId, "binding"));
            }
        }
    }

    @Override public void onPluginEnd() {
        if (options == null) return;
        DaggerEvidenceFiles.write(options, compilerVersion, artifact, unmapped, incomplete, edges);
    }

    private String daggerCompilerVersion() {
        ClassLoader loader = DaggerEvidencePlugin.class.getClassLoader();
        try (InputStream input = loader.getResourceAsStream(VERSION_RESOURCE)) {
            if (input == null) throw new IllegalArgumentException("Dagger compiler version resource is missing");
            String version = new String(input.readAllBytes(), StandardCharsets.UTF_8).strip();
            if (!version.matches("[A-Za-z0-9._+\\-]{1,128}")) throw new IllegalArgumentException("Dagger compiler version is invalid");
            return version;
        } catch (IOException error) {
            throw new IllegalArgumentException("Dagger compiler version could not be read", error);
        }
    }

    private String identity(Element element) {
        if (element instanceof TypeElement type) {
            String owner = owner(type);
            return owner == null ? null : "class:" + owner;
        }
        if (element instanceof VariableElement field && field.getKind() == ElementKind.FIELD && field.getEnclosingElement() instanceof TypeElement type) {
            String owner = owner(type);
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
            ArrayList<String> arguments = new ArrayList<>();
            for (VariableElement parameter : method.getParameters()) {
                String descriptor = descriptor(parameter.asType());
                if (descriptor == null) return null;
                arguments.add(descriptor);
            }
            String returns = descriptor(method.getReturnType());
            return returns == null ? null : "method:" + owner + "#" + name + "(" + String.join("", arguments) + ")" + returns;
        }
        return null;
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
}
