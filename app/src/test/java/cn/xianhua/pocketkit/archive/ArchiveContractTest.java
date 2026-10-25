package cn.xianhua.pocketkit.archive;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * ArchiveActivity 的接线契约（源码级，不需要设备）：
 *  - ToolRegistry 通过反射找的 supports/limitation 必须存在且认 F03；
 *  - 收到的 Intent 必须同时处理 getData() 和 ClipData（单文件解压、多文件打包）；
 *  - 必须用 SAF 选输出目录并持久化授权，不能自己拼本地路径；
 *  - 结果必须带落点与部分失败的说明，不能只报「成功」。
 *
 * 这里只证明「接线写对了」，不证明真机上 SAF / Intent 真的按预期工作。
 */
public final class ArchiveContractTest {

    private static int checks;
    private static final java.util.List<String> failures = new java.util.ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path root = args.length == 0 ? Path.of(".") : Path.of(args[0]);
        String activity = read(root, "app/src/main/java/cn/xianhua/pocketkit/archive/ArchiveActivity.java");
        require(read(root, "app/src/main/java/cn/xianhua/pocketkit/ArchiveActivity.java").contains("extends cn.xianhua.pocketkit.archive.ArchiveActivity"), "根包入口桥接归档实现");
        String formats = read(root, "app/src/main/java/cn/xianhua/pocketkit/archive/ArchiveFormats.java");
        String extractor = read(root, "app/src/main/java/cn/xianhua/pocketkit/archive/ZipExtractor.java");
        String lib = read(root, "app/src/main/java/cn/xianhua/pocketkit/archive/LibarchiveExtractor.java");
        String saf = read(root, "app/src/main/java/cn/xianhua/pocketkit/archive/SafDirectory.java");

        // ToolRegistry 契约
        require(activity.contains("public static boolean supports(String id)")
                && activity.contains("\"F03\".equals(id)"), "supports(id) 认 F03");
        require(activity.contains("public static String limitation(String id)"), "limitation(id) 存在");
        require(activity.contains("ArchiveFormats.limitation()"), "能力声明只有一份来源");
        require(activity.contains("extends BaseToolActivity"), "沿用 BaseToolActivity 的原生风格");

        // 输入 Intent：data 与 ClipData 都要认
        require(activity.contains("intent.getData()"), "处理 getData() 传入");
        require(activity.contains("getClipData()"), "处理 ClipData 传入");
        require(activity.contains("Intent.EXTRA_STREAM"), "处理 ACTION_SEND 的 EXTRA_STREAM");
        require(activity.contains("incoming.size() == 1"), "单文件按「解压」处理");
        require(activity.contains("sources.addAll(incoming)"), "多文件按「打包」处理");
        require(activity.contains("onNewIntent"), "已打开的页面再收到文件也处理");

        // SAF 输出目录
        require(activity.contains("Intent.ACTION_OPEN_DOCUMENT_TREE"), "用系统目录选择器选输出目录");
        require(activity.contains("takePersistableUriPermission"), "目录授权持久化");
        require(!activity.contains("getExternalStorageDirectory"), "不自己拼全盘路径");
        require(!activity.contains("MANAGE_EXTERNAL_STORAGE"), "不要求全盘访问");
        require(saf.contains("DocumentsContract.createDocument"), "落盘走 DocumentsContract");

        // 不覆盖原件 / 只删本次新建
        require(activity.contains("deleteCreated()"), "取消与失败都走「只删本次新建」");
        require(saf.contains("deleteCreated"), "清理实现存在");
        require(activity.contains("EntryNames.rootName"), "解压落在独立子目录里");
        require(extractor.contains("output.delete(path)"), "单条目失败时删掉这条半成品");
        require(lib.contains("output.delete(path)"), "libarchive 路径同样删半成品");
        require(!extractor.contains("File.deleteOnExit"), "不假设本地文件系统");

        // 边界与取消
        require(extractor.contains("limits.checkEntryCount"), "ZIP 路径检查条目数");
        require(lib.contains("limits.checkEntryCount"), "libarchive 路径检查条目数");
        require(extractor.contains("new Budget(limits, archiveBytes)"), "ZIP 路径按包大小判压缩比");
        require(lib.contains("new Budget(limits, archiveBytes)"), "libarchive 路径同样判压缩比");
        require(activity.contains("isCancelled()"), "取消通道接上了");
        require(activity.contains("cancelButton.setEnabled(true)"), "期间可以取消");

        // 进度与结果
        require(activity.contains("onProgress("), "有进度回调");
        require(activity.contains("result.summary()"), "结果用摘要展示落点与失败项");
        require(formats.contains("不能读：" ) || formats.contains("不能读"), "声明里写了不能读什么");
        require(formats.contains("不能创建 7z/RAR"), "声明里写了不能建什么");

        // 流式：不能把整包读进内存
        require(!extractor.contains("toByteArray()"), "ZIP 解压不整包缓冲");
        require(!lib.contains("toByteArray()"), "libarchive 解压不整包缓冲");
        require(activity.contains("Pump.CHUNK") || activity.contains("LibarchiveReader.stage"),
                "按块读取或流式缓存");

        // readDataIntoFd 明确不用（注释里提到名字没关系，代码里不能有这次调用）
        String libReader = read(root,
                "app/src/main/java/cn/xianhua/pocketkit/archive/LibarchiveReader.java");
        require(!libReader.contains("Archive.readDataIntoFd"),
                "不用无总量控制的 readDataIntoFd");
        require(libReader.contains("Archive.readData(handle, buffer)"), "按块读并自己计数");
        require(libReader.contains("buffer.clear()"), "复用 direct buffer 前先 clear，符合 position/limit 契约");

        if (!failures.isEmpty()) {
            System.out.println("FAILED " + failures.size() + " / " + checks);
            for (String failure : failures) System.out.println("  - " + failure);
            System.exit(1);
        }
        System.out.println("ArchiveContractTest OK: " + checks + " checks");
    }

    private static String read(Path root, String relative) throws Exception {
        return Files.readString(root.resolve(relative), StandardCharsets.UTF_8);
    }

    private static void require(boolean ok, String label) {
        checks++;
        if (!ok) failures.add(label);
    }
}
