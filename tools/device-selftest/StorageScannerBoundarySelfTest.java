package cn.xianhua.pocketkit.device;

import java.io.File;
import java.nio.file.Files;

/** Boundary checks for direct scanner deletion guards; Android traversal needs a device. */
public final class StorageScannerBoundarySelfTest {
  public static void main(String[] args) throws Exception {
    File root = Files.createTempDirectory("scanner-boundary").toFile();
    File nonEmpty = new File(root, "nonempty"); nonEmpty.mkdir(); Files.write(new File(nonEmpty, "new.txt").toPath(), new byte[]{1});
    StorageScanner.Entry staleEmpty = new StorageScanner.Entry(nonEmpty, nonEmpty.getParentFile().getCanonicalPath(), "nonempty", true, -1, 0);
    check(StorageScanner.delete(staleEmpty, "078") != null, "scan-after-new-file refuses non-empty directory");
    StorageScanner.Entry escape = new StorageScanner.Entry(new File("/tmp/escape.txt"), "/tmp", "../escape", false, 0, -1);
    check(StorageScanner.delete(escape, "079") != null, "path escape/outside shared storage refuses deletion");
    File empty = new File(root, "empty"); empty.mkdir();
    StorageScanner.Entry emptyEntry = new StorageScanner.Entry(empty, empty.getParentFile().getCanonicalPath(), "empty", true, -1, 0);
    check(StorageScanner.delete(emptyEntry, "078") != null, "non-shared test root refuses deletion");
    StorageScanner.Result cancelled = StorageScanner.scan(null, null, () -> true);
    check(cancelled.cancelled && cancelled.entries.isEmpty(), "scan cancellation before filesystem access");
    delete(root);
    System.out.println("storage-scanner boundary selftest: PASS");
  }
  private static void delete(File f) { File[] c=f.listFiles(); if(c!=null) for(File x:c) { if(x.isDirectory()) delete(x); else x.delete(); } f.delete(); }
  private static void check(boolean ok, String name) { if(!ok) throw new AssertionError(name); }
}