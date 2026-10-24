package cn.xianhua.pocketkit.device;

import com.ibm.icu.text.Transliterator;
import android.database.Cursor;

/** 094/104: bundled read-only MOE dictionary; never falls back to network. */
public final class MoeQueryTool {
    private MoeQueryTool() {}
    public static boolean supports(String id) { return "094".equals(id) || "104".equals(id); }
    public static String limitation(String id) {
        if (!supports(id)) return "";
        return "本地只读查询教育部《重編國語辭典修訂本》161194条镜像原记录。镜像声明民国110年11月台湾学术网络第六版，非最新官方版、非大陆规范字典。首次解压约70.45 MiB私有存储，需额外安装临时空间；不联网、不自动更新。简体仅辅助查询，正文繁体原样保留，多义简繁需核对。近反义只显示原义组明确synonyms/antonyms，没字段表示未收录、不推断。原文CC BY-ND 3.0台湾，按署名分发。";
    }
    public static void build(Ui ui, String id) {
        ui.note(limitation(id));
        android.widget.EditText word = ui.field("字或词语", "例如：春、美丽、快乐");
        android.widget.Spinner mode = ui.select("查询文字（不改变结果原文）", "简体转繁体辅助查询", "按输入原样查询");
        ui.button("104".equals(id) ? "查询近反义词" : "查询词条", () -> {
            String typed=word.getText().toString().trim();
            if (typed.isEmpty()) { ui.show("请输入字或词语。"); return; }
            if (typed.codePointCount(0,typed.length())>64 || typed.codePoints().anyMatch(Character::isISOControl)) { ui.show("查询词须在64个字符以内且不含控制字符。"); return; }
            boolean convert = mode.getSelectedItemPosition()==0;
            ui.background(() -> {
                OfflineLexiconStore store=null; Cursor c=null;
                try {
                    String query=convert ? Transliterator.getInstance("Simplified-Traditional").transliterate(typed) : typed;
                    store=OfflineLexiconStore.open(ui.activity()); c=store.query(query);
                    if (!c.moveToFirst()) { ui.show("未收录："+typed+"（本地库无此精确词目；未联网查询）"); return; }
                    StringBuilder all=new StringBuilder();
                    do { all.append(OfflineLexiconFormatter.format(c.getString(1), c.getString(2))).append('\n'); } while(c.moveToNext());
                    ui.show("查询："+typed+"\n检索词："+query+"\n"+all+"\n没有显示近义/反义字段时表示原义组未收录，不代表不存在。\n原文署名：中華民國教育部（Ministry of Education, R.O.C.）。《重編國語辭典修訂本》（鏡像声明中華民國110年11月臺灣學術網路第六版）。\nhttps://dict.revised.moe.edu.tw/\n格式镜像：g0v/moedict-data；编辑格式kcwu CC0，原文仍CC BY-ND 3.0 TW。\nhttps://creativecommons.org/licenses/by-nd/3.0/tw/\n正文完整保原文，不转简体，不联网，镜像不是最新官方版。");
                } catch (Exception e) { ui.show("本地词典不可用："+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage())); }
                finally { if(c!=null)c.close(); if(store!=null)store.close(); }
            });
        });
        ui.show("输入词语后查询；原数据繁体保持原样。词典不可用时不会自动联网。");
    }
}
