package cn.xianhua.pocketkit.lanshare;

import java.util.List;
import java.util.Locale;

/**
 * 选出「同一局域网里的电脑浏览器能连上」的那个 IPv4 地址。
 *
 * 为什么不能监听 0.0.0.0：那会把服务开到蜂窝网、VPN 隧道和全局 IPv6 上。
 * 这里只接受 RFC1918 私有地址，并明确排除隧道 / 蜂窝接口名 —— VPN 的 tun0 通常
 * 也带一个 10.x 私有地址，光看地址段会选错，地址会被写进二维码交给对方，错一次就白折腾。
 *
 * 纯逻辑：不依赖 Android，可在普通 JVM 上测试。
 */
public final class LanAddress {

    /** 一张网卡上的候选地址。vpn/up 由 Android 侧通过 ConnectivityManager 标注。 */
    public static final class Candidate {
        public final String interfaceName;
        public final String address;
        public final boolean vpn;
        public final boolean up;

        public Candidate(String interfaceName, String address, boolean vpn, boolean up) {
            this.interfaceName = interfaceName == null ? "" : interfaceName;
            this.address = address == null ? "" : address;
            this.vpn = vpn;
            this.up = up;
        }

        @Override public String toString() {
            return interfaceName + "=" + address + (vpn ? "(vpn)" : "");
        }
    }

    private LanAddress() {
    }

    /** 返回得分最高的候选；没有可用地址时返回 null。 */
    public static Candidate pick(List<Candidate> candidates) {
        if (candidates == null) {
            return null;
        }
        Candidate best = null;
        int bestScore = -1;
        for (Candidate candidate : candidates) {
            int score = score(candidate);
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    static int score(Candidate candidate) {
        if (candidate == null || !candidate.up || candidate.vpn) {
            return -1;
        }
        if (!isPrivateIpv4(candidate.address)) {
            return -1;
        }
        String name = candidate.interfaceName.toLowerCase(Locale.US);
        if (isTunnelName(name) || isCellularName(name)) {
            return -1;
        }
        // 169.254 是没拿到 DHCP 时的兜底地址，能连但基本没法用，排最后。
        if (candidate.address.startsWith("169.254.")) {
            return 1;
        }
        int nameScore = 0;
        if (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan")
                || name.startsWith("softap") || name.startsWith("eth") || name.startsWith("wl")) {
            nameScore = 100;
        } else if (!name.isEmpty()) {
            nameScore = 10;
        }
        int bandScore;
        if (candidate.address.startsWith("192.168.")) {
            bandScore = 3;
        } else if (candidate.address.startsWith("10.")) {
            bandScore = 2;
        } else {
            bandScore = 1;
        }
        return nameScore + bandScore;
    }

    /** 隧道 / VPN 常见接口名。命中即排除，不看它有没有私有地址。 */
    public static boolean isTunnelName(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.US);
        return n.startsWith("tun") || n.startsWith("tap") || n.startsWith("ppp")
                || n.startsWith("ipsec") || n.startsWith("wg") || n.startsWith("utun")
                || n.startsWith("vpn") || n.startsWith("clat") || n.startsWith("pptp")
                || n.startsWith("l2tp") || n.startsWith("sit") || n.startsWith("gre");
    }

    /** 蜂窝接口：rmnet / ccmni / pdp 等，绝不能拿来开共享。 */
    public static boolean isCellularName(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.US);
        return n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("pdp")
                || n.startsWith("wwan") || n.startsWith("radio");
    }

    /** RFC1918（10/8、172.16/12、192.168/16）与链路本地 169.254/16。 */
    public static boolean isPrivateIpv4(String text) {
        if (text == null) {
            return false;
        }
        String[] parts = text.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            try {
                octets[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException ex) {
                return false;
            }
            if (octets[i] < 0 || octets[i] > 255) {
                return false;
            }
        }
        int a = octets[0];
        int b = octets[1];
        if (a == 10) {
            return true;
        }
        if (a == 172 && b >= 16 && b <= 31) {
            return true;
        }
        if (a == 192 && b == 168) {
            return true;
        }
        return a == 169 && b == 254;
    }
}
