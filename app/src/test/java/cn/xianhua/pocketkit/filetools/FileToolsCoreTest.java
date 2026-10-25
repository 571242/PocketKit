package cn.xianhua.pocketkit.filetools;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 重复查找与批量改名的宿主测试：真断言 + 真临时文件，不依赖 Android。
 *
 * <p>覆盖的是那些"说错了就会误删或误改名"的判定：分组、失败项、每组留一份、删前核验、
 * 非法名、重名拦截、逐项失败。UI 与 SAF 提供器的行为不在本测试范围内，那需要真机。
 */
public final class FileToolsCoreTest {

    private static int checks;

    public static void main(String[] args) throws Exception {
        // 没有 -ea 时 assert 全部变成空操作，测试会假装通过。宁可在这里炸掉。
        if (!FileToolsCoreTest.class.desiredAssertionStatus()) {
            throw new IllegalStateException("必须以 -ea 运行本测试，否则断言不会生效");
        }
        Path work = Path.of(args.length > 0 ? args[0] : ".scratch/file-tools-cleanup/work");
        Files.createDirectories(work);

        sizeBuckets();
        scanGroupsRealFiles(work);
        scanFailureIsReported();
        scanCancelKeepsPartial();
        deletionSelection();
        verifyBeforeDelete();
        streamDigest(work);
        extensionSplitting();
        namingRules();
        illegalNames();
        planBlocks();
        siblingsUnavailableIsWarned();
        runIsPerItem();
        runStopsOnCancel();
        renameOnDisk(work);
        describeCounts();

        DuplicateScan.Item guarded = new DuplicateScan.Item("guarded", "guarded", 4, 1);
        guarded.sha256 = "original";
        assert DuplicateScan.contentStillDuplicate(guarded, "original", "original");
        assert !DuplicateScan.contentStillDuplicate(guarded, "changed", "original");
        assert !DuplicateScan.contentStillDuplicate(guarded, "original", "changed");
        assert !DuplicateScan.contentStillDuplicate(guarded, "original", null);
        checks += 4;
        assert DuplicateScan.sameStorageObject(1, 42, 1, 42);
        assert !DuplicateScan.sameStorageObject(1, 42, 2, 42);
        assert !DuplicateScan.sameStorageObject(1, 42, 1, 43);
        assert !DuplicateScan.sameStorageObject(1, 0, 1, 0);
        checks += 4;
        System.out.println(checks + " assertions passed");
    }

    // ------------------------------------------------------------ F01

    private static void sizeBuckets() {
        DuplicateScan.Result result = new DuplicateScan.Result();
        List<DuplicateScan.Item> items = Arrays.asList(
                new DuplicateScan.Item("a", "a.txt", 100, 1),
                new DuplicateScan.Item("b", "b.txt", 100, 1),
                new DuplicateScan.Item("c", "c.txt", 200, 1),
                new DuplicateScan.Item("d", "d.txt", 0, 1),
                new DuplicateScan.Item("e", "e.txt", 0, 1),
                new DuplicateScan.Item("f", "f.txt", 300, 1));
        List<List<DuplicateScan.Item>> buckets = DuplicateScan.sizeBuckets(items, result);
        assert buckets.size() == 1 : "只有 100 字节那一桶需要读内容";
        assert buckets.get(0).size() == 2 : "同尺寸两项应进同一桶";
        assert result.emptySkipped == 2 : "0 字节文件不参与比较";
        assert result.uniqueSize == 2 : "200 与 300 两项大小唯一，无需读内容";
        checks += 4;
    }

    private static void scanGroupsRealFiles(Path work) throws Exception {
        Path dir = Files.createDirectories(work.resolve("dupes"));
        Files.write(dir.resolve("one.txt"), "same".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("two.txt"), "same".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("three.txt"), "different".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("four.txt"), "same".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("empty1.bin"), new byte[0]);
        Files.write(dir.resolve("empty2.bin"), new byte[0]);

        List<DuplicateScan.Item> items = itemsOf(dir);
        Map<String, Path> paths = pathsOf(items);
        DuplicateScan.Result result = DuplicateScan.scan(items,
                item -> DuplicateScan.sha256(new FileInputStream(paths.get(item.id).toFile()), null),
                null, null);

        assert result.groups.size() == 1 : "只有 same 的三份是一组，实际 " + result.groups.size();
        assert result.groups.get(0).size() == 3 : "same 写了三次";
        assert result.duplicateFiles() == 3;
        assert result.reclaimable() == 8 : "4 字节 ×2 可省，实际 " + result.reclaimable();
        assert result.emptySkipped == 2 : "空文件必须被排除并计数";
        assert result.unreadable.isEmpty() : "临时文件都能读，不该有失败项";
        assert !result.cancelled;
        checks += 7;
    }

    private static void scanFailureIsReported() {
        DuplicateScan.Item ok1 = new DuplicateScan.Item("ok1", "ok1.bin", 10, 1);
        DuplicateScan.Item ok2 = new DuplicateScan.Item("ok2", "ok2.bin", 10, 1);
        DuplicateScan.Item bad = new DuplicateScan.Item("bad", "bad.bin", 10, 1);
        DuplicateScan.Result result = DuplicateScan.scan(Arrays.asList(ok1, ok2, bad), item -> {
            if ("bad".equals(item.id)) {
                throw new IOException("提供器拒绝读取");
            }
            return "hash-" + item.id.charAt(item.id.length() - 1);
        }, null, null);
        assert result.unreadable.size() == 1 : "读失败必须单独列出，不能悄悄当没重复";
        assert "提供器拒绝读取".equals(result.unreadable.get(0).error);
        assert result.groups.isEmpty() : "ok1/ok2 摘要不同，不成组";
        checks += 3;
    }

    private static void scanCancelKeepsPartial() {
        Cancel cancel = new Cancel();
        List<DuplicateScan.Item> items = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            items.add(new DuplicateScan.Item("id" + i, "f" + i, 10, 1));
        }
        final int[] seen = {0};
        DuplicateScan.Result result = DuplicateScan.scan(items, item -> {
            seen[0]++;
            if (seen[0] == 2) {
                cancel.cancel();
            }
            return "same";
        }, cancel, null);
        assert result.cancelled : "取消后必须如实标记为已取消";
        assert seen[0] <= 3 : "取消应在下一步就停，实际算了 " + seen[0];
        checks += 2;
    }

    private static void deletionSelection() {
        List<DuplicateScan.Item> group = Arrays.asList(
                new DuplicateScan.Item("1", "a", 5, 1),
                new DuplicateScan.Item("2", "b", 5, 1),
                new DuplicateScan.Item("3", "c", 5, 1));
        assert DuplicateScan.selectForDeletion(group, new HashSet<>()).isEmpty() : "默认不勾就一项都不删";

        Set<String> all = new HashSet<>(Arrays.asList("1", "2", "3"));
        List<DuplicateScan.Item> picked = DuplicateScan.selectForDeletion(group, all);
        assert picked.size() == 2 : "全勾也只能删 2 项，必须留一份，实际 " + picked.size();
        assert !picked.contains(group.get(2)) : "被留下的应是最后一份";

        Set<String> two = new HashSet<>(Arrays.asList("1", "2"));
        assert DuplicateScan.selectForDeletion(group, two).size() == 2 : "勾两项就删两项（还剩一项）";

        List<DuplicateScan.Item> pair = Arrays.asList(group.get(0), group.get(1));
        assert DuplicateScan.selectForDeletion(pair, all).size() == 1 : "两项目组也只删一项";
        checks += 5;
    }

    private static void verifyBeforeDelete() {
        DuplicateScan.Item item = new DuplicateScan.Item("x", "x.bin", 100, 5000);
        assert DuplicateScan.verifyUnchanged(item, 100, 5000) == null : "没变化就该放行";
        assert DuplicateScan.verifyUnchanged(item, 101, 5000) != null : "大小变了必须拦下";
        assert DuplicateScan.verifyUnchanged(item, 100, 5001) != null : "修改时间变了必须拦下";
        assert DuplicateScan.verifyUnchanged(item, -1, 0) != null : "两项都拿不到时不能当成没变化";
        assert DuplicateScan.verifyUnchanged(null, 100, 5000) != null;
        // 元数据全拿不到时，调用方必须走重算摘要那条路，而不是直接删。
        assert DuplicateScan.metadataUnknown(item, -1, 0) : "两项都不知 → 需要重算摘要";
        assert DuplicateScan.metadataUnknown(item, 100, 0) == false : "大小已知就够判断";
        assert !DuplicateScan.metadataUnknown(item, 100, 5000);
        checks += 8;
    }

    private static void streamDigest(Path work) throws Exception {
        Path a = work.resolve("digest-a.bin");
        Path b = work.resolve("digest-b.bin");
        Files.write(a, "abc".getBytes(StandardCharsets.UTF_8));
        Files.write(b, "abc".getBytes(StandardCharsets.UTF_8));
        String first;
        String second;
        try (InputStream in = new FileInputStream(a.toFile())) {
            first = DuplicateScan.sha256(in, null);
        }
        try (InputStream in = new FileInputStream(b.toFile())) {
            second = DuplicateScan.sha256(in, null);
        }
        assert first.equals(second) : "内容相同必须同摘要";
        assert "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad".equals(first)
                : "SHA-256(abc) 标准向量，实际 " + first;

        Cancel cancel = new Cancel();
        cancel.cancel();
        boolean stopped = false;
        try (InputStream in = new FileInputStream(a.toFile())) {
            DuplicateScan.sha256(in, cancel);
        } catch (Cancel.Stop expected) {
            stopped = true;
        }
        assert stopped : "已取消时必须中断读取";
        checks += 3;
    }

    // ------------------------------------------------------------ F02

    private static void extensionSplitting() {
        assert Arrays.equals(RenameRules.splitExtension("a.tar.gz"), new String[]{"a.tar", ".gz"});
        assert Arrays.equals(RenameRules.splitExtension("noext"), new String[]{"noext", ""});
        assert Arrays.equals(RenameRules.splitExtension(".hidden"), new String[]{".hidden", ""})
                : "点开头是隐藏文件，不是扩展名";
        assert Arrays.equals(RenameRules.splitExtension("a."), new String[]{"a.", ""});
        assert Arrays.equals(RenameRules.splitExtension("照片.JPG"), new String[]{"照片", ".JPG"});
        checks += 5;
    }

    private static void namingRules() {
        RenameRules.Rule rule = new RenameRules.Rule();
        rule.find = "副本";
        rule.replace = "";
        assert "报告.txt".equals(RenameRules.targetName("报告副本.txt", rule, 0)) : "替换只落在主名上";

        RenameRules.Rule ext = new RenameRules.Rule();
        ext.find = "txt";
        ext.replace = "md";
        assert "note.txt".equals(RenameRules.targetName("note.txt", ext, 0)) : "扩展名默认不被替换";

        RenameRules.Rule all = new RenameRules.Rule();
        all.find = "txt";
        all.replace = "md";
        all.keepExtension = false;
        assert "note.md".equals(RenameRules.targetName("note.txt", all, 0)) : "关掉保留扩展名后整名参与替换";

        RenameRules.Rule decorate = new RenameRules.Rule();
        decorate.prefix = "[a]";
        decorate.suffix = "-v2";
        assert "[a]photo-v2.png".equals(RenameRules.targetName("photo.png", decorate, 0));

        RenameRules.Rule numbered = new RenameRules.Rule();
        numbered.numbered = true;
        numbered.start = 7;
        numbered.digits = 3;
        assert "photo007.png".equals(RenameRules.targetName("photo.png", numbered, 0));
        assert "photo008.png".equals(RenameRules.targetName("photo.png", numbered, 1));

        RenameRules.Rule front = new RenameRules.Rule();
        front.numbered = true;
        front.start = 1;
        front.numberFirst = true;
        assert "1photo.png".equals(RenameRules.targetName("photo.png", front, 0)) : "序号可放名字前";
        checks += 7;
    }

    private static void illegalNames() {
        assert RenameRules.illegalReason("") != null;
        assert RenameRules.illegalReason(".") != null;
        assert RenameRules.illegalReason("..") != null;
        assert RenameRules.illegalReason("a/b.txt") != null;
        assert RenameRules.illegalReason("a\\b.txt") != null;
        assert RenameRules.illegalReason("a\u0000b") != null;
        assert RenameRules.illegalReason("a\u0007b") != null : "控制字符要拦";
        assert RenameRules.illegalReason("   ") != null : "只有空格不算名字";
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 256; i++) {
            longName.append('a');
        }
        assert RenameRules.illegalReason(longName.toString()) != null : "超过 255 字节要拦";
        StringBuilder chineseOk = new StringBuilder();
        for (int i = 0; i < 80; i++) {
            chineseOk.append('中');
        }
        assert RenameRules.illegalReason(chineseOk.toString()) == null : "80 个汉字 = 240 字节，仍然合法";
        StringBuilder chineseBad = new StringBuilder();
        for (int i = 0; i < 90; i++) {
            chineseBad.append('中');
        }
        assert RenameRules.illegalReason(chineseBad.toString()) != null : "90 个汉字 = 270 字节，按字节算要拦";
        assert RenameRules.illegalReason("正常 名字（1）.txt") == null : "合法名字不该被拦";
        checks += 12;
    }

    private static void planBlocks() {
        RenameRules.Rule rule = new RenameRules.Rule();
        rule.find = "a";
        rule.replace = "b";
        List<RenameRules.Entry> entries = Arrays.asList(
                new RenameRules.Entry("1", "a1.txt", "dir"),
                new RenameRules.Entry("2", "a2.txt", "dir"));
        Map<String, Set<String>> siblings = new LinkedHashMap<>();
        siblings.put("dir", new HashSet<>(Arrays.asList("a1.txt", "a2.txt")));
        RenameRules.Plan ok = RenameRules.plan(entries, rule,
                directory -> siblings.getOrDefault(directory, new HashSet<>()));
        assert ok.executable() : "b1/b2 不与旧名冲突，应可执行：" + ok.blockers;
        assert ok.changeCount() == 2;

        RenameRules.Rule flatten = new RenameRules.Rule();
        flatten.find = "a1";
        flatten.replace = "x";
        RenameRules.Rule flatten2 = new RenameRules.Rule();
        flatten2.find = "a2";
        flatten2.replace = "x";
        List<RenameRules.Entry> same = new ArrayList<>();
        same.add(new RenameRules.Entry("1", "x.txt", "dir"));
        same.add(new RenameRules.Entry("2", "x.txt", "dir"));
        RenameRules.Plan clash = RenameRules.plan(same, new RenameRules.Rule(), directory -> new HashSet<>());
        assert !clash.executable() : "批次内改成同一个名字必须整批拦下";
        assert clash.blockers.size() == 1 : "一组冲突只报一条，实际 " + clash.blockers.size();
        assert clash.entries.get(0).blocked != null && clash.entries.get(1).blocked != null;

        List<RenameRules.Entry> illegal = new ArrayList<>();
        illegal.add(new RenameRules.Entry("1", "ok.txt", "dir"));
        RenameRules.Rule bad = new RenameRules.Rule();
        bad.prefix = "a/b";
        RenameRules.Plan blocked = RenameRules.plan(illegal, bad, directory -> new HashSet<>());
        assert !blocked.executable() : "名字非法必须阻止执行，不跳过";
        assert "ok.txt".equals(blocked.entries.get(0).name) : "被拦下时原名不变";
        checks += 8;
    }

    private static void siblingsUnavailableIsWarned() {
        List<RenameRules.Entry> entries = new ArrayList<>();
        entries.add(new RenameRules.Entry("1", "a.txt", "dir"));
        RenameRules.Rule rule = new RenameRules.Rule();
        rule.prefix = "n_";
        RenameRules.Plan plan = RenameRules.plan(entries, rule, directory -> {
            throw new IOException("提供器不支持列目录");
        });
        assert !plan.executable() : "无法核验同目录重名时必须阻止执行";
        assert plan.blockers.size() == 1 : "要求授权真实父目录后再改名";
        assert plan.blockers.get(0).contains("提供器不支持列目录");
        checks += 3;
    }

    private static void runIsPerItem() {
        RenameRules.Rule rule = new RenameRules.Rule();
        rule.find = "a";
        rule.replace = "A";
        List<RenameRules.Entry> entries = Arrays.asList(
                new RenameRules.Entry("ok", "abc.txt", "dir"),
                new RenameRules.Entry("bad", "cat.txt", "dir"),
                new RenameRules.Entry("same", "dog.txt", "dir"));
        RenameRules.Plan plan = RenameRules.plan(entries, rule, directory -> new HashSet<>());
        assert plan.changeCount() == 2 : "只有 abc 与 cat 会变，dog 不含 a";
        RenameRules.run(plan, entry -> {
            if ("bad".equals(entry.id)) {
                throw new IOException("提供器不支持重命名");
            }
            return "uri/" + entry.newName;
        }, null, null);
        assert "uri/Abc.txt".equals(plan.entries.get(0).newId) : "成功的项要记下提供器返回的新位置";
        assert plan.entries.get(0).failure == null;
        assert "提供器不支持重命名".equals(plan.entries.get(1).failure) : "失败按项记录，不冒充成功";
        assert plan.entries.get(1).newId == null;
        assert plan.entries.get(2).newId == null : "名字没变的项不该被重命名";
        assert plan.entries.get(2).failure == null;
        checks += 5;
    }

    private static void runStopsOnCancel() {
        RenameRules.Rule rule = new RenameRules.Rule();
        rule.prefix = "n_";
        List<RenameRules.Entry> entries = Arrays.asList(
                new RenameRules.Entry("1", "a.txt", "dir"),
                new RenameRules.Entry("2", "b.txt", "dir"));
        RenameRules.Plan plan = RenameRules.plan(entries, rule, directory -> new HashSet<>());
        Cancel cancel = new Cancel();
        cancel.cancel();
        RenameRules.run(plan, entry -> "uri/" + entry.newName, cancel, null);
        assert plan.entries.get(0).newId == null : "取消后一项都不该执行";
        assert plan.entries.get(1).newId == null;
        assert plan.entries.get(0).failure != null && plan.entries.get(0).failure.contains("已取消")
                : "取消要按项说明，不能静默";
        checks += 3;
    }

    /** 真的在临时目录里改名，验证规则跑在磁盘上也不会打架。 */
    private static void renameOnDisk(Path work) throws Exception {
        Path dir = Files.createDirectories(work.resolve("renames"));
        for (String name : new String[]{"照片 1.jpg", "照片 2.jpg", "照片 3.jpg"}) {
            Files.write(dir.resolve(name), name.getBytes(StandardCharsets.UTF_8));
        }
        RenameRules.Rule rule = new RenameRules.Rule();
        rule.find = "照片 ";
        rule.replace = "IMG_";
        rule.prefix = "2026-";
        List<RenameRules.Entry> entries = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                entries.add(new RenameRules.Entry(path.getFileName().toString(), path.getFileName().toString(), "dir"));
            }
        }
        entries.sort((a, b) -> a.name.compareTo(b.name));
        assert entries.size() == 3;
        RenameRules.Plan plan = RenameRules.plan(entries, rule, directory -> {
            Set<String> names = new HashSet<>();
            try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
                for (Path path : (Iterable<Path>) stream::iterator) {
                    names.add(path.getFileName().toString());
                }
            }
            return names;
        });
        assert plan.executable() : "目标名不与现有名字冲突，应可执行：" + plan.blockers;
        RenameRules.run(plan, entry -> {
            Path from = dir.resolve(entry.name);
            Path to = dir.resolve(entry.newName);
            Files.move(from, to);
            return to.toString();
        }, null, null);
        assert Files.exists(dir.resolve("2026-IMG_1.jpg")) : "实际改名结果要落在磁盘上";
        assert Files.exists(dir.resolve("2026-IMG_3.jpg"));
        assert !Files.exists(dir.resolve("照片 1.jpg")) : "旧名必须真的没了";
        assert plan.entries.get(0).newId != null;
        checks += 4;
    }

    private static void describeCounts() {
        List<RenameRules.Entry> entries = new ArrayList<>();
        RenameRules.Entry ok = new RenameRules.Entry("1", "a.txt", "d");
        ok.newId = "uri";
        RenameRules.Entry failed = new RenameRules.Entry("2", "b.txt", "d");
        failed.failure = "拒绝";
        RenameRules.Entry untouched = new RenameRules.Entry("3", "c.txt", "d");
        entries.add(ok);
        entries.add(failed);
        entries.add(untouched);
        RenameRules.Plan plan = new RenameRules.Plan();
        plan.entries.addAll(entries);
        String text = RenameRules.describe(plan);
        assert text.contains("成功 1 项") : text;
        assert text.contains("失败 1 项") : text;
        assert text.contains("未改动 1 项") : text;
        checks += 3;
    }

    // ------------------------------------------------------------ helpers

    private static List<DuplicateScan.Item> itemsOf(Path dir) throws IOException {
        List<DuplicateScan.Item> items = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                items.add(new DuplicateScan.Item(path.toAbsolutePath().toString(),
                        path.getFileName().toString(), Files.size(path), Files.getLastModifiedTime(path).toMillis()));
            }
        }
        items.sort((a, b) -> a.label.compareTo(b.label));
        return items;
    }

    private static Map<String, Path> pathsOf(List<DuplicateScan.Item> items) {
        Map<String, Path> paths = new LinkedHashMap<>();
        for (DuplicateScan.Item item : items) {
            paths.put(item.id, Path.of(item.id));
        }
        return paths;
    }
}
