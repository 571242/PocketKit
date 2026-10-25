package cn.xianhua.pocketkit.archive;

import java.io.IOException;
import java.io.InputStream;

/** 要打进压缩包的一份来源。Activity 用 SAF 实现，测试用本地文件实现。 */
public interface ArchiveSource {

    /** 用户看得见的名字，包内名字由 {@link EntryNames#forZipEntry} 从这里派生。 */
    String name();

    /** 预计字节数，只用于进度；未知传 -1。 */
    long size();

    InputStream open() throws IOException;
}
