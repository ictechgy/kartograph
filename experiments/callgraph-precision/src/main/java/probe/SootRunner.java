package probe;
import java.util.*;
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation;
import sootup.java.bytecode.frontend.inputlocation.DefaultRuntimeAnalysisInputLocation;
import sootup.java.core.views.JavaView;
import sootup.callgraph.*;

/** 같은 main root에서 SootUp CHA/RTA의 가능한 run 대상만 비교한다. */
public final class SootRunner {
    public static void main(String[] args) {
        var inputs = new ArrayList<sootup.core.inputlocation.AnalysisInputLocation>();
        var application = new JavaClassPathAnalysisInputLocation(args[1], sootup.core.model.SourceType.Application);
        inputs.add(application);
        inputs.add(new DefaultRuntimeAnalysisInputLocation());
        if (args.length > 2) inputs.add(new JavaClassPathAnalysisInputLocation(args[2], sootup.core.model.SourceType.Library));
        var view = new JavaView(inputs);
        var applicationClasses = new TreeSet<String>();
        for (var source : application.getClassSources(view).toList()) {
            var type = view.getClass(source.getClassType()).orElseThrow();
            if (type.isLibraryClass()) throw new IllegalStateException("application class assigned to library scope");
            applicationClasses.add(source.getClassType().getFullyQualifiedName());
        }
        for (String name : List.of("Live", "Dormant")) {
            if (view.getClass(view.getIdentifierFactory().getClassType("probe.Entry$" + name)).isEmpty())
                throw new IllegalStateException("comparison declaration missing");
        }
        var entry = view.getIdentifierFactory().parseMethodSignature("<probe.Entry: void main(java.lang.String[])>");
        CallGraphAlgorithm algorithm = switch (args[0]) {
            case "cha" -> new ClassHierarchyAnalysisAlgorithm(view);
            case "rta" -> new RapidTypeAnalysisAlgorithm(view);
            default -> throw new IllegalArgumentException("unknown comparison algorithm");
        };
        var graph = algorithm.initialize(List.of(entry));
        var targets = new TreeSet<String>();
        for (var method : graph.getMethodSignatures()) {
            String owner = method.getDeclClassType().getFullyQualifiedName();
            if (method.getName().equals("run") && Set.of("probe.Entry$Live", "probe.Entry$Dormant").contains(owner))
                targets.add(owner.substring(owner.indexOf('$') + 1));
        }
        String values = String.join(",", targets.stream().map(s -> "\"" + s + "\"").toList());
        String classes = String.join(",", applicationClasses.stream().map(s -> "\"" + s + "\"").toList());
        System.out.println("{\"targets\":[" + values + "],\"applicationClasses\":[" + classes + "],\"methodCount\":" + graph.getMethodSignatures().size() + "}");
    }
}
