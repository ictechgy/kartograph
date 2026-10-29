import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.util.Set;

/** 소유한 임시 디렉터리만 읽어 hosted Linux JDK의 descriptor 기능을 실제 확인한다. */
class VerifySecureDirectoryStream {
    public static void main(String[] args) throws Exception {
        if (!System.getProperty("os.name").equals("Linux")) {
            throw new IllegalStateException("this capability experiment supports hosted Linux only");
        }
        int version = Runtime.version().feature();
        if (version != 17 && version != 21) {
            throw new IllegalStateException("this capability experiment requires JDK 17 or 21");
        }
        Path root = Files.createTempDirectory("kartograph-secure-directory-probe-");
        Path child = root.resolve("child");
        Path regular = child.resolve("input.xml");
        Path link = child.resolve("link.xml");
        try {
            Files.createDirectory(child);
            Files.write(regular, new byte[] {1, 2, 3});
            Files.createSymbolicLink(link, Path.of("input.xml"));
            try (DirectoryStream<Path> opened = Files.newDirectoryStream(root)) {
                if (!(opened instanceof SecureDirectoryStream<Path> secure)) {
                    throw new IllegalStateException("secure directory descriptors are unavailable");
                }
                try (SecureDirectoryStream<Path> nested = secure.newDirectoryStream(Path.of("child"), LinkOption.NOFOLLOW_LINKS);
                     SeekableByteChannel input = nested.newByteChannel(Path.of("input.xml"),
                         Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                    ByteBuffer bytes = ByteBuffer.allocate(4);
                    while (input.read(bytes) > 0) { }
                    if (bytes.position() != 3 || bytes.get(0) != 1 || bytes.get(1) != 2 || bytes.get(2) != 3) {
                        throw new IllegalStateException("descriptor-relative bytes differ");
                    }
                    boolean refused = false;
                    try (SeekableByteChannel ignored = nested.newByteChannel(Path.of("link.xml"),
                            Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                        // 링크를 열 수 있었다면 아래 검증이 실패한다.
                    } catch (IOException expected) {
                        refused = true;
                    }
                    if (!refused) throw new IllegalStateException("descriptor no-follow did not reject the owned symlink");
                }
            }
            System.out.println("{\"format\":\"kartograph-secure-directory-capability\",\"os\":\"Linux\",\"javaFeature\":"
                + version + ",\"secureDirectoryStream\":true,\"descriptorRelativeRead\":true,\"symlinkRefused\":true}");
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(regular);
            Files.deleteIfExists(child);
            Files.deleteIfExists(root);
        }
    }
}
