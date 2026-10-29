package dev.kartograph.collectors;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** v3 위치 계약과 독립된 compiler 작성 호출/완전성의 bounded v1 sidecar다. */
public final class AuthoredCallProtocol {
    public static final String FORMAT = "kartograph-authored-source-calls";
    public static final String PROFILE = "ordinary-body-v1";
    public static final int MAX_NODES = 100_000;
    public static final int MAX_DEPTH = 256;
    private AuthoredCallProtocol() {}

    public record Call(String target, String origin, String targetPath, int start, int end) {
        public Call {
            if (target == null || target.length() > 4096 || !target.startsWith("method:") ||
                !(origin.equals("PROJECT") || origin.equals("BINARY")) || targetPath == null ||
                (origin.equals("PROJECT") == targetPath.isEmpty()) || start < 0 || end <= start) {
                throw new IllegalArgumentException("invalid authored call");
            }
        }
    }
    public record Caller(String identity, EvidenceProtocol.Source source, int start, int end,
        int nodes, int explicitCalls, List<String> reasons, List<Call> calls) {
        public Caller {
            if (identity == null || !identity.startsWith("method:") || identity.length() > 4096 || source == null ||
                start < 0 || end <= start || nodes < 0 || nodes > MAX_NODES || explicitCalls < 0 || explicitCalls > nodes ||
                calls.size() > explicitCalls || reasons.size() > 32 || reasons.stream().anyMatch(it -> !it.matches("[A-Z_]{1,64}")) ||
                reasons.isEmpty() && (nodes == 0 || calls.size() != explicitCalls)) throw new IllegalArgumentException("invalid authored caller coverage");
            reasons = reasons.stream().distinct().sorted().toList();
            calls = calls.stream().sorted(Comparator.comparingInt(Call::start).thenComparing(Call::target)).toList();
            var keys = new TreeSet<String>();
            for (Call call : calls) {
                if (call.start() < start || call.end() > end || !keys.add(call.start() + ":" + call.end())) {
                    throw new IllegalArgumentException("duplicate or out-of-range authored call");
                }
            }
        }
    }
    public static Path output(EvidenceProtocol.Options options) {
        Path primary = EvidenceProtocol.outputFile(options.output());
        return primary.resolveSibling(primary.getFileName().toString().replaceFirst("\\.tsv$", "") + "-authored.tsv");
    }
    public static void invalidate(EvidenceProtocol.Options options) {
        if (!options.authoredCalls()) return;
        try { Files.deleteIfExists(output(options)); }
        catch (IOException error) { throw new IllegalArgumentException("authored evidence could not be invalidated", error); }
    }
    public static void write(EvidenceProtocol.Options options, String compiler, String artifact,
        Collection<EvidenceProtocol.Source> sources, int unmappedCallers, Collection<Caller> callers) {
        if (!options.authoredCalls() || unmappedCallers < 0) throw new IllegalArgumentException("authored evidence requires opt-in");
        Map<String, EvidenceProtocol.Source> inventory = new TreeMap<>();
        sources.forEach(source -> { if (inventory.putIfAbsent(source.path(), source) != null) throw new IllegalArgumentException("duplicate authored source"); });
        Map<String, Caller> ordered = new TreeMap<>();
        long rows = 7L + inventory.size();
        for (Caller caller : callers) {
            if (!caller.source().equals(inventory.get(caller.source().path())) || ordered.putIfAbsent(caller.identity(), caller) != null) {
                throw new IllegalArgumentException("conflicting authored caller identity");
            }
            rows += 1L + caller.calls().size();
        }
        if (rows > EvidenceProtocol.MAX_ROWS) throw new IllegalArgumentException("authored evidence exceeds row limit");
        StringBuilder out = new StringBuilder();
        row(out, "format", FORMAT, "1");
        row(out, "collector", options.collector());
        row(out, "compiler", compiler);
        row(out, "token", EvidenceProtocol.readToken(options));
        row(out, "artifact", artifact);
        row(out, "profile", PROFILE);
        row(out, "unmapped", unmappedCallers);
        for (var source : inventory.values()) row(out, "source", enc(source.path()), source.sha256());
        for (Caller caller : ordered.values()) {
            row(out, "caller", enc(caller.identity()), enc(caller.source().path()), caller.start(), caller.end(),
                caller.reasons().isEmpty() ? "COMPLETE" : "INCOMPLETE", caller.nodes(), caller.explicitCalls(),
                caller.reasons().isEmpty() ? "NONE" : String.join(",", caller.reasons()));
            for (Call call : caller.calls()) row(out, "authored", enc(caller.identity()), enc(call.target()), call.origin(),
                call.targetPath().isEmpty() ? "-" : enc(call.targetPath()), call.start(), call.end());
        }
        byte[] bytes = out.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > EvidenceProtocol.MAX_ENCODED_BYTES) throw new IllegalArgumentException("authored evidence exceeds byte limit");
        EvidenceProtocol.publish(output(options), bytes);
    }
    private static String enc(String value) { return EvidenceProtocol.encode(value); }
    private static void row(StringBuilder out, Object... values) {
        String text = String.join("\t", java.util.Arrays.stream(values).map(Object::toString).toList()) + "\n";
        if (text.length() > EvidenceProtocol.MAX_ENCODED_BYTES - out.length()) throw new IllegalArgumentException("authored evidence exceeds byte limit");
        out.append(text);
    }
}
