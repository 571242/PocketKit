package cn.xianhua.pocketkit.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极小的 JSON 解析器（只解析，不做泛型数据绑定）。
 *
 * 为什么自己写：汇率接口返回的是很小的扁平 JSON，而 core/ 必须是纯 Java
 * （不依赖 Android 的 org.json），也不想为几十行解析引入第三方库。
 * 本解析器严格解析：格式不符就抛错，绝不返回半截结果。
 *
 * 支持：对象、数组、字符串（含引号、反斜杠、斜杠、退格、换页、换行、回车、制表符、
 * 以及码点形式的十六进制转义）、数字、true/false/null。
 * 不支持：注释、单引号字符串、尾随逗号（这些都不是合法 JSON，拒绝解析是对的）。
 */
public final class MiniJson {

    private final String src;
    private int pos;

    private MiniJson(String src) {
        this.src = src;
        this.pos = 0;
    }

    public static Object parse(String text) throws ToolException {
        if (text == null || text.isEmpty()) {
            throw new ToolException("服务返回了空内容，无法解析。");
        }
        MiniJson p = new MiniJson(text);
        p.skipWhitespace();
        Object v = p.readValue();
        p.skipWhitespace();
        if (p.pos < p.src.length()) {
            throw new ToolException("服务返回的内容在结尾有多余字符，JSON 不完整或格式不对。");
        }
        return v;
    }

    /** 把解析结果当作对象取用。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object v) throws ToolException {
        if (!(v instanceof Map)) {
            throw new ToolException("期望得到 JSON 对象，实际是 " + describe(v) + "。");
        }
        return (Map<String, Object>) v;
    }

    public static String describe(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Map) {
            return "对象";
        }
        if (v instanceof List) {
            return "数组";
        }
        if (v instanceof String) {
            return "字符串";
        }
        if (v instanceof Number) {
            return "数字";
        }
        return v.getClass().getSimpleName();
    }

    private Object readValue() throws ToolException {
        if (pos >= src.length()) {
            throw new ToolException("JSON 在这里就结束了，内容不完整。");
        }
        char c = src.charAt(pos);
        switch (c) {
            case '{':
                return readObject();
            case '[':
                return readArray();
            case '"':
                return readString();
            case 't':
                expect("true");
                return Boolean.TRUE;
            case 'f':
                expect("false");
                return Boolean.FALSE;
            case 'n':
                expect("null");
                return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return readNumber();
                }
                throw new ToolException("JSON 第 " + (pos + 1) + " 个字符「" + c + "」不是合法的起始字符。");
        }
    }

    private Map<String, Object> readObject() throws ToolException {
        Map<String, Object> out = new LinkedHashMap<>();
        pos++; // {
        skipWhitespace();
        if (peek('}')) {
            pos++;
            return out;
        }
        while (true) {
            skipWhitespace();
            if (!peek('"')) {
                throw new ToolException("JSON 对象的键必须是字符串，位置 " + (pos + 1) + "。");
            }
            String key = readString();
            skipWhitespace();
            if (!peek(':')) {
                throw new ToolException("键「" + key + "」后面缺少冒号。");
            }
            pos++;
            skipWhitespace();
            out.put(key, readValue());
            skipWhitespace();
            if (peek(',')) {
                pos++;
                continue;
            }
            if (peek('}')) {
                pos++;
                return out;
            }
            throw new ToolException("JSON 对象里期望逗号或右花括号，位置 " + (pos + 1) + "。");
        }
    }

    private List<Object> readArray() throws ToolException {
        List<Object> out = new ArrayList<>();
        pos++; // [
        skipWhitespace();
        if (peek(']')) {
            pos++;
            return out;
        }
        while (true) {
            skipWhitespace();
            out.add(readValue());
            skipWhitespace();
            if (peek(',')) {
                pos++;
                continue;
            }
            if (peek(']')) {
                pos++;
                return out;
            }
            throw new ToolException("JSON 数组里期望逗号或右方括号，位置 " + (pos + 1) + "。");
        }
    }

    private String readString() throws ToolException {
        pos++; // opening quote
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) {
                throw new ToolException("字符串没有闭合的引号。");
            }
            char c = src.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= src.length()) {
                throw new ToolException("字符串以转义符结尾，内容不完整。");
            }
            char e = src.charAt(pos++);
            switch (e) {
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'u':
                    if (pos + 4 > src.length()) {
                        throw new ToolException("\\u 转义后面缺少 4 位十六进制数字。");
                    }
                    String hex = src.substring(pos, pos + 4);
                    try {
                        sb.append((char) Integer.parseInt(hex, 16));
                    } catch (NumberFormatException ex) {
                        throw new ToolException("\\u" + hex + " 不是合法的十六进制转义。");
                    }
                    pos += 4;
                    break;
                default:
                    throw new ToolException("无法识别的转义 \\" + e + "。");
            }
        }
    }

    private Object readNumber() throws ToolException {
        int start = pos;
        if (peek('-')) {
            pos++;
        }
        while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
            pos++;
        }
        if (peek('.')) {
            pos++;
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
                pos++;
            }
        }
        if (peek('e') || peek('E')) {
            pos++;
            if (peek('+') || peek('-')) {
                pos++;
            }
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
                pos++;
            }
        }
        String num = src.substring(start, pos);
        try {
            return new java.math.BigDecimal(num);
        } catch (NumberFormatException e) {
            throw new ToolException("「" + num + "」不是合法的 JSON 数字。");
        }
    }

    private void expect(String literal) throws ToolException {
        if (!src.startsWith(literal, pos)) {
            throw new ToolException("JSON 第 " + (pos + 1) + " 个字符处期望 " + literal + "。");
        }
        pos += literal.length();
    }

    private void skipWhitespace() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                break;
            }
        }
    }

    private boolean peek(char c) {
        return pos < src.length() && src.charAt(pos) == c;
    }
}
