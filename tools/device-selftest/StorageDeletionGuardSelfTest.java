package cn.xianhua.pocketkit.device;
import java.io.File;
import java.nio.file.Files;
public final class StorageDeletionGuardSelfTest {
    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory(new File(".scratch").toPath(), "delete-guard-").toFile();
        try {
            String boundary = root.getCanonicalPath();
            File dir = new File(root, "empty"); dir.mkdir();
            StorageScanner.Entry folder = new StorageScanner.Entry(dir, boundary, "empty", true, -1, 0);
            check(StorageScanner.deletionError(folder, "078") == null);
            File child = new File(dir, "new-file"); Files.write(child.toPath(), new byte[]{1});
            check(StorageScanner.deletionError(folder, "078") != null); child.delete();
            File file = new File(root, "zero"); file.createNewFile();
            StorageScanner.Entry zero = new StorageScanner.Entry(file, boundary, "zero", false, 0, -1);
            check(StorageScanner.deletionError(zero, "079") == null);
            Files.write(file.toPath(), new byte[]{1}); check(StorageScanner.deletionError(zero, "079") != null);
            file.delete(); file.mkdir(); check(StorageScanner.deletionError(zero, "079") != null);
            StorageScanner.Entry rootEntry = new StorageScanner.Entry(root, boundary, "root", true, -1, 0);
            check(StorageScanner.deletionError(rootEntry, "078") != null);
            StorageScanner.Entry outside = new StorageScanner.Entry(dir, new File(root, "other").getCanonicalPath(), "escape", true, -1, 0);
            check(StorageScanner.deletionError(outside, "078") != null);
            System.out.println("deletion-guard selftest: PASS (empty, new content, changed size/type, root, escape)");
        } finally { File[] children=root.listFiles(); if(children!=null)for(File f:children) f.delete(); root.delete(); }
    }
    static void check(boolean value) { if (!value) throw new AssertionError(); }
}
