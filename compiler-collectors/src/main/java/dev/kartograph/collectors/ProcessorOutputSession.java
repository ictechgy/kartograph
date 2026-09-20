package dev.kartograph.collectors;

import java.io.FilterOutputStream;
import java.io.FilterWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** processor API 출력과 명시된 디렉터리의 callback 중 byte 변경을 분리해서 기록한다. */
public final class ProcessorOutputSession {
    private record Output(String kind, String observation, EvidenceProtocol.Source file) {}
    private final EvidenceProtocol.Options options;
    private final String kind, processor, artifact, collector, token;
    private final Path directRoot;
    private final Map<String, Output> outputs = new TreeMap<>();
    private final Set<Path> unclosed = new HashSet<>();
    private Map<String, EvidenceProtocol.Source> before;

    public ProcessorOutputSession(Map<String, String> values, String kind, Class<?> processor, Class<?> collector) {
        if (!Set.of("javac", "kapt", "ksp").contains(kind)) throw new IllegalArgumentException("unsupported processor kind");
        this.kind = kind;
        this.processor = processor.getName();
        this.artifact = EvidenceProtocol.artifactFingerprint(processor);
        this.collector = EvidenceProtocol.artifactFingerprint(collector);
        options = EvidenceProtocol.parse("javac-processors", "root=" + required(values, "root"),
            "output=" + required(values, "output"), "token=" + required(values, "token"));
        token = EvidenceProtocol.readToken(options);
        String direct = values.get("kartograph.outputs.directRoot");
        try {
            directRoot = direct == null ? null : Path.of(direct).toRealPath();
            if (directRoot != null && (!directRoot.startsWith(options.root()) || directRoot.equals(options.root())))
                throw new IllegalArgumentException("direct output scope must be a project subdirectory");
            Files.deleteIfExists(EvidenceProtocol.outputFile(options.output()));
        } catch (IOException error) { throw new IllegalArgumentException("processor output scope is unavailable", error); }
    }

    /** delegate callback의 경계만 관찰한다. 전체 파일 시스템이나 비동기 writer의 신원은 추측하지 않는다. */
    public void beginCallback() {
        if (before != null) throw new IllegalStateException("nested processor callback");
        before = directInventory();
    }
    public void endCallback() {
        if (before == null) throw new IllegalStateException("missing processor callback");
        for (var entry : directInventory().entrySet()) {
            if (!entry.getValue().equals(before.get(entry.getKey()))) {
                var source = entry.getValue();
                outputs.put(source.path(), new Output("file", "callback-scope", source));
            }
        }
        before = null;
        bounded();
    }
    private Map<String, EvidenceProtocol.Source> directInventory() {
        var files = new TreeMap<String, EvidenceProtocol.Source>();
        if (directRoot == null) return files;
        try (var paths = Files.walk(directRoot)) {
            var iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (Files.isSymbolicLink(path)) throw new IllegalArgumentException("symbolic direct output is unsupported");
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
                var source = EvidenceProtocol.source(options.root(), path);
                files.put(source.path(), source);
                if (files.size() > 100_000) throw new IllegalArgumentException("direct output inventory exceeds limit");
            }
        } catch (IOException error) { throw new IllegalArgumentException("direct output inventory is unavailable", error); }
        return files;
    }
    public void opened(Path path) {
        try { path = path.toFile().getCanonicalFile().toPath(); }
        catch (IOException error) { throw new IllegalArgumentException("processor output path is unavailable", error); }
        if (!path.startsWith(options.root()) || directRoot != null && path.startsWith(directRoot))
            throw new IllegalArgumentException("API output must be inside project and outside the direct-write scope");
        if (unclosed.contains(path) || outputs.containsKey(options.root().relativize(path).toString().replace('\\', '/')))
            throw new IllegalArgumentException("processor output was opened twice");
        unclosed.add(path);
        bounded();
    }
    private void closed(Path path, String outputKind) {
        var source = EvidenceProtocol.source(options.root(), path);
        var output = new Output(outputKind, "api", source);
        var previous = outputs.putIfAbsent(source.path(), output);
        if (previous != null && !previous.equals(output)) throw new IllegalArgumentException("processor output changed after close");
        try { unclosed.remove(path.toRealPath()); }
        catch (IOException error) { throw new IllegalArgumentException("closed processor output is unavailable", error); }
    }
    public OutputStream stream(OutputStream stream, Path path, String kind) {
        return new FilterOutputStream(stream) {
            @Override public void close() throws IOException { super.close(); closed(path, kind); }
        };
    }
    public Writer writer(Writer writer, Path path, String kind) {
        return new FilterWriter(writer) {
            @Override public void close() throws IOException { super.close(); closed(path, kind); }
        };
    }
    /** raw sidecar는 processor 완료만 나타낸다. 외부 runner가 빌드 성공과 현재 bytes를 별도로 결합한다. */
    public void finish() {
        if (before != null || !unclosed.isEmpty()) throw new IllegalArgumentException("processor has unfinished outputs or callbacks");
        if (!token.equals(EvidenceProtocol.readToken(options))) throw new IllegalArgumentException("processor input token changed");
        var text = new StringBuilder("format\tkartograph-processor-outputs\t1\nkind\t" + kind + "\ntoken\t" + token +
            "\nprocessor\t" + encode(processor) + "\nprocessorArtifact\t" + artifact + "\ncollectorArtifact\t" + collector + "\n");
        for (var output : outputs.values()) {
            var source = output.file();
            if (!source.equals(EvidenceProtocol.source(options.root(), options.root().resolve(source.path()))))
                throw new IllegalArgumentException("processor output changed before completion");
            text.append("output\t").append(output.kind()).append('\t').append(output.observation()).append('\t')
                .append(encode(source.path())).append('\t').append(source.sha256()).append('\n');
            if (text.length() > 16 * 1024 * 1024) throw new IllegalArgumentException("processor output evidence exceeds byte limit");
        }
        EvidenceProtocol.publish(EvidenceProtocol.outputFile(options.output()), text.toString().getBytes(StandardCharsets.UTF_8));
    }
    private void bounded() { if (outputs.size() + unclosed.size() > 100_000) throw new IllegalArgumentException("processor output count exceeds limit"); }
    private static String required(Map<String, String> values, String key) {
        String value = values.get("kartograph.outputs." + key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("processor output root, output and token are required");
        return value;
    }
    private static String encode(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
}
