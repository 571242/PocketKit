import cn.xianhua.pocketkit.browser.SessionArchive;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.nio.file.*;
import java.util.Arrays;

public final class ArchiveBoundaryTest {
    interface Check { void run() throws Exception; }
    static int count;
    static void reject(Check check) throws Exception {
        count++;
        try { check.run(); } catch (Exception expected) { return; }
        throw new AssertionError("Accepted incomplete archive");
    }
    public static void main(String[] args) throws Exception {
        KeyGenerator gen = KeyGenerator.getInstance("AES"); gen.init(256);
        SecretKey key = gen.generateKey(), wrong = gen.generateKey();
        byte[] full = SessionArchive.seal(new byte[1024 * 1024 * 2], key);
        int boundary = 18 + 4 + 1024 * 1024 + 16;
        reject(() -> SessionArchive.open(Arrays.copyOf(full, boundary), key));
        reject(() -> SessionArchive.open(Arrays.copyOf(full, full.length - 20), key));
        reject(() -> SessionArchive.open(Arrays.copyOf(full, 18), key));
        byte[] empty = SessionArchive.seal(new byte[0], key);
        reject(() -> SessionArchive.open(empty, wrong));
        reject(() -> SessionArchive.open(empty, null));
        byte[] appended = Arrays.copyOf(full, full.length + 1);
        reject(() -> SessionArchive.open(appended, key));
        Path root = Paths.get(".scratch/browser-boundary-test").toAbsolutePath().normalize();
        SessionArchive.deleteTree(root); Files.createDirectories(root);
        try {
            Path source = root.resolve("source"); Files.createDirectories(source.resolve("Default/site/Cache"));
            Files.write(source.resolve("Default/site/Cache/important"), new byte[]{1, 2, 3});
            Path archive = root.resolve("saved.enc"); SessionArchive.pack(source, archive, key);
            Path restored = root.resolve("restored"); SessionArchive.unpack(archive, restored, key);
            if (!Files.exists(restored.resolve("Default/site/Cache/important"))) throw new AssertionError("Site data filtered"); count++;
            byte[] saved = Files.readAllBytes(archive);
            Path cut = root.resolve("cut.enc"); Files.write(cut, Arrays.copyOf(saved, saved.length - 20));
            Path output = root.resolve("bad-output");
            reject(() -> SessionArchive.unpack(cut, output, key));
            if (Files.exists(output)) throw new AssertionError("Failed restore left output"); count++;
        } finally { SessionArchive.deleteTree(root); }
        System.out.println("PASS " + count + " authenticated boundary and retained data checks");
    }
}
