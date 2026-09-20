package dev.kartograph.collectors;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

/** Filer의 비정규 URI도 한 canonical identity로 닫히며 중복 생성은 거부한다. */
public final class ProcessorOutputSessionTest {
    public static void main(String[] args) throws Exception {
        if (new OutputRecordingProcessor().getCompletions(null, null, null, "").iterator().hasNext())
            throw new AssertionError("uninitialized processor has completions");
        Path root = Files.createDirectories(Path.of(args[0])).toRealPath();
        Files.createDirectories(root.resolve("nested")); Files.createDirectories(root.resolve("generated"));
        Path token = root.resolve("token"), output = root.resolve("outputs.tsv");
        Files.writeString(token, "a".repeat(64));
        var session = new ProcessorOutputSession(Map.of("kartograph.outputs.root", root.toUri().toString(),
            "kartograph.outputs.output", output.toUri().toString(), "kartograph.outputs.token", token.toUri().toString()),
            "javac", OutputRecordingProcessor.class, OutputRecordingProcessor.class);
        Path alias = root.resolve("nested/../generated/Observed.txt");
        session.opened(alias);
        try (var writer = session.writer(Files.newBufferedWriter(alias), alias, "resource")) { writer.write("observed"); }
        boolean rejected = false;
        try { session.opened(root.resolve("generated/Observed.txt")); }
        catch (IllegalArgumentException expected) { rejected = true; }
        if (!rejected) throw new AssertionError("duplicate canonical output was accepted");
        session.finish();
        String path = Base64.getUrlEncoder().withoutPadding().encodeToString("generated/Observed.txt".getBytes(StandardCharsets.UTF_8));
        if (!Files.readString(output).contains("output\tresource\tapi\t" + path + "\t"))
            throw new AssertionError("output did not retain its canonical path");
    }
}
