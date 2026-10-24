package cn.xianhua.pocketkit.local;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

import cn.xianhua.pocketkit.core.LedgerBook;
import cn.xianhua.pocketkit.core.ToolException;

/**
 * 账本 / 周期付款 / 纪念日的本地存储。
 *
 * 用 SharedPreferences：数据量小（个人记账），不需要 SQLite 的查询能力，
 * 也不引入额外依赖。全部数据只在本机，不上传、不联网。
 *
 * 防丢数据的关键做法：
 * 1. 写入前先把新内容拼好，再一次性 commit，失败时报告而不是留下半截数据。
 * 2. 保存旧值，写入异常时回滚（SharedPreferences 的 commit 是原子的，这里主要防编辑期异常）。
 * 3. 删除必须先调用 deleteEntry 并带确认标记，界面负责二次确认。
 * 4. 每次写入后立刻读回校验，确保真的落盘。
 */
public final class LocalStore {

    private static final String PREF = "pocketkit_local";
    private static final String KEY_ENTRIES = "ledger_entries_v1";
    private static final String KEY_RECURRING = "recurring_v1";
    private static final String KEY_ANNIVERSARY = "anniversary_v1";
    private static final String KEY_SCOREBOARD = "scoreboard_v1";

    private final SharedPreferences prefs;

    public LocalStore(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    // ---------- 通用读写 ----------

    private List<String> readLines(String key) {
        String raw = prefs.getString(key, "");
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String line : raw.split("\n")) {
            if (!line.isEmpty()) {
                out.add(line);
            }
        }
        return out;
    }

    /** 写入并读回校验；校验失败就抛错，而不是假装成功。 */
    private void writeLines(String key, List<String> lines) throws ToolException {
        String raw = String.join("\n", lines);
        String previous = prefs.getString(key, "");
        if (!prefs.edit().putString(key, raw).commit()) {
            throw new ToolException("保存失败：本机存储写入被拒绝。请检查系统存储空间后重试。");
        }
        String readBack = prefs.getString(key, "");
        if (!raw.equals(readBack)) {
            // 尽量回到写入前的内容，避免留下不一致的数据
            prefs.edit().putString(key, previous == null ? "" : previous).commit();
            throw new ToolException("保存后读回的内容与写入不一致，已回滚以免数据丢失。"
                    + "请稍后重试；若反复出现，请先导出备份。");
        }
        memoryCache = null;
    }

    private List<LedgerBook.Entry> memoryCache;

    // ---------- 账目 CRUD ----------

    /** 全部账目，按日期倒序（新的在前）。 */
    public List<LedgerBook.Entry> entries() {
        if (memoryCache != null) {
            return new ArrayList<>(memoryCache);
        }
        List<LedgerBook.Entry> out = new ArrayList<>();
        int bad = 0;
        for (String line : readLines(KEY_ENTRIES)) {
            try {
                out.add(LedgerBook.decodeEntry(line));
            } catch (ToolException e) {
                // 单条坏了不能拖垮整个账本：跳过它，界面用 corruptedCount() 提示。
                bad++;
            }
        }
        corruptedCount = bad;
        out.sort((a, b) -> b.date.compareTo(a.date));
        memoryCache = new ArrayList<>(out);
        return out;
    }

    private int corruptedCount = 0;

    /** 有多少条记录读不出来（损坏或被旧版本写过）。 */
    public int corruptedCount() {
        return corruptedCount;
    }

    /** 在「导出为 CSV 或保存到文件」之前，先把账目读出来，让损坏条数就位。 */
    public int refreshCorruptedCount() {
        entries();
        return corruptedCount;
    }

    public void add(LedgerBook.Entry entry) throws ToolException {
        List<String> lines = readLines(KEY_ENTRIES);
        for (String line : lines) {
            String[] f = line.split("\u0001", -1);
            if (f.length > 0 && f[0].equals(entry.id)) {
                throw new ToolException("已存在编号为 " + entry.id + " 的记录。"
                        + "重复添加会导致统计重复计算，已取消这次操作。");
            }
        }
        lines.add(LedgerBook.encode(entry));
        writeLines(KEY_ENTRIES, lines);
    }

    /** 按 id 更新：找不到就报错，不静默新增。 */
    public void update(LedgerBook.Entry entry) throws ToolException {
        List<String> lines = readLines(KEY_ENTRIES);
        for (int i = 0; i < lines.size(); i++) {
            String[] f = lines.get(i).split("\u0001", -1);
            if (f.length > 0 && f[0].equals(entry.id)) {
                lines.set(i, LedgerBook.encode(entry));
                writeLines(KEY_ENTRIES, lines);
                return;
            }
        }
        throw new ToolException("没有找到编号为 " + entry.id + " 的记录，无法更新。");
    }

    /** 删除单条。confirmed 必须为 true —— 界面负责先弹出确认。 */
    public void delete(String id, boolean confirmed) throws ToolException {
        if (!confirmed) {
            throw new ToolException("删除需要先确认。这条记录没有被删除。");
        }
        List<String> lines = readLines(KEY_ENTRIES);
        List<String> kept = new ArrayList<>();
        boolean found = false;
        for (String line : lines) {
            String[] f = line.split("\u0001", -1);
            if (f.length > 0 && f[0].equals(id)) {
                found = true;
                continue;
            }
            kept.add(line);
        }
        if (!found) {
            throw new ToolException("没有找到编号为 " + id + " 的记录，没有可删除的内容。");
        }
        writeLines(KEY_ENTRIES, kept);
    }

    // ---------- 周期付款 ----------

    public List<LedgerBook.Recurring> recurring() {
        List<LedgerBook.Recurring> out = new ArrayList<>();
        for (String line : readLines(KEY_RECURRING)) {
            try {
                out.add(LedgerBook.decodeRecurring(line));
            } catch (ToolException e) {
                corruptedCount++;
            }
        }
        return out;
    }

    /** 新增周期的同时把它落盘；重复 id 直接拒绝，避免统计重复。 */
    public void addRecurring(LedgerBook.Recurring plan) throws ToolException {
        List<String> lines = readLines(KEY_RECURRING);
        for (String line : lines) {
            String[] f = line.split("\u0001", -1);
            if (f.length > 0 && f[0].equals(plan.id)) {
                throw new ToolException("已存在编号相同的周期计划，已取消这次操作。");
            }
        }
        lines.add(LedgerBook.encode(plan));
        writeLines(KEY_RECURRING, lines);
    }

    public void deleteRecurring(String id, boolean confirmed) throws ToolException {
        if (!confirmed) {
            throw new ToolException("删除周期计划需要先确认。");
        }
        List<String> lines = readLines(KEY_RECURRING);
        List<String> kept = new ArrayList<>();
        boolean found = false;
        for (String line : lines) {
            String[] f = line.split("\u0001", -1);
            if (f.length > 0 && f[0].equals(id)) {
                found = true;
                continue;
            }
            kept.add(line);
        }
        if (!found) {
            throw new ToolException("没有找到编号为 " + id + " 的周期计划。");
        }
        writeLines(KEY_RECURRING, kept);
    }

    // ---------- 纪念日 ----------

    public List<String[]> anniversaries() {
        List<String[]> out = new ArrayList<>();
        for (String line : readLines(KEY_ANNIVERSARY)) {
            String[] f = line.split("\u0001", -1);
            if (f.length >= 4) {
                out.add(new String[]{f[0], field(f, 1), f[2], f[3]});
            }
        }
        return out;
    }

    public void addAnniversary(String id, String name, String date, boolean yearly) throws ToolException {
        List<String> lines = readLines(KEY_ANNIVERSARY);
        for (String line : lines) {
            String[] f = line.split("\u0001", -1);
            if (f.length > 0 && f[0].equals(id)) {
                throw new ToolException("已存在同名的纪念日记录。");
            }
        }
        lines.add(String.join("\u0001", id, escape(name), date, yearly ? "1" : "0", V2));
        writeLines(KEY_ANNIVERSARY, lines);
    }

    public void updateAnniversary(String id, String name, String date, boolean yearly)
            throws ToolException {
        List<String> lines = readLines(KEY_ANNIVERSARY);
        for (int i = 0; i < lines.size(); i++) {
            String[] f = lines.get(i).split("\u0001", -1);
            if (f.length > 0 && f[0].equals(id)) {
                lines.set(i, String.join("\u0001", id, escape(name), date, yearly ? "1" : "0", V2));
                writeLines(KEY_ANNIVERSARY, lines);
                return;
            }
        }
        throw new ToolException("没有找到要更新的纪念日。");
    }

    public void deleteAnniversary(String id, boolean confirmed) throws ToolException {
        if (!confirmed) {
            throw new ToolException("删除纪念日需要先确认。");
        }
        List<String> lines = readLines(KEY_ANNIVERSARY);
        List<String> kept = new ArrayList<>();
        boolean found = false;
        for (String line : lines) {
            String[] f = line.split("\u0001", -1);
            if (f.length > 0 && f[0].equals(id)) {
                found = true;
                continue;
            }
            kept.add(line);
        }
        if (!found) {
            throw new ToolException("没有找到要删除的纪念日。");
        }
        writeLines(KEY_ANNIVERSARY, kept);
    }

    // ---------- 记分牌 ----------

    public List<String[]> scoreboard() {
        List<String[]> out = new ArrayList<>();
        for (String line : readLines(KEY_SCOREBOARD)) {
            String[] f = line.split("\u0001", -1);
            if (f.length >= 3) {
                out.add(new String[]{field(f, 0), f[1], f[2]});
            }
        }
        return out;
    }

    public void saveScoreboard(List<String[]> rows) throws ToolException {
        List<String> lines = new ArrayList<>();
        for (String[] r : rows) {
            lines.add(String.join("\u0001", escape(r[0]), r[1], r.length > 2 ? r[2] : "", V2));
        }
        writeLines(KEY_SCOREBOARD, lines);
    }

    // ---------- 导出 ----------

    /** 导出全部内容为 CSV 文本（含 BOM 便于 Excel 直接打开）。 */
    public String exportCsv() throws ToolException {
        StringBuilder sb = new StringBuilder();
        sb.append('\uFEFF');
        sb.append("类型,日期,金额,分类,备注,已报销,收据,周期\n");
        for (LedgerBook.Entry e : entries()) {
            sb.append(csv("支出")).append(',')
                    .append(csv(e.date)).append(',')
                    .append(csv(e.amount.toPlainString())).append(',')
                    .append(csv(e.category)).append(',')
                    .append(csv(e.note)).append(',')
                    .append(csv(e.reimbursed ? "是" : "否")).append(',')
                    .append(csv(e.receipt)).append(',')
                    .append(csv(e.recurring ? "周期" : "")).append('\n');
        }
        for (LedgerBook.Recurring r : recurring()) {
            sb.append(csv("周期计划")).append(',')
                    .append(csv(r.startDate)).append(',')
                    .append(csv(r.amount.toPlainString())).append(',')
                    .append(csv(r.category)).append(',')
                    .append(csv(r.name)).append(',')
                    .append(csv("")).append(',')
                    .append(csv("")).append(',')
                    .append(csv(r.cycle.label)).append('\n');
        }
        for (String[] a : anniversaries()) {
            sb.append(csv("纪念日")).append(',')
                    .append(csv(a[2])).append(',')
                    .append(csv("")).append(',')
                    .append(csv("")).append(',')
                    .append(csv(a[1])).append(',')
                    .append(csv("")).append(',')
                    .append(csv("")).append(',')
                    .append(csv("1".equals(a[3]) ? "每年" : "仅一次")).append('\n');
        }
        return sb.toString();
    }

    /** CSV 单元格转义。逻辑在 core/CsvSafe，那里可被纯 Java 测试覆盖。 */
    static String csv(String v) {
        return cn.xianhua.pocketkit.core.CsvSafe.cell(v);
    }

    /**
     * 新格式记录末位的版本标记。
     *
     * 旧 escape() 把换行和 \u0001 直接换成空格（用户输入被静默改写），
     * 并且旧记录里可能留着原样的反斜杠（名字写成 C:\temp）。
     * 若对新旧记录统一反转义，旧名字里的「\t」会被解成「t」，又是一次静默改数据。
     * 所以只对带 v2 标记的新记录反转义，旧记录一个字都不动。
     */
    private static final String V2 = "v2";

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\u0001", "\\u0001")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    /** 取一行里的某个字段：只有新格式记录才反转义，旧记录原样返回。 */
    private static String field(String[] f, int index) {
        return V2.equals(f[f.length - 1]) ? unescape(f[index]) : f[index];
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                sb.append(c);
                continue;
            }
            char n = s.charAt(++i);
            if (n == 'n') {
                sb.append('\n');
            } else if (n == 'r') {
                sb.append('\r');
            } else if (n == 'u' && s.startsWith("0001", i + 1)) {
                // escape 写出的是「\u0001」这 6 个字符，此处 i 指向 'u'，后面还有 4 位数字。
                sb.append('\u0001');
                i += 4;
            } else {
                sb.append(n);
            }
        }
        return sb.toString();
    }

    /** 备份用的原始文本（界面可保存到用户选定的文件）。 */
    public String rawDump() {
        return "entries\n" + String.join("\n", readLines(KEY_ENTRIES))
                + "\nrecurring\n" + String.join("\n", readLines(KEY_RECURRING))
                + "\nanniversary\n" + String.join("\n", readLines(KEY_ANNIVERSARY))
                + "\nscoreboard\n" + String.join("\n", readLines(KEY_SCOREBOARD));
    }

    private static long lastIdMillis = 0;
    private static int idCounter = 0;

    /**
     * 生成一条新记录的编号。
     *
     * 只用 currentTimeMillis 是不够的：连续两次操作可能落在同一毫秒里，
     * 编号就会撞车，而 add() 会拒绝重复编号 —— 用户看到的是「莫名其妙记不进去」。
     * 因此同毫秒内再加一个递增序号。
     */
    public static synchronized String newId() {
        long now = System.currentTimeMillis();
        if (now == lastIdMillis) {
            idCounter++;
        } else {
            lastIdMillis = now;
            idCounter = 0;
        }
        return now + "-" + idCounter;
    }
}
