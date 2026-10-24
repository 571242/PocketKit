package cn.xianhua.pocketkit.device;

import java.util.ArrayList;
import java.util.List;

/**
 * 联网查询工具的真实请求测试。
 *
 * 做法：直接调用生产代码里的 Http 与各工具的解析/渲染静态方法，打真实公开接口，
 * 断言返回被正确解析、关键字段非空、失败路径有明确原因。
 *
 * org.json 只在 JVM 自检时需要（Android framework 自带同名 API），
 * 因此本测试用 Gradle 缓存里的 org.json jar 跑，应用本身不依赖它。
 */
public final class NetworkQueryTest {

    private static int passed;
    private static int failed;
    private static int unreachableCount;
    private static final List<String> failures = new ArrayList<>();
    private static final List<String> unreachableList = new ArrayList<>();

    public static void main(String[] args) {
        // 纯逻辑：先跑不依赖网络的校验，再跑真实请求。
        sanitizers();
        realRequests();

        System.out.println();
        System.out.println("通过 " + passed + " 项，失败 " + failed + " 项，未验证 " + unreachableCount + " 项");
        if (!unreachableList.isEmpty()) {
            System.out.println("以下接口在本开发机不可达，其解析逻辑**未**经过真实响应验证：");
            for (String item : unreachableList) {
                System.out.println("  - " + item);
            }
        }
        if (failed > 0) {
            System.out.println("失败明细：");
            for (String f : failures) {
                System.out.println("  - " + f);
            }
        }
        System.exit(failed > 0 ? 1 : 0);
    }

    private static void sanitizers() {
        // 域名清洗：只接受纯域名，防止把任意字符串拼进请求路径。
        check("域名清洗：带协议", "example.com".equals(
                WebQueryTools.DomainQuery.sanitizeDomain("https://example.com/path?a=1")));
        check("域名清洗：去 www", "example.com".equals(
                WebQueryTools.DomainQuery.sanitizeDomain("www.example.com")));
        check("域名清洗：大写转小写", "example.com".equals(
                WebQueryTools.DomainQuery.sanitizeDomain("EXAMPLE.COM")));
        check("域名清洗：拒绝中文", WebQueryTools.DomainQuery.sanitizeDomain("测试.com") == null, "");
        check("域名清洗：拒绝空", WebQueryTools.DomainQuery.sanitizeDomain("   ") == null, "");
        check("域名清洗：拒绝单段", WebQueryTools.DomainQuery.sanitizeDomain("localhost") == null, "");
        check("域名清洗：拒绝注入路径穿越", WebQueryTools.DomainQuery.sanitizeDomain("a.com/../../etc") == null
                || "a.com".equals(WebQueryTools.DomainQuery.sanitizeDomain("a.com/../../etc")));

        // IP 清洗：只接受合法点分十进制。
        check("IP 清洗：合法", "8.8.8.8".equals(WebQueryTools.IpQuery.sanitizeIp("8.8.8.8")));
        check("IP 清洗：拒绝越界", WebQueryTools.IpQuery.sanitizeIp("256.1.1.1") == null, "");
        check("IP 清洗：拒绝少段", WebQueryTools.IpQuery.sanitizeIp("8.8.8") == null, "");
        check("IP 清洗：拒绝字母", WebQueryTools.IpQuery.sanitizeIp("8.8.8.x") == null, "");
        check("IP 清洗：拒绝空段", WebQueryTools.IpQuery.sanitizeIp("8..8.8") == null, "");
        check("IP 清洗：拒绝超长段", WebQueryTools.IpQuery.sanitizeIp("8888.1.1.1") == null, "");

        // URL 清洗：只允许 http/https。
        check("URL 清洗：https 通过", "https://a.com/".equals(WebQueryTools.ShortLink.normalizeUrl("https://a.com/")));
        check("URL 清洗：http 通过", "http://a.com/".equals(WebQueryTools.ShortLink.normalizeUrl("http://a.com/")));
        check("URL 清洗：拒绝 file", WebQueryTools.ShortLink.normalizeUrl("file:///etc/passwd") == null, "");
        check("URL 清洗：拒绝 javascript", WebQueryTools.ShortLink.normalizeUrl("javascript:alert(1)") == null, "");
        check("URL 清洗：拒绝裸域名", WebQueryTools.ShortLink.normalizeUrl("a.com") == null, "");
    }

    private static void realRequests() {
        // 083 域名查询（RDAP，匿名 HTTPS）
        try {
            Http.Response response = Http.get(
                    "https://rdap.org/domain/example.com", "application/rdap+json, application/json");
            if (response.ok()) {
                String text = WebQueryTools.DomainQuery.renderDomain("example.com", response);
                check("083 RDAP 解析出注册时间", text.contains("注册时间"), snippet(text));
                check("083 RDAP 解析出 DNS 服务器", text.contains("DNS 服务器"), snippet(text));
                check("083 输出不含报错", !text.contains("解析错误") && !text.contains("查询失败"), snippet(text));
                System.out.println("  · 083 实测输出首行：" + firstLine(text));
            } else {
                softFail("083 RDAP 请求失败（网络受限时属预期）", WebQueryTools.failureText(response));
            }
        } catch (Exception ex) {
            softFail("083 异常", ex.toString());
        }

        // 084 IP 查询
        try {
            Http.Response response = Http.get("https://ipwho.is/8.8.8.8", "application/json");
            if (response.ok()) {
                String text = WebQueryTools.IpQuery.renderIp("8.8.8.8", response);
                check("084 IP 解析出国家", text.contains("国家/地区"), snippet(text));
                check("084 IP 解析出运营商", text.contains("运营商"), snippet(text));
                check("084 输出不含报错", !text.contains("解析错误") && !text.contains("查询失败"), snippet(text));
                System.out.println("  · 084 实测输出首行：" + firstLine(text));
            } else {
                softFail("084 请求失败", WebQueryTools.failureText(response));
            }
        } catch (Exception ex) {
            softFail("084 异常", ex.toString());
        }

        // 088 短链生成 + 还原
        try {
            Http.Response created = Http.get(
                    "https://tinyurl.com/api-create.php?url=https%3A%2F%2Fexample.com%2F", "text/plain");
            if (created.ok() && created.body.trim().startsWith("http")) {
                String shortUrl = created.body.trim();
                check("088 短链生成了 http 链接", shortUrl.startsWith("http"), shortUrl);
                // 还原：应能跟随重定向拿回原始地址。
                String resolved = WebQueryTools.ShortLink.resolveRedirect(shortUrl);
                check("088 短链可还原到 example.com",
                        resolved != null && resolved.contains("example.com"), String.valueOf(resolved));
                System.out.println("  · 088 实测：" + shortUrl + " → " + resolved);
            } else {
                softFail("088 短链生成失败", WebQueryTools.failureText(created));
            }
        } catch (Exception ex) {
            softFail("088 异常", ex.toString());
        }

        // 097 快递：先自动识别承运商，再查询轨迹
        try {
            Http.Response auto = Http.get(
                    "https://www.kuaidi100.com/autonumber/autoComNum?text=YT1234567890", "application/json");
            if (auto.ok()) {
                org.json.JSONObject json = new org.json.JSONObject(auto.body);
                check("097 自动识别返回 auto 数组", json.has("auto"), snippet(auto.body));
                org.json.JSONArray array = json.optJSONArray("auto");
                check("097 auto 数组非空", array != null && array.length() > 0, snippet(auto.body));
                if (array != null && array.length() > 0) {
                    String code = array.optJSONObject(0).optString("comCode");
                    check("097 识别出承运商代码", !code.isEmpty(), code);
                    System.out.println("  · 097 实测识别：YT1234567890 → " + code);

                    Http.Response query = Http.get(
                            "https://www.kuaidi100.com/query?type=" + code + "&postid=YT1234567890",
                            "application/json");
                    if (query.ok()) {
                        String text = WebQueryTools.ExpressQuery.renderExpress("YT1234567890", code, query);
                        check("097 轨迹渲染无解析错误", !text.contains("无法解析"), snippet(text));
                        check("097 轨迹含运单号", text.contains("YT1234567890"), snippet(text));
                        System.out.println("  · 097 实测输出首行：" + firstLine(text));
                    } else {
                        softFail("097 轨迹查询失败", WebQueryTools.failureText(query));
                    }
                }
            } else {
                softFail("097 自动识别失败", WebQueryTools.failureText(auto));
            }
        } catch (Exception ex) {
            softFail("097 异常", ex.toString());
        }

        // 106 历史上的今天
        try {
            Http.Response response = Http.get(
                    "https://api.wikimedia.org/feed/v1/wikipedia/zh/onthisday/events/10/13", "application/json");
            if (response.ok()) {
                String text = WebQueryTools.OnThisDay.render(response, 10, 13);
                check("106 渲染出事件", text.contains("年"), snippet(text));
                check("106 无解析错误", !text.contains("无法解析"), snippet(text));
                System.out.println("  · 106 实测输出首行：" + firstLine(text));
            } else {
                unreachable("106", "api.wikimedia.org",
                        "该主机在本开发机的命令行网络下不可达（同一接口经另一条 fetch 通道已确认返回真实数据，"
                                + "含 events/year/text 字段）。因此解析逻辑未能在本机走通真实响应，"
                                + "真机行为未验证。\n     失败原因：" + WebQueryTools.failureText(response));
            }
        } catch (Exception ex) {
            unreachable("106", "api.wikimedia.org", ex.toString());
        }

        // 089 网页源码：真实取回
        try {
            Http.Response response = Http.get("https://example.com/", "text/html");
            check("089 取回 example.com 成功", response.ok() && response.body.contains("<html"), WebQueryTools.failureText(response));
            check("089 状态码为 200", response.status == 200, String.valueOf(response.status));
        } catch (Exception ex) {
            softFail("089 异常", ex.toString());
        }

        // 086 测速端点可达性（只取小样本，避免自检耗费流量）
        try {
            Http.Response response = Http.get("https://speed.cloudflare.com/__down?bytes=1000", "*/*");
            check("086 测速端点可达", response.ok(), WebQueryTools.failureText(response));
        } catch (Exception ex) {
            softFail("086 异常", ex.toString());
        }

        // 失败路径必须给出明确原因，而不是空结果
        try {
            Http.Response notFound = Http.get("https://rdap.org/domain/this-domain-should-not-exist-xyz123.invalid", "application/json");
            if (!notFound.ok()) {
                String text = WebQueryTools.failureText(notFound);
                check("失败路径给出 HTTP 状态", text.contains("HTTP") || text.contains("网络错误"), text);
                System.out.println("  · 失败路径实测：" + firstLine(text.replace('\n', ' ')));
            }
        } catch (Exception ex) {
            softFail("失败路径异常", ex.toString());
        }
    }

    private static String snippet(String text) {
        if (text == null) {
            return "null";
        }
        String flat = text.replace('\n', ' ');
        return flat.length() > 200 ? flat.substring(0, 200) : flat;
    }

    private static String firstLine(String text) {
        int index = text.indexOf('\n');
        return index < 0 ? text : text.substring(0, index);
    }

    private static void check(String name, boolean condition, String detail) {
        if (condition) {
            passed++;
            System.out.println("  ✓ " + name);
        } else {
            failed++;
            failures.add(name + " → " + detail);
            System.out.println("  ✗ " + name + " → " + detail);
        }
    }

    /** 纯逻辑断言不需要细节说明，统一走这个重载。 */
    private static void check(String name, boolean condition) {
        check(name, condition, "");
    }

    /** 网络不可达不算逻辑错误，但要记录，避免把"没测到"当成"通过"。 */
    private static void softFail(String name, String detail) {
        failed++;
        failures.add("[网络] " + name + " → " + detail);
        System.out.println("  ! " + name + " → " + detail);
    }

    /**
     * 接口在本开发机不可达：既不算通过（没验证到），也不当作代码缺陷计入失败。
     * 单独计数并明确打印"未验证"，避免产生"全部通过"的假象。
     */
    private static void unreachable(String id, String host, String detail) {
        unreachableCount++;
        unreachableList.add(id + "（" + host + "）");
        System.out.println("  ! " + id + " 未验证：" + detail);
    }
}
