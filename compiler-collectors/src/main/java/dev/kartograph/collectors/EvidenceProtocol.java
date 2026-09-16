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
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** collector 세 종류가 공유하는 bounded raw TSV 교환 형식과 안전한 파일 지문이다. */
public final class EvidenceProtocol {
    public static final String FORMAT = "kartograph-compiler-evidence";
    public static final String VERSION = "1";
    private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern COLLECTOR = Pattern.compile("javac-constants|kotlin-constants|dagger-bindings");
    private static final Pattern SAFE_VERSION = Pattern.compile("[A-Za-z0-9._+\\-]{1,128}");
    private static final String ROOT_OPTION = "root";
    private static final String OUTPUT_OPTION = "output";
    private static final String TOKEN_OPTION = "token";

    private EvidenceProtocol() {}

    /** compiler가 읽을 절대 경로 옵션이다. 이 값들은 raw 문서에 직렬화하지 않는다. */
    public record Options(Path root, Path output, Path token, String collector) {
        public Options {
            Objects.requireNonNull(root, "root");
            Objects.requireNonNull(output, "output");
            Objects.requireNonNull(token, "token");
            if (!COLLECTOR.matcher(collector).matches()) throw new IllegalArgumentException("unsupported compiler evidence collector");
        }

        public Options withCollector(String value) { return new Options(root, output, token, value); }
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

    /** javac -Xplugin 인자에서 collector 옵션을 읽는다. */
    public static Options parse(String defaultCollector, String... arguments) {
        String collector = defaultCollector;
        Path root = null, output = null, token = null;
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
                default -> throw new IllegalArgumentException("unsupported compiler evidence option");
            }
        }
        if (root == null || output == null || token == null) throw new IllegalArgumentException("compiler evidence root, output and token are required");
        return new Options(root, output, token, collector);
    }

    /** Dagger SPI가 전달한 -A 옵션의 짧은 이름과 정식 이름을 모두 허용한다. */
    public static Options fromDaggerOptions(Map<String, String> arguments) {
        String root = option(arguments, ROOT_OPTION);
        String output = option(arguments, OUTPUT_OPTION);
        String token = option(arguments, TOKEN_OPTION);
        if (root == null || output == null || token == null) throw new IllegalArgumentException("compiler evidence root, output and token are required");
        return new Options(pathOption(root), pathOption(output), pathOption(token), "dagger-bindings");
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
            Path normalizedRoot = root.toAbsolutePath().normalize();
            Path normalizedFile = file.toAbsolutePath().normalize();
            requireRegular(normalizedFile);
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
        if (!SAFE_VERSION.matcher(compiler).matches() || !FINGERPRINT.matcher(artifact).matches()) {
            throw new IllegalArgumentException("invalid compiler evidence identity");
        }
        if (unmapped < 0) throw new IllegalArgumentException("invalid unmapped compiler reference count");
        String token = readToken(options);
        Map<String, Source> sources = new TreeMap<>();
        for (Source source : sourceValues) {
            Source previous = sources.putIfAbsent(source.path(), source);
            if (previous != null && !previous.equals(source)) throw new IllegalArgumentException("compiler source changed during collection");
        }
        TreeSet<Edge> edges = new TreeSet<>(Comparator.comparing(Edge::source).thenComparing(Edge::target).thenComparing(Edge::kind));
        edges.addAll(edgeValues);
        if (6L + sources.size() + edges.size() > 200_000) throw new IllegalArgumentException("compiler evidence exceeds the row limit");
        StringBuilder document = new StringBuilder();
        document.append("format\t").append(FORMAT).append('\t').append(VERSION).append('\n');
        document.append("collector\t").append(options.collector()).append('\n');
        document.append("compiler\t").append(compiler).append('\n');
        document.append("token\t").append(token).append('\n');
        document.append("artifact\t").append(artifact).append('\n');
        for (Source source : sources.values()) {
            document.append("source\t").append(encode(source.path())).append('\t').append(source.sha256()).append('\n');
            if (document.length() > 16 * 1024 * 1024) throw new IllegalArgumentException("compiler evidence exceeds the byte limit");
        }
        document.append("unmapped\t").append(unmapped).append('\n');
        for (Edge edge : edges) {
            document.append("edge\t").append(encode(edge.source())).append('\t').append(encode(edge.target())).append('\t').append(edge.kind()).append('\n');
            if (document.length() > 16 * 1024 * 1024) throw new IllegalArgumentException("compiler evidence exceeds the byte limit");
        }
        publish(outputFile(options.output()), document.toString().getBytes(StandardCharsets.UTF_8));
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
