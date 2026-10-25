package cn.xianhua.pocketkit.filetools;

/**
 * 精确重复检测的纯逻辑：先按大小分桶，同尺寸才读内容算 SHA-256。
 *
 * <p>刻意不碰 Android：分组规则、失败项、删除选择集都由宿主测试直接验证。真正读文件的部分
 * 通过 {@link Hasher} 注入，任何一项读不出来就记进 {@link Result#unreadable}，绝不把它当成
 * "没有重复"。
 */
public final class DuplicateScan {

    private DuplicateScan() {
    }

    /** 一个待比较的文件。id 必须唯一且稳定（这里用 URI 字符串）。 */
    public static final class Item {
        public final String id;
        /** 用户看到的相对路径。 */
        public final String label;
        /** 声明的大小；-1 表示提供器没给。 */
        public final long size;
        /** 修改时间，0 表示未知。 */
        public final long modified;
        public String sha256;
        public String error;

        public Item(String id, String label, long size, long modified) {
            this.id = id;
            this.label = label;
            this.size = size;
            this.modified = modified;
        }

        public boolean unreadable() {
            return error != null;
        }
    }

    /** 计算一项的摘要；抛异常即"读不出来"。 */
    public interface Hasher {
        String hash(Item item) throws Exception;
    }

    /** 进度回调，只在工作线程被调用。 */
    public interface Progress {
        void stage(int done, int total, String label);
    }

    public static final class Result {
        /** 每组至少两项，且 sha256 全同。按可省空间从大到小。 */
        public final java.util.List<java.util.List<Item>> groups = new java.util.ArrayList<>();
        /** 读取失败的项，附带原因；这些必须显示，不能算作"无重复"。 */
        public final java.util.List<Item> unreadable = new java.util.ArrayList<>();
        /** 因 0 字节被排除的项数。 */
        public int emptySkipped;
        /** 大小唯一、无需读内容即可判定不重复的项数。 */
        public int uniqueSize;
        public boolean cancelled;

        public int duplicateFiles() {
            int n = 0;
            for (java.util.List<Item> group : groups) {
                n += group.size();
            }
            return n;
        }

        /** 每组留一份之后能省下的字节数；大小未知按 0 计。 */
        public long reclaimable() {
            long total = 0;
            for (java.util.List<Item> group : groups) {
                long size = group.get(0).size;
                if (size > 0) {
                    total += size * (group.size() - 1);
                }
            }
            return total;
        }
    }

    /**
     * 分桶：同尺寸（含"大小未知"）才需要读内容。
     *
     * <p>0 字节文件不计入——空文件内容当然相同，但把空文件当重复删掉是误删，不是清理。
     */
    public static java.util.List<java.util.List<Item>> sizeBuckets(java.util.List<Item> items, Result result) {
        java.util.LinkedHashMap<Long, java.util.List<Item>> bySize = new java.util.LinkedHashMap<>();
        for (Item item : items) {
            if (item == null) {
                continue;
            }
            if (item.size == 0) {
                result.emptySkipped++;
                continue;
            }
            bySize.computeIfAbsent(item.size, key -> new java.util.ArrayList<>()).add(item);
        }
        java.util.List<java.util.List<Item>> buckets = new java.util.ArrayList<>();
        for (java.util.List<Item> bucket : bySize.values()) {
            if (bucket.size() >= 2) {
                buckets.add(bucket);
            } else {
                result.uniqueSize += bucket.size();
            }
        }
        return buckets;
    }

    /**
     * 完整的一遍：分桶 → 逐项算 SHA-256 → 按摘要分组。
     *
     * <p>取消时立刻返回，已算好的分组仍留在结果里，界面显示"已取消"而不是假装扫完了。
     */
    public static Result scan(java.util.List<Item> items, Hasher hasher, Cancel cancel, Progress progress) {
        Result result = new Result();
        if (items == null || items.isEmpty()) {
            return result;
        }
        java.util.List<java.util.List<Item>> buckets = sizeBuckets(items, result);
        int total = 0;
        for (java.util.List<Item> bucket : buckets) {
            total += bucket.size();
        }
        int done = 0;
        java.util.LinkedHashMap<String, java.util.List<Item>> byHash = new java.util.LinkedHashMap<>();
        for (java.util.List<Item> bucket : buckets) {
            for (Item item : bucket) {
                if (cancel != null && cancel.isCancelled()) {
                    result.cancelled = true;
                    break;
                }
                done++;
                if (progress != null) {
                    progress.stage(done, total, item.label);
                }
                try {
                    item.sha256 = hasher.hash(item);
                    if (item.sha256 == null || item.sha256.isEmpty()) {
                        item.error = "没有取到摘要";
                    }
                } catch (Exception error) {
                    // 读不出来就是读不出来：记原因、跳过，不假装它没重复。
                    item.error = message(error);
                }
                if (item.unreadable()) {
                    result.unreadable.add(item);
                    continue;
                }
                byHash.computeIfAbsent(item.sha256, key -> new java.util.ArrayList<>()).add(item);
            }
            if (result.cancelled) {
                break;
            }
        }
        for (java.util.List<Item> group : byHash.values()) {
            if (group.size() >= 2) {
                group.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
                result.groups.add(group);
            }
        }
        result.groups.sort((a, b) -> {
            long left = a.get(0).size * (a.size() - 1);
            long right = b.get(0).size * (b.size() - 1);
            if (left != right) {
                return Long.compare(right, left);
            }
            return a.get(0).label.compareToIgnoreCase(b.get(0).label);
        });
        return result;
    }

    /**
     * 这一组里真正要删的项：只看勾选的，且整组都被勾选时保留最后一份。
     *
     * <p>这是"每组至少保留一份"的唯一实现点，界面不重复做判断。
     */
    public static java.util.List<Item> selectForDeletion(java.util.List<Item> group, java.util.Set<String> checked) {
        java.util.List<Item> picked = new java.util.ArrayList<>();
        if (group == null || checked == null) {
            return picked;
        }
        for (Item item : group) {
            if (checked.contains(item.id)) {
                picked.add(item);
            }
        }
        if (picked.size() >= group.size() && !picked.isEmpty()) {
            picked.remove(picked.size() - 1);
        }
        return picked;
    }

    /**
     * 删除前重新核对：大小或修改时间任一项变了就不删。
     *
     * <p>返回 null 表示没发现变化。两项都拿不到时返回"无法确认"——这时调用方应该改用
     * {@link #metadataUnknown} 判断并重算摘要，不能因为看不到元数据就直接删。
     */
    public static String verifyUnchanged(Item item, long actualSize, long actualModified) {
        if (item == null) {
            return "项已丢失";
        }
        boolean sizeKnown = item.size >= 0 && actualSize >= 0;
        boolean timeKnown = item.modified > 0 && actualModified > 0;
        if (sizeKnown && item.size != actualSize) {
            return "大小已从 " + item.size + " 变为 " + actualSize + " 字节";
        }
        if (timeKnown && item.modified != actualModified) {
            return "修改时间已变化";
        }
        if (!sizeKnown && !timeKnown) {
            return "无法确认内容未变化";
        }
        return null;
    }

    /** 两个元数据都拿不到：只能靠重算摘要来核验，不能直接放行。 */
    public static boolean metadataUnknown(Item item, long actualSize, long actualModified) {
        return item == null || (!(item.size >= 0 && actualSize >= 0) && !(item.modified > 0 && actualModified > 0));
    }

    /** 流式 SHA-256；每 64 KiB 检查一次取消。 */
    public static String sha256(java.io.InputStream in, Cancel cancel) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[65536];
        int read;
        while ((read = in.read(buffer)) != -1) {
            if (cancel != null) {
                cancel.check();
            }
            digest.update(buffer, 0, read);
        }
        return hex(digest.digest());
    }

    public static boolean sameStorageObject(long leftDevice, long leftInode, long rightDevice, long rightInode) {
        return leftInode != 0 && leftDevice == rightDevice && leftInode == rightInode;
    }

    public static boolean contentStillDuplicate(Item item, String currentHash, String keptHash) {
        return item != null && item.sha256 != null && item.sha256.equals(currentHash) && item.sha256.equals(keptHash);
    }

    public static String hex(byte[] data) {
        StringBuilder text = new StringBuilder(data.length * 2);
        for (byte value : data) {
            text.append(Character.forDigit((value >> 4) & 0xF, 16));
            text.append(Character.forDigit(value & 0xF, 16));
        }
        return text.toString();
    }

    static String message(Throwable error) {
        String text = error.getMessage();
        if (text == null || text.trim().isEmpty()) {
            text = error.getClass().getSimpleName();
        }
        return text;
    }
}
