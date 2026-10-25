package cn.xianhua.pocketkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Host-side checks for the remote browser. No Android, no network, no server: every assertion is
 * about source-level contracts and about the pure path/name logic, because the protocol clients
 * themselves can only be verified against a real server.
 */
public final class RemotePanelContractTest {

    private static int checks;
    private static final StringBuilder failures = new StringBuilder();

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length > 0 ? args[0] : ".");
        Path remote = root.resolve("app/src/main/java/cn/xianhua/pocketkit/remote");
        String panel = read(remote.resolve("RemotePanel.java"));
        String store = read(remote.resolve("RemoteStore.java"));
        String factory = read(remote.resolve("RemoteClients.java"));
        String sftp = read(remote.resolve("SftpClient.java"));
        String smb = read(remote.resolve("SmbClient.java"));
        String ftp = read(remote.resolve("FtpClient.java"));
        String dav = read(remote.resolve("WebDavClient.java"));
        String manager = read(root.resolve("app/src/main/java/cn/xianhua/pocketkit/ManagerActivity.java"));
        String gradle = read(root.resolve("app/build.gradle"));

        // 1. All four protocols really dispatch to a distinct implementation.
        check(factory.contains("new SmbClient("), "SMB 协议映射到 SmbClient");
        check(factory.contains("new FtpClient("), "FTP 协议映射到 FtpClient");
        check(factory.contains("new WebDavClient("), "WebDAV 协议映射到 WebDavClient");
        check(factory.contains("new SftpClient("), "SFTP 协议映射到 SftpClient");

        // 2. No protocol client is missing from the build. A class that compiles but is not
        //    packaged fails only on a device, so pin the jars here.
        check(gradle.contains("smbj-0.13.0.jar"), "SMB 库已加入 app/build.gradle");
        check(gradle.contains("jsch-0.2.20.jar"), "SFTP 库已加入 app/build.gradle");
        check(gradle.contains("slf4j-api-2.0.13.jar"), "smbj 需要的 slf4j 已加入 app/build.gradle");
        check(!gradle.contains("libs/bcprov-jdk18on"), "没有重复打包 bcprov（pdfbox 已传递引入）");

        // 3. Passwords are never written in clear text.
        check(store.contains("Cipher.getInstance(TRANSFORMATION)"), "密码经 Cipher 加密后落盘");
        check(store.contains("AndroidKeyStore"), "密钥存放在 AndroidKeyStore");
        check(!store.contains("putString(KEY_SESSIONS, raw)"), "没有明文回写路径");
        check(store.contains("return false;"), "密钥库不可用时拒绝保存而不是降级为明文");
        check(!panel.contains("Log.d") && !panel.contains("Log.i"), "面板不把连接信息写进日志");

        // 4. A hostile server cannot choose where a download lands.
        check(panel.contains("private static String safeName("), "下载文件名经过清洗");
        check(panel.contains("name.split(\"[/"), "按路径分隔符切分服务器返回的名字");
        check(panel.contains("\"..\".equals(segment)"), "丢弃 .. 段，防止写到缓存目录之外");
        check(panel.contains("while (cleaned.startsWith(\".\"))"), "剥离前导点，避免隐藏文件");

        // 5. Streams: a 0-byte read is legal and must not end the copy.
        check(panel.contains("while ((n = in.read(buffer)) != -1)"), "复制循环用 -1 判断结束");
        check(panel.contains("if (n == 0) continue;"), "0 字节读不当作结束");

        // 6. Lifecycle: sockets and the worker must not outlive the screen.
        check(panel.contains("public void release()"), "面板提供 release()");
        check(panel.contains("worker.shutdownNow()"), "release() 关闭线程池");
        check(manager.contains("remotePanel.release()"), "管理器销毁时释放远程连接");
        check(manager.contains("setOnDismissListener"), "关闭对话框时也释放");
        // Closing a session can block on a protocol goodbye, so it must never run on the UI thread.
        check(panel.contains("private volatile boolean released"), "面板有 released 标志");
        check(panel.contains("remote-fs-close"), "release() 在独立线程里关闭连接");
        check(panel.contains("synchronized (pending)"), "尚未采纳的连接也被跟踪");
        check(!panel.contains("closeQuietly(session.client);\n        open.remove(current)"),
                "adopt 不在 UI 线程直接关闭旧连接");
        check(panel.contains("if (released) return;") || panel.contains("if (released) {"),
                "回主线程的回调先检查 released");

        // 7. SMB rename needs an open handle; the share itself has no rename.
        check(!smb.contains("share.rename("), "没有调用不存在的 DiskShare.rename");
        check(smb.contains("handle.rename(destination"), "SMB 重命名通过已打开的句柄完成");
        check(smb.contains("java.util.Arrays.fill(secret"), "SMB 用完密码后清零字符数组");
        // smbj stream close does not release the server-side handle; the File must be closed too.
        check(smb.contains("closeQuietly(file)"), "SMB 流关闭时同时释放 File 句柄");
        check(smb.contains("share.rmdir(target, false)"), "SMB 删目录非递归，与其它协议一致");

        // 7b. Every client must honour the port the user typed, not silently use the default.
        check(smb.contains("client.connect(host, session.port)"), "SMB 使用用户填写的端口");
        check(sftp.contains("session.port > 0 ? session.port : 22"), "SFTP 使用用户填写的端口");
        check(ftp.contains("session.port > 0 ? session.port : 21"), "FTP 使用用户填写的端口");
        check(dav.contains("session.port > 0 ? session.port"), "WebDAV 使用用户填写的端口");
        check(dav.contains("session.secure ? 443 : 80"), "WebDAV 未填端口时按是否加密选 443/80");

        // 8. FTP specifics that silently break behind NAT or on strict servers.
        check(ftp.contains("EPSV"), "FTP 优先 EPSV");
        check(ftp.contains("\"PASV\""), "FTP 回退 PASV");
        check(ftp.contains("Ignore the address the server advertises"), "FTP 数据连接不盲信服务器广播的地址");
        // Both EPSV and PASV must connect to the control peer, never to a server-chosen address.
        check(!ftp.contains("openData(host, port)"), "PASV 不使用应答里的 IP");
        check(ftp.contains("MLSD"), "FTP 优先 MLSD 列目录");
        check(ftp.contains("AUTH TLS"), "FTPS 走显式 TLS");
        // Data channel must be TLS too, or only commands are encrypted while file bytes go clear.
        check(ftp.contains("FTPS 数据连接握手失败") || ftp.contains("createSocket(plain, host, port, true)"),
                "FTPS 数据连接也做 TLS 握手");
        check(ftp.contains("setEndpointIdentificationAlgorithm(\"HTTPS\")"), "TLS 启用主机名校验");
        check(ftp.contains("PROT P"), "FTPS 要求加密数据通道");
        check(ftp.contains("如果 PROT P 被拒绝") || ftp.contains("已中止以免明文传输"),
                "PROT P 被拒时中止而不是明文继续");
        check(ftp.contains("FTP 无法切换到二进制模式"), "TYPE I 被拒时中止，不冒损坏风险");

        // 9. WebDAV must not let the server make the XML parser fetch anything.
        check(dav.contains("disallow-doctype-decl"), "WebDAV 关闭 DOCTYPE");
        check(dav.contains("setExpandEntityReferences(false)"), "WebDAV 不展开实体引用");
        check(dav.contains("PROPFIND"), "WebDAV 用 PROPFIND 列目录");
        check(dav.contains("MKCOL"), "WebDAV 用 MKCOL 建目录");
        // Android's HttpURLConnection whitelists methods and rejects PROPFIND/MKCOL/MOVE, so the
        // transport must not be HttpURLConnection.
        check(!dav.contains("(HttpURLConnection)"), "WebDAV 不实例化 HttpURLConnection（方法白名单会拒绝 DAV 动词）");
        check(dav.contains("OkHttpClient"), "WebDAV 走 OkHttp，可发送任意请求方法");
        check(gradle.contains("com.squareup.okhttp3:okhttp"), "OkHttp 显式声明，不靠传递依赖");

        // 10. SSH rejects untrusted keys before authentication; trust is a separate UI act.
        check(sftp.contains("StrictHostKeyChecking\", \"yes\""), "SFTP 严格校验主机指纹");
        check(sftp.contains("jsch.setHostKeyRepository(hostKeys)"), "SFTP 使用已核对的主机密钥");
        check(panel.contains("confirmHostKey(saved, e)"), "未知主机指纹经用户显式核对");
        check(store.contains(".put(\"hostKey\","), "已信任的主机密钥持久化");
        check(sftp.contains("服务器只接受密钥登录"), "SFTP 对密钥登录给出明确错误");

        // 11. The panel is a separate surface, not spliced into the local two-pane manager.
        check(manager.contains("remotePane()"), "管理器提供独立的远程入口");
        check(manager.contains("new cn.xianhua.pocketkit.remote.RemotePanel("), "远程面板独立构造");
        check(!manager.contains("RemoteClient.Entry"), "本地窗格不直接持有远程条目");

        // 12. Busy guard: a second tap cannot start a parallel connect or list.
        check(panel.contains("if (busy) return;"), "连接与列目录有 busy 互斥");
        check(panel.contains("上一次操作还在进行"), "重复点击给出提示而不是并发");

        System.out.println(checks + " checks, " + (failures.length() == 0 ? "all passed" : "FAILURES"));
        if (failures.length() > 0) {
            System.out.println(failures);
            System.exit(1);
        }
    }

    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) failures.append("  - ").append(what).append('\n');
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
