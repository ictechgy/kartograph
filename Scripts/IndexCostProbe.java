import dev.kartograph.core.EdgeOrigin;
import dev.kartograph.core.NodeAttribute;
import dev.kartograph.index.ClassFileIndexer;
import dev.kartograph.index.GraphExportIndexOptions;
import dev.kartograph.index.IndexedClasses;
import dev.kartograph.index.IndexingStatistics;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 공개 인덱서의 단계 시간만 관측하며 제품 옵션이나 분석 정책을 추가하지 않는다. */
public final class IndexCostProbe {
    public static void main(String[] args) {
        long entryUptimeMillis = ManagementFactory.getRuntimeMXBean().getUptime();
        if (args.length < 2) throw new IllegalArgumentException("profile and roots required");
        String profile = args[0];
        if (!List.of("graph", "dead", "architecture").contains(profile))
            throw new IllegalArgumentException("unknown profile");
        List<Path> roots = new ArrayList<>();
        for (int i = 1; i < args.length; i++) roots.add(Path.of(args[i]));
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        com.sun.management.ThreadMXBean allocations = bean instanceof com.sun.management.ThreadMXBean
            ? (com.sun.management.ThreadMXBean) bean : null;
        boolean allocationAvailable = allocations != null && allocations.isThreadAllocatedMemorySupported()
            && allocations.isThreadAllocatedMemoryEnabled();
        long thread = Thread.currentThread().getId();
        long allocatedBefore = allocationAvailable ? allocations.getThreadAllocatedBytes(thread) : -1;
        long started = System.nanoTime();
        ClassFileIndexer indexer = new ClassFileIndexer(null, false);
        IndexedClasses indexed = profile.equals("dead")
            ? indexer.indexWithObservations(roots, Collections.emptyList(), Collections.emptyList(), Collections.emptyList())
            : indexer.indexForGraphExport(roots, null, Collections.emptyList(), Collections.emptyList(),
                new GraphExportIndexOptions(profile.equals("architecture")
                    ? Collections.singleton(EdgeOrigin.DISPATCH_MODEL) : Collections.emptySet(), 256, false)).getIndexed();
        long invocationNanos = System.nanoTime() - started;
        long allocatedAfter = allocationAvailable ? allocations.getThreadAllocatedBytes(thread) : -1;
        IndexingStatistics s = indexed.getStatistics();
        String allocation = allocatedBefore >= 0 && allocatedAfter >= allocatedBefore
            ? Long.toString(allocatedAfter - allocatedBefore) : "null";
        System.out.println("{\"profile\":\"" + profile + "\",\"entryUptimeMillis\":" + entryUptimeMillis
            + ",\"invocationNanos\":" + invocationNanos
            + ",\"currentThreadAllocatedBytes\":" + allocation
            + ",\"allocationScope\":\"index-invocation-current-thread-only; excludes-other-threads-and-startup\""
            + ",\"nodes\":" + indexed.getGraph().getNodeCount()
            + ",\"locatedNodes\":" + indexed.getGraph().getNodes().values().stream()
                .filter(node -> node.getLocation() != null && !node.getAttributes().contains(NodeAttribute.EXTERNAL_STUB)).count()
            + ",\"edges\":" + indexed.getGraph().getEdgeCount()
            + ",\"externalCalls\":" + indexed.getGraph().getExternalCalls().size()
            + ",\"classFiles\":" + s.getClassFiles()
            + ",\"parsedClasses\":" + s.getParsedClasses()
            + ",\"cacheHits\":" + s.getCacheHits()
            + ",\"cacheMisses\":" + s.getCacheMisses()
            + ",\"readNanos\":" + s.getReadNanos()
            + ",\"cacheReadNanos\":" + s.getCacheReadNanos()
            + ",\"parseNanos\":" + s.getParseNanos()
            + ",\"assemblyNanos\":" + s.getAssemblyNanos()
            + ",\"hierarchyNanos\":" + s.getHierarchyNanos()
            + ",\"runtimeNanos\":" + s.getRuntimeNanos()
            + ",\"dispatchNanos\":" + s.getDispatchNanos()
            + ",\"cacheWriteNanos\":" + s.getCacheWriteNanos()
            + ",\"totalNanos\":" + s.getTotalNanos() + "}");
    }
}
