package dev.kartograph.collectors;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** opt-in call protocol의 옵션 순서, 실패 원자성, 수집 단계 상한을 검사한다. */
public final class EvidenceProtocolCallPositionsTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createDirectories(Path.of(args[0])).toRealPath();
        Path token = Files.writeString(root.resolve("token"), "a".repeat(64));
        optionOrder(root, token);
        collectionCapsClearAndStop();
        invalidDocumentsPublishNothing(root, token);
        oversizedSourceAndEdgePublishNothing(root, token);
    }

    private static void optionOrder(Path root, Path token) {
        String rootValue = root.toUri().toString();
        String tokenValue = token.toUri().toString();
        String outputValue = root.resolve("option.tsv").toUri().toString();
        var disabled = EvidenceProtocol.parse("javac-constants",
            "root=" + rootValue, "output=" + outputValue, "token=" + tokenValue,
            "callPositions=true", "callPositions=false");
        if (disabled.callPositions()) throw new AssertionError("last explicit false did not disable call positions");
        var enabled = EvidenceProtocol.parse("javac-constants",
            "root=" + rootValue, "output=" + outputValue, "token=" + tokenValue,
            "callPositions=false", "callPositions=true");
        if (!enabled.callPositions()) throw new AssertionError("last explicit true did not enable call positions");
    }

    private static void collectionCapsClearAndStop() {
        var source = new EvidenceProtocol.Source("src/A.java", "b".repeat(64));
        var position = new EvidenceProtocol.CallPosition("method:a/A#a()V", "method:a/B#b()V", source, 0, 1, 1, 1);

        var rows = new EvidenceProtocol.CallPositionBuffer();
        for (int index = 0; index < EvidenceProtocol.MAX_ROWS; index++) rows.retainPending(0);
        rejectedLimit(() -> rows.retainPending(0), "retained row overflow was accepted");
        if (!rows.positionsList().isEmpty()) throw new AssertionError("row overflow retained partial call evidence");
        rejectedNonLimit(() -> rows.add(position), "failed row buffer resumed collection as a limit failure");

        var bytes = new EvidenceProtocol.CallPositionBuffer();
        bytes.retainPending(EvidenceProtocol.MAX_ENCODED_BYTES);
        rejectedLimit(() -> bytes.retainPending(1), "encoded byte overflow was accepted");
        if (!bytes.positionsList().isEmpty()) throw new AssertionError("byte overflow retained partial call evidence");
        rejectedNonLimit(() -> bytes.observe(), "failed byte buffer resumed stats collection as a limit failure");

        var inconsistent = new EvidenceProtocol.CallPositionBuffer();
        rejectedNonLimit(() -> inconsistent.dropPending(0), "invalid buffer state was classified as a limit");
    }

    private static void invalidDocumentsPublishNothing(Path root, Path token) {
        var source = new EvidenceProtocol.Source("src/A.java", "b".repeat(64));
        var first = new EvidenceProtocol.CallPosition("method:a/A#a()V", "method:a/B#b()V", source, 0, 1, 1, 1);
        var conflict = new EvidenceProtocol.CallPosition("method:a/A#a()V", "method:a/B#b()V", source, 0, 2, 1, 1);
        rejected(() -> new EvidenceProtocol.CallPosition("", "method:a/B#b()V", source, 0, 1, 1, 1),
            "invalid call row was accepted");
        assertWriteRejected(root, token, "duplicate.tsv", List.of(first, first), 2, 0, 0);
        assertWriteRejected(root, token, "conflict.tsv", List.of(first, conflict), 2, 0, 0);
        assertWriteRejected(root, token, "stats.tsv", List.of(first), 2, 0, 0);
    }

    private static void assertWriteRejected(Path root, Path token, String name,
        List<EvidenceProtocol.CallPosition> positions, int observed, int unmapped, int ambiguous) {
        Path output = root.resolve(name);
        var options = new EvidenceProtocol.Options(root, output, token, "javac-constants", true);
        var source = positions.get(0).source();
        rejected(() -> EvidenceProtocol.write(options, "17", "c".repeat(64), List.of(source), 0, List.of(), null,
            positions, observed, unmapped, ambiguous), "invalid v3 document was accepted");
        if (Files.exists(output)) throw new AssertionError("invalid v3 document was partially published");
    }

    private static void oversizedSourceAndEdgePublishNothing(Path root, Path token) {
        String oversized = "x".repeat(12 * 1024 * 1024);
        var source = new EvidenceProtocol.Source("src/" + oversized, "d".repeat(64));
        Path sourceOutput = root.resolve("oversized-source.tsv");
        var sourceOptions = new EvidenceProtocol.Options(root, sourceOutput, token, "javac-constants");
        rejected(() -> EvidenceProtocol.write(sourceOptions, "17", "e".repeat(64), List.of(source), 0, List.of()),
            "oversized source row was accepted");
        if (Files.exists(sourceOutput)) throw new AssertionError("oversized source document was partially published");

        Path edgeOutput = root.resolve("oversized-edge.tsv");
        var edgeOptions = new EvidenceProtocol.Options(root, edgeOutput, token, "javac-constants");
        var edge = new EvidenceProtocol.Edge("method:a/A#" + oversized, "method:a/B#b()V", "constant");
        rejected(() -> EvidenceProtocol.write(edgeOptions, "17", "f".repeat(64), List.of(), 0, List.of(edge)),
            "oversized edge row was accepted");
        if (Files.exists(edgeOutput)) throw new AssertionError("oversized edge document was partially published");
    }

    private static void rejected(Runnable operation, String message) {
        try { operation.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError(message);
    }

    private static void rejectedLimit(Runnable operation, String message) {
        try { operation.run(); }
        catch (EvidenceProtocol.CallPositionLimitException expected) { return; }
        catch (IllegalArgumentException wrongKind) { throw new AssertionError(message + ": wrong exception type", wrongKind); }
        throw new AssertionError(message);
    }

    private static void rejectedNonLimit(Runnable operation, String message) {
        try { operation.run(); }
        catch (EvidenceProtocol.CallPositionLimitException wrongKind) { throw new AssertionError(message, wrongKind); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError(message);
    }
}
