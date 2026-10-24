package cn.xianhua.pocketkit.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 汇率换算（052）的纯计算部分：解析服务返回、换算金额、整理可展示的币种表。
 *
 * 汇率来源是 <b>Frankfurter</b>（https://frankfurter.dev ）：
 * - 开源 MIT，代码与文档随其仓库公开；无需 API 密钥；
 * - 数据是各国央行与官方机构公布的**参考汇率**，属于公开数据；
 * - Frankfurter 自己不拥有这些汇率，明确声明**不保证实时**，可能延迟、缺失或被修订，
 *   并声明混合汇率是它自己的计算而非任何机构的官方汇率（这些限制界面必须照写）。
 *
 * 用 **v2** 接口而不是 v1：v1 只覆盖 30 个币种，v2 覆盖 165 个
 * （实测 TWD、RUB、VND 等只在 v2 里有）。v2 返回数组形式：
 *   [{"date":"2026-10-12","base":"USD","quote":"CNY","rate":6.6883}]
 *
 * 本类只做纯计算与解析，不联网：网络请求由界面层在用户明确同意后发起，
 * 这样解析逻辑可以被纯 Java 测试完整覆盖。
 */
public final class FxRates {

    /** 数据来源，必须与汇率一起展示。 */
    public static final String SOURCE_NAME = "Frankfurter";
    public static final String SOURCE_URL = "https://frankfurter.dev";
    /** v2 端点。base 与 quotes 由调用方拼上。 */
    public static final String ENDPOINT = "https://api.frankfurter.dev/v2/rates";
    public static final String SOURCE_NOTE =
            "汇率来自各国央行与官方机构公布的参考汇率，属于公开数据。"
                    + "不保证实时：可能延迟、缺失或被修订。周末与节假日央行不发布，因此不会更新。"
                    + "这不是任何机构的官方定价，不能用于税务、会计或法律用途。";

    /** 一次成功的取数结果。 */
    public static final class Snapshot {
        /** 基准币种，例如 USD。 */
        public final String base;
        /** 汇率生效日期（服务返回的 date 字段），例如 2026-10-12。 */
        public final String rateDate;
        /** 币种 -> 1 基准币兑换多少该币种。 */
        public final Map<String, BigDecimal> rates;

        Snapshot(String base, String rateDate, Map<String, BigDecimal> rates) {
            this.base = base;
            this.rateDate = rateDate;
            this.rates = rates;
        }

        public boolean has(String code) {
            return rates.containsKey(code);
        }

        public BigDecimal rate(String code) {
            return rates.get(code);
        }
    }

    private FxRates() {
    }

    /** 拼请求地址。base 必填，quotes 为空表示取全部。 */
    public static String url(String base, List<String> quotes) {
        StringBuilder sb = new StringBuilder(ENDPOINT).append("?base=").append(base);
        if (quotes != null && !quotes.isEmpty()) {
            sb.append("&quotes=").append(String.join(",", quotes));
        }
        return sb.toString();
    }

    /**
     * 解析 Frankfurter v2 的响应数组。
     *
     * 任何字段缺失都视为失败：宁可报错，也不能用猜出来的汇率算钱。
     * 返回里**没有出现**的目标币种不会出现在结果中 —— 调用方必须检查，
     * 并如实告诉用户「这个币种该服务不提供」，而不是拿别的数字顶上。
     */
    public static Snapshot parseV2(String json, String requestedBase) throws ToolException {
        Object parsed = MiniJson.parse(json);

        // 先处理错误对象：v2 出错时返回 {"message":"not found"} 之类的对象
        if (parsed instanceof Map) {
            Map<String, Object> obj = MiniJson.asObject(parsed);
            Object msg = obj.get("message");
            if (msg != null) {
                throw new ToolException("服务返回错误：" + msg);
            }
            Object err = obj.get("error");
            if (err != null) {
                throw new ToolException("服务返回错误：" + err);
            }
            throw new ToolException("服务返回的是对象而不是汇率数组，格式不符合预期。");
        }
        if (!(parsed instanceof List)) {
            throw new ToolException("服务返回的不是汇率数组，格式不符合预期。");
        }
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) parsed;
        if (list.isEmpty()) {
            throw new ToolException("服务没有返回任何汇率。请确认这两个币种都受支持。");
        }

        Map<String, BigDecimal> rates = new LinkedHashMap<>();
        String date = null;
        for (Object item : list) {
            Map<String, Object> row = MiniJson.asObject(item);
            Object q = row.get("quote");
            Object r = row.get("rate");
            Object d = row.get("date");
            Object b = row.get("base");
            if (!(q instanceof String)) {
                throw new ToolException("汇率记录缺少 quote（目标币种）字段。");
            }
            if (!(r instanceof BigDecimal)) {
                throw new ToolException("汇率 " + q + " 的值不是数字，无法使用。");
            }
            if (!(d instanceof String) || ((String) d).isEmpty()) {
                throw new ToolException("汇率记录缺少 date（汇率日期）。"
                        + "没有日期就无法判断汇率是否过期，因此不展示结果。");
            }
            if (b instanceof String && requestedBase != null && !b.equals(requestedBase)) {
                throw new ToolException("服务返回的基准币种是 " + b
                        + "，与请求的 " + requestedBase + " 不一致，结果不可信。");
            }
            date = (String) d;
            rates.put((String) q, (BigDecimal) r);
        }
        String base = requestedBase;
        return new Snapshot(base == null ? "" : base, date, rates);
    }

    /** 用汇率换算金额：amount 乘以「1 基准币 = rate 目标币」。 */
    public static BigDecimal convert(BigDecimal amount, BigDecimal rate) throws ToolException {
        if (amount == null || rate == null) {
            throw new ToolException("缺少金额或汇率，无法换算。");
        }
        if (rate.signum() <= 0) {
            throw new ToolException("汇率必须大于 0，服务返回的汇率不可用。");
        }
        if (amount.signum() < 0) {
            throw new ToolException("金额不能是负数。");
        }
        return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 界面上给用户选的常见币种（含中文名）。
     *
     * 这份名单是**逐个对照 v2 的 165 个币种实测筛过的**：
     * BGN（保加利亚，已改用欧元）与 HRK（克罗地亚，已改用欧元）在 v2 里不存在，已剔除。
     * 不在名单里的币种仍可手动输入代码查询 —— 名单只是常用项，不是能力上限。
     */
    public static final Map<String, String> CURRENCIES = new LinkedHashMap<>();

    static {
        CURRENCIES.put("CNY", "人民币");
        CURRENCIES.put("USD", "美元");
        CURRENCIES.put("EUR", "欧元");
        CURRENCIES.put("JPY", "日元");
        CURRENCIES.put("GBP", "英镑");
        CURRENCIES.put("HKD", "港币");
        CURRENCIES.put("TWD", "新台币");
        CURRENCIES.put("KRW", "韩元");
        CURRENCIES.put("SGD", "新加坡元");
        CURRENCIES.put("AUD", "澳元");
        CURRENCIES.put("CAD", "加元");
        CURRENCIES.put("CHF", "瑞士法郎");
        CURRENCIES.put("NZD", "新西兰元");
        CURRENCIES.put("THB", "泰铢");
        CURRENCIES.put("MYR", "马来西亚林吉特");
        CURRENCIES.put("INR", "印度卢比");
        CURRENCIES.put("IDR", "印尼盾");
        CURRENCIES.put("PHP", "菲律宾比索");
        CURRENCIES.put("VND", "越南盾");
        CURRENCIES.put("RUB", "俄罗斯卢布");
        CURRENCIES.put("BRL", "巴西雷亚尔");
        CURRENCIES.put("MXN", "墨西哥比索");
        CURRENCIES.put("ZAR", "南非兰特");
        CURRENCIES.put("TRY", "土耳其里拉");
        CURRENCIES.put("SEK", "瑞典克朗");
        CURRENCIES.put("NOK", "挪威克朗");
        CURRENCIES.put("DKK", "丹麦克朗");
        CURRENCIES.put("PLN", "波兰兹罗提");
        CURRENCIES.put("CZK", "捷克克朗");
        CURRENCIES.put("HUF", "匈牙利福林");
        CURRENCIES.put("ILS", "以色列新谢克尔");
        CURRENCIES.put("AED", "阿联酋迪拉姆");
        CURRENCIES.put("SAR", "沙特里亚尔");
        CURRENCIES.put("RON", "罗马尼亚列伊");
        CURRENCIES.put("ISK", "冰岛克朗");
    }

    /** 币种代码 -> 展示名（含中文名）。 */
    public static String label(String code) {
        String cn = CURRENCIES.get(code);
        return cn == null ? code : code + "（" + cn + "）";
    }

    /** 供界面下拉框使用的展示名数组，顺序与 {@link #codes()} 一致。 */
    public static List<String> codes() {
        return new ArrayList<>(CURRENCIES.keySet());
    }

    public static String[] labels() {
        List<String> out = new ArrayList<>();
        for (String c : CURRENCIES.keySet()) {
            out.add(label(c));
        }
        return out.toArray(new String[0]);
    }

    /** 从展示名反解币种代码。 */
    public static String codeOf(String label) {
        int cut = label.indexOf('（');
        return cut < 0 ? label : label.substring(0, cut);
    }
}
