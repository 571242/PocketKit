package cn.xianhua.pocketkit.lanshare;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * 从系统的网络接口里收集候选 IPv4 地址，交给 {@link LanAddress#pick} 打分。
 *
 * 只报地址，不做判断：选哪个地址的规则在纯逻辑的 LanAddress 里，可脱机测试。
 * Wi-Fi 与热点都算（热点在系统里可能是 wlan0 或 ap0/swlan0），VPN 隧道与蜂窝一律标出来排除。
 */
public final class LanAddresses {

    private LanAddresses() {
    }

    public static List<LanAddress.Candidate> candidates(Context context) {
        List<LanAddress.Candidate> result = new ArrayList<>();
        ConnectivityManager manager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) {
            return result;
        }
        if (Build.VERSION.SDK_INT < 23) {
            // API 21/22 走不到：本项目的 minSdk 是 26，这里只是防御性分支。
            return result;
        }
        for (Network network : manager.getAllNetworks()) {
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
            LinkProperties properties = manager.getLinkProperties(network);
            if (properties == null) {
                continue;
            }
            boolean vpn = capabilities != null
                    && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
            boolean cellular = capabilities != null
                    && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
            String name = properties.getInterfaceName();
            if (name == null) {
                name = vpn ? "tun" : (cellular ? "rmnet" : "");
            }
            for (LinkAddress link : properties.getLinkAddresses()) {
                InetAddress address = link.getAddress();
                if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) {
                    continue;
                }
                // 蜂窝接口整个跳过：它不是"同一局域网"，也绝不该出现在二维码里。
                if (cellular) {
                    continue;
                }
                result.add(new LanAddress.Candidate(name, address.getHostAddress(), vpn, true));
            }
        }
        return result;
    }

    /** 选中的地址；没有可用局域网时返回 null。 */
    public static LanAddress.Candidate pick(Context context) {
        return LanAddress.pick(candidates(context));
    }
}
