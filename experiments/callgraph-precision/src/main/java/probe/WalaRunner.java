package probe;
import java.util.*;
import com.ibm.wala.classLoader.Language;
import com.ibm.wala.core.util.config.AnalysisScopeReader;
import com.ibm.wala.ipa.callgraph.*;
import com.ibm.wala.ipa.callgraph.impl.Util;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.types.*;

/** reflection 입력 없이 0-1-CFA의 receiver 후보와 누락 경계를 측정한다. */
public final class WalaRunner {
    public static void main(String[] args) throws Exception {
        var scope = AnalysisScopeReader.instance.makeJavaBinaryAnalysisScope(args[0], null);
        try (var dependency = args.length > 1 ? new java.util.jar.JarFile(args[1]) : null) {
        if (dependency != null) scope.addToScope(ClassLoaderReference.Primordial, new com.ibm.wala.classLoader.JarFileModule(dependency));
        var hierarchy = ClassHierarchyFactory.make(scope);
        var applicationClasses = new TreeSet<String>();
        for (var type : hierarchy) {
            if (type.getClassLoader().getReference().equals(ClassLoaderReference.Application))
                applicationClasses.add(type.getName().toString().substring(1).replace('/', '.'));
        }
        for (String name : List.of("Live", "Dormant")) {
            if (hierarchy.lookupClass(TypeReference.findOrCreate(ClassLoaderReference.Application, "Lprobe/Entry$" + name)) == null)
                throw new IllegalStateException("comparison declaration missing");
        }
        var entries = Util.makeMainEntrypoints(hierarchy, "Lprobe/Entry");
        var options = new AnalysisOptions(scope, entries);
        options.setReflectionOptions(AnalysisOptions.ReflectionOptions.FULL);
        var builder = Util.makeZeroOneCFABuilder(Language.JAVA, options, new AnalysisCacheImpl(), hierarchy);
        var graph = builder.makeCallGraph(options, null);
        var targets = new TreeSet<String>();
        for (var node : graph) {
            var method = node.getMethod();
            String owner = method.getDeclaringClass().getName().toString();
            if (method.getSelector().toString().equals("run()V") && Set.of("Lprobe/Entry$Live", "Lprobe/Entry$Dormant").contains(owner))
                targets.add(owner.substring(owner.indexOf('$') + 1));
        }
        String values = String.join(",", targets.stream().map(s -> "\"" + s + "\"").toList());
        String classes = String.join(",", applicationClasses.stream().map(s -> "\"" + s + "\"").toList());
        System.out.println("{\"targets\":[" + values + "],\"applicationClasses\":[" + classes + "],\"contextCount\":" + graph.getNumberOfNodes() + "}");
        }
    }
}
