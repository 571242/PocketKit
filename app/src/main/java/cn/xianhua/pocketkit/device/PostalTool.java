package cn.xianhua.pocketkit.device;

import com.ibm.icu.text.Transliterator;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class PostalTool {
    private PostalTool() {}
    public static boolean supports(String id) { return "105".equals(id); }
    public static String limitation(String id) {
        return supports(id) ? "离线查询GeoNames中国地区粗粒度邮编（仅以00结尾），不是全国街道级完整邮编，不可直接当作具体收件地址的精确编码。原始地名主要为拼音/拉丁写法；中文仅辅助转拼音，音译与多音字可能不匹配，优先用原拼音或已知邮编。源可能遗漏、过时、不准确；不自动更新。不补零猜测未收录精细邮编。CC BY 4.0署名GeoNames。" : "";
    }
    public static void build(Ui ui) {
        ui.note("离线地区邮编参考 · GeoNames · 仅粗粒度，以00结尾");
        ui.note(limitation("105"));
        android.widget.EditText input=ui.field("六位邮编或一个地区名", "例如：200000、Shanghai、合肥（不含省市后缀）");
        ui.button("查询参考邮编",()-> {
            String value=input.getText().toString().trim();
            if(value.isEmpty()) {ui.show("请输入邮编或地区名。");return;}
            if(value.codePointCount(0,value.length())>80) {ui.show("地区名请限制在80个字符以内。");return;}
            ui.background(()-> {
                PostalCatalog catalog;
                try(InputStreamReader reader=new InputStreamReader(ui.activity().getAssets().open("geonames-cn.tsv"),StandardCharsets.UTF_8)) {
                    catalog=PostalCatalog.read(reader);
                }
                String query=value.matches("[0-9]+") ? value : Transliterator.getInstance("Han-Latin").transliterate(value);
                PostalCatalog.Matches result=catalog.search(query,30);
                StringBuilder out=new StringBuilder("中国地区粗粒度邮编参考（不是街道编码）\n输入：").append(value).append("\n检索文字：").append(query).append("\n\n");
                if(result.total==0) out.append("未收录或地名写法没有匹配。不说明此地址不存在，也不猜测精确邮编；可尝试原拼音/拉丁名或查中国邮政。\n");
                else for(PostalCatalog.Entry item:result.entries) out.append(item.code).append(" → ").append(item.place).append("\n省级：").append(item.province).append("\n原地区标签：").append(item.region).append(" / ").append(item.district).append("\n\n");
                out.append("匹配数据行：").append(result.total).append("；展示：").append(result.entries.size()).append("。一邮编可有多个地区行，不能把行数当成唯一邮编数。\n目录总行数：").append(catalog.entries.size()).append("\n数据快照：2026-10-13下载，不代表每个行政名称于当日核订。\n数据来自GeoNames：").append(PostalCatalog.SOURCE).append("\n署名：https://www.geonames.org/\n许可：CC BY 4.0 https://creativecommons.org/licenses/by/4.0/\n具体投递地址请以中国邮政现行查询为准。应用不联网查询、不自动更新邮编。");
                ui.show(out.toString());
            });
        });
        ui.show("按原始目录匹配，精细邮编没收录就不补零。地名含行政后缀、别名、译名或多音字时可能无法匹配。");
    }
}
