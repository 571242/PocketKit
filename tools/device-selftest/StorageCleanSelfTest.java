import cn.xianhua.pocketkit.device.FileRules;
import java.io.File;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** JVM boundary checks for storage-clean rules; no Android device required. */
public final class StorageCleanSelfTest {
  public static void main(String[] args) throws Exception {
    check(FileRules.isEmptyDir(0), "empty directory");
    check(!FileRules.isEmptyDir(-1), "unreadable directory is not empty");
    check(!FileRules.isEmptyDir(2), "non-empty directory");
    check(FileRules.isEmptyFile(0), "zero-byte file");
    check(!FileRules.isEmptyFile(-1), "unknown file size");
    check(FileRules.isBigFile(FileRules.BIG_FILE_BYTES, FileRules.BIG_FILE_BYTES), "threshold inclusive");
    File dir = Files.createTempDirectory("apk-rule").toFile();
    File valid = new File(dir, "valid.apk");
    try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(valid.toPath()))) { out.putNextEntry(new ZipEntry("AndroidManifest.xml")); out.write(1); out.closeEntry(); }
    File fake = new File(dir, "fake.apk"); Files.write(fake.toPath(), new byte[]{1,2,3});
    check(FileRules.isApk(valid), "APK zip manifest");
    check(!FileRules.isApk(fake), "fake apk rejected");
    valid.delete(); fake.delete(); dir.delete();
    System.out.println("storage-clean selftest: PASS");
  }
  private static void check(boolean ok, String name) { if (!ok) throw new AssertionError(name); }
}
