package cn.xianhua.pocketkit.archive;

import java.io.IOException;
import java.io.OutputStream;

/**
 * 解压落点。真实实现在 SAF（{@link SafDirectory}），测试实现是本地临时目录。
 *
 * 只认相对路径，绝对路径由实现自己拼：调用方永远拿不到「写到哪」的决定权，
 * 路径安全在 {@link EntryNames} 就已经做完。
 */
public interface OutputDirectory {

    /** 建目录（含中间层），已存在不报错。 */
    void mkdir(String path) throws IOException;

    /** 新建文件；同名已存在时由调用方先用 {@link EntryNames#unique} 避开。 */
    OutputStream create(String path) throws IOException;

    /** 撤销本次新建的文件或目录；用于取消与失败清理。不存在时静默。 */
    void delete(String path) throws IOException;

    /** 给用户看的落点描述。 */
    String describe();
}
