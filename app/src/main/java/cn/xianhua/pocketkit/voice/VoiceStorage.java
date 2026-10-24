package cn.xianhua.pocketkit.voice;

import java.io.File;

/** Removes the built-in AI voice models older installs unpacked into app storage. */
public final class VoiceStorage {
    private VoiceStorage() { }

    /** Deletes only the app-private OpenVoice model snapshot; recordings and exports are never touched. */
    public static long purgeLegacyModels(File filesDir) {
        return purge(new File(filesDir, "voice-ai"));
    }

    static long purge(File target) {
        if (target == null || java.nio.file.Files.isSymbolicLink(target.toPath()) || !target.isDirectory()) return 0;
        File[] children = target.listFiles();
        if (children == null) return 0;
        long freed = 0;
        for (File child : children) {
            if (java.nio.file.Files.isSymbolicLink(child.toPath())) continue;
            if (child.isDirectory()) freed += purge(child);
            else {
                long size = child.length();
                if (child.delete()) freed += size;
            }
        }
        target.delete();
        return freed;
    }
}
