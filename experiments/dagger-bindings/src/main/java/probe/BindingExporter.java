package probe;

import dagger.spi.model.*;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.util.*;

/** Dagger 2.59의 선택된 Javac binding graph를 원문 qualifier 없이 내보내는 독립 실험이다. */
public final class BindingExporter implements BindingGraphPlugin {
    private ProcessingEnvironment environment;
    @Override public void init(DaggerProcessingEnv env, Map<String, String> options) {
        if (env.backend() != DaggerProcessingEnv.Backend.JAVAC)
            throw new IllegalArgumentException("binding experiment requires the Javac backend");
        environment = env.javac();
    }
    @Override public String pluginName() { return "KartographBindingExperiment"; }
    @Override public void visitGraph(BindingGraph graph, DiagnosticReporter reporter) {
        if (graph.isFullBindingGraph() || graph.isModuleBindingGraph() || graph.isPartialBindingGraph()) return;
        var component = graph.rootComponentNode().componentPath().currentComponent().javac();
        var rows = new TreeMap<String, Integer>();
        int missing = 0;
        for (var edge : graph.dependencyEdges()) {
            var endpoints = graph.network().incidentNodes(edge);
            Element caller = edge.isEntryPoint()
                ? edge.dependencyRequest().requestElement().map(DaggerElement::javac).orElse(null)
                : endpoints.source() instanceof Binding binding ? binding.bindingElement().map(DaggerElement::javac).orElse(null) : null;
            Element target = endpoints.target() instanceof Binding binding ? binding.bindingElement().map(DaggerElement::javac).orElse(null) : null;
            String sourceId = identity(caller), targetId = identity(target);
            if (sourceId == null || targetId == null) { missing++; continue; }
            rows.merge("edge\t" + sourceId + "\t" + targetId + "\t" + (edge.isEntryPoint() ? "entryPoint" : "dependency"), 1, Integer::sum);
        }
        String componentId = environment.getElementUtils().getBinaryName(component).toString();
        String resource = "META-INF/kartograph/dagger/" + componentId + ".tsv";
        try (var writer = environment.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "", resource, component).openWriter()) {
            writer.write("component\t" + identity(component) + "\n");
            writer.write("unmapped\t" + missing + "\n");
            for (var row : rows.entrySet()) writer.write(row.getKey() + "\t" + row.getValue() + "\n");
        } catch (IOException error) {
            throw new IllegalStateException("binding evidence could not be written", error);
        }
    }
    private String identity(Element element) {
        if (element instanceof TypeElement type) return "class:" + owner(type);
        if (!(element instanceof ExecutableElement method) || !(method.getEnclosingElement() instanceof TypeElement type)) return null;
        String name = method.getKind() == ElementKind.CONSTRUCTOR ? "<init>" : method.getSimpleName().toString();
        var arguments = method.getParameters().stream().map(p -> descriptor(p.asType())).toList();
        String returns = descriptor(method.getReturnType());
        if (returns == null || arguments.contains(null)) return null;
        return "method:" + owner(type) + "#" + name + "(" + String.join("", arguments) + ")" + returns;
    }
    private String owner(TypeElement type) { return environment.getElementUtils().getBinaryName(type).toString().replace('.', '/'); }
    private String descriptor(TypeMirror type) {
        return switch (type.getKind()) {
            case BOOLEAN -> "Z"; case BYTE -> "B"; case SHORT -> "S"; case INT -> "I"; case LONG -> "J";
            case CHAR -> "C"; case FLOAT -> "F"; case DOUBLE -> "D"; case VOID -> "V";
            case ARRAY -> { String component = descriptor(((ArrayType) type).getComponentType()); yield component == null ? null : "[" + component; }
            case DECLARED -> "L" + owner((TypeElement) ((DeclaredType) type).asElement()) + ";";
            case TYPEVAR -> descriptor(environment.getTypeUtils().erasure(type));
            default -> null;
        };
    }
}
