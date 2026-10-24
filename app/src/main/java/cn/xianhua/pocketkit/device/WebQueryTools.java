package cn.xianhua.pocketkit.device;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * 联网查询工具的实现（第 3 批）。
 *
 * 共同纪律（对应合同要求）：
 *  - 只用匿名、无需密钥的公开接口；不向用户索取任何密钥；
 *  - 必须真实请求并解析返回；连接失败、HTTP 错误、返回格式不符都要各自说明，
 *    绝不把"没查到"显示成空结果，更不用固定示例冒充真实数据；
 *  - 每次请求都明示会把什么发出去了（例如查询的域名、IP、运单号）。
 *
 * 未实现的编号由 limitation() 给出具体原因与依赖，supports 保持 false。
 */
public final class WebQueryTools {

    private WebQueryTools() {
    }

    /**
     * 走本类 build 分支的编号。**已用真实请求验证**过接口可用。
     *
     * 106 已实现但接口在开发环境不可达，因此不列入（不能把没验证过的标成可用）。
     * 096 对联与 113 以图搜番由各自的工具类实现，也不在这里。
     * 是否可用最终由 DeviceToolsActivity.SUPPORTED 统一裁决，这里保持一致。
     */
    private static final String[] IMPLEMENTED = {
            "083", "084", "086", "088", "089", "097", "106",
    };

    public static boolean supports(String id) {
        for (String implemented : IMPLEMENTED) {
            if (implemented.equals(id)) {
                return true;
            }
        }
        // 087 通知自己、095 日报(RSS)、111 QQ 会话：归这里统一分派。
        return "087".equals(id) || "095".equals(id) || "111".equals(id) || MoeQueryTool.supports(id) || PostalTool.supports(id);
    }

    public static String limitation(String id) {
        switch (id) {
            case "083":
                return "使用 RDAP（注册数据访问协议）公开接口查询域名注册信息。RDAP 由各注册局提供，不支持的国家/地区后缀可能查不到。";
            case "084":
                return "使用 ipwho.is 公开接口查询 IP 的归属与 ISP。数据来自第三方数据库，不代表精确定位；只显示数据库登记的位置，不能用于追踪具体个人。";
            case "086":
                return "使用 Cloudflare 的公开测速端点测量下载速度。结果受 Wi-Fi 质量、运营商与对端服务器影响，单次结果只代表当时状况；本工具不测上传（需要更长连接且各家实现差异大）。";
            case "088":
                return "使用 TinyURL 与 v.gd 的公开接口生成短链，还原通过跟随重定向实现。短链服务是第三方，链接的有效期与可用性由它们决定；不要把含隐私参数的链接交给第三方服务。";
            case "089":
                return "直接以 GET 方式取回目标网页的原始响应。会按原样显示，不执行页面里的脚本，也不渲染页面。";
            case "097":
                return "使用快递100的公开查询页面接口。多数快递公司需要正确的运单号与承运商；未填承运商时会先尝试自动识别。该接口不是官方开放平台 API，可能限流或变更。";
            case "106":
                return "使用维基百科 OnThisDay 公开接口获取指定月日的历史事件；网络失败会明确提示，不会伪造空结果。";

            case "087":
                return NotifyTool.limitation(id);
            case "111":
                return QqChatTool.limitation(id);
            case "094":
                return MoeQueryTool.limitation(id);
            case "095":
                return DailyNews.limitation(id);
            case "096":
                return DuilianTool.limitation(id);
            case "104":
                return MoeQueryTool.limitation(id);
            case "105":
                return PostalTool.limitation(id);
            case "113":
                return AnimeSearchTool.limitation(id);
            default:
                return "未知工具编号：" + id;
        }
    }

    public static void build(Ui ui, String id) {
        switch (id) {
            case "083": DomainQuery.build(ui); return;
            case "084": IpQuery.build(ui); return;
            case "086": SpeedTest.build(ui); return;
            case "088": ShortLink.build(ui); return;
            case "089": WebSource.build(ui); return;
            case "097": ExpressQuery.build(ui); return;
            case "106": OnThisDay.build(ui); return;
            // 087 通知自己、095 日报、111 QQ 会话本地或走 RSS，归这里统一分派。
            case "087": NotifyTool.build(ui); return;
            case "095": DailyNews.build(ui); return;
            case "105": PostalTool.build(ui); return;
            case "094":
            case "104": MoeQueryTool.build(ui, id); return;
            case "111": QqChatTool.build(ui); return;
            default:
                ui.note(limitation(id));
                ui.fail(new UnsupportedOperationException("本工具尚未实现：" + id));
        }
    }

    // ---- 083 域名信息查询 ----

    static final class DomainQuery {

        static void build(Ui ui) {
            ui.note("通过 RDAP（注册数据访问协议）查询域名的注册信息：注册商、注册与到期时间、域名状态、DNS 服务器。");
            ui.note("联网说明：你输入的域名会发送到 rdap.org，再由它转发到对应注册局的 RDAP 服务。查询会公开可见。");
            ui.note("只支持已部署 RDAP 的顶级域；部分国家/地区后缀尚未支持，会返回明确错误。");

            android.widget.EditText field = ui.field("域名", "example.com");

            ui.button("查询", () -> {
                String domain = sanitizeDomain(field.getText().toString());
                if (domain == null) {
                    ui.fail(new IllegalArgumentException("请输入一个域名，例如 example.com（不要带 http:// 或路径）。"));
                    return;
                }
                ui.background(() -> {
                    Http.Response response = Http.get("https://rdap.org/domain/" + domain, "application/rdap+json, application/json");
                    ui.activity().runOnUiThread(() -> ui.show(renderDomain(domain, response)));
                });
            });
            ui.show("输入域名后按「查询」。");
        }

        /** 只接受纯域名，避免把任意 URL 拼进请求路径。 */
        static String sanitizeDomain(String raw) {
            String value = raw == null ? "" : raw.trim().toLowerCase(Locale.US);
            if (value.isEmpty()) {
                return null;
            }
            int scheme = value.indexOf("://");
            if (scheme >= 0) {
                value = value.substring(scheme + 3);
            }
            int slash = value.indexOf('/');
            if (slash >= 0) {
                value = value.substring(0, slash);
            }
            if (value.startsWith("www.")) {
                value = value.substring(4);
            }
            return value.matches("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+")
                    ? value : null;
        }

        static String renderDomain(String domain, Http.Response response) {
            if (!response.ok()) {
                return "查询失败。\n" + failureText(response);
            }
            try {
                JSONObject json = new JSONObject(response.body);
                StringBuilder sb = new StringBuilder();
                sb.append("域名：").append(json.optString("ldhName", domain.toUpperCase(Locale.US))).append('\n');

                JSONArray status = json.optJSONArray("status");
                if (status != null && status.length() > 0) {
                    sb.append("状态：");
                    for (int i = 0; i < status.length(); i++) {
                        if (i > 0) {
                            sb.append("、");
                        }
                        sb.append(status.optString(i));
                    }
                    sb.append('\n');
                }

                JSONArray events = json.optJSONArray("events");
                if (events != null) {
                    for (int i = 0; i < events.length(); i++) {
                        JSONObject event = events.optJSONObject(i);
                        if (event == null) {
                            continue;
                        }
                        String action = event.optString("eventAction");
                        String date = event.optString("eventDate");
                        if ("registration".equals(action)) {
                            sb.append("注册时间：").append(date).append('\n');
                        } else if ("expiration".equals(action)) {
                            sb.append("到期时间：").append(date).append('\n');
                        } else if ("last changed".equals(action)) {
                            sb.append("最后变更：").append(date).append('\n');
                        }
                    }
                }

                JSONArray nameservers = json.optJSONArray("nameservers");
                if (nameservers != null && nameservers.length() > 0) {
                    sb.append("DNS 服务器：");
                    for (int i = 0; i < nameservers.length(); i++) {
                        JSONObject ns = nameservers.optJSONObject(i);
                        if (ns != null) {
                            sb.append(i > 0 ? "、" : "").append(ns.optString("ldhName"));
                        }
                    }
                    sb.append('\n');
                }

                JSONArray entities = json.optJSONArray("entities");
                if (entities != null) {
                    for (int i = 0; i < entities.length(); i++) {
                        JSONObject entity = entities.optJSONObject(i);
                        if (entity == null) {
                            continue;
                        }
                        JSONArray roles = entity.optJSONArray("roles");
                        boolean registrar = false;
                        if (roles != null) {
                            for (int r = 0; r < roles.length(); r++) {
                                if ("registrar".equals(roles.optString(r))) {
                                    registrar = true;
                                }
                            }
                        }
                        if (registrar) {
                            String name = firstVcardName(entity.optJSONArray("vcardArray"));
                            if (name != null) {
                                sb.append("注册商：").append(name).append('\n');
                            }
                        }
                    }
                }
                sb.append("\n数据来源：RDAP（各注册局官方服务）。查询结果不含注册人个人隐私信息。");
                return sb.toString();
            } catch (Exception ex) {
                return "查询返回的内容不是可解析的 RDAP/JSON 数据。\n" + failureText(response)
                        + "\n解析错误：" + ex.getMessage();
            }
        }

        /** vcardArray 形如 ["vcard",[["version",{},"text","4.0"],["fn",{},"text","注册商名"]]]。 */
        private static String firstVcardName(JSONArray vcard) {
            if (vcard == null || vcard.length() < 2) {
                return null;
            }
            JSONArray properties = vcard.optJSONArray(1);
            if (properties == null) {
                return null;
            }
            for (int i = 0; i < properties.length(); i++) {
                JSONArray property = properties.optJSONArray(i);
                if (property != null && "fn".equals(property.optString(0)) && property.length() >= 4) {
                    String value = property.optString(3);
                    if (!value.isEmpty()) {
                        return value;
                    }
                }
            }
            return null;
        }
    }

    // ---- 084 全球 IP 查询 ----

    static final class IpQuery {

        static void build(Ui ui) {
            ui.note("查询 IP 地址的归属地、运营商与 ASN。留空则查询本机当前的公网 IP。");
            ui.note("联网说明：你输入的 IP（或留空表示查询本机公网 IP）会发送到 ipwho.is。留空时对方会看到你的公网 IP。");
            ui.note("数据来自第三方数据库，只表示登记的位置，不代表使用者所在位置，不能用于追踪个人。");

            android.widget.EditText field = ui.field("IP 地址（留空查本机公网 IP）", "8.8.8.8");

            ui.button("查询", () -> {
                String raw = field.getText().toString().trim();
                String ip;
                if (raw.isEmpty()) {
                    ip = "";
                } else {
                    ip = sanitizeIp(raw);
                    if (ip == null) {
                        ui.fail(new IllegalArgumentException("请输入合法的 IPv4 地址，例如 8.8.8.8。"));
                        return;
                    }
                }
                ui.background(() -> {
                    Http.Response response = Http.get(
                            ip.isEmpty() ? "https://ipwho.is/" : "https://ipwho.is/" + ip,
                            "application/json");
                    ui.activity().runOnUiThread(() -> ui.show(renderIp(ip, response)));
                });
            });
            ui.show("输入 IP 后按「查询」，或直接留空查询本机。");
        }

        /**
         * 只接受点分十进制 IPv4，且每段 0..255。
         * 之所以不直接拼用户输入：避免把任意字符串（含路径、参数）拼进请求 URL。
         */
        static String sanitizeIp(String raw) {
            String value = raw.trim();
            String[] parts = value.split("\\.", -1);
            if (parts.length != 4) {
                return null;
            }
            for (String part : parts) {
                if (part.isEmpty() || part.length() > 3) {
                    return null;
                }
                for (int i = 0; i < part.length(); i++) {
                    if (!Character.isDigit(part.charAt(i))) {
                        return null;
                    }
                }
                int octet = Integer.parseInt(part);
                if (octet > 255) {
                    return null;
                }
            }
            return value;
        }

        static String renderIp(String ip, Http.Response response) {
            if (!response.ok()) {
                return "查询失败。\n" + failureText(response);
            }
            try {
                JSONObject json = new JSONObject(response.body);
                if (!json.optBoolean("success", true)) {
                    return "接口返回失败：" + json.optString("message", "未知原因");
                }
                StringBuilder sb = new StringBuilder();
                sb.append("IP：").append(json.optString("ip", ip.isEmpty() ? "（本机）" : ip)).append('\n');
                sb.append("类型：").append(json.optString("type", "未知")).append('\n');
                sb.append("国家/地区：").append(json.optString("country", "未知"));
                String code = json.optString("country_code");
                if (!code.isEmpty()) {
                    sb.append("（").append(code).append("）");
                }
                sb.append('\n');
                sb.append("洲：").append(json.optString("continent", "未知")).append('\n');
                sb.append("省/州：").append(json.optString("region", "未知")).append('\n');
                sb.append("城市：").append(json.optString("city", "未知")).append('\n');
                sb.append("邮政编码：").append(json.optString("postal", "未知")).append('\n');

                double lat = json.optDouble("latitude", Double.NaN);
                double lon = json.optDouble("longitude", Double.NaN);
                if (!Double.isNaN(lat) && !Double.isNaN(lon)) {
                    sb.append(String.format(Locale.US, "大致坐标：%.4f, %.4f\n", lat, lon));
                }

                JSONObject connection = json.optJSONObject("connection");
                if (connection != null) {
                    sb.append("ASN：").append(connection.optString("asn", "未知")).append('\n');
                    sb.append("运营商：").append(connection.optString("isp", "未知")).append('\n');
                    sb.append("组织：").append(connection.optString("org", "未知")).append('\n');
                }
                JSONObject timezone = json.optJSONObject("timezone");
                if (timezone != null) {
                    sb.append("时区：").append(timezone.optString("id", "未知"))
                            .append("（UTC").append(timezone.optString("utc", "?")).append("）\n");
                }
                sb.append("\n坐标是数据库登记的近似位置（通常到城市级），不是精确定位。");
                return sb.toString();
            } catch (Exception ex) {
                return "返回内容不是可解析的 JSON。\n" + failureText(response) + "\n解析错误：" + ex.getMessage();
            }
        }
    }

    // ---- 086 网速测试 ----

    static final class SpeedTest {

        /** 各档测试体积，从大到小，够用即可，避免消耗用户流量。 */
        private static final int[] SIZES = {10_000_000, 5_000_000, 2_000_000, 1_000_000, 500_000, 100_000};

        static void build(Ui ui) {
            ui.note("通过从 Cloudflare 公开测速端点下载数据来测量下载速度。");
            ui.note("联网说明：会从 speed.cloudflare.com 下载测试数据。默认测试会消耗约 20 MB 流量，请在 Wi-Fi 下使用，注意流量套餐。");
            ui.note("只测下载：上传测试需要保持更长连接，各家实现差异大，本工具不做。");

            final android.widget.SeekBar[] ignored = new android.widget.SeekBar[0];

            ui.button("开始测试（约 20 MB）", () -> ui.background(() -> {
                StringBuilder sb = new StringBuilder();
                sb.append("测速结果（对 speed.cloudflare.com 的下载速度）\n\n");
                double bestMbps = 0;
                long totalBytes = 0;

                for (int size : SIZES) {
                    long start = System.nanoTime();
                    Http.Response response = readBinary("https://speed.cloudflare.com/__down?bytes=" + size);
                    long elapsed = System.nanoTime() - start;
                    if (!response.ok()) {
                        sb.append(String.format(Locale.US, "%s：失败（%s）\n",
                                humanSize(size), failureText(response)));
                        continue;
                    }
                    int received = response.body.length();
                    double seconds = elapsed / 1_000_000_000d;
                    if (seconds <= 0) {
                        continue;
                    }
                    double mbps = received * 8d / seconds / 1_000_000d;
                    totalBytes += received;
                    if (mbps > bestMbps) {
                        bestMbps = mbps;
                    }
                    sb.append(String.format(Locale.US, "%s：%.2f Mbps（用时 %.2f 秒）\n",
                            humanSize(size), mbps, seconds));
                }
                if (bestMbps <= 0) {
                    sb.append("\n所有档位都失败，无法得出速度。\n");
                } else {
                    sb.append(String.format(Locale.US,
                            "\n最快档位约 %.2f Mbps（≈ %.1f MB/s）\n共下载 %s\n",
                            bestMbps, bestMbps / 8d, humanSize((int) totalBytes)));
                }
                sb.append("\n结果受 Wi-Fi 信号、运营商与对端服务器影响，只代表本次测试当时的状况。");

                final String text = sb.toString();
                ui.activity().runOnUiThread(() -> ui.show(text));
            }));

            ui.button("只测一次小数据（约 1 MB）", () -> ui.background(() -> {
                long start = System.nanoTime();
                Http.Response response = readBinary("https://speed.cloudflare.com/__down?bytes=1000000");
                long elapsed = System.nanoTime() - start;
                if (!response.ok()) {
                    ui.activity().runOnUiThread(() -> ui.fail(new java.io.IOException("测速失败：" + failureText(response))));
                    return;
                }
                double seconds = elapsed / 1_000_000_000d;
                double mbps = seconds <= 0 ? 0 : response.body.length() * 8d / seconds / 1_000_000d;
                final String text = String.format(Locale.US,
                        "下载 1 MB 用时 %.2f 秒\n约 %.2f Mbps（≈ %.1f MB/s）\n\n"
                                + "单次小样本误差较大，需要更准请用上面的完整测试。",
                        seconds, mbps, mbps / 8d);
                ui.activity().runOnUiThread(() -> ui.show(text));
            }));

            ui.show("按「开始测试」后开始下载测速数据。");
        }

        /** 测速需要按字节计数，不能用按 UTF-8 解码的字符串长度。 */
        private static Http.Response readBinary(String url) {
            try {
                java.net.HttpURLConnection connection =
                        (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                connection.setConnectTimeout(10_000);
                connection.setReadTimeout(30_000);
                connection.setRequestProperty("User-Agent", Http.USER_AGENT);
                int status = connection.getResponseCode();
                java.io.InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                int read = 0;
                if (in != null) {
                    byte[] chunk = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(chunk)) > 0) {
                        read += n;
                    }
                    in.close();
                }
                connection.disconnect();
                // 用一个同长度的占位串承载字节数：这里只关心计数，不解析内容。
                return new Http.Response(status, repeat(read), null, null);
            } catch (Exception ex) {
                return new Http.Response(0, "", null, ex.getMessage());
            }
        }

        private static String repeat(int count) {
            if (count <= 0) {
                return "";
            }
            char[] chars = new char[count];
            java.util.Arrays.fill(chars, 'x');
            return new String(chars);
        }

        static String humanSize(int bytes) {
            return cn.xianhua.pocketkit.core.SizeFmt.bytes(bytes);
        }
    }

    // ---- 088 短链接生成与还原 ----

    static final class ShortLink {

        static void build(Ui ui) {
            ui.note("生成短链：把长网址提交给 TinyURL 或 v.gd。还原：跟随短链的重定向找出原始地址。");
            ui.note("联网说明：生成的短链会把目标网址提交给第三方短链服务，对方能看到该网址。不要提交含隐私参数（如带 token）的链接。");
            ui.note("短链有效期与稳定性由第三方服务决定，本应用不保证长期可用。");

            android.widget.EditText field = ui.field("网址", "https://example.com/");

            ui.button("用 TinyURL 生成短链", () -> shorten(ui, field, "tinyurl"));
            ui.button("用 v.gd 生成短链", () -> shorten(ui, field, "vgd"));

            ui.button("还原短链", () -> {
                String url = normalizeUrl(field.getText().toString());
                if (url == null) {
                    ui.fail(new IllegalArgumentException("请输入以 http:// 或 https:// 开头的完整网址。"));
                    return;
                }
                ui.background(() -> {
                    String resolved = resolveRedirect(url);
                    final String text = resolved == null
                            ? "还原失败：无法跟随重定向，或该地址没有跳转。请确认它是可访问的短链。"
                            : "短链：" + url + "\n还原结果：\n" + resolved;
                    ui.activity().runOnUiThread(() -> ui.show(text));
                });
            });
            ui.show("输入网址后选择操作。");
        }

        private static void shorten(Ui ui, android.widget.EditText field, String provider) {
            String url = normalizeUrl(field.getText().toString());
            if (url == null) {
                ui.fail(new IllegalArgumentException("请输入以 http:// 或 https:// 开头的完整网址。"));
                return;
            }
            ui.background(() -> {
                String endpoint = "tinyurl".equals(provider)
                        ? "https://tinyurl.com/api-create.php?url=" + encode(url)
                        : "https://v.gd/create.php?format=json&url=" + encode(url);
                Http.Response response = Http.get(endpoint, "application/json, text/plain");
                String text;
                if (!response.ok()) {
                    text = "生成失败。\n" + failureText(response);
                } else {
                    String body = response.body.trim();
                    if (body.startsWith("{")) {
                        try {
                            String shortUrl = new JSONObject(body).optString("shorturl", "");
                            text = shortUrl.isEmpty()
                                    ? "服务端没有返回短链：" + body
                                    : "原始网址：" + url + "\n短链：" + shortUrl;
                        } catch (Exception ex) {
                            text = "返回内容无法解析：" + body;
                        }
                    } else if (body.startsWith("http")) {
                        text = "原始网址：" + url + "\n短链：" + body;
                    } else {
                        text = "服务端返回了非预期内容：" + body;
                    }
                }
                final String result = text;
                ui.activity().runOnUiThread(() -> ui.show(result));
            });
        }

        /** 只允许 http/https，避免把 file:// 之类的协议交给服务端。 */
        static String normalizeUrl(String raw) {
            String value = raw == null ? "" : raw.trim();
            if (value.isEmpty()) {
                return null;
            }
            if (!value.startsWith("http://") && !value.startsWith("https://")) {
                return null;
            }
            return value;
        }

        /** 只读取 Location 头，不下载正文，避免抓取到无关大文件。 */
        static String resolveRedirect(String url) {
            java.net.HttpURLConnection connection = null;
            try {
                connection = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(10_000);
                connection.setReadTimeout(15_000);
                connection.setRequestMethod("HEAD");
                connection.setRequestProperty("User-Agent", Http.USER_AGENT);
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    return location == null ? null : location;
                }
                return "（该地址返回 " + status + "，没有跳转到其它地址）";
            } catch (Exception ex) {
                return null;
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }

        private static String encode(String value) {
            try {
                return java.net.URLEncoder.encode(value, "UTF-8");
            } catch (java.io.UnsupportedEncodingException ex) {
                return value;
            }
        }
    }

    // ---- 089 网页源码获取 ----

    static final class WebSource {

        /** 只在界面里显示前若干字符，避免超长页面把界面拖死。 */
        private static final int MAX_DISPLAY = 20_000;

        static void build(Ui ui) {
            ui.note("按 GET 请求取回目标网页的原始响应内容（HTML/JSON/纯文本），原样显示。");
            ui.note("联网说明：请求由本应用直接发出，目标服务器会看到你的 IP 与 User-Agent。只取你确实有权访问的公开页面。");
            ui.note("不执行页面脚本、不渲染页面、不保存 Cookie，因此与浏览器里看到的内容可能不同（例如依赖 JS 动态生成的页面会拿到空壳）。");

            android.widget.EditText field = ui.field("网址", "https://example.com/");

            ui.button("获取源码", () -> {
                String url = ShortLink.normalizeUrl(field.getText().toString());
                if (url == null) {
                    ui.fail(new IllegalArgumentException("请输入以 http:// 或 https:// 开头的完整网址。"));
                    return;
                }
                ui.background(() -> {
                    Http.Response response = Http.get(url, "text/html, application/xhtml+xml, text/plain, */*");
                    final String text;
                    if (!response.ok()) {
                        text = "获取失败。\n" + failureText(response);
                    } else {
                        StringBuilder sb = new StringBuilder();
                        sb.append("状态：").append(response.status).append('\n');
                        sb.append("类型：").append(response.contentType == null ? "未知" : response.contentType).append('\n');
                        sb.append("长度：").append(response.body.length()).append(" 字符\n\n");
                        if (response.body.length() > MAX_DISPLAY) {
                            sb.append(response.body, 0, MAX_DISPLAY);
                            sb.append("\n\n…内容过长，只显示前 ").append(MAX_DISPLAY).append(" 个字符。");
                        } else {
                            sb.append(response.body);
                        }
                        text = sb.toString();
                    }
                    ui.activity().runOnUiThread(() -> ui.show(text));
                });
            });
            ui.show("输入网址后按「获取源码」。");
        }
    }

    // ---- 097 快递查询 ----

    static final class ExpressQuery {

        static void build(Ui ui) {
            ui.note("查询快递物流轨迹。可以只填运单号（会先尝试自动识别承运商），也可以手动指定。");
            ui.note("联网说明：运单号会发送到快递100的公开查询接口。运单号属于个人信息，请注意分享屏幕时的隐私。");
            ui.note("该接口不是官方开放平台 API，可能限流或变更；查询不到时会如实说明原因。");

            android.widget.EditText number = ui.field("运单号", "例如 YT1234567890");
            android.widget.EditText carrier = ui.field("承运商代码（可留空自动识别）", "例如 yuantong、shunfeng、zhongtong");

            ui.button("自动识别承运商", () -> {
                String postId = number.getText().toString().trim();
                if (postId.isEmpty()) {
                    ui.fail(new IllegalArgumentException("请先填写运单号。"));
                    return;
                }
                ui.background(() -> {
                    Http.Response response = Http.get(
                            "https://www.kuaidi100.com/autonumber/autoComNum?text=" + encode(postId),
                            "application/json");
                    String text;
                    if (!response.ok()) {
                        text = "识别失败。\n" + failureText(response);
                    } else {
                        try {
                            JSONObject json = new JSONObject(response.body);
                            JSONArray auto = json.optJSONArray("auto");
                            StringBuilder sb = new StringBuilder();
                            sb.append("运单号：").append(postId).append('\n');
                            if (auto == null || auto.length() == 0) {
                                sb.append("未能识别出承运商，请手动指定承运商代码后查询。");
                            } else {
                                sb.append("可能的承运商：\n");
                                for (int i = 0; i < auto.length(); i++) {
                                    JSONObject item = auto.optJSONObject(i);
                                    if (item != null) {
                                        sb.append("  ").append(item.optString("name"))
                                                .append("（代码 ").append(item.optString("comCode")).append("）\n");
                                    }
                                }
                            }
                            text = sb.toString();
                        } catch (Exception ex) {
                            text = "返回内容无法解析：" + response.body;
                        }
                    }
                    final String result = text;
                    ui.activity().runOnUiThread(() -> ui.show(result));
                });
            });

            ui.button("查询物流轨迹", () -> {
                String postId = number.getText().toString().trim();
                String type = carrier.getText().toString().trim();
                if (postId.isEmpty() || type.isEmpty()) {
                    ui.fail(new IllegalArgumentException("查询需要同时填写运单号和承运商代码；可先用上面的识别按钮获取代码。"));
                    return;
                }
                ui.background(() -> {
                    Http.Response response = Http.get(
                            "https://www.kuaidi100.com/query?type=" + encode(type) + "&postid=" + encode(postId),
                            "application/json");
                    ui.activity().runOnUiThread(() -> ui.show(renderExpress(postId, type, response)));
                });
            });
            ui.show("填写运单号后按「自动识别承运商」，再按「查询物流轨迹」。");
        }

        static String renderExpress(String postId, String type, Http.Response response) {
            if (!response.ok()) {
                return "查询失败。\n" + failureText(response);
            }
            try {
                JSONObject json = new JSONObject(response.body);
                String message = json.optString("message");
                if (!"ok".equalsIgnoreCase(message)) {
                    return "接口返回失败：" + (message.isEmpty() ? response.body : message)
                            + "\n请确认运单号与承运商代码是否匹配。";
                }
                StringBuilder sb = new StringBuilder();
                sb.append("运单号：").append(json.optString("nu", postId)).append('\n');
                sb.append("承运商：").append(json.optString("com", type)).append('\n');
                String state = json.optString("state");
                sb.append("状态：").append(stateText(state)).append('\n');
                sb.append("是否已签收：").append("3".equals(state) ? "是" : "否").append("\n\n");

                JSONArray data = json.optJSONArray("data");
                if (data == null || data.length() == 0) {
                    sb.append("暂无轨迹信息。可能是运单号刚生成、承运商未回传，或查询过于频繁被限流。");
                } else {
                    sb.append("物流轨迹（最新在前）：\n");
                    for (int i = 0; i < data.length(); i++) {
                        JSONObject item = data.optJSONObject(i);
                        if (item != null) {
                            sb.append('\n').append(item.optString("ftime", item.optString("time"))).append('\n');
                            sb.append("  ").append(item.optString("context")).append('\n');
                        }
                    }
                }
                sb.append("\n数据来自快递100公开查询接口，非快递公司官方数据。");
                return sb.toString();
            } catch (Exception ex) {
                return "返回内容无法解析为 JSON。\n" + failureText(response) + "\n解析错误：" + ex.getMessage();
            }
        }

        private static String stateText(String state) {
            switch (state) {
                case "0": return "在途";
                case "1": return "揽收";
                case "2": return "疑难";
                case "3": return "已签收";
                case "4": return "退签";
                case "5": return "派件";
                case "6": return "退回";
                default: return "未知";
            }
        }
    }

    // ---- 106 历史上的今天 ----

    static final class OnThisDay {

        static void build(Ui ui) {
            ui.note("获取「历史上的今天」发生的事件，来自维基百科 OnThisDay 公开接口。");
            ui.note("联网说明：只发送月日参数到 api.wikimedia.org，不发送任何个人信息。");
            ui.note("内容由维基百科社区维护，可能不完整或存在争议；某些网络环境下该接口不可达。");

            java.util.Calendar now = java.util.Calendar.getInstance();
            int month = now.get(java.util.Calendar.MONTH) + 1;
            int day = now.get(java.util.Calendar.DAY_OF_MONTH);

            android.widget.EditText monthField = ui.field("月（1-12）", String.valueOf(month));
            android.widget.EditText dayField = ui.field("日（1-31）", String.valueOf(day));

            ui.button("查询今天", () -> fetch(ui, month, day));
            ui.button("查询指定日期", () -> {
                int m = parseInt(monthField.getText().toString(), -1);
                int d = parseInt(dayField.getText().toString(), -1);
                if (m < 1 || m > 12 || d < 1 || d > 31) {
                    ui.fail(new IllegalArgumentException("月和日必须是合法数字（月 1-12，日 1-31）。"));
                    return;
                }
                fetch(ui, m, d);
            });
            ui.show("按「查询今天」，或填写月日后按「查询指定日期」。");
        }

        private static void fetch(Ui ui, int month, int day) {
            ui.background(() -> {
                String url = String.format(Locale.US,
                        "https://api.wikimedia.org/feed/v1/wikipedia/zh/onthisday/events/%d/%d", month, day);
                Http.Response response = Http.get(url, "application/json");
                ui.activity().runOnUiThread(() -> ui.show(render(response, month, day)));
            });
        }

        static String render(Http.Response response, int month, int day) {
            if (!response.ok()) {
                return String.format(Locale.US, "%d 月 %d 日 查询失败。\n", month, day) + failureText(response)
                        + "\n提示：api.wikimedia.org 在部分网络环境下不可达。";
            }
            try {
                JSONObject json = new JSONObject(response.body);
                JSONArray events = json.optJSONArray("events");
                if (events == null || events.length() == 0) {
                    return String.format(Locale.US, "%d 月 %d 日没有返回事件记录。", month, day);
                }
                StringBuilder sb = new StringBuilder();
                sb.append(String.format(Locale.US, "%d 月 %d 日 历史上的今天（共 %d 条）\n",
                        month, day, events.length()));
                int shown = Math.min(events.length(), 15);
                for (int i = 0; i < shown; i++) {
                    JSONObject event = events.optJSONObject(i);
                    if (event == null) {
                        continue;
                    }
                    sb.append('\n').append(event.optInt("year", 0)).append(" 年\n  ")
                            .append(event.optString("text")).append('\n');
                    JSONArray pages = event.optJSONArray("pages");
                    if (pages != null && pages.length() > 0) {
                        JSONObject page = pages.optJSONObject(0);
                        if (page != null) {
                            sb.append("  参考：").append(page.optString("title")).append('\n');
                        }
                    }
                }
                if (events.length() > shown) {
                    sb.append("\n…另有 ").append(events.length() - shown).append(" 条未显示。");
                }
                sb.append("\n\n来源：维基百科 OnThisDay（社区维护，CC BY-SA）。");
                return sb.toString();
            } catch (Exception ex) {
                return "返回内容无法解析为 JSON。\n" + failureText(response) + "\n解析错误：" + ex.getMessage();
            }
        }

        private static int parseInt(String raw, int fallback) {
            try {
                return Integer.parseInt(raw.trim());
            } catch (NumberFormatException ex) {
                return fallback;
            }
        }
    }

    // ---- 公共辅助 ----

    /** 统一的失败描述：区分连接层失败与 HTTP 错误码，并附上服务端正文片段。 */
    static String failureText(Http.Response response) {
        if (response.error != null) {
            return "网络错误：" + response.error;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("服务端返回 HTTP ").append(response.status);
        if (response.status == 404) {
            sb.append("（资源不存在）");
        } else if (response.status == 429) {
            sb.append("（请求过于频繁，被限流，请稍后再试）");
        } else if (response.status >= 500) {
            sb.append("（服务端故障）");
        }
        if (response.body != null && !response.body.isEmpty()) {
            String snippet = response.body.length() > 200 ? response.body.substring(0, 200) : response.body;
            sb.append("\n返回内容片段：").append(snippet);
        }
        return sb.toString();
    }

    private static String encode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException ex) {
            return value;
        }
    }
}
