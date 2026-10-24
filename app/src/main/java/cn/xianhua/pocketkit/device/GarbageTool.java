package cn.xianhua.pocketkit.device;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class GarbageTool {
    private GarbageTool() {}
    public static String limitation(String id) {
        return "098".equals(id) ? "离线查询本项目按上海2024版官方分类指引独立整理的常见物品参考目录。仅上海口径，不是全国分类库、不识别照片、不自动更新；未收录物品明确返回未收录。名称、材质和污染情况会影响分类，相关条目不能代替精确匹配；专项回收与最新执行规定以当地管理部门为准。" : "";
    }
    public static void build(Ui ui) {
        ui.note("上海生活垃圾分类参考 · 离线 · 依据2024版官方指引");
        ui.note(limitation("098"));
        android.widget.EditText query = ui.field("具体物品名称（不要只填泛称）", "例如：纸巾、玻璃瓶、大骨头、鸡骨头");
        ui.button("查询分类", () -> {
            String value = query.getText().toString().trim();
            if (value.isEmpty()) { ui.show("请输入具体物品名称。"); return; }
            ui.background(() -> {
                GarbageCatalog catalog;
                try (InputStreamReader reader = new InputStreamReader(ui.activity().getAssets().open("garbage-shanghai.tsv"), StandardCharsets.UTF_8)) {
                    catalog = GarbageCatalog.read(reader);
                }
                List<GarbageCatalog.Entry> exact = catalog.exact(value);
                List<GarbageCatalog.Entry> found = exact.isEmpty() ? catalog.related(value, 12) : exact;
                StringBuilder out = new StringBuilder("上海口径 · 2024版参考\n\n");
                if (found.isEmpty()) out.append("未收录这个物品，不猜测分类。请查看当地官方指南或询问管理人员。\n");
                else {
                    if (exact.isEmpty()) out.append("没有匹配到具体物品。以下仅为名称相关条目，不是对你输入物品的分类结论；请确认材质与状态：\n\n");
                    for (GarbageCatalog.Entry item : found) out.append(item.name).append(" → ").append(item.category)
                            .append('\n').append(item.tip).append("\n来源：").append(item.source).append("\n\n");
                }
                out.append("数据范围：").append(catalog.entries.size()).append("个常见物品条目。非完整官方目录。\n官方指引：").append(GarbageCatalog.SOURCE)
                        .append("\n离线数据不会自动更新；如区级专项回收或当地执行口径不同，以官方现行告知为准。");
                ui.show(out.toString());
            });
        });
        ui.button("查看分类口径与来源", () -> ui.show("上海采用可回收物、有害垃圾、湿垃圾、干垃圾四类。干湿不是按含水量区分。\n本项目自行整理分类事实与提示，不复制商业数据库或官方图标；源为沪绿容〔2024〕278号行政性文件及绿化市容局供稿说明。\n" + GarbageCatalog.SOURCE + "\n" + GarbageCatalog.EXPLANATION));
        ui.show("输入具体名称查询。查不到不表示可以随意投入干垃圾。");
    }
}
