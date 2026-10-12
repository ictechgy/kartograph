package dev.kartograph.collectors;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** 모든 source body와 방출 method를 별도 버전으로 기록하며 부재를 생성 근거로 승격하지 않는다. */
final class CallerInventoryProtocol {
    static final String FORMAT = "kartograph-source-caller-inventory";
    static final String PROFILE = "source-backend-accounting-v1";
    static final java.util.Set<String> KINDS = java.util.Set.of("ORDINARY_METHOD", "LOCAL_METHOD", "ANONYMOUS_METHOD",
        "ABSTRACT_METHOD", "NATIVE_METHOD", "CONSTRUCTOR", "IMPLICIT_CONSTRUCTOR", "PROPERTY_INITIALIZER",
        "STATIC_INITIALIZER", "INSTANCE_INITIALIZER", "PARAMETER_DEFAULT", "ACCESSOR", "LOCAL_FUNCTION", "LAMBDA", "UNSUPPORTED");
    record Body(String key, String path, int start, int end, String kind, boolean present, String origin, List<String> methods) {
        Body {
            if (!key.matches("b[0-9]{1,9}") || path.isEmpty() || !(start == -1 && end == -1 || start >= 0 && end > start) ||
                !KINDS.contains(kind) || origin.isEmpty() || origin.length() > 256 || origin.chars().anyMatch(c -> c < 32) ||
                methods.size() > EvidenceProtocol.MAX_ROWS || new TreeSet<>(methods).size() != methods.size()) throw invalid();
            methods = methods.stream().sorted().toList();
        }
    }
    private CallerInventoryProtocol() {}
    static Path output(EvidenceProtocol.Options options) {
        Path primary = EvidenceProtocol.outputFile(options.output());
        return primary.resolveSibling(primary.getFileName().toString().replaceFirst("\\.tsv$", "") + "-callers.tsv");
    }
    static void invalidate(EvidenceProtocol.Options options) {
        if (!options.callerInventory()) return;
        try { Files.deleteIfExists(output(options)); }
        catch (IOException error) { throw new IllegalArgumentException("caller inventory could not be invalidated", error); }
    }
    static void write(EvidenceProtocol.Options options, String compiler, String artifact,
        Collection<EvidenceProtocol.Source> sources, Collection<Body> bodies, List<CallerOutputHeaders.Output> outputs,
        Map<String, String> backendOrigins) {
        if (!options.callerInventory() || !options.authoredCalls()) throw invalid();
        var sourceMap = new TreeMap<String, EvidenceProtocol.Source>();
        for (var source : sources) if (sourceMap.putIfAbsent(source.path(), source) != null) throw invalid();
        var bodyMap = new TreeMap<String, Body>();
        var associations = new TreeMap<String, List<String>>();
        long rows = 7L + 2L * sources.size();
        for (Body body : bodies) {
            if (!sourceMap.containsKey(body.path()) || bodyMap.putIfAbsent(body.key(), body) != null) throw invalid();
            rows += 1L + body.methods().size();
            for (String method : body.methods()) associations.computeIfAbsent(method, unused -> new java.util.ArrayList<>()).add(body.key());
        }
        var methods = new TreeMap<String, CallerOutputHeaders.Method>();
        var files = new TreeMap<String, CallerOutputHeaders.Output>();
        var owners = new TreeSet<String>();
        for (var file : outputs) {
            if (files.putIfAbsent(file.path(), file) != null || !file.owner().isEmpty() && !owners.add(file.owner())) throw invalid();
            rows++;
            for (var method : file.methods()) {
                if (methods.putIfAbsent(method.identity(), method) != null) throw invalid();
                rows++;
            }
        }
        if (owners.isEmpty() || rows > EvidenceProtocol.MAX_ROWS || !methods.keySet().containsAll(associations.keySet())) throw invalid();
        StringBuilder text = new StringBuilder();
        row(text, "format", FORMAT, "1");
        row(text, "collector", options.collector());
        row(text, "compiler", compiler);
        row(text, "token", EvidenceProtocol.readToken(options));
        row(text, "artifact", artifact);
        row(text, "profile", PROFILE);
        row(text, "output-root", enc(options.root().relativize(options.classOutput()).toString().replace(java.io.File.separatorChar, '/')));
        var counts = new TreeMap<String, Integer>();
        for (var body : bodyMap.values()) counts.merge(body.path(), 1, Integer::sum);
        for (var source : sourceMap.values()) {
            row(text, "source", enc(source.path()), source.sha256());
            row(text, "body-count", enc(source.path()), counts.getOrDefault(source.path(), 0));
        }
        for (var body : bodyMap.values()) {
            row(text, "body", body.key(), enc(body.path()), body.start(), body.end(), body.kind(), body.present(), enc(body.origin()));
            for (var method : body.methods()) row(text, "body-method", body.key(), enc(method));
        }
        for (var file : files.values()) row(text, "output", enc(file.path()), file.sha256(), file.owner().isEmpty() ? "-" : enc(file.owner()));
        for (var method : methods.values()) {
            List<String> links = associations.getOrDefault(method.identity(), List.of());
            String origin = backendOrigins.getOrDefault(method.identity(), "UNOBSERVED");
            if (origin.length() > 256 || origin.isEmpty()) throw invalid();
            row(text, "backend", enc(method.identity()), method.access(), method.code(), links.size() == 1 ? "SOURCE_BOUND" : "UNKNOWN",
                links.size() == 1 ? links.get(0) : "-", enc(origin));
        }
        byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > EvidenceProtocol.MAX_ENCODED_BYTES) throw invalid();
        EvidenceProtocol.publish(output(options), bytes);
    }
    private static String enc(String value) { return EvidenceProtocol.encode(value); }
    private static void row(StringBuilder out, Object... values) {
        String text = String.join("\t", java.util.Arrays.stream(values).map(Object::toString).toList()) + "\n";
        if (text.length() > EvidenceProtocol.MAX_ENCODED_BYTES - out.length()) throw invalid();
        out.append(text);
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("invalid or oversized caller inventory"); }
}
