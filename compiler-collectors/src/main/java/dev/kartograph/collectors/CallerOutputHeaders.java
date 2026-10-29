package dev.kartograph.collectors;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;

/** 실제 compiler 출력의 모든 method를 보존한다. bytecode에서 source origin을 추론하지 않는다. */
final class CallerOutputHeaders {
    static final int MAX_CLASS_BYTES = 16 * 1024 * 1024;
    static final long MAX_OUTPUT_BYTES = 256L * 1024 * 1024;
    record Method(String identity, int access, boolean code) {}
    record Output(String path, String sha256, String owner, List<Method> methods) {}
    private CallerOutputHeaders() {}

    static Path requireFresh(Path output) {
        try {
            Path root = output.toRealPath();
            if (!Files.isDirectory(root)) throw invalid();
            try (var files = Files.walk(root)) {
                int[] visited = {0};
                if (files.anyMatch(it -> ++visited[0] > EvidenceProtocol.MAX_ROWS || !Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS))) {
                    throw new IllegalArgumentException("caller inventory requires an initially empty task output directory");
                }
            }
            return root;
        } catch (IOException error) { throw new IllegalArgumentException("caller inventory output is unavailable", error); }
    }

    static List<Output> readDirectory(Path output) {
        try {
            Path root = output.toRealPath();
            List<Output> result = new ArrayList<>();
            long bytes = 0;
            int count = 0;
            int methods = 0;
            try (var paths = Files.walk(root)) {
                var iterator = paths.iterator();
                while (iterator.hasNext()) {
                    Path path = iterator.next();
                    if (++count > EvidenceProtocol.MAX_ROWS) throw invalid();
                    if (Files.isSymbolicLink(path)) throw invalid();
                    if (Files.isDirectory(path)) continue;
                    if (!Files.isRegularFile(path)) throw invalid();
                    long size = Files.size(path);
                    if (size > MAX_CLASS_BYTES || (bytes += size) > MAX_OUTPUT_BYTES) throw invalid();
                    String relative = root.relativize(path).toString().replace(java.io.File.separatorChar, '/');
                    byte[] value;
                    try (var input = Files.newInputStream(path)) { value = input.readNBytes(MAX_CLASS_BYTES + 1); }
                    if (value.length != size || value.length > MAX_CLASS_BYTES) throw invalid();
                    Output parsed = read(relative, value);
                    methods += parsed.methods().size();
                    if (methods > EvidenceProtocol.MAX_ROWS) throw invalid();
                    result.add(parsed);
                }
            }
            result.sort(java.util.Comparator.comparing(Output::path));
            return List.copyOf(result);
        } catch (IOException error) { throw new IllegalArgumentException("caller inventory output could not be read", error); }
    }

    static Output read(String path, byte[] bytes) {
        if (bytes.length > MAX_CLASS_BYTES || path.isEmpty() || path.startsWith("/") || path.contains("\\") ||
            path.split("/", -1).length > 256 || java.util.Arrays.stream(path.split("/", -1)).anyMatch(it -> it.isEmpty() || it.equals("..") || it.equals("."))) throw invalid();
        String hash;
        try { hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
        if (!path.endsWith(".class")) return new Output(path, hash, "", List.of());
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != 0xcafebabe) throw invalid();
            in.readUnsignedShort();
            int version = in.readUnsignedShort();
            if (version < 45 || version > 65) throw invalid();
            int poolSize = in.readUnsignedShort();
            if (poolSize == 0) throw invalid();
            Object[] pool = new Object[poolSize];
            for (int i = 1; i < poolSize; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> pool[i] = in.readUTF();
                    case 3, 4 -> in.readInt();
                    case 5, 6 -> { in.readLong(); if (++i >= poolSize) throw invalid(); }
                    case 7 -> pool[i] = Integer.valueOf(in.readUnsignedShort());
                    case 8, 16, 19, 20 -> in.readUnsignedShort();
                    case 9, 10, 11, 12, 17, 18 -> in.readInt();
                    case 15 -> { in.readUnsignedByte(); in.readUnsignedShort(); }
                    default -> throw invalid();
                }
            }
            in.readUnsignedShort();
            int classIndex = in.readUnsignedShort();
            if (classIndex <= 0 || classIndex >= pool.length || !(pool[classIndex] instanceof Integer)) throw invalid();
            String owner = utf(pool, (Integer) pool[classIndex]);
            if (!path.equals(owner + ".class") || owner.isEmpty()) throw invalid();
            in.readUnsignedShort();
            int interfaces = in.readUnsignedShort();
            in.skipNBytes(interfaces * 2L);
            int fields = in.readUnsignedShort();
            for (int i = 0; i < fields; i++) {
                in.readUnsignedShort(); utf(pool, in.readUnsignedShort()); utf(pool, in.readUnsignedShort());
                attributes(in, pool);
            }
            int methodCount = in.readUnsignedShort();
            List<Method> methods = new ArrayList<>(methodCount);
            var seen = new TreeSet<String>();
            for (int i = 0; i < methodCount; i++) {
                int access = in.readUnsignedShort();
                String name = utf(pool, in.readUnsignedShort());
                String descriptor = utf(pool, in.readUnsignedShort());
                String identity = "method:" + owner + "#" + name + descriptor;
                if (identity.length() > 4096 || !seen.add(identity)) throw invalid();
                boolean code = attributes(in, pool);
                if (code == ((access & (0x0400 | 0x0100)) != 0)) throw invalid();
                methods.add(new Method(identity, access, code));
            }
            attributes(in, pool);
            if (in.available() != 0) throw invalid();
            return new Output(path, hash, owner, List.copyOf(methods));
        } catch (IOException error) { throw new IllegalArgumentException("invalid caller inventory class header", error); }
    }

    private static boolean attributes(DataInputStream in, Object[] pool) throws IOException {
        int count = in.readUnsignedShort();
        boolean code = false;
        for (int i = 0; i < count; i++) {
            String name = utf(pool, in.readUnsignedShort());
            long length = Integer.toUnsignedLong(in.readInt());
            if (length > in.available()) throw invalid();
            if (name.equals("Code")) {
                if (code) throw invalid();
                code = true;
            }
            in.skipNBytes(length);
        }
        return code;
    }

    private static String utf(Object[] pool, int index) {
        if (index <= 0 || index >= pool.length || !(pool[index] instanceof String value) || value.isEmpty()) throw invalid();
        return (String) pool[index];
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("invalid or oversized caller inventory output"); }
}
