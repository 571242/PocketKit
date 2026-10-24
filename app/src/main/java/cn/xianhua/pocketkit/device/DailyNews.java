package cn.xianhua.pocketkit.device;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 095 日报：默认读取每日 60s 的公开 RSS 摘要，其他来源保留为标题资讯。
 * 每次由用户触发联网；日期、时间、来源和原文链接均来自返回内容。
 */
public final class DailyNews {

    private DailyNews() {
    }

    public static boolean supports(String id) {
        return "095".equals(id);
    }

    public static String limitation(String id) {
        return "095".equals(id)
                ? "普通来源读取公开 RSS 标题；每日60s读取其摘要并显示来源日期。网络或格式异常会提示失败。"
                : "";
    }

    /** 可选来源。键为界面显示名，值为 RSS 地址（均为已实测的 HTTPS）。 */
    static final Map<String, String> SOURCES = new LinkedHashMap<>();

    static {
        SOURCES.put("每日60s摘要", "https://60s.viki.moe/v2/60s/rss");
        SOURCES.put("人民网·时政", "https://www.people.com.cn/rss/politics.xml");
        SOURCES.put("人民网·国际", "https://www.people.com.cn/rss/world.xml");
        SOURCES.put("新华网·时政", "https://www.xinhuanet.com/politics/news_politics.xml");
        SOURCES.put("中新网·即时", "https://www.chinanews.com.cn/rss/scroll-news.xml");
        SOURCES.put("中新网·要闻", "https://www.chinanews.com.cn/rss/importnews.xml");
    }

    /** 一次最多显示多少条，避免界面过长。 */
    private static final int MAX_ITEMS = 20;

    public static void build(Ui ui) {
        ui.note("默认获取每日60s摘要；也可选择其他 RSS 读取资讯标题。");
        ui.note("联网只连接你选择的来源，不上传个人信息；打开原文会离开本应用。");

        final String[] labels = SOURCES.keySet().toArray(new String[0]);
        final android.widget.Spinner spinner = ui.select("来源", labels);

        android.widget.EditText keyword = ui.field("标题关键词筛选（可留空）", "例如 经济");

        final android.widget.TextView stateLabel = (android.widget.TextView)
                ui.note("尚未读取。");

        ui.button("获取摘要 / 读取资讯", () -> {
            String label = labels[Math.max(0, spinner.getSelectedItemPosition())];
            String url = SOURCES.get(label);
            String key = keyword.getText().toString().trim();
            ui.background(() -> {
                Http.Response response = Http.get(url, "application/rss+xml, application/xml, text/xml, */*");
                String text;
                if (!response.ok()) {
                    text = "读取失败：" + WebQueryTools.failureText(response)
                            + "\n来源地址：" + url;
                } else {
                    try {
                        RssReader.Feed feed = RssReader.parse(response.body);
                        text = "每日60s摘要".equals(label)
                                ? renderDailySummary(url, feed)
                                : render(label, url, feed, key);
                    } catch (IllegalArgumentException ex) {
                        text = "来源格式无法读取：" + ex.getMessage()
                                + "\n来源地址：" + url;
                    } catch (Exception ex) {
                        text = "读取失败：" + ex.getMessage() + "\n来源地址：" + url;
                    }
                }
                final String result = text;
                ui.activity().runOnUiThread(() -> {
                    ui.show(result);
                    stateLabel.setText(firstLine(result));
                });
            });
        });

        ui.button("查看 RSS 来源与许可说明", () -> ui.show(
                "本工具读取的是各新闻网站公开发布的 RSS 订阅源：\n\n"
                        + "· 人民网（people.com.cn）\n"
                        + "· 新华网（xinhuanet.com）\n"
                        + "· 中国新闻网（chinanews.com.cn）\n"
                        + "· 每日60s（60s.viki.moe，项目代码 MIT；新闻正文版权归原发布方）\n\n"
                        + "普通来源只展示标题；每日60s显示其 RSS 摘要。内容版权归来源方，阅读原文请遵守其声明。\n"
                        + "如需引用或转载，请遵守对应网站的版权声明，并访问原文。\n\n"
                        + "本工具与上述网站没有隶属或合作关系。"));

        ui.show("默认来源为每日60s摘要；选择其他来源可读取资讯标题。");
    }

    /** 渲染每日60s专用摘要；只显示服务端当前条目，不缓存或补写历史内容。 */
    public static String renderDailySummary(String url, RssReader.Feed feed) {
        if (feed == null || feed.items.isEmpty()) {
            return "来源未返回摘要。\n来源地址：" + url;
        }
        RssReader.Item item = feed.items.get(0);
        String summary = item.summary == null ? "" : item.summary.trim();
        if (summary.isEmpty()) {
            return "来源条目没有摘要。\n来源地址：" + url;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("每日60s读懂世界\n");
        sb.append("来源日期：").append(item.title == null ? "未标注" : item.title).append('\n');
        sb.append("发布时间：").append(RssReader.shortDate(item.pubDate)).append('\n');
        sb.append("来源：").append(feed.title).append("\n\n");
        sb.append(summary).append("\n\n");
        sb.append("原文：").append(item.link == null ? "未提供" : item.link).append("\n");
        sb.append("内容与日期来自来源 RSS，版权与事实责任归来源方。");
        return sb.toString();
    }

    /** 渲染新闻列表。每条都带来源与时间，方便核实。 */
    public static String render(String label, String url, RssReader.Feed feed, String keyword) {
        StringBuilder sb = new StringBuilder();
        sb.append("来源：").append(label).append("（").append(feed.title).append("）\n");
        sb.append("地址：").append(url).append('\n');

        int total = feed.items.size();
        int shown = 0;
        int filtered = 0;
        sb.append("共 ").append(total).append(" 条");
        if (keyword != null && !keyword.isEmpty()) {
            sb.append("，筛选「").append(keyword).append("」");
        }
        sb.append("\n\n");

        for (RssReader.Item item : feed.items) {
            if (shown >= MAX_ITEMS) {
                break;
            }
            if (keyword != null && !keyword.isEmpty()
                    && (item.title == null || !item.title.contains(keyword))) {
                filtered++;
                continue;
            }
            shown++;
            sb.append(shown).append(". ").append(item.title == null ? "（无标题）" : item.title).append('\n');
            sb.append("   ").append(RssReader.shortDate(item.pubDate));
            sb.append("　·　").append(label).append('\n');
            if (item.link != null) {
                sb.append("   ").append(item.link).append('\n');
            }
            sb.append('\n');
        }

        if (shown == 0) {
            sb.append("没有匹配「").append(keyword).append("」的标题，共跳过 ").append(filtered).append(" 条。\n");
        } else if (total > shown + filtered) {
            sb.append("…另有 ").append(total - shown - filtered).append(" 条未显示。\n");
        }

        sb.append("\n以上标题与链接来自 ").append(label)
                .append(" 的 RSS，版权归原网站所有；本工具不复制正文。点击链接会由浏览器打开原文。");
        return sb.toString();
    }

    private static String firstLine(String text) {
        int index = text.indexOf('\n');
        return index < 0 ? text : text.substring(0, index);
    }

    /** 供自检使用：来源数量。 */
    public static int sourceCount() {
        return SOURCES.size();
    }

    public static String describeSource(String label) {
        return String.format(Locale.US, "%s -> %s", label, SOURCES.get(label));
    }
}
