package cn.xianhua.pocketkit.reading;

import com.jayway.jsonpath.JsonPath;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.ClassShutter;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextAction;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small, deliberately incomplete Legado-style rule evaluator.
 *
 * <p>Semantics were independently reimplemented after checking the GPL-3.0 Legado snapshot
 * huajideshutiao/legado@d0c42f32426d01741dba0122982554f2934209e7, notably AnalyzeRule,
 * AnalyzeByJSoup/JSONPath/XPath and AnalyzeUrl. No Kotlin/Android/Room code is copied here.
 *
 * <p>The script path runs in a restricted Rhino scope and is additionally screened by a static
 * allowlist ({@link #validateScript}). That screening is a hardening layer, not a sandbox: files,
 * sockets, reflection and class loading are never exposed, but the instruction observer is a best
 * effort against hostile loops rather than a formal proof of termination.
 */
public final class RuleEvaluator {
    private static final Pattern TEMPLATE = Pattern.compile("\\{\\{([\\s\\S]*?)}}", Pattern.MULTILINE);
    private static final Pattern JS_BLOCK = Pattern.compile("(?is)<js>(.*?)</js>|@js:(.*)$");
    private static final Pattern XPATH_SUFFIX =
            Pattern.compile("^(.*)/(?:@([\\w:.-]+)|text\\s*\\(\\s*\\))$");
    private static final int MAX_SCRIPT_SOURCE = 128 * 1024;
    // Manga decoders legitimately run long: the AES/obfuscation routines that several sources
    // inline are interpreted (optimization level -1) and walk kilobyte payloads character by
    // character. The budget stays finite so a hostile infinite loop still dies quickly.
    private static final long SCRIPT_LIMIT_NANOS = 8_000_000_000L;
    // Interpreting a pure-JS AES decoder walks a few thousand ciphertext bytes byte by byte;
    // that legitimately costs hundreds of millions of bytecode instructions. The wall-clock
    // deadline above is the real guard against hostile loops; this is only a backstop.
    private static final int SCRIPT_INSTRUCTION_LIMIT = 400_000_000;
    private static final Pattern PUT_RULE = Pattern.compile("@put:\\s*\\{([\\s\\S]*?)\\}", Pattern.CASE_INSENSITIVE);
    private static final Pattern GET_RULE = Pattern.compile("@get:\\s*\\{\\s*([^{}\\s]+)\\s*\\}", Pattern.CASE_INSENSITIVE);
    private static final Pattern INLINE_EXPR = Pattern.compile("\\{\\{@@([\\s\\S]*?)\\}\\}");
    private static final java.util.concurrent.ConcurrentHashMap<String, String> PUT_VARS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Constructs that need a real engine / class access / unbounded allocation. */
    private static final Pattern[] SCRIPT_DENY = {
            Pattern.compile("\\beval\\s*\\("),
            Pattern.compile("\\bFunction\\s*\\("),
            Pattern.compile("\\bnew\\s+(?:java|javax|org|com)\\s*\\."),
            Pattern.compile("\\.repeat\\s*\\(\\s*\\d{5,}\\s*\\)"),
            Pattern.compile("\\bArray\\s*\\(\\s*\\d+\\s*\\)\\s*\\.\\s*join\\s*\\("),
            Pattern.compile("\\bwhile\\s*\\(\\s*(?:true|1)\\s*\\)"),
            Pattern.compile("\\bfor\\s*\\(\\s*;\\s*;"),
            Pattern.compile("\\b(?:importClass|importPackage|JavaAdapter|JavaImporter)\\b"),
            // Class/reflection access is refused; Packages survives only behind the class whitelist below.
            Pattern.compile("\\b(?:getClass|Reflection|ClassLoader)\\b"),
            Pattern.compile("\\bClass\\s*\\.\\s*forName\\b"),
            // The class whitelist already hides these at runtime; refuse them statically too so a
            // hostile source never even reaches Rhino with an escape attempt.
            Pattern.compile("\\bPackages\\s*\\.\\s*(?:java|javax|org|com)\\s*\\.\\s*(?:(?:lang|io|net|nio|reflect)\\s*\\.\\s*)?"
                    + "(?:System|Runtime|ProcessBuilder|Thread|Class|ClassLoader|File|Files|Paths|Socket|URL|URI|Method|Field)\\b"),
            Pattern.compile("\\bXMLHttpRequest\\b"),
            Pattern.compile("\\b(?:readFile|readAsText|openConnection|Runtime|ProcessBuilder)\\b"),
    };

    private final Object content;
    private final String baseUrl;
    private final String jsLib;
    private final String sourceKey;

    public RuleEvaluator(String content, String baseUrl) {
        this((Object) content, baseUrl, "", "");
    }

    public RuleEvaluator(Object content, String baseUrl, String jsLib, String sourceKey) {
        this.content = content == null ? "" : content;
        this.baseUrl = baseUrl == null ? "" : baseUrl;
        this.jsLib = jsLib == null ? "" : jsLib;
        this.sourceKey = sourceKey == null ? "" : sourceKey;
    }

    RuleEvaluator(Object content, String baseUrl) {
        this(content, baseUrl, "", "");
    }

    public List<Object> nodes(String rule) throws Exception {
        if (blank(rule)) return List.of(content);
        RuleParts parts = RuleParts.parse(preprocess(rule.trim()));
        List<Object> result = evaluateNodes(parts.rule);
        if (!parts.regex.isEmpty()) {
            ArrayList<Object> replaced = new ArrayList<>(result.size());
            for (Object value : result) replaced.add(replace(String.valueOf(value), parts));
            return replaced;
        }
        return result;
    }

    public List<String> strings(String rule) throws Exception {
        if (blank(rule)) return List.of();
        RuleParts parts = RuleParts.parse(preprocess(rule.trim()));
        List<Object> values;
        // A rule that already resolved to an absolute URL (usually via a {{...}} template) is a value,
        // not a CSS selector: several book sources build bookUrl this way.
        if (isLiteralUrl(parts.rule)) values = List.of(parts.rule);
        else if (isJsonRule(parts.rule)) values = flatten(json(parts.rule));
        else if (isXPathRule(parts.rule)) values = xpath(parts.rule);
        else if (isRegexRule(parts.rule)) values = new ArrayList<>(regex(parts.rule));
        else if (containsJs(parts.rule)) values = List.of(scriptRule(parts.rule));
        else values = new ArrayList<>(cssValues(parts.rule));

        ArrayList<String> result = new ArrayList<>();
        for (Object value : values) {
            String text = scalar(value);
            if (!parts.regex.isEmpty()) text = replace(text, parts);
            if (!text.isEmpty()) result.add(text);
        }
        return result;
    }

    public String string(String rule) throws Exception {
        List<String> values = strings(rule);
        return values.isEmpty() ? "" : String.join("\n", values);
    }

    /** Extracts image URLs from a manga chapter rule (either HTML <img> tags, JSON arrays, or newline URLs). */
    public List<String> images(String rule) throws Exception {
        List<Object> values = blank(rule) ? List.of(content) : nodes(rule);
        ArrayList<String> urls = new ArrayList<>();
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (Object value : values) {
            if (value instanceof Element) {
                Element el = (Element) value;
                if ("img".equalsIgnoreCase(el.tagName())) {
                    addImgFromElement(el, seen);
                } else {
                    int before = seen.size();
                    for (Element img : el.select("img")) addImgFromElement(img, seen);
                    // Some sources put a JSON array or newline list inside a container instead of <img>.
                    if (seen.size() == before) extractImgUrlsFromText(el.wholeText(), seen);
                }
            } else {
                String raw = scalar(value);
                extractImgUrlsFromText(raw, seen);
            }
        }
        if (seen.isEmpty() && !blank(rule)) {
            for (String raw : strings(rule)) extractImgUrlsFromText(raw, seen);
        }
        urls.addAll(seen);
        return urls;
    }

    private void addImgFromElement(Element img, java.util.Set<String> seen) {
        for (String attr : new String[]{"data-original", "data-src", "data-echo", "data-lazy-src", "src"}) {
            String val = img.absUrl(attr);
            if (val.isEmpty()) val = img.attr(attr).trim();
            if (!val.isEmpty() && !val.startsWith("data:")) {
                try {
                    String resolved = absolute(val);
                    if (resolved.startsWith("http://") || resolved.startsWith("https://")) {
                        seen.add(resolved);
                        return;
                    }
                } catch (IOException ignored) { }
            }
        }
    }

    private void extractImgUrlsFromText(String raw, java.util.Set<String> seen) {
        if (raw == null || raw.isBlank()) return;
        if (raw.contains("<img") || raw.contains("<IMG")) {
            Document doc = Jsoup.parseBodyFragment(raw, baseUrl);
            for (Element img : doc.select("img")) addImgFromElement(img, seen);
            if (!seen.isEmpty()) return;
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("[")) {
            try {
                JSONArray arr = new JSONArray(trimmed);
                for (int i = 0; i < arr.length(); i++) {
                    Object item = arr.opt(i);
                    String candidate = item instanceof JSONObject
                            ? ((JSONObject) item).optString("url", ((JSONObject) item).optString("src", ""))
                            : String.valueOf(item);
                    try {
                        String resolved = absolute(candidate);
                        if (resolved.startsWith("http://") || resolved.startsWith("https://")) seen.add(resolved);
                    } catch (IOException ignored) { }
                }
                if (!seen.isEmpty()) return;
            } catch (Exception ignored) { }
        }
        for (String line : trimmed.split("[\\r\\n]+")) {
            String item = line.trim();
            if (item.isEmpty()) continue;
            try {
                String resolved = absolute(item);
                if (resolved.startsWith("http://") || resolved.startsWith("https://")) seen.add(resolved);
            } catch (IOException ignored) { }
        }
    }

    private String preprocess(String raw) throws Exception {
        if (raw == null || raw.isEmpty()) return "";
        String out = raw;
        Matcher putMatcher = PUT_RULE.matcher(out);
        StringBuffer sb = new StringBuffer();
        while (putMatcher.find()) {
            String body = putMatcher.group(1).trim();
            try {
                JSONObject map = new JSONObject("{" + body + "}");
                java.util.Iterator<String> it = map.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    String expr = map.optString(k, "");
                    String val = new RuleEvaluator(content, baseUrl, jsLib, sourceKey).string(expr);
                    PUT_VARS.put(varKey(sourceKey, k), val);
                }
            } catch (Exception ignored) { }
            putMatcher.appendReplacement(sb, "");
        }
        putMatcher.appendTail(sb);
        out = sb.toString().trim();

        Matcher getMatcher = GET_RULE.matcher(out);
        StringBuffer getSb = new StringBuffer();
        while (getMatcher.find()) {
            String k = getMatcher.group(1).trim();
            String val = PUT_VARS.getOrDefault(varKey(sourceKey, k), PUT_VARS.getOrDefault(varKey("", k), ""));
            getMatcher.appendReplacement(getSb, Matcher.quoteReplacement(val));
        }
        getMatcher.appendTail(getSb);
        out = getSb.toString();

        Matcher inlineMatcher = INLINE_EXPR.matcher(out);
        StringBuffer inlineSb = new StringBuffer();
        while (inlineMatcher.find()) {
            String inner = inlineMatcher.group(1).trim();
            String val = new RuleEvaluator(content, baseUrl, jsLib, sourceKey).string(inner);
            inlineMatcher.appendReplacement(inlineSb, Matcher.quoteReplacement(val));
        }
        inlineMatcher.appendTail(inlineSb);
        out = inlineSb.toString();

        // Legado also allows {{rule}} inside a rule string: the braces hold a sub-rule evaluated
        // against the current node (e.g. {{$.path_word}}). Bare template variables such as
        // {{key}}/{{page}} belong to the request layer and are left untouched here.
        Matcher templateMatcher = TEMPLATE.matcher(out);
        StringBuffer templateSb = new StringBuffer();
        while (templateMatcher.find()) {
            String expr = templateMatcher.group(1).trim();
            String replacement = templateMatcher.group(0);
            if (!expr.isEmpty() && !expr.startsWith("@@") && !TEMPLATE_VARS.contains(expr.toLowerCase(Locale.ROOT))) {
                try {
                    String val = new RuleEvaluator(content, baseUrl, jsLib, sourceKey).string(expr);
                    if (!val.isEmpty()) replacement = val;
                } catch (Exception ignored) { }
            }
            templateMatcher.appendReplacement(templateSb, Matcher.quoteReplacement(replacement));
        }
        templateMatcher.appendTail(templateSb);
        return templateSb.toString();
    }

    private static final java.util.Set<String> TEMPLATE_VARS =
            java.util.Set.of("key", "query", "page", "keyword", "searchkey");

    private static String varKey(String sourceKey, String name) {
        return (sourceKey == null ? "" : sourceKey) + "::" + name;
    }

    /** Extracts readable plain text while retaining block and line-break boundaries. */
    public String plainText(String rule) throws Exception {
        List<Object> values = blank(rule) ? List.of(content) : nodes(rule);
        ArrayList<String> chunks = new ArrayList<>();
        for (Object value : values) {
            String text;
            if (value instanceof Element) text = blockPlainText((Element) value);
            else {
                String raw = scalar(value);
                text = raw.indexOf('<') >= 0 ? Jsoup.parseBodyFragment(raw).wholeText() : raw;
            }
            text = cleanText(text);
            if (!text.isEmpty()) chunks.add(text);
        }
        if (chunks.isEmpty() && !blank(rule)) {
            for (String value : strings(rule)) {
                String cleaned = cleanText(value);
                if (!cleaned.isEmpty()) chunks.add(cleaned);
            }
        }
        return String.join("\n\n", chunks);
    }

    public String absolute(String value) throws IOException {
        if (blank(value)) return "";
        value = stripUrlOptions(value.trim());
        // A multi-match next-page rule can yield several whitespace-separated URLs; a real URL
        // never contains a raw space, so the first token is the address the rule meant.
        int space = -1;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') { space = i; break; }
        }
        if (space > 0) value = value.substring(0, space);
        try {
            URI uri = URI.create(value.trim());
            if (uri.isAbsolute()) return uri.toString();
            if (baseUrl.isEmpty()) throw new IOException("相对 URL 缺少基准地址：" + value);
            return URI.create(baseUrl).resolve(uri).toString();
        } catch (IllegalArgumentException e) {
            throw new IOException("规则生成了无效 URL：" + value, e);
        }
    }

    /**
     * Legado lets a rule append request options to a URL, e.g.
     * {@code /books/1/2.html,{"webView":true}}. The option blob is not part of the address, so
     * strip it before the URL is parsed or fetched.
     */
    public static String stripUrlOptions(String value) {
        if (value == null || value.isEmpty()) return value;
        int at = value.lastIndexOf(",{");
        while (at > 0) {
            try {
                new JSONObject(value.substring(at + 1).trim());
                return value.substring(0, at).trim();
            } catch (Exception ignored) {
                at = value.lastIndexOf(",{", at - 1);
            }
        }
        return value;
    }

    static String renderTemplate(String template, Map<String, Object> variables, String baseUrl)
            throws Exception {
        return renderTemplate(template, variables, baseUrl, "", "");
    }

    static String renderTemplate(String template, Map<String, Object> variables, String baseUrl,
                                 String jsLib, String sourceKey) throws Exception {
        if (template == null) return "";
        Matcher matcher = TEMPLATE.matcher(template);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String expression = matcher.group(1).trim();
            Object direct = variables.get(expression);
            Object value = direct != null ? direct : evaluateJs(expression, variables, baseUrl, null, jsLib, sourceKey);
            matcher.appendReplacement(out, Matcher.quoteReplacement(jsString(value)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * Renders a request template that may be a plain URL, a {{expr}} template, or an inline
     * &lt;js&gt;/@js: block (many Legado sources build the search URL, and a POST body plus its
     * JSON options, in JavaScript).
     */
    static String renderRequest(String template, Map<String, Object> variables, String baseUrl,
                                String jsLib, String sourceKey) throws Exception {
        if (template == null) return "";
        if (!containsJs(template)) return renderTemplate(template, variables, baseUrl, jsLib, sourceKey);
        Matcher matcher = JS_BLOCK.matcher(template);
        StringBuilder out = new StringBuilder();
        int end = 0;
        while (matcher.find()) {
            out.append(renderTemplate(template.substring(end, matcher.start()), variables, baseUrl, jsLib, sourceKey));
            String script = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            out.append(jsString(evaluateJs(script, variables, baseUrl, null, jsLib, sourceKey)));
            end = matcher.end();
        }
        out.append(renderTemplate(template.substring(end), variables, baseUrl, jsLib, sourceKey));
        return out.toString();
    }

    private List<Object> evaluateNodes(String rule) throws Exception {
        if (isJsonRule(rule)) return flatten(json(rule));
        if (isXPathRule(rule)) return xpath(rule);
        if (isRegexRule(rule)) return new ArrayList<>(regex(rule));
        if (containsJs(rule)) return List.of(scriptRule(rule));
        return cssNodes(rule);
    }

    private List<Object> cssNodes(String rawRule) {
        String rule = stripPrefix(rawRule, "@CSS:");
        // Legado: "||" is first-non-empty fallback, "&&" is concatenation of matches.
        List<String> orParts = splitTop(rule, "||");
        if (orParts.size() > 1) {
            for (String part : orParts) {
                List<Object> attempt = cssNodes(part.trim());
                if (!attempt.isEmpty()) return attempt;
            }
            return List.of();
        }
        List<String> andParts = splitTop(rule, "&&");
        if (andParts.size() > 1) {
            ArrayList<Object> merged = new ArrayList<>();
            for (String part : andParts) merged.addAll(cssNodes(part.trim()));
            return merged;
        }
        return cssNodesSingle(rule);
    }

    private List<Object> cssNodesSingle(String rule) {
        List<String> chain = splitCssChain(rule);
        List<Object> current = List.of(htmlRoot(content));
        for (String token : chain) {
            if (token.isEmpty()) continue;
            if (isAttributeToken(token)) return current;
            ArrayList<Object> next = new ArrayList<>();
            Selector selector = Selector.parse(token);
            for (Object value : current) {
                if (!(value instanceof Element)) continue;
                Elements selected = ((Element) value).select(selector.css);
                if (selector.index >= 0) {
                    if (selector.index < selected.size()) next.add(selected.get(selector.index));
                } else next.addAll(selected);
            }
            current = next;
        }
        return current;
    }

    private List<String> cssValues(String rawRule) {
        String rule = stripPrefix(rawRule, "@CSS:");
        // Legado: "||" is first-non-empty fallback, "&&" is concatenation of matches.
        List<String> orParts = splitTop(rule, "||");
        if (orParts.size() > 1) {
            for (String part : orParts) {
                List<String> attempt = cssValues(part.trim());
                if (!attempt.isEmpty()) return attempt;
            }
            return List.of();
        }
        List<String> andParts = splitTop(rule, "&&");
        if (andParts.size() > 1) {
            ArrayList<String> merged = new ArrayList<>();
            for (String part : andParts) merged.addAll(cssValues(part.trim()));
            return merged;
        }
        return cssValuesSingle(rule);
    }

    private List<String> cssValuesSingle(String rule) {
        List<String> chain = splitCssChain(rule);
        String attribute = null;
        if (!chain.isEmpty() && isAttributeToken(chain.get(chain.size() - 1))) {
            attribute = chain.remove(chain.size() - 1);
        }
        String nodeRule = String.join("@", chain);
        List<Object> nodes = nodeRule.isEmpty() ? List.of(htmlRoot(content)) : cssNodesSingle(nodeRule);
        ArrayList<String> values = new ArrayList<>();
        for (Object value : nodes) {
            if (!(value instanceof Element)) {
                values.add(scalar(value));
                continue;
            }
            Element element = (Element) value;
            String extracted;
            if (attribute == null || attribute.equalsIgnoreCase("text")) extracted = element.text();
            else if (attribute.equalsIgnoreCase("ownText")) extracted = element.ownText();
            else if (attribute.equalsIgnoreCase("html")) extracted = element.html();
            else if (attribute.equalsIgnoreCase("outerHtml")) extracted = element.outerHtml();
            else if (attribute.equalsIgnoreCase("textNodes")) extracted = element.wholeOwnText();
            else if (attribute.equalsIgnoreCase("href")) extracted = element.absUrl("href").isEmpty()
                    ? element.attr("href") : element.absUrl("href");
            else if (attribute.equalsIgnoreCase("src")) extracted = element.absUrl("src").isEmpty()
                    ? element.attr("src") : element.absUrl("src");
            else extracted = element.attr(attribute);
            values.add(extracted);
        }
        return values;
    }

    private List<Object> xpath(String rawRule) {
        String rule = stripPrefix(rawRule, "@XPath:");
        String attribute = null;
        boolean textNode = false;
        Matcher suffix = XPATH_SUFFIX.matcher(rule.trim());
        if (suffix.matches()) {
            rule = suffix.group(1);
            if (suffix.group(2) != null) attribute = suffix.group(2);
            else textNode = true;
        }
        Element root = htmlRoot(content);
        final Elements found;
        try {
            found = root.selectXpath(rule);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("不支持或无效的 XPath 规则：" + rule, e);
        }
        ArrayList<Object> out = new ArrayList<>();
        for (Element element : found) {
            if (attribute != null) {
                if (attribute.equals("href")) out.add(element.absUrl("href").isEmpty()
                        ? element.attr("href") : element.absUrl("href"));
                else if (attribute.equals("src")) out.add(element.absUrl("src").isEmpty()
                        ? element.attr("src") : element.absUrl("src"));
                else out.add(element.attr(attribute));
            } else if (textNode) out.add(element.text());
            else out.add(element);
        }
        return out;
    }

    private Object json(String rawRule) {
        String rule = stripPrefix(rawRule, "@Json:");
        try {
            // Never stringify a parsed Map/List: that yields fake JSON like "{name=A}". Read it directly.
            if (content instanceof String) return JsonPath.read((String) content, rule);
            return JsonPath.read(plainJson(content), rule);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("JSONPath 规则失败：" + rule, e);
        }
    }

    /** org.json types are not Map/List, and JsonPath only understands plain collections. */
    private static Object plainJson(Object value) {
        if (value instanceof JSONObject) {
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            JSONObject object = (JSONObject) value;
            java.util.Iterator<String> it = object.keys();
            while (it.hasNext()) { String key = it.next(); map.put(key, plainJson(object.opt(key))); }
            return map;
        }
        if (value instanceof JSONArray) {
            ArrayList<Object> list = new ArrayList<>();
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) list.add(plainJson(array.opt(i)));
            return list;
        }
        if (value == JSONObject.NULL) return null;
        return value;
    }

    private List<String> regex(String rawRule) {
        String rule = stripPrefix(rawRule, "@Regex:");
        if (rule.startsWith(":")) rule = rule.substring(1);
        final Pattern pattern;
        try { pattern = Pattern.compile(rule, Pattern.DOTALL); }
        catch (RuntimeException e) { throw new IllegalArgumentException("正则规则无效：" + rule, e); }
        Matcher matcher = pattern.matcher(textOf(content));
        ArrayList<String> values = new ArrayList<>();
        while (matcher.find()) values.add(matcher.groupCount() == 0 ? matcher.group() : matcher.group(1));
        return values;
    }

    private Object scriptRule(String rule) throws Exception {
        Matcher matcher = JS_BLOCK.matcher(rule);
        Object value = content;
        int end = 0;
        boolean found = false;
        while (matcher.find()) {
            found = true;
            String before = rule.substring(end, matcher.start()).trim();
            if (!before.isEmpty()) value = new RuleEvaluator(value, baseUrl, jsLib, sourceKey).string(before);
            String script = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            value = evaluateJs(script, Map.of("result", safeScriptValue(value)), baseUrl, value, jsLib, sourceKey);
            end = matcher.end();
        }
        String after = rule.substring(end).trim();
        if (!after.isEmpty()) value = new RuleEvaluator(value, baseUrl, jsLib, sourceKey).string(after);
        if (!found) throw new IllegalArgumentException("脚本规则格式无效");
        return value == null || value == Undefined.instance ? "" : value;
    }

    /** Static screen before any script runs; refuses constructs the restricted scope cannot honour. */
    static void validateScript(String script) throws IOException {
        if (script == null) return;
        for (Pattern deny : SCRIPT_DENY) {
            if (deny.matcher(script).find()) {
                throw new IOException("受限脚本包含不支持的能力：" + deny.pattern());
            }
        }
    }

    private static Object evaluateJs(String script, Map<String, Object> variables, String baseUrl,
                                     Object result, String jsLib, String sourceKey) throws Exception {
        if (script == null || script.trim().isEmpty()) return "";
        if (script.length() > MAX_SCRIPT_SOURCE) throw new IOException("脚本超过 128KB 上限");
        validateScript(script);
        if (jsLib != null && !jsLib.isBlank()) validateScript(jsLib);
        SafeContextFactory factory = new SafeContextFactory(System.nanoTime() + SCRIPT_LIMIT_NANOS);
        try {
            return factory.call((ContextAction<Object>) cx -> {
                ScriptableObject scope = cx.initStandardObjects(null, false);
                for (String forbidden : List.of("getClass",
                        "JavaAdapter", "JavaImporter", "importClass", "importPackage")) {
                    ScriptableObject.deleteProperty(scope, forbidden);
                }
                for (Map.Entry<String, Object> entry : variables.entrySet()) {
                    ScriptableObject.putProperty(scope, entry.getKey(), Context.javaToJS(safeScriptValue(entry.getValue()), scope));
                }
                ScriptableObject.putProperty(scope, "result", Context.javaToJS(safeScriptValue(result), scope));
                ScriptableObject.putProperty(scope, "src", Context.javaToJS(safeScriptValue(result), scope));
                ScriptableObject.putProperty(scope, "baseUrl", baseUrl == null ? "" : baseUrl);
                ScriptableObject.putProperty(scope, "java", bridge(baseUrl, sourceKey, result, jsLib));
                ScriptableObject.putProperty(scope, "source", sourceBridge(sourceKey, baseUrl));
                ScriptableObject.putProperty(scope, "cookie", cookieBridge());
                ScriptableObject.putProperty(scope, "cache", cacheBridge(sourceKey));
                ScriptableObject.putProperty(scope, "book", bookBridge());
                if (jsLib != null && !jsLib.isBlank()) {
                    cx.evaluateString(scope, jsLib, "reading-jslib", 1, null);
                }
                // A script that already is a self-contained expression or IIFE must be evaluated as
                // written: wrapping it in another function body would throw its value away. Only a
                // bare statement list that uses `return` needs the function wrapper.
                String trimmedScript = script.trim();
                boolean expression = trimmedScript.startsWith("(") || trimmedScript.startsWith("!")
                        || trimmedScript.startsWith("function") || trimmedScript.startsWith("void ");
                String source = !expression && Pattern.compile("\\breturn\\b").matcher(script).find()
                        ? "(function(){" + script + "}).call(null)" : script;
                Object value = cx.evaluateString(scope, source, "reading-rule", 1, null);
                return Context.jsToJava(value, Object.class);
            });
        } catch (ScriptLimitException e) {
            throw new IOException("脚本执行超时或超过指令上限", e);
        } catch (RuntimeException e) {
            throw new IOException("受限脚本执行失败：" + message(e), e);
        }
    }

    private static NativeObject bridge(String baseUrl, String sourceKey, Object content, String jsLib) {
        NativeObject java = new NativeObject();
        putFunction(java, "urlEncode", args -> URLEncoder.encode(arg(args, 0), StandardCharsets.UTF_8));
        // Legado's java.encodeURI(str[, charset]) is the one most rules actually call.
        putFunction(java, "encodeURI", args -> {
            String charset = args.length > 1 ? arg(args, 1).trim() : "UTF-8";
            try {
                return URLEncoder.encode(arg(args, 0), charset.isEmpty() ? "UTF-8" : charset);
            } catch (Exception e) {
                return URLEncoder.encode(arg(args, 0), StandardCharsets.UTF_8);
            }
        });
        putFunction(java, "base64Encode", args -> Base64.getEncoder().encodeToString(arg(args, 0).getBytes(StandardCharsets.UTF_8)));
        putFunction(java, "base64Decode", args -> new String(Base64.getDecoder().decode(arg(args, 0)), StandardCharsets.UTF_8));
        putFunction(java, "base64DecodeToByteArray", args -> Base64.getDecoder().decode(arg(args, 0)));
        putFunction(java, "hexDecodeToString", args -> {
            String hex = arg(args, 0);
            byte[] out = new byte[hex.length() / 2];
            for (int i = 0; i + 1 < hex.length(); i += 2) out[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
            return new String(out, StandardCharsets.UTF_8);
        });
        putFunction(java, "digestHex", args -> digest("MD5", arg(args, 0)));
        putFunction(java, "md5Encode", args -> digest("MD5", arg(args, 0)));
        // Legado's java.getString(rule[, isUrl]) evaluates a sub-rule against the current page.
        putFunction(java, "getString", args -> {
            String sub = arg(args, 0);
            if (sub.isEmpty()) return "";
            try {
                return new RuleEvaluator(content, baseUrl, jsLib, sourceKey).string(sub);
            } catch (Exception e) {
                return "";
            }
        });
        putFunction(java, "getElement", args -> {
            try {
                List<Object> nodes = new RuleEvaluator(content, baseUrl, jsLib, sourceKey).nodes(arg(args, 0));
                return nodes.isEmpty() ? "" : nodes.get(0);
            } catch (Exception e) { return ""; }
        });
        putFunction(java, "getElements", args -> {
            try {
                return new RuleEvaluator(content, baseUrl, jsLib, sourceKey).nodes(arg(args, 0));
            } catch (Exception e) { return List.of(); }
        });
        putFunction(java, "md5", args -> digest("MD5", arg(args, 0)));
        putFunction(java, "sha256", args -> digest("SHA-256", arg(args, 0)));
        putFunction(java, "put", args -> {
            String k = arg(args, 0), v = arg(args, 1);
            PUT_VARS.put(varKey(sourceKey, k), v);
            return v;
        });
        putFunction(java, "get", args -> {
            String k = arg(args, 0);
            return PUT_VARS.getOrDefault(varKey(sourceKey, k), PUT_VARS.getOrDefault(varKey("", k), ""));
        });
        putFunction(java, "t2s", args -> arg(args, 0));
        putFunction(java, "s2t", args -> arg(args, 0));
        putFunction(java, "log", args -> arg(args, 0));
        putFunction(java, "toast", args -> "");
        putFunction(java, "longToast", args -> "");
        putFunction(java, "timeFormat", args -> {
            long ms;
            try { ms = Long.parseLong(arg(args, 0)); } catch (Exception e) { ms = System.currentTimeMillis(); }
            return javaTimeFormat(ms);
        });
        putFunction(java, "timeFormatUTC", args -> {
            long ms;
            try { ms = Long.parseLong(arg(args, 0)); } catch (Exception e) { ms = System.currentTimeMillis(); }
            return javaTimeFormatUtc(ms);
        });
        putFunction(java, "ajax", args -> {
            String raw = arg(args, 0);
            String resolved;
            try { resolved = URI.create(baseUrl == null || baseUrl.isEmpty() ? raw : baseUrl).resolve(raw).toString(); }
            catch (RuntimeException e) { throw new IOException("ajax URL 无效", e); }
            return SourceHttp.execute(SourceHttp.Request.get(resolved), 512 * 1024).body;
        });
        java.sealObject();
        return java;
    }

    private static String javaTimeFormat(long ms) {
        java.util.Date date = new java.util.Date(ms);
        return new java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.ROOT).format(date);
    }

    private static String javaTimeFormatUtc(long ms) {
        java.util.Date date = new java.util.Date(ms);
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.ROOT);
        format.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return format.format(date);
    }

    private static NativeObject sourceBridge(String sourceKey, String baseUrl) {
        NativeObject src = new NativeObject();
        ScriptableObject.putProperty(src, "bookSourceUrl", baseUrl == null ? "" : baseUrl);
        ScriptableObject.putProperty(src, "sourceUrl", baseUrl == null ? "" : baseUrl);
        putFunction(src, "getKey", args -> baseUrl == null ? "" : baseUrl);
        putFunction(src, "getVariable", args -> PUT_VARS.getOrDefault(varKey(sourceKey, "__source_var__"), ""));
        putFunction(src, "setVariable", args -> {
            String v = arg(args, 0);
            PUT_VARS.put(varKey(sourceKey, "__source_var__"), v);
            return v;
        });
        putFunction(src, "getLoginInfo", args -> "");
        putFunction(src, "getLoginInfoMap", args -> new NativeObject());
        putFunction(src, "getLoginHeader", args -> "");
        src.sealObject();
        return src;
    }

    private static NativeObject cookieBridge() {
        NativeObject cookie = new NativeObject();
        putFunction(cookie, "getCookie", args -> "");
        putFunction(cookie, "removeCookie", args -> "");
        cookie.sealObject();
        return cookie;
    }

    private static NativeObject cacheBridge(String sourceKey) {
        NativeObject cache = new NativeObject();
        putFunction(cache, "get", args -> PUT_VARS.getOrDefault(varKey(sourceKey, "cache:" + arg(args, 0)), ""));
        putFunction(cache, "put", args -> {
            String v = arg(args, 1);
            PUT_VARS.put(varKey(sourceKey, "cache:" + arg(args, 0)), v);
            return v;
        });
        putFunction(cache, "getFromMemory", args -> PUT_VARS.getOrDefault(varKey(sourceKey, "mem:" + arg(args, 0)), ""));
        putFunction(cache, "putMemory", args -> {
            String v = arg(args, 1);
            PUT_VARS.put(varKey(sourceKey, "mem:" + arg(args, 0)), v);
            return v;
        });
        cache.sealObject();
        return cache;
    }

    private static NativeObject bookBridge() {
        NativeObject book = new NativeObject();
        ScriptableObject.putProperty(book, "name", "");
        ScriptableObject.putProperty(book, "author", "");
        ScriptableObject.putProperty(book, "kind", "");
        putFunction(book, "getVariable", args -> "");
        putFunction(book, "putVariable", args -> "");
        book.sealObject();
        return book;
    }

    private interface BridgeCall { Object run(Object[] args) throws Exception; }

    private static void putFunction(ScriptableObject target, String name, BridgeCall call) {
        ScriptableObject.putProperty(target, name, new BaseFunction() {
            @Override public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                try { return call.run(args); }
                catch (Exception e) { throw Context.throwAsScriptRuntimeEx(e); }
            }
        });
    }

    private static String arg(Object[] args, int index) {
        return index < args.length && args[index] != Undefined.instance ? Context.toString(args[index]) : "";
    }

    private static String digest(String algorithm, String value) throws Exception {
        byte[] bytes = MessageDigest.getInstance(algorithm).digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }

    private static Object safeScriptValue(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof Element) return ((Element) value).outerHtml();
        if (value instanceof List<?>) {
            ArrayList<Object> safe = new ArrayList<>();
            for (Object item : (List<?>) value) safe.add(safeScriptValue(item));
            return safe.toArray();
        }
        if (value instanceof Map<?, ?>) {
            // A bare java.util.Map reaches JS as a NativeJavaObject whose keys are NOT properties:
            // `result.id` would be undefined. Build a real JS object so JSON sources can read fields.
            NativeObject object = new NativeObject();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                ScriptableObject.putProperty(object, String.valueOf(entry.getKey()), safeScriptValue(entry.getValue()));
            }
            return object;
        }
        return String.valueOf(value);
    }

    private Element htmlRoot(Object value) {
        if (value instanceof Element) return (Element) value;
        Document document = Jsoup.parse(String.valueOf(value), baseUrl);
        return document;
    }

    private static List<Object> flatten(Object value) {
        if (value == null || value == Undefined.instance) return List.of();
        // A Map is a single JSON object node; unwrapping it into .values() would lose the fields.
        if (value instanceof List<?>) return new ArrayList<>((List<?>) value);
        return List.of(value);
    }

    /** Plain text of any content without turning Maps/Lists into "{k=v}" pseudo JSON. */
    private static String textOf(Object value) {
        if (value instanceof Element) return ((Element) value).text();
        if (value instanceof Map<?, ?> || value instanceof List<?>) return jsonText(value);
        return String.valueOf(value);
    }

    private static String scalar(Object value) {
        if (value == null || value == Undefined.instance) return "";
        if (value instanceof Element) return ((Element) value).text();
        if (value instanceof Map<?, ?> || value instanceof List<?>) return jsonText(value);
        return String.valueOf(value).trim();
    }

    private static String jsonText(Object value) {
        if (value instanceof Map<?, ?>) return new JSONObject((Map<?, ?>) value).toString();
        if (value instanceof List<?>) {
            JSONArray array = new JSONArray();
            for (Object item : (List<?>) value) array.put(item);
            return array.toString();
        }
        return String.valueOf(value);
    }

    /** Renders an element's text with a newline after every block child, then cleans it. */
    private static String blockPlainText(Element element) {
        for (Element br : element.select("br")) br.replaceWith(new org.jsoup.nodes.TextNode("\n"));
        for (Element block : element.select("p,div,br,li,h1,h2,h3,h4,h5,h6,tr,blockquote,section,article")) {
            block.appendText("\n");
        }
        return element.wholeText();
    }

    private static String cleanText(String value) {
        if (value == null) return "";
        return value.replace('\u00a0', ' ').replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n[ \\t]+", "\n")
                .replaceAll("\\n{3,}", "\n\n").trim();
    }

    private static String replace(String value, RuleParts parts) {
        try {
            Matcher matcher = Pattern.compile(parts.regex, Pattern.DOTALL).matcher(value);
            if (parts.firstOnly) return matcher.find() ? matcher.group().replaceFirst(parts.regex, parts.replacement) : "";
            return matcher.replaceAll(parts.replacement);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("替换正则无效：" + parts.regex, e);
        }
    }

    private static List<String> splitCssChain(String rule) {
        ArrayList<String> parts = new ArrayList<>();
        StringBuilder part = new StringBuilder();
        int brackets = 0;
        for (int i = 0; i < rule.length(); i++) {
            char c = rule.charAt(i);
            if (c == '[' || c == '(') brackets++;
            else if (c == ']' || c == ')') brackets = Math.max(0, brackets - 1);
            if (c == '@' && brackets == 0) {
                parts.add(part.toString().trim());
                part.setLength(0);
            } else part.append(c);
        }
        parts.add(part.toString().trim());
        return parts;
    }

    /** Splits on a top-level two-char operator, ignoring separators inside [] or (). */
    private static List<String> splitTop(String rule, String operator) {
        ArrayList<String> parts = new ArrayList<>();
        StringBuilder part = new StringBuilder();
        int brackets = 0;
        for (int i = 0; i < rule.length(); i++) {
            char c = rule.charAt(i);
            if (c == '[' || c == '(') brackets++;
            else if (c == ']' || c == ')') brackets = Math.max(0, brackets - 1);
            if (brackets == 0 && c == operator.charAt(0) && i + 1 < rule.length()
                    && rule.charAt(i + 1) == operator.charAt(1)) {
                parts.add(part.toString().trim());
                part.setLength(0);
                i++;
            } else part.append(c);
        }
        parts.add(part.toString().trim());
        return parts;
    }

    private static boolean isAttributeToken(String token) {
        if (token == null || token.isEmpty()) return false;
        String lower = token.toLowerCase(Locale.ROOT);
        return lower.equals("text") || lower.equals("owntext") || lower.equals("textnodes")
                || lower.equals("html") || lower.equals("outerhtml") || lower.equals("href")
                || lower.equals("src") || lower.startsWith("data-") || lower.equals("content")
                || lower.equals("title") || lower.equals("value") || lower.equals("alt")
                || lower.equals("class");
    }

    private static boolean isJsonRule(String rule) {
        String value = rule.trim();
        return startsIgnoreCase(value, "@Json:") || value.startsWith("$.") || value.startsWith("$[");
    }

    /** Already absolute URL produced by a template; treat as a literal value, not a selector. */
    private static boolean isLiteralUrl(String rule) {
        String value = rule.trim();
        if (!(value.regionMatches(true, 0, "http://", 0, 7)
                || value.regionMatches(true, 0, "https://", 0, 8))) return false;
        // A selector could never start with a scheme, but an option payload may follow a comma.
        return !value.contains("\n") && !value.contains(" ");
    }

    private static boolean isXPathRule(String rule) {
        String value = rule.trim();
        return startsIgnoreCase(value, "@XPath:") || value.startsWith("/");
    }

    private static boolean isRegexRule(String rule) {
        String value = rule.trim();
        return startsIgnoreCase(value, "@Regex:") || value.startsWith(":");
    }

    private static boolean containsJs(String rule) {
        return JS_BLOCK.matcher(rule).find();
    }

    private static String stripPrefix(String value, String prefix) {
        return startsIgnoreCase(value, prefix) ? value.substring(prefix.length()) : value;
    }

    private static boolean startsIgnoreCase(String value, String prefix) {
        return value.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }

    private static String jsString(Object value) {
        if (value == null || value == Undefined.instance) return "";
        if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            if (number == Math.rint(number)) return Long.toString((long) number);
        }
        return String.valueOf(value);
    }

    private static String message(Throwable error) {
        String value = error.getMessage();
        return value == null || value.isBlank() ? error.getClass().getSimpleName() : value;
    }

    private static final class Selector {
        final String css;
        final int index;

        Selector(String css, int index) { this.css = css; this.index = index; }

        static Selector parse(String raw) {
            String value = raw.trim();
            int index = -1;
            Matcher suffix = Pattern.compile("^(.*)\\.(\\d+)$").matcher(value);
            if (suffix.matches()) {
                value = suffix.group(1);
                index = Integer.parseInt(suffix.group(2));
            }
            if (startsIgnoreCase(value, "class.")) value = "." + value.substring(6);
            else if (startsIgnoreCase(value, "id.")) value = "#" + value.substring(3);
            else if (startsIgnoreCase(value, "tag.")) value = value.substring(4);
            if (value.isEmpty()) value = "*";
            return new Selector(value, index);
        }
    }

    private static final class RuleParts {
        final String rule;
        final String regex;
        final String replacement;
        final boolean firstOnly;

        RuleParts(String rule, String regex, String replacement, boolean firstOnly) {
            this.rule = rule;
            this.regex = regex;
            this.replacement = replacement;
            this.firstOnly = firstOnly;
        }

        static RuleParts parse(String raw) {
            String[] parts = raw.split("##", -1);
            return new RuleParts(parts[0].trim(), parts.length > 1 ? parts[1] : "",
                    parts.length > 2 ? parts[2] : "", parts.length > 3);
        }
    }

    private static final class SafeContextFactory extends ContextFactory {
        private final long deadline;

        SafeContextFactory(long deadline) { this.deadline = deadline; }

        @Override protected Context makeContext() {
            Context context = super.makeContext();
            context.setOptimizationLevel(-1);
            context.setInstructionObserverThreshold(10_000);
            context.setClassShutter(new PackageClassShutter());
            return context;
        }

        @Override protected void observeInstructionCount(Context context, int instructionCount) {
            // Key on this factory instance, not on the class: Rhino keeps one Context per thread
            // and a class-keyed counter would accumulate across every script of the session,
            // making later evaluations fail as if they alone had exhausted the budget.
            Object old = context.getThreadLocal(this);
            int used = old instanceof Integer ? (Integer) old : 0;
            used += instructionCount;
            context.putThreadLocal(this, used);
            if (used > SCRIPT_INSTRUCTION_LIMIT || System.nanoTime() > deadline) throw new ScriptLimitException();
        }
    }

    /**
     * Legado sources routinely use {@code Packages.java.util.Base64}/{@code javax.crypto.Cipher} for
     * the AES-obfuscated image lists that many manga sites serve. Refusing every Java class makes
     * those sources unusable, so a small, fixed whitelist of pure data/crypto helpers stays visible;
     * files, processes, reflection and networking remain invisible even through Packages.
     */
    private static final class PackageClassShutter implements ClassShutter {
        private static final java.util.Set<String> ALLOWED = java.util.Set.of(
                "java.lang.Object", "java.lang.String", "java.lang.StringBuilder", "java.lang.Character",
                "java.lang.Integer", "java.lang.Long", "java.lang.Boolean", "java.lang.Double", "java.lang.Float",
                "java.lang.Short", "java.lang.Byte", "java.lang.Math",
                "java.util.Base64", "java.util.Base64$Decoder", "java.util.Base64$Encoder",
                "java.util.Arrays", "java.util.ArrayList", "java.util.HashMap", "java.util.LinkedHashMap",
                "javax.crypto.Cipher", "javax.crypto.spec.SecretKeySpec", "javax.crypto.spec.IvParameterSpec");

        @Override public boolean visibleToScripts(String fullClassName) { return ALLOWED.contains(fullClassName); }
    }

    private static final class ScriptLimitException extends Error {
        private static final long serialVersionUID = 1L;
    }
}
