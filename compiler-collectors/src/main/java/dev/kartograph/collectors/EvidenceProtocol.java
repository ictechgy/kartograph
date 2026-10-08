package dev.kartograph.collectors;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** collector 세 종류가 공유하는 bounded raw TSV 교환 형식과 안전한 파일 지문이다. */
public final class EvidenceProtocol {
    public static final String FORMAT = "kartograph-compiler-evidence";
    public static final String VERSION = "1";
    static final int MAX_ROWS = 200_000;
    static final int MAX_ENCODED_BYTES = 16 * 1024 * 1024;
    private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern COLLECTOR = Pattern.compile("javac-constants|kotlin-constants|dagger-bindings|javac-processors");
    private static final Pattern SAFE_VERSION = Pattern.compile("[A-Za-z0-9._+\\-]{1,128}");
    private static final String ROOT_OPTION = "root";
    private static final String OUTPUT_OPTION = "output";
    private static final String TOKEN_OPTION = "token";
    private static final String CALL_POSITIONS_OPTION = "callPositions";

    private EvidenceProtocol() {}

    /** compiler가 읽을 절대 경로 옵션이다. 이 값들은 raw 문서에 직렬화하지 않는다. */
    public record Options(Path root, Path output, Path token, String collector, boolean callPositions) {
        public Options {
            Objects.requireNonNull(root, "root");
            Objects.requireNonNull(output, "output");
            Objects.requireNonNull(token, "token");
            try { root = root.toRealPath(); }
            catch (IOException error) { throw new IllegalArgumentException("compiler project root is unavailable", error); }
            if (!COLLECTOR.matcher(collector).matches()) throw new IllegalArgumentException("unsupported compiler evidence collector");
            if (callPositions && !(collector.equals("javac-constants") || collector.equals("kotlin-constants"))) {
                throw new IllegalArgumentException("call positions require a constants collector");
            }
        }

        public Options(Path root, Path output, Path token, String collector) {
            this(root, output, token, collector, false);
        }

        public Options withCollector(String value) { return new Options(root, output, token, value, callPositions); }
    }

    public record Source(String path, String sha256) {
        public Source {
            if (path.isBlank() || path.startsWith("/") || path.contains("\\") || path.contains(":") ||
                path.chars().anyMatch(c -> c < 32) || java.util.Arrays.asList(path.split("/", -1)).contains("..")) {
                throw new IllegalArgumentException("invalid compiler source identity");
            }
            if (!FINGERPRINT.matcher(sha256).matches()) throw new IllegalArgumentException("invalid compiler source fingerprint");
        }
    }

    public record Edge(String source, String target, String kind) {
        public Edge {
            if (source.isBlank() || target.isBlank() || !(kind.equals("constant") || kind.equals("binding"))) {
                throw new IllegalArgumentException("invalid compiler evidence edge");
            }
        }
    }

    /** 정확히 compiler selector에 대응하는 opt-in v3 호출 위치다. */
    public record CallPosition(
        String caller,
        String target,
        Source source,
        int start,
        int end,
        int line,
        int column
    ) {
        public CallPosition {
            if (caller.isBlank() || target.isBlank() || source == null || start < 0 || end <= start || line < 1 || column < 1) {
                throw new IllegalArgumentException("invalid compiler call position");
            }
        }
    }

    static final class CallPositionLimitException extends IllegalArgumentException {
        CallPositionLimitException(String message) { super(message); }
    }

    /** producer-side bounded v3 accumulator; it fails before an oversized document can grow unbounded state. */
    static final class CallPositionBuffer {
        private final ArrayList<CallPosition> positions = new ArrayList<>();
        private int observed;
        private int unmapped;
        private int ambiguous;
        private int retainedRows;
        private long retainedBytes;
        private boolean failed;

        void observe() {
            observed = increment(observed);
        }

        void unmapped() {
            unmapped = increment(unmapped);
        }

        void ambiguous() {
            ambiguous = increment(ambiguous);
        }

        void add(CallPosition position) {
            retain(encodedCallBytes(position));
            positions.add(position);
        }

        void retainPending(long encodedBytes) {
            retain(encodedBytes);
        }

        void dropPending(long encodedBytes) {
            requireActive();
            release(encodedBytes);
        }

        void replacePending(long pendingBytes, CallPosition position) {
            requireActive();
            release(pendingBytes);
            retain(encodedCallBytes(position));
            positions.add(position);
        }

        private int increment(int value) {
            requireActive();
            if (value == Integer.MAX_VALUE) limit("compiler call position count exceeds Int range");
            return value + 1;
        }

        private void retain(long encodedBytes) {
            requireActive();
            if (encodedBytes < 0) fail("compiler call position budget is inconsistent");
            if (retainedRows == MAX_ROWS) limit("compiler evidence exceeds the row limit");
            if (encodedBytes > MAX_ENCODED_BYTES - retainedBytes) limit("compiler evidence exceeds the byte limit");
            retainedRows++;
            retainedBytes += encodedBytes;
        }

        private void release(long encodedBytes) {
            if (encodedBytes < 0 || retainedRows < 1 || encodedBytes > retainedBytes) {
                fail("compiler call position budget is inconsistent");
            }
            retainedRows--;
            retainedBytes -= encodedBytes;
        }

        private void requireActive() {
            if (failed) throw new IllegalArgumentException("compiler call position collection already failed");
        }

        private void fail(String message) {
            abort();
            throw new IllegalArgumentException(message);
        }

        private void limit(String message) {
            abort();
            throw new CallPositionLimitException(message);
        }

        private void abort() {
            positions.clear();
            retainedRows = 0;
            retainedBytes = 0;
            failed = true;
        }

        List<CallPosition> positionsList() { return List.copyOf(positions); }
        int observedCount() { return observed; }
        int unmappedCount() { return unmapped; }
        int ambiguousCount() { return ambiguous; }
    }

    /** JVM identity가 정해지기 전 보관하는 semantic call payload의 canonical encoded 크기다. */
    static long pendingCallBytes(String callerPath, String targetPath, int start, int end, int line, int column) {
        return 8L + encodedLength(callerPath) + encodedLength(targetPath) + decimalLength(start) + decimalLength(end) +
            decimalLength(line) + decimalLength(column) + 6L;
    }

    static long pendingCallBytes(CallPosition position, String targetOwner, String targetPath) {
        return encodedCallBytes(position) + 9L + encodedLength(targetOwner) + encodedLength(targetPath) + 2L;
    }

    private static long encodedCallBytes(CallPosition position) {
        return 5L + encodedLength(position.caller()) + 1L + encodedLength(position.target()) + 1L +
            encodedLength(position.source().path()) + 1L + position.source().sha256().length() + 1L +
            decimalLength(position.start()) + 1L + decimalLength(position.end()) + 1L +
            decimalLength(position.line()) + 1L + decimalLength(position.column()) + 1L;
    }

    private static long encodedLength(String value) {
        long bytes = value.getBytes(StandardCharsets.UTF_8).length;
        return (bytes / 3L) * 4L + switch ((int) (bytes % 3L)) { case 0 -> 0L; case 1 -> 2L; default -> 3L; };
    }

    private static int decimalLength(int value) {
        return Integer.toString(value).length();
    }

    /** 명시적으로 선택된 processor 하나의 실제 source 생성 관찰이다. */
    public record Generation(String processor, String artifact, Collection<Source> sources) {
        public Generation {
            if (processor.length() > 1024 || !processor.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*") ||
                !FINGERPRINT.matcher(artifact).matches() || sources.size() > 100_000) {
                throw new IllegalArgumentException("invalid processor generation evidence");
            }
        }
    }

    /** javac -Xplugin 인자에서 collector 옵션을 읽는다. */
    public static Options parse(String defaultCollector, String... arguments) {
        String collector = defaultCollector;
        Path root = null, output = null, token = null;
        boolean callPositions = false;
        for (String argument : arguments) {
            int separator = argument.indexOf('=');
            if (separator <= 0) throw new IllegalArgumentException("compiler evidence options must be key=value");
            String key = argument.substring(0, separator);
            String value = argument.substring(separator + 1);
            if (value.isBlank()) throw new IllegalArgumentException("compiler evidence option is empty");
            switch (key) {
                case "collector" -> collector = value;
                case ROOT_OPTION -> root = pathOption(value);
                case OUTPUT_OPTION -> output = pathOption(value);
                case TOKEN_OPTION -> token = pathOption(value);
                case CALL_POSITIONS_OPTION -> {
                    if (!value.equals("true") && !value.equals("false")) {
                        throw new IllegalArgumentException("compiler call positions option must be true or false");
                    }
                    callPositions = Boolean.parseBoolean(value);
                }
                default -> throw new IllegalArgumentException("unsupported compiler evidence option");
            }
        }
        if (root == null || output == null || token == null) throw new IllegalArgumentException("compiler evidence root, output and token are required");
        if (callPositions && !(collector.equals("javac-constants") || collector.equals("kotlin-constants"))) {
            throw new IllegalArgumentException("call positions require a constants collector");
        }
        return new Options(root, output, token, collector, callPositions);
    }

    /** Dagger SPI가 전달한 -A 옵션의 짧은 이름과 정식 이름을 모두 허용한다. */
    public static Options fromDaggerOptions(Map<String, String> arguments) {
        String root = option(arguments, ROOT_OPTION);
        String output = option(arguments, OUTPUT_OPTION);
        String token = option(arguments, TOKEN_OPTION);
        if (root == null || output == null || token == null) throw new IllegalArgumentException("compiler evidence root, output and token are required");
        return new Options(pathOption(root), pathOption(output), pathOption(token), "dagger-bindings", false);
    }

    /** javac plugin의 공백 분리와 충돌하지 않는 file URI 또는 일반 경로를 받는다. */
    public static Path pathOption(String value) {
        try { return value.startsWith("file:") ? Path.of(URI.create(value)) : Path.of(value); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("invalid compiler evidence path option", invalid); }
    }

    private static String option(Map<String, String> arguments, String name) {
        for (String prefix : new String[]{"kartograph.evidence.", "kartograph.compiler-evidence.", ""}) {
            String value = arguments.get(prefix + name);
            if (value != null) return value;
        }
        return null;
    }

    public static String readToken(Options options) {
        try {
            requireRegular(options.token());
            String token = Files.readString(options.token(), StandardCharsets.UTF_8).strip();
            if (!FINGERPRINT.matcher(token).matches()) throw new IllegalArgumentException("compiler evidence token is not a SHA-256 value");
            return token;
        } catch (IOException | RuntimeException error) {
            if (error instanceof IllegalArgumentException) throw (IllegalArgumentException) error;
            throw new IllegalArgumentException("compiler evidence token could not be read", error);
        }
    }

    /** packaged collector jar만 허용한다. exploded class directory는 artifact identity가 아니다. */
    public static String artifactFingerprint(Class<?> anchor) {
        try {
            if (anchor.getProtectionDomain() == null || anchor.getProtectionDomain().getCodeSource() == null) {
                throw new IllegalArgumentException("collector artifact location is unavailable");
            }
            URI location = anchor.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path path = Path.of(location).toAbsolutePath().normalize();
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || !path.getFileName().toString().endsWith(".jar")) {
                throw new IllegalArgumentException("collector artifact must be a packaged jar");
            }
            return contentFingerprint(path);
        } catch (URISyntaxException | IOException error) {
            throw new IllegalArgumentException("collector artifact could not be fingerprinted", error);
        }
    }

    /** 실제 compiler unit 파일만 호출자가 넘겨야 한다. filesystem walk로 source coverage를 만들지 않는다. */
    public static Source source(Path root, Path file) {
        try {
            Path normalizedRoot = root.toRealPath();
            requireRegular(file);
            Path normalizedFile = file.toRealPath();
            if (!normalizedFile.startsWith(normalizedRoot)) throw new IllegalArgumentException("compiler source is outside project root");
            String relative = normalizedRoot.relativize(normalizedFile).toString().replace('\\', '/');
            return new Source(relative, rawSha256(normalizedFile));
        } catch (IOException error) {
            throw new IllegalArgumentException("compiler source could not be fingerprinted", error);
        }
    }

    /** source/edge row를 정렬·중복 제거하고 temporary file을 원자적으로 공개한다. */
    public static void write(
        Options options,
        String compiler,
        String artifact,
        Collection<Source> sourceValues,
        int unmapped,
        Collection<Edge> edgeValues
    ) {
        write(options, compiler, artifact, sourceValues, unmapped, edgeValues, null);
    }

    public static void write(Options options, String compiler, String artifact, Collection<Source> sourceValues,
        int unmapped, Collection<Edge> edgeValues, Generation generation) {
        write(options, compiler, artifact, sourceValues, unmapped, edgeValues, generation,
            List.of(), 0, 0, 0);
    }

    public static void write(Options options, String compiler, String artifact, Collection<Source> sourceValues,
        int unmapped, Collection<Edge> edgeValues, Generation generation, Collection<CallPosition> callPositions,
        int observedCalls, int unmappedCalls, int ambiguousCalls) {
        if (options.collector().equals("javac-processors") != (generation != null)) throw new IllegalArgumentException("processor evidence identity is missing or misplaced");
        if (!SAFE_VERSION.matcher(compiler).matches() || !FINGERPRINT.matcher(artifact).matches()) {
            throw new IllegalArgumentException("invalid compiler evidence identity");
        }
        if (unmapped < 0) throw new IllegalArgumentException("invalid unmapped compiler reference count");
        if (!options.callPositions() && (!callPositions.isEmpty() || observedCalls != 0 || unmappedCalls != 0 || ambiguousCalls != 0)) {
            throw new IllegalArgumentException("call positions were supplied without opt-in");
        }
        if (options.callPositions() && (generation != null ||
            !(options.collector().equals("javac-constants") || options.collector().equals("kotlin-constants")))) {
            throw new IllegalArgumentException("call positions require a constants collector");
        }
        if (observedCalls < 0 || unmappedCalls < 0 || ambiguousCalls < 0) {
            throw new IllegalArgumentException("invalid compiler call position counts");
        }
        String token = readToken(options);
        Map<String, Source> sources = new TreeMap<>();
        for (Source source : sourceValues) {
            Source previous = sources.putIfAbsent(source.path(), source);
            if (previous != null && !previous.equals(source)) throw new IllegalArgumentException("compiler source changed during collection");
        }
        TreeSet<Edge> edges = new TreeSet<>(Comparator.comparing(Edge::source).thenComparing(Edge::target).thenComparing(Edge::kind));
        edges.addAll(edgeValues);
        Map<String, CallPosition> uniquePositions = new HashMap<>();
        for (CallPosition position : callPositions) {
            Source source = sources.get(position.source().path());
            if (!position.source().equals(source)) throw new IllegalArgumentException("compiler call position source does not match inventory");
            String key = position.caller() + "\u0000" + position.target() + "\u0000" + position.source().path() + "\u0000" + position.start();
            if (uniquePositions.putIfAbsent(key, position) != null) throw new IllegalArgumentException("duplicate compiler call position");
        }
        List<CallPosition> sortedPositions = new ArrayList<>(uniquePositions.values());
        sortedPositions.sort(Comparator.comparing(CallPosition::caller).thenComparing(CallPosition::target)
            .thenComparing(position -> position.source().path()).thenComparingInt(CallPosition::start)
            .thenComparingInt(CallPosition::end));
        long accountedCalls = (long) sortedPositions.size() + unmappedCalls + ambiguousCalls;
        if (options.callPositions() && observedCalls != accountedCalls) {
            throw new IllegalArgumentException("compiler call position counts do not add up");
        }
        long rows = 8L + sources.size() + edges.size() + (generation == null ? 0 : generation.sources().size()) +
            (options.callPositions() ? 1L + sortedPositions.size() : 0L);
        if (rows > MAX_ROWS) throw new IllegalArgumentException("compiler evidence exceeds the row limit");
        String documentVersion = options.callPositions() ? "3" : generation == null ? VERSION : "2";
        long documentBytes = 0;
        documentBytes = addDocumentBytes(documentBytes, rawRowBytes("format", FORMAT, documentVersion));
        documentBytes = addDocumentBytes(documentBytes, rawRowBytes("collector", options.collector()));
        documentBytes = addDocumentBytes(documentBytes, rawRowBytes("compiler", compiler));
        documentBytes = addDocumentBytes(documentBytes, rawRowBytes("token", token));
        documentBytes = addDocumentBytes(documentBytes, rawRowBytes("artifact", artifact));
        if (generation != null) {
            documentBytes = addDocumentBytes(documentBytes, 11L + encodedLength(generation.processor()));
            documentBytes = addDocumentBytes(documentBytes, rawRowBytes("processorArtifact", generation.artifact()));
            for (Source source : generation.sources()) {
                if (!source.equals(sources.get(source.path()))) throw new IllegalArgumentException("generated source was not observed by compiler");
                documentBytes = addDocumentBytes(documentBytes,
                    12L + encodedLength(source.path()) + source.sha256().length());
            }
        }
        for (Source source : sources.values()) {
            documentBytes = addDocumentBytes(documentBytes,
                9L + encodedLength(source.path()) + source.sha256().length());
        }
        documentBytes = addDocumentBytes(documentBytes, rawRowBytes("unmapped", Integer.toString(unmapped)));
        for (Edge edge : edges) {
            documentBytes = addDocumentBytes(documentBytes,
                8L + encodedLength(edge.source()) + encodedLength(edge.target()) + edge.kind().length());
        }
        if (options.callPositions()) {
            documentBytes = addDocumentBytes(documentBytes, rawRowBytes("callStats", Integer.toString(observedCalls),
                Integer.toString(sortedPositions.size()), Integer.toString(unmappedCalls), Integer.toString(ambiguousCalls)));
            for (CallPosition position : sortedPositions) {
                documentBytes = addDocumentBytes(documentBytes, encodedCallBytes(position));
            }
        }
        StringBuilder document = new StringBuilder();
        document.append("format\t").append(FORMAT).append('\t').append(documentVersion).append('\n');
        document.append("collector\t").append(options.collector()).append('\n');
        document.append("compiler\t").append(compiler).append('\n');
        document.append("token\t").append(token).append('\n');
        document.append("artifact\t").append(artifact).append('\n');
        if (generation != null) {
            document.append("processor\t").append(encode(generation.processor())).append('\n');
            document.append("processorArtifact\t").append(generation.artifact()).append('\n');
            for (Source source : generation.sources().stream().sorted(Comparator.comparing(Source::path)).toList()) {
                document.append("generated\t").append(encode(source.path())).append('\t').append(source.sha256()).append('\n');
            }
        }
        for (Source source : sources.values()) {
            document.append("source\t").append(encode(source.path())).append('\t').append(source.sha256()).append('\n');
        }
        document.append("unmapped\t").append(unmapped).append('\n');
        for (Edge edge : edges) {
            document.append("edge\t").append(encode(edge.source())).append('\t').append(encode(edge.target())).append('\t').append(edge.kind()).append('\n');
        }
        if (options.callPositions()) {
            document.append("callStats\t").append(observedCalls).append('\t').append(sortedPositions.size()).append('\t')
                .append(unmappedCalls).append('\t').append(ambiguousCalls).append('\n');
            for (CallPosition position : sortedPositions) {
                document.append("call\t").append(encode(position.caller())).append('\t').append(encode(position.target())).append('\t')
                    .append(encode(position.source().path())).append('\t').append(position.source().sha256()).append('\t')
                    .append(position.start()).append('\t').append(position.end()).append('\t').append(position.line()).append('\t')
                    .append(position.column()).append('\n');
            }
        }
        byte[] encodedDocument = document.toString().getBytes(StandardCharsets.UTF_8);
        if (encodedDocument.length != documentBytes || encodedDocument.length > MAX_ENCODED_BYTES) {
            throw new IllegalArgumentException("compiler evidence exceeds the byte limit");
        }
        publish(outputFile(options.output()), encodedDocument);
    }

    private static long rawRowBytes(String... fields) {
        long bytes = fields.length;
        for (String field : fields) bytes += field.getBytes(StandardCharsets.UTF_8).length;
        return bytes;
    }

    private static long addDocumentBytes(long current, long rowBytes) {
        if (rowBytes < 0 || rowBytes > MAX_ENCODED_BYTES - current) {
            throw new IllegalArgumentException("compiler evidence exceeds the byte limit");
        }
        return current + rowBytes;
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    static Path outputFile(Path configured) {
        try {
            if (Files.exists(configured, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(configured)) throw new IllegalArgumentException("compiler evidence output is symbolic");
                if (Files.isDirectory(configured, LinkOption.NOFOLLOW_LINKS)) return configured.resolve("compiler-references.tsv");
                if (Files.isRegularFile(configured, LinkOption.NOFOLLOW_LINKS)) return configured;
                throw new IllegalArgumentException("compiler evidence output is not a regular file or directory");
            }
            return configured.getFileName().toString().endsWith(".tsv") ? configured : configured.resolve("compiler-references.tsv");
        } catch (RuntimeException error) {
            if (error instanceof IllegalArgumentException) throw error;
            throw new IllegalArgumentException("compiler evidence output could not be inspected", error);
        }
    }

    static void publish(Path target, byte[] bytes) {
        try {
            Path parent = target.toAbsolutePath().normalize().getParent();
            if (parent == null) throw new IllegalArgumentException("compiler evidence output has no parent");
            Files.createDirectories(parent);
            if (Files.isSymbolicLink(target)) throw new IllegalArgumentException("compiler evidence output is symbolic");
            Path temporary = Files.createTempFile(parent, ".kartograph-compiler-evidence-", ".tmp");
            try {
                Files.write(temporary, bytes);
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException error) {
            throw new IllegalArgumentException("compiler evidence output could not be published", error);
        }
    }

    static void invalidateOutput(Options options) {
        try {
            Path output = outputFile(options.output());
            if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
                requireRegular(output);
                Files.delete(output);
            }
        } catch (IOException error) {
            throw new IllegalArgumentException("previous compiler evidence could not be invalidated", error);
        }
    }

    private static String contentFingerprint(Path path) throws IOException {
        requireRegular(path);
        String raw = rawSha256(path);
        MessageDigest digest = sha256();
        put(digest, "file");
        put(digest, raw);
        return hex(digest.digest());
    }

    private static String rawSha256(Path path) throws IOException {
        requireRegular(path);
        MessageDigest digest = sha256();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        return hex(digest.digest());
    }

    private static void requireRegular(Path path) {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("compiler evidence input is not a regular file");
        }
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static void put(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value));
        return result.toString();
    }
}
