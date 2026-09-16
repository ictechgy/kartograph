package dev.kartograph.collectors;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/** Gradle의 서로 다른 compiler/processor classloader 사이에서 미완료 Dagger 사실만 전달한다. */
final class DaggerEvidenceFiles {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final String MAGIC = "kartograph-dagger-stage-1";

    record Stage(String compiler, String artifact, int unmapped, boolean incomplete, List<EvidenceProtocol.Edge> edges) {}

    static void begin(EvidenceProtocol.Options options, String token, String artifact) {
        cleanup(options);
        EvidenceProtocol.publish(request(options), marker(token, artifact));
    }

    static void requireRequest(EvidenceProtocol.Options options, String artifact) {
        try {
            byte[] expected = marker(EvidenceProtocol.readToken(options), artifact);
            if (!java.util.Arrays.equals(expected, read(request(options), 1024))) {
                throw new IllegalArgumentException("Dagger evidence request does not match its javac collector");
            }
        } catch (IOException error) {
            EvidenceProtocol.invalidateOutput(options);
            throw new IllegalArgumentException("Dagger evidence requires a paired javac collector request", error);
        }
    }

    static void write(EvidenceProtocol.Options options, String compiler, String artifact,
        int unmapped, boolean incomplete, Collection<EvidenceProtocol.Edge> references) {
        requireRequest(options, artifact);
        List<EvidenceProtocol.Edge> edges = references.stream().sorted(Comparator.comparing(EvidenceProtocol.Edge::source)
            .thenComparing(EvidenceProtocol.Edge::target).thenComparing(EvidenceProtocol.Edge::kind)).toList();
        if (unmapped < 0 || edges.size() > 200_000 || edges.stream().mapToLong(edge ->
            16L + 3L * (edge.source().length() + edge.target().length() + edge.kind().length())).sum() > MAX_BYTES - 1024) {
            throw new IllegalArgumentException("Dagger evidence stage exceeds its limit");
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeUTF(MAGIC);
                output.writeUTF(EvidenceProtocol.readToken(options));
                output.writeUTF(artifact);
                output.writeUTF(compiler);
                output.writeInt(unmapped);
                output.writeBoolean(incomplete);
                output.writeInt(edges.size());
                for (EvidenceProtocol.Edge edge : edges) {
                    output.writeUTF(edge.source()); output.writeUTF(edge.target()); output.writeUTF(edge.kind());
                }
            }
            EvidenceProtocol.publish(stage(options), bytes.toByteArray());
        } catch (IOException error) {
            throw new IllegalArgumentException("Dagger evidence could not be staged", error);
        }
    }

    static Stage read(EvidenceProtocol.Options options, String artifact) {
        requireRequest(options, artifact);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(read(stage(options), MAX_BYTES)))) {
            if (!MAGIC.equals(input.readUTF()) || !EvidenceProtocol.readToken(options).equals(input.readUTF()) || !artifact.equals(input.readUTF())) {
                throw new IllegalArgumentException("Dagger evidence stage does not match its javac request");
            }
            String compiler = input.readUTF();
            int unmapped = input.readInt(); boolean incomplete = input.readBoolean(); int count = input.readInt();
            if (unmapped < 0 || count < 0 || count > 200_000) throw new IllegalArgumentException("invalid Dagger evidence stage count");
            List<EvidenceProtocol.Edge> edges = new ArrayList<>();
            for (int index = 0; index < count; index++) edges.add(new EvidenceProtocol.Edge(input.readUTF(), input.readUTF(), input.readUTF()));
            if (input.read() != -1) throw new IllegalArgumentException("trailing Dagger evidence stage data");
            return new Stage(compiler, artifact, unmapped, incomplete, edges);
        } catch (IOException error) {
            throw new IllegalArgumentException("Dagger did not publish a readable evidence stage", error);
        }
    }

    static void cleanup(EvidenceProtocol.Options options) {
        try { Files.deleteIfExists(request(options)); Files.deleteIfExists(stage(options)); }
        catch (IOException error) { throw new IllegalArgumentException("Dagger evidence stage could not be removed", error); }
    }

    private static byte[] read(Path path, int limit) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("missing evidence stage");
        byte[] bytes;
        try (var stream = Files.newInputStream(path)) { bytes = stream.readNBytes(limit + 1); }
        if (bytes.length > limit) throw new IllegalArgumentException("Dagger evidence stage exceeds its byte limit");
        return bytes;
    }

    private static byte[] marker(String token, String artifact) {
        return ("kartograph-dagger-request-1\n" + token + "\n" + artifact + "\n").getBytes(StandardCharsets.UTF_8);
    }
    private static Path request(EvidenceProtocol.Options options) {
        Path output = EvidenceProtocol.outputFile(options.output());
        return output.resolveSibling(output.getFileName() + ".request.tmp");
    }
    private static Path stage(EvidenceProtocol.Options options) {
        Path output = EvidenceProtocol.outputFile(options.output());
        return output.resolveSibling(output.getFileName() + ".dagger.tmp");
    }
}
