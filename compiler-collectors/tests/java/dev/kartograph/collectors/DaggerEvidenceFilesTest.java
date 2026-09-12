package dev.kartograph.collectors;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

/** 실제 파일 경계에서 classloader 간 미완료 요청의 혼합과 손상을 거부하는지 검사한다. */
public final class DaggerEvidenceFilesTest {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]); Files.createDirectories(root);
        String artifact = "a".repeat(64);
        var first = options(root, "first", "1".repeat(64));
        var second = options(root, "second", "2".repeat(64));
        var edge = new EvidenceProtocol.Edge("method:sample/A#a()V", "method:sample/B#b()V", "binding");
        var ready = new CountDownLatch(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var tasks = List.of(first, second).stream().map(options -> executor.submit(() -> {
                DaggerEvidenceFiles.begin(options, EvidenceProtocol.readToken(options), artifact);
                ready.countDown(); ready.await();
                DaggerEvidenceFiles.write(options, "2.59", artifact, 0, false, List.of(edge));
                if (!DaggerEvidenceFiles.read(options, artifact).edges().equals(List.of(edge))) throw new AssertionError("request isolation failed");
                return true;
            })).toList();
            for (var task : tasks) task.get();
        } finally { executor.shutdownNow(); }

        Path firstStage = first.output().resolveSibling("first.tsv.dagger.tmp");
        Path secondStage = second.output().resolveSibling("second.tsv.dagger.tmp");
        Files.copy(firstStage, secondStage, StandardCopyOption.REPLACE_EXISTING);
        rejected(() -> DaggerEvidenceFiles.read(second, artifact), "cross-request stage was accepted");
        rejected(() -> DaggerEvidenceFiles.read(first, "b".repeat(64)), "wrong collector artifact was accepted");
        Files.write(firstStage, new byte[]{1}, java.nio.file.StandardOpenOption.APPEND);
        rejected(() -> DaggerEvidenceFiles.read(first, artifact), "trailing stage data was accepted");
        DaggerEvidenceFiles.write(first, "2.59", artifact, 1, true, List.of());
        if (!DaggerEvidenceFiles.read(first, artifact).incomplete()) throw new AssertionError("incomplete marker was lost");
        Files.write(firstStage, new byte[16 * 1024 * 1024 + 1]);
        rejected(() -> DaggerEvidenceFiles.read(first, artifact), "oversized stage was accepted");
        DaggerEvidenceFiles.cleanup(first); DaggerEvidenceFiles.cleanup(second);
        if (Files.exists(firstStage) || Files.exists(secondStage)) throw new AssertionError("stage cleanup failed");
    }

    private static EvidenceProtocol.Options options(Path root, String name, String token) throws Exception {
        Path file = Files.writeString(root.resolve(name + ".token"), token);
        return new EvidenceProtocol.Options(root, root.resolve(name + ".tsv"), file, "dagger-bindings");
    }

    private static void rejected(Runnable operation, String message) {
        try { operation.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError(message);
    }
}
