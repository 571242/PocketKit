import cn.xianhua.pocketkit.device.DailyNews;
import cn.xianhua.pocketkit.device.LexiconTool;
import cn.xianhua.pocketkit.device.Pingze;
import cn.xianhua.pocketkit.device.RssReader;

import java.util.ArrayList;
import java.util.List;

/**
 * 第 3 批新增纯逻辑自检：RSS 解析、平仄/对联规则、词条检索与许可判定。
 * 纯 Java，不需要 Android，也不需要网络。
 */
public final class Batch3LogicTest {

    private static int passed;
    private static int failed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        rssParsing();
        rssDates();
        entities();
        pingze();
        duilianRules();
        lexicon();
        sourceAssessment();
        newsSources();

        System.out.println();
        System.out.println("通过 " + passed + " 项，失败 " + failed + " 项");
        if (failed > 0) {
            System.out.println("失败明细：");
            for (String f : failures) {
                System.out.println("  - " + f);
            }
            System.exit(1);
        }
    }

    private static void rssParsing() {
        String rss = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<rss version=\"2.0\"><channel><title>测试频道</title>\n"
                + "<item><title>第一条 &amp; 标题</title><link>https://a.example/1</link>"
                + "<pubDate>Tue, 13 Oct 2026 08:00:00 +0800</pubDate>"
                + "<description><![CDATA[<p>摘要一</p>]]></description></item>\n"
                + "<item><title>第二条</title><link>https://a.example/2</link>"
                + "<pubDate>Mon, 12 Oct 2026 09:30:00 +0800</pubDate></item>\n"
                + "</channel></rss>";
        RssReader.Feed feed = RssReader.parse(rss);
        check("RSS 频道标题", "测试频道".equals(feed.title), feed.title);
        check("RSS 条目数", feed.items.size() == 2, String.valueOf(feed.items.size()));
        check("RSS 标题实体解码", "第一条 & 标题".equals(feed.items.get(0).title), feed.items.get(0).title);
        check("RSS 链接", "https://a.example/1".equals(feed.items.get(0).link), feed.items.get(0).link);
        check("RSS CDATA 摘要去标签", "摘要一".equals(feed.items.get(0).summary), feed.items.get(0).summary);
        check("RSS 缺省摘要为 null", feed.items.get(1).summary == null, String.valueOf(feed.items.get(1).summary));

        // <items> 不能被误当成 <item>
        RssReader.Feed tricky = RssReader.parse("<rss><channel><title>T</title>"
                + "<items><item><title>真条目</title></item></items></channel></rss>");
        check("不把 <items> 误认为 <item>", tricky.items.size() == 1, String.valueOf(tricky.items.size()));

        // Atom
        RssReader.Feed atom = RssReader.parse("<?xml version=\"1.0\"?><feed><title>原子</title>"
                + "<entry><title>原子条目</title><link href=\"https://b.example/1\"/>"
                + "<updated>2026-10-13T08:00:00Z</updated></entry></feed>");
        check("Atom 解析出条目", atom.items.size() == 1, String.valueOf(atom.items.size()));
        check("Atom link 属性", "https://b.example/1".equals(atom.items.get(0).link), atom.items.get(0).link);

        // 失败必须抛错，不能返回空列表冒充"没有新闻"
        expectThrow("空内容抛错", () -> RssReader.parse(""));
        expectThrow("HTML 页面抛错（不是静默返回空）", () -> RssReader.parse("<html><body>登录</body></html>"));
        expectThrow("无条目抛错", () -> RssReader.parse("<rss><channel><title>空</title></channel></rss>"));
    }

    private static void rssDates() {
        check("RSS 日期规整", "2026-10-13 08:00".equals(RssReader.shortDate("Tue, 13 Oct 2026 08:00:00 +0800")),
                RssReader.shortDate("Tue, 13 Oct 2026 08:00:00 +0800"));
        check("ISO 日期规整", "2026-10-13 08:00".equals(RssReader.shortDate("2026-10-13T08:00:00Z")),
                RssReader.shortDate("2026-10-13T08:00:00Z"));
        check("空日期有说明", "时间未标注".equals(RssReader.shortDate(null)), RssReader.shortDate(null));
    }

    private static void entities() {
        check("命名实体", "a<b>c&d".equals(RssReader.decodeEntities("a&lt;b&gt;c&amp;d")),
                RssReader.decodeEntities("a&lt;b&gt;c&amp;d"));
        check("十进制实体", "中".equals(RssReader.decodeEntities("&#20013;")),
                RssReader.decodeEntities("&#20013;"));
        check("十六进制实体", "中".equals(RssReader.decodeEntities("&#x4E2D;")),
                RssReader.decodeEntities("&#x4E2D;"));
        check("无分号不算实体", "&abc".equals(RssReader.decodeEntities("&abc")),
                RssReader.decodeEntities("&abc"));
        check("去 HTML 标签", "纯文本".equals(RssReader.stripTags("<p>纯<b>文本</b></p>")),
                RssReader.stripTags("<p>纯<b>文本</b></p>"));
    }

    private static void pingze() {
        check("一声是平", "平".equals(Pingze.pingzeOfTone(1)), Pingze.pingzeOfTone(1));
        check("二声是平", "平".equals(Pingze.pingzeOfTone(2)), Pingze.pingzeOfTone(2));
        check("三声是仄", "仄".equals(Pingze.pingzeOfTone(3)), Pingze.pingzeOfTone(3));
        check("四声是仄", "仄".equals(Pingze.pingzeOfTone(4)), Pingze.pingzeOfTone(4));
        check("零声调无法判定", "？".equals(Pingze.pingzeOfTone(0)), Pingze.pingzeOfTone(0));

        check("读取一声符号", Pingze.toneOf("mā") == 1, String.valueOf(Pingze.toneOf("mā")));
        check("读取二声符号", Pingze.toneOf("má") == 2, String.valueOf(Pingze.toneOf("má")));
        check("读取三声符号", Pingze.toneOf("mǎ") == 3, String.valueOf(Pingze.toneOf("mǎ")));
        check("读取四声符号", Pingze.toneOf("mà") == 4, String.valueOf(Pingze.toneOf("mà")));
        check("ü 四声", Pingze.toneOf("lǜ") == 4, String.valueOf(Pingze.toneOf("lǜ")));
        check("无调拼音无法判定", Pingze.toneOf("ma") == 0, String.valueOf(Pingze.toneOf("ma")));

        // 平仄串：两个黄鹂鸣翠柳 -> 仄仄平平平仄仄（按传统读法）
        String pattern = Pingze.pattern("liǎng gè huáng lí míng cuì liǔ", "两个黄鹂鸣翠柳");
        check("平仄串长度等于汉字数", pattern.length() == 7, pattern);
        check("平仄串只含平仄问号", pattern.matches("[平仄？]+"), pattern);
    }

    private static void duilianRules() {
        // 字数不等必须报出来
        Pingze.Report uneven = Pingze.analyse("上联五个字", "shàng lián wǔ gè zì",
                "下联四个", "xià lián sì gè");
        check("字数不等被发现", !uneven.sameLength, uneven.upperPattern + "/" + uneven.lowerPattern);
        check("字数不等时判定失败", !uneven.allPassed(), "");
        check("字数不等有文字说明", uneven.detail.contains("字数不等"), uneven.detail);

        // 重复字检测
        check("重复字检测", "人".equals(Pingze.repeatedChars("人人从众")), Pingze.repeatedChars("人人从众"));
        check("无重复返回空", Pingze.repeatedChars("甲乙丙丁").isEmpty(), Pingze.repeatedChars("甲乙丙丁"));

        // 汉字下标换算（跳过标点）
        check("汉字下标换算", Pingze.hanIndexToRaw("一，二", 1) == 2, String.valueOf(Pingze.hanIndexToRaw("一，二", 1)));
    }

    private static void lexicon() {
        List<LexiconTool.Entry> corpus = new ArrayList<>();
        corpus.add(new LexiconTool.Entry("美丽", "好看"));
        corpus.add(new LexiconTool.Entry("美好", "好"));
        corpus.add(new LexiconTool.Entry("美", "美的基本义"));
        corpus.add(new LexiconTool.Entry("漂亮", "好看"));

        List<LexiconTool.Entry> exact = LexiconTool.search(corpus, "美", 10);
        check("精确匹配排最前", exact.size() >= 1 && "美".equals(exact.get(0).term),
                exact.isEmpty() ? "(空)" : exact.get(0).term);
        check("前缀匹配都返回", exact.size() == 3, String.valueOf(exact.size()));
        check("非前缀不返回", LexiconTool.search(corpus, "美丽", 10).size() == 1,
                String.valueOf(LexiconTool.search(corpus, "美丽", 10).size()));
        check("limit 生效", LexiconTool.search(corpus, "美", 2).size() == 2,
                String.valueOf(LexiconTool.search(corpus, "美", 2).size()));
        check("空查询返回空", LexiconTool.search(corpus, "  ", 10).isEmpty(), "");
        check("null 语料安全", LexiconTool.search(null, "美", 10).isEmpty(), "");

        List<String> terms = LexiconTool.splitTerms("好看，漂亮、美丽; 标致");
        check("近反义词拆分", terms.size() == 4, terms.toString());
        check("去重", LexiconTool.splitTerms("好看,好看").size() == 1, LexiconTool.splitTerms("好看,好看").toString());
        check("空输入返回空表", LexiconTool.splitTerms(null).isEmpty(), "");
    }

    private static void sourceAssessment() {
        // 这是任务书点名的合规红线，必须由测试盯住
        String xinhua = LexiconTool.assessSource("chinese-xinhua");
        check("chinese-xinhua 因正文版权被判不可用", xinhua.startsWith("不采用"), xinhua);
        check("说明指出 MIT 覆盖不到正文", xinhua.contains("新华字典"), xinhua);

        String mdbg = LexiconTool.assessSource("mdbg.net");
        check("mdbg 因禁止自动化访问被判不可用", mdbg.startsWith("不采用"), mdbg);

        String cedict = LexiconTool.assessSource("CC-CEDICT");
        check("CC-CEDICT 可采用但需署名", cedict.startsWith("可采用") && cedict.contains("署名"), cedict);

        String unknown = LexiconTool.assessSource("some-random-repo");
        check("未知来源要求先核实", unknown.contains("未核实"), unknown);
        check("null 来源不可用", LexiconTool.assessSource(null).startsWith("来源未标明"),
                LexiconTool.assessSource(null));
    }

    private static void newsSources() {
        check("新闻来源数量", DailyNews.sourceCount() >= 3, String.valueOf(DailyNews.sourceCount()));
        for (String label : new String[]{"人民网·时政", "新华网·时政", "中新网·即时"}) {
            String desc = DailyNews.describeSource(label);
            check(label + " 使用 HTTPS（明文会被系统拦截）", desc.contains("https://"), desc);
        }
        String summaryRss = "<?xml version=\"1.0\"?><rss><channel><title>每日 60s 读懂世界</title>"
                + "<item><title>📅 2026-10-13 星期二</title><link>https://60s-static.viki.moe?date=2026-10-13</link>"
                + "<pubDate>Tue, 13 Oct 2026 00:00:00 +0800</pubDate>"
                + "<description><![CDATA[<p>1. 今日要点甲</p><p>2. 今日要点乙</p>]]></description></item>"
                + "</channel></rss>";
        RssReader.Feed summaryFeed = RssReader.parse(summaryRss);
        String rendered = DailyNews.renderDailySummary("https://60s.viki.moe/v2/60s/rss", summaryFeed);
        check("每日60s摘要保留日期", rendered.contains("2026-10-13"), rendered);
        check("每日60s摘要保留发布时间", rendered.contains("2026-10-13 00:00"), rendered);
        check("每日60s摘要保留要点", rendered.contains("今日要点甲") && rendered.contains("今日要点乙"), rendered);
        check("每日60s摘要标注来源", rendered.contains("https://60s-static.viki.moe?date=2026-10-13"), rendered);
        RssReader.Feed emptySummary = RssReader.parse("<rss><channel><title>x</title><item><title>2026-10-13</title><link>fixture</link></item></channel></rss>");
        check("每日60s摘要空正文不伪造", DailyNews.renderDailySummary("fixture", emptySummary).contains("来源条目没有摘要"), "");
    }

    private static void expectThrow(String name, Runnable action) {
        try {
            action.run();
            check(name, false, "没有抛异常");
        } catch (Exception ex) {
            check(name, true, "");
        }
    }

    private static void check(String name, boolean condition, String detail) {
        if (condition) {
            passed++;
        } else {
            failed++;
            failures.add(name + (detail == null || detail.isEmpty() ? "" : " → " + detail));
        }
    }
}
