import dev.kartograph.export.QuerySnapshot;
import dev.kartograph.export.QuerySnapshotCodec;
import java.nio.file.Files;
import java.nio.file.Path;

/** 실제 128 MiB 초과 파일을 reader와 writer 양쪽으로 통과시키는 독립 JVM probe다. */
public final class LargeSnapshotProbe {
    public static void main(String[] args) throws Exception {
        if (args.length == 4 && args[3].equals("reject128")) {
            reject(Files.readString(Path.of(args[0])), QuerySnapshotCodec.INSTANCE.maximumBytes(128));
            System.out.println("{\"rejectedAbove128MiB\":true}");
            return;
        }
        int maximum = QuerySnapshotCodec.INSTANCE.maximumBytes(256);
        Path input = Path.of(args[0]);
        Path output = Path.of(args[1]);
        boolean compact = Boolean.parseBoolean(args[2]);
        long start = System.nanoTime();
        QuerySnapshot snapshot = QuerySnapshotCodec.INSTANCE.parse(Files.readString(input), maximum);
        long parsedHeap = usedHeap();
        String encoded = QuerySnapshotCodec.INSTANCE.render(snapshot, compact, maximum);
        long renderHeap = usedHeap();
        Files.writeString(output, encoded);
        if (Files.size(output) <= 128L * 1024 * 1024) throw new AssertionError("fixture did not exceed old bound");
        try {
            QuerySnapshotCodec.INSTANCE.parse(encoded, QuerySnapshotCodec.INSTANCE.maximumBytes(128));
            throw new AssertionError("128 MiB limit accepted larger input");
        } catch (IllegalArgumentException expected) { }
        reject(encoded.substring(0, encoded.lastIndexOf('}')), maximum);
        reject("!" + encoded.substring(1), maximum);
        int nodes = snapshot.getGraph().getNodeCount();
        encoded = null;
        snapshot = null;
        System.gc();
        QuerySnapshot restored = QuerySnapshotCodec.INSTANCE.parse(Files.readString(output), maximum);
        if (nodes != restored.getGraph().getNodeCount()) throw new AssertionError("node count changed");
        if (restored.getGraph().getEdgeCount() != nodes - 1) throw new AssertionError("chain edges changed");
        System.out.println("{\"bytes\":" + Files.size(output) + ",\"nodes\":" + nodes + ",\"edges\":" + (nodes - 1)
            + ",\"parsedHeapBytes\":" + parsedHeap + ",\"renderHeapBytes\":" + renderHeap
            + ",\"elapsedSeconds\":" + ((System.nanoTime() - start) / 1e9) + "}");
    }
    private static void reject(String value, int maximum) {
        try {
            QuerySnapshotCodec.INSTANCE.parse(value, maximum);
            throw new AssertionError("invalid or oversized input was accepted");
        } catch (IllegalArgumentException expected) { }
    }
    private static long usedHeap() { return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(); }
}
