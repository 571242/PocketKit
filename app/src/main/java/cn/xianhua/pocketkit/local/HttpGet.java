package cn.xianhua.pocketkit.local;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import cn.xianhua.pocketkit.core.ToolException;

/**
 * 汇率取数用的极小 HTTPS GET 客户端（052）。
 *
 * 设计约束，逐条对应任务书的要求：
 * - **只在用户明确点「获取汇率」时才被调用**。本类不做任何自动请求、不做后台刷新、
 *   不在启动时预热，因此不会出现「用户不知情就联网」的情况。
 * - 只访问固定的一台主机（api.frankfurter.dev），地址由 FxRates 拼出，不接受任意 URL。
 * - 失败一律抛出带人话原因的异常，**绝不返回缓存或猜测的汇率**：
 *   拿旧汇率冒充当前汇率比报错更糟。
 * - 明文风险：这里用的是 TLS（https），不是明文 HTTP。
 */
final class HttpGet {

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    /** 显式上限，避免异常响应把内存吃满。汇率响应正常只有几 KB。 */
    private static final int MAX_BYTES = 512 * 1024;

    private HttpGet() {
    }

    /**
     * 发起一次 HTTPS GET，返回响应体。
     * HTTP 状态非 2xx 时读取响应体作为错误信息并抛出。
     */
    static String fetch(String url) throws ToolException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept", "application/json");
            // 明确要求不缓存：汇率必须有日期，用缓存冒充当前值会误导用户
            conn.setRequestProperty("Cache-Control", "no-cache");
            conn.connect();

            int code = conn.getResponseCode();
            String body = code >= 200 && code < 300
                    ? read(conn.getInputStream())
                    : read(conn.getErrorStream());

            if (code < 200 || code >= 300) {
                String detail = body == null || body.trim().isEmpty()
                        ? "服务没有给出说明。" : body.trim();
                if (code == 404) {
                    throw new ToolException("服务找不到这两个币种（HTTP 404）。"
                            + "可能有币种代码写错了，或者该服务不支持其中一个币种。");
                }
                throw new ToolException("获取汇率失败：服务返回 HTTP " + code + "。" + detail);
            }
            if (body == null || body.trim().isEmpty()) {
                throw new ToolException("服务返回了空内容，无法得到汇率。");
            }
            return body;
        } catch (ToolException e) {
            throw e;
        } catch (java.net.UnknownHostException e) {
            throw new ToolException("无法解析服务域名（api.frankfurter.dev）。"
                    + "请检查手机是否联网。");
        } catch (java.net.SocketTimeoutException e) {
            throw new ToolException("连接汇率服务超时。网络可能不稳定，请稍后重试。"
                    + "本次没有取得任何汇率，不会显示旧数据。");
        } catch (javax.net.ssl.SSLException e) {
            throw new ToolException("与汇率服务建立加密连接失败。"
                    + "如果是公共网络或代理环境，可能被拦截了。");
        } catch (IOException e) {
            String m = e.getMessage();
            throw new ToolException("获取汇率时网络出错：" + (m == null || m.isEmpty()
                    ? e.getClass().getSimpleName() : m) + "。本次没有取得任何汇率。");
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            int total = 0;
            while ((n = r.read(buf)) > 0) {
                total += n;
                if (total > MAX_BYTES) {
                    throw new IOException("响应体超过 " + MAX_BYTES + " 字节，已中止读取。");
                }
                sb.append(buf, 0, n);
            }
        }
        return sb.toString();
    }
}
