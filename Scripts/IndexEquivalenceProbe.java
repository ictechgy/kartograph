import dev.kartograph.export.GraphJsonRenderer;
import dev.kartograph.index.ClassFileIndexer;
import dev.kartograph.index.ClassIndexCache;
import dev.kartograph.index.IndexedClasses;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.CRC32C;

/** 공개 고정 입력의 raw cache payload와 전체 graph를 별도 관측한다. 시간 판정에는 포함하지 않는다. */
public final class IndexEquivalenceProbe {
    private static final int LIMIT = 8 * 1024 * 1024;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("output and class roots required");
        Path output = Path.of(args[0]);
        Files.createDirectory(output);
        Path cache = output.resolve("cache");
        var roots = new ArrayList<Path>();
        for (int i = 1; i < args.length; i++) roots.add(Path.of(args[i]));
        ClassFileIndexer indexer = new ClassFileIndexer(new ClassIndexCache(cache), true);
        IndexedClasses cold = indexer.indexWithObservations(roots, Collections.emptyList());
        require(cold.getStatistics().getParsedClasses() > 0, "empty-cold-input");
        require(cold.getStatistics().getCacheMisses() == cold.getStatistics().getParsedClasses(), "cold-miss-count");
        require(cold.getStatistics().getWriteFailures() == 0 && cold.getStatistics().getUnavailableEntries() == 0,
            "cold-cache-unavailable");
        String facts = facts(cache, cold.getStatistics().getParsedClasses());
        String graph = render(cold);
        IndexedClasses warm = indexer.indexWithObservations(roots, Collections.emptyList());
        require(warm.getStatistics().getCacheHits() == cold.getStatistics().getParsedClasses()
            && warm.getStatistics().getParsedClasses() == 0 && warm.getStatistics().getInvalidEntries() == 0,
            "warm-cache-not-consumed");
        require(graph.equals(render(warm)), "warm-graph-changed");
        require(facts.equals(facts(cache, cold.getStatistics().getParsedClasses())), "warm-facts-changed");
        Files.writeString(output.resolve("raw-facts.json"), facts, StandardCharsets.UTF_8);
        Files.writeString(output.resolve("graphs.json"), graph, StandardCharsets.UTF_8);
        System.out.println("{\"classes\":" + cold.getStatistics().getParsedClasses()
            + ",\"cacheHits\":" + warm.getStatistics().getCacheHits() + "}");
    }

    private static String render(IndexedClasses indexed) throws Exception {
        return "{\"json\":" + graphDigest(indexed, false) + ",\"ndjson\":" + graphDigest(indexed, true) + "}\n";
    }

    private static String graphDigest(IndexedClasses indexed, boolean ndjson) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long[] count = {0};
        // 실제 큰 callback 그래프도 문자열/파일로 중복 저장하지 않는다. 이 진단만의 유한 스트림 예산이다.
        OutputStream bounded = new OutputStream() {
            @Override public void write(int value) {
                require(++count[0] <= 512L * 1024 * 1024, "semantic-graph-limit");
                digest.update((byte) value);
            }
            @Override public void write(byte[] bytes, int offset, int length) {
                count[0] += length;
                require(count[0] <= 512L * 1024 * 1024, "semantic-graph-limit");
                digest.update(bytes, offset, length);
            }
        };
        try (var writer = new OutputStreamWriter(bounded, StandardCharsets.UTF_8)) {
            if (ndjson) GraphJsonRenderer.INSTANCE.writeNdjson(indexed.getGraph(), "equivalence-oracle", writer,
                Collections.emptyMap(), Collections.emptyList(), true);
            else GraphJsonRenderer.INSTANCE.write(indexed.getGraph(), "equivalence-oracle", writer,
                Collections.emptyMap(), Collections.emptyList());
        }
        return "{\"bytes\":" + count[0] + ",\"sha256\":\"" + HexFormat.of().formatHex(digest.digest()) + "\"}";
    }

    private static String facts(Path cache, int count) throws Exception {
        Map<String, String> payloads = new TreeMap<>();
        String engine = null;
        try (var paths = Files.list(cache)) {
            for (Path path : paths.toList()) {
                require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && path.toString().endsWith(".cix"),
                    "unexpected-cache-file");
                require(Files.size(path) <= LIMIT, "cache-entry-limit");
                try (var input = new DataInputStream(new ByteArrayInputStream(Files.readAllBytes(path)))) {
                    require(input.readInt() == 0x4b584332 && input.readInt() == 10, "unexpected-cache-format");
                    String identity = hexString(input);
                    String content = hexString(input);
                    if (engine == null) engine = identity;
                    require(engine.equals(identity) && path.getFileName().toString().startsWith(content + "-"),
                        "cache-identity-mismatch");
                    int size = input.readInt();
                    require(size > 0 && size <= LIMIT && size == input.available() - 4, "invalid-cache-payload-size");
                    byte[] payload = input.readNBytes(size);
                    CRC32C checksum = new CRC32C();
                    checksum.update(payload);
                    require((int) checksum.getValue() == input.readInt() && input.available() == 0, "cache-checksum");
                    String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
                    require(payloads.put(content, sha) == null, "duplicate-cache-content");
                }
            }
        }
        require(payloads.size() == count && engine != null, "cache-coverage");
        StringBuilder result = new StringBuilder("{\"format\":\"class-facts-v10-payload-sha256\",\"engine\":\"")
            .append(engine).append("\",\"payloads\":{");
        boolean first = true;
        for (var row : payloads.entrySet()) {
            if (!first) result.append(',');
            first = false;
            result.append('"').append(row.getKey()).append("\":\"").append(row.getValue()).append('"');
        }
        return result.append("}}\n").toString();
    }

    private static String hexString(DataInputStream input) throws Exception {
        require(input.readUnsignedShort() == 64, "cache-identity-length");
        String value = new String(input.readNBytes(64), StandardCharsets.US_ASCII);
        require(value.matches("[0-9a-f]{64}"), "cache-identity-format");
        return value;
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(reason);
    }
}
