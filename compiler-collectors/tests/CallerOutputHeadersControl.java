package dev.kartograph.collectors;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** 별도 프로세스에서 header reader의 실패 경계를 검증한다. */
public final class CallerOutputHeadersControl {
    private static int rejected;
    private static void reject(Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException expected) { rejected++; return; }
        throw new AssertionError("malformed output was accepted");
    }
    public static void main(String[] arguments) throws Exception {
        Path classFile = Path.of(arguments[0]);
        String relative = arguments[1];
        byte[] bytes = Files.readAllBytes(classFile);
        CallerOutputHeaders.Output output = CallerOutputHeaders.read(relative, bytes);
        if (output.methods().isEmpty()) throw new AssertionError("positive method inventory missing");
        reject(() -> CallerOutputHeaders.read("Wrong.class", bytes));
        reject(() -> CallerOutputHeaders.read("../escape.class", bytes));
        reject(() -> CallerOutputHeaders.read(relative, new byte[0]));
        reject(() -> CallerOutputHeaders.read(relative, Arrays.copyOf(bytes, bytes.length - 1)));
        reject(() -> CallerOutputHeaders.read(relative, Arrays.copyOf(bytes, bytes.length + 1)));
        byte[] magic = bytes.clone(); magic[0] = 0;
        reject(() -> CallerOutputHeaders.read(relative, magic));
        byte[] version = bytes.clone(); version[6] = 0; version[7] = 66;
        reject(() -> CallerOutputHeaders.read(relative, version));
        byte[] tag = bytes.clone(); tag[10] = 2;
        reject(() -> CallerOutputHeaders.read(relative, tag));
        byte[] count = bytes.clone(); count[8] = 0; count[9] = 0;
        reject(() -> CallerOutputHeaders.read(relative, count));
        reject(() -> CallerOutputHeaders.read(relative, new byte[CallerOutputHeaders.MAX_CLASS_BYTES + 1]));
        Path directory = Files.createDirectory(Path.of(arguments[2]));
        CallerOutputHeaders.requireFresh(directory);
        Files.writeString(directory.resolve("leftover.txt"), "stale");
        reject(() -> CallerOutputHeaders.requireFresh(directory));
        if (rejected != 11) throw new AssertionError("missing header negative");
        System.out.println("11 bounded header/fresh-output negatives passed");
    }
}
