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
import java.util.Arrays;
import java.util.Comparator;

/** 분리된 javac/processor classloader가 완료 전 생성 관찰을 교환한다. */
final class ProcessorEvidenceFiles {
    private static final int LIMIT = 16 * 1024 * 1024;
    private static final String MAGIC = "kartograph-processor-stage-1";
    static void begin(EvidenceProtocol.Options options, String artifact) {
        cleanup(options);
        EvidenceProtocol.publish(request(options), marker(options, artifact));
    }
    static void cleanup(EvidenceProtocol.Options options) {
        try { Files.deleteIfExists(request(options)); Files.deleteIfExists(stage(options)); }
        catch (IOException error) { throw new IllegalArgumentException("processor stage could not be removed", error); }
    }
    static boolean hasStage(EvidenceProtocol.Options options) {
        return Files.exists(stage(options), LinkOption.NOFOLLOW_LINKS);
    }
    static void requireRequest(EvidenceProtocol.Options options, String artifact) {
        if (!Arrays.equals(marker(options, artifact), read(request(options), 1024))) {
            throw new IllegalArgumentException("processor evidence requires a matching paired javac collector");
        }
    }
    static void write(EvidenceProtocol.Options options, String artifact, EvidenceProtocol.Generation generation) {
        requireRequest(options, artifact);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeUTF(MAGIC); output.writeUTF(EvidenceProtocol.readToken(options)); output.writeUTF(artifact);
                output.writeUTF(generation.processor()); output.writeUTF(generation.artifact());
                output.writeInt(generation.sources().size());
                for (var source : generation.sources().stream().sorted(Comparator.comparing(EvidenceProtocol.Source::path)).toList()) {
                    output.writeUTF(source.path()); output.writeUTF(source.sha256());
                    if (bytes.size() > LIMIT) throw new IllegalArgumentException("processor stage exceeds its byte limit");
                }
            }
            EvidenceProtocol.publish(stage(options), bytes.toByteArray());
        } catch (IOException error) { throw new IllegalArgumentException("processor observations could not be staged", error); }
    }
    static EvidenceProtocol.Generation read(EvidenceProtocol.Options options, String artifact) {
        requireRequest(options, artifact);
        try (var input = new DataInputStream(new ByteArrayInputStream(read(stage(options), LIMIT)))) {
            if (!MAGIC.equals(input.readUTF()) || !EvidenceProtocol.readToken(options).equals(input.readUTF()) || !artifact.equals(input.readUTF())) {
                throw new IllegalArgumentException("processor stage belongs to a different compilation");
            }
            String processor = input.readUTF(), processorArtifact = input.readUTF();
            int count = input.readInt();
            if (count < 0 || count > 100_000) throw new IllegalArgumentException("invalid processor output count");
            var sources = new ArrayList<EvidenceProtocol.Source>();
            for (int i = 0; i < count; i++) sources.add(new EvidenceProtocol.Source(input.readUTF(), input.readUTF()));
            if (input.read() != -1) throw new IllegalArgumentException("trailing processor stage content");
            return new EvidenceProtocol.Generation(processor, processorArtifact, sources);
        } catch (IOException error) { throw new IllegalArgumentException("processor stage is incomplete", error); }
    }
    private static byte[] read(Path path, int limit) {
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("processor stage is missing or symbolic");
            byte[] bytes;
            try (var input = Files.newInputStream(path)) { bytes = input.readNBytes(limit + 1); }
            if (bytes.length > limit) throw new IllegalArgumentException("processor stage exceeds its byte limit");
            return bytes;
        } catch (IOException error) { throw new IllegalArgumentException("processor stage could not be read", error); }
    }
    private static byte[] marker(EvidenceProtocol.Options options, String artifact) {
        return (MAGIC + "\n" + EvidenceProtocol.readToken(options) + "\n" + artifact + "\n").getBytes(StandardCharsets.UTF_8);
    }
    private static Path request(EvidenceProtocol.Options options) {
        Path output = EvidenceProtocol.outputFile(options.output());
        return output.resolveSibling(output.getFileName() + ".processor-request.tmp");
    }
    private static Path stage(EvidenceProtocol.Options options) {
        Path output = EvidenceProtocol.outputFile(options.output());
        return output.resolveSibling(output.getFileName() + ".processor-stage.tmp");
    }
}
