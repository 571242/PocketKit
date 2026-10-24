package cn.xianhua.pocketkit.device;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;

import java.net.Inet4Address;
import java.net.InetAddress;

/**
 * 找出当前 Wi-Fi 局域网的私有 IPv4 地址。
 *
 * 为什么不用 0.0.0.0：那等于在所有网卡上监听，蜂窝数据、全局 IPv6、
 * VPN 都会被一并暴露。共享只应该发生在局域网里。
 *
 * 为什么不用已废弃的 WifiManager.getConnectionInfo().getIpAddress()：
 * Android 12 起该接口返回的是占位值，不可用。这里统一走 ConnectivityManager。
 */
public final class WifiAddress {

    private WifiAddress() {
    }

    /** 返回 Wi-Fi 上的私有 IPv4 地址；没有可用地址时返回 null。 */
    public static InetAddress wifiIpv4(Context context) {
        ConnectivityManager manager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) {
            return null;
        }
        if (Build.VERSION.SDK_INT >= 23) {
            for (Network network : manager.getAllNetworks()) {
                NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
                if (capabilities == null
                        || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    continue;
                }
                InetAddress address = privateIpv4(manager.getLinkProperties(network));
                if (address != null) {
                    return address;
                }
            }
            return null;
        }
        return privateIpv4(manager.getLinkProperties(manager.getActiveNetwork()));
    }

    /** 从链路属性里挑出第一个私有 IPv4 地址。 */
    static InetAddress privateIpv4(LinkProperties properties) {
        if (properties == null) {
            return null;
        }
        for (LinkAddress link : properties.getLinkAddresses()) {
            InetAddress address = link.getAddress();
            if (address instanceof Inet4Address && isPrivate(address)) {
                return address;
            }
        }
        return null;
    }

    /**
     * 私有 / 链路本地地址判定（RFC 1918 与 RFC 3927）。
     * 公网地址一律不接受：那意味着共享会暴露到局域网之外。
     * 公开供自检直接调用，逻辑本身不依赖任何 Android 类。
     */
    public static boolean isPrivate(InetAddress address) {
        if (!(address instanceof Inet4Address)) {
            return false;
        }
        byte[] octets = address.getAddress();
        int a = octets[0] & 0xFF;
        int b = octets[1] & 0xFF;
        if (a == 10) {
            return true;
        }
        if (a == 172 && b >= 16 && b <= 31) {
            return true;
        }
        if (a == 192 && b == 168) {
            return true;
        }
        // 169.254/16 链路本地：通常表示没拿到 DHCP 地址，可连但多半没网。
        return a == 169 && b == 254;
    }
}
