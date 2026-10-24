package cn.xianhua.pocketkit.proxy;

import android.net.*;
import android.os.Build;
import android.os.Process;
import android.system.OsConstants;
import io.nekohasekai.libbox.*;
import java.net.InetSocketAddress;
import java.util.*;

/** Android network integration; every core socket must escape its own TUN. */
final class ProxyPlatform implements PlatformInterface {
    private final PocketVpnService service;
    private final ConnectivityManager connectivity;
    private volatile InterfaceUpdateListener listener;
    private ConnectivityManager.NetworkCallback callback;
    private volatile Network underlying;

    ProxyPlatform(PocketVpnService service) {
        this.service = service;
        connectivity = service.getSystemService(ConnectivityManager.class);
    }

    public boolean usePlatformAutoDetectInterfaceControl() { return true; }
    public void autoDetectInterfaceControl(int fd) throws Exception {
        if (!service.protect(fd)) throw new java.io.IOException("Cannot protect proxy socket");
    }
    public int openTun(TunOptions options) throws Exception { return service.openTun(options); }
    public boolean useProcFS() { return Build.VERSION.SDK_INT < 29; }
    public ConnectionOwner findConnectionOwner(int protocol, String src, int srcPort, String dst, int dstPort) throws Exception {
        if (Build.VERSION.SDK_INT < 29) throw new UnsupportedOperationException("Process lookup requires Android 10");
        int uid = connectivity.getConnectionOwnerUid(protocol, new InetSocketAddress(src, srcPort), new InetSocketAddress(dst, dstPort));
        if (uid == Process.INVALID_UID) throw new java.io.IOException("Connection owner unavailable");
        ConnectionOwner result = new ConnectionOwner();
        result.setUserId(uid);
        String[] packages = service.getPackageManager().getPackagesForUid(uid);
        result.setAndroidPackageNames(strings(packages == null ? Collections.emptyList() : Arrays.asList(packages)));
        return result;
    }

    public synchronized void startDefaultInterfaceMonitor(InterfaceUpdateListener update) {
        listener = update;
        if (callback == null) {
            callback = new ConnectivityManager.NetworkCallback() {
                public void onAvailable(Network network) { refreshDefault(); }
                public void onLost(Network network) { refreshDefault(); }
                public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) { refreshDefault(); }
                public void onLinkPropertiesChanged(Network network, LinkProperties links) { refreshDefault(); }
            };
            connectivity.registerNetworkCallback(new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), callback);
        }
        refreshDefault();
    }

    private void refreshDefault() {
        InterfaceUpdateListener update = listener;
        if (update == null) return;
        Network chosen = null;
        Network active = connectivity.getActiveNetwork();
        NetworkCapabilities activeCaps = connectivity.getNetworkCapabilities(active);
        if (activeCaps != null && activeCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) chosen = active;
        if (chosen == null && usable(underlying)) chosen = underlying;
        if (chosen == null) for (Network network : connectivity.getAllNetworks()) {
            if (usable(network)) { chosen = network; break; }
        }
        underlying = chosen;
        service.setUnderlyingNetworks(chosen == null ? null : new Network[]{chosen});
        if (chosen == null) { update.updateDefaultInterface("", -1, false, false); return; }
        LinkProperties links = connectivity.getLinkProperties(chosen);
        NetworkCapabilities caps = connectivity.getNetworkCapabilities(chosen);
        if (links == null || links.getInterfaceName() == null || caps == null) return;
        try {
            java.net.NetworkInterface net = java.net.NetworkInterface.getByName(links.getInterfaceName());
            if (net != null) update.updateDefaultInterface(net.getName(), net.getIndex(),
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED), false);
        } catch (java.net.SocketException ignored) { }
    }

    private boolean usable(Network network) {
        if (network == null) return false;
        NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    public synchronized void closeDefaultInterfaceMonitor(InterfaceUpdateListener ignored) {
        listener = null;
        if (callback != null) {
            ConnectivityManager.NetworkCallback previous = callback;
            callback = null;
            try { connectivity.unregisterNetworkCallback(previous); } catch (RuntimeException ignoredError) { }
        }
        underlying = null;
    }
    void close() { closeDefaultInterfaceMonitor(null); }

    public NetworkInterfaceIterator getInterfaces() throws Exception {
        List<io.nekohasekai.libbox.NetworkInterface> result = new ArrayList<>();
        for (Network network : connectivity.getAllNetworks()) {
            if (!usable(network)) continue;
            LinkProperties links = connectivity.getLinkProperties(network);
            NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
            if (links == null || links.getInterfaceName() == null || caps == null) continue;
            java.net.NetworkInterface nativeInterface = java.net.NetworkInterface.getByName(links.getInterfaceName());
            if (nativeInterface == null) continue;
            io.nekohasekai.libbox.NetworkInterface item = new io.nekohasekai.libbox.NetworkInterface();
            item.setName(nativeInterface.getName());
            item.setIndex(nativeInterface.getIndex());
            item.setMTU(nativeInterface.getMTU());
            int flags = OsConstants.IFF_UP | OsConstants.IFF_RUNNING;
            if (nativeInterface.isLoopback()) flags |= OsConstants.IFF_LOOPBACK;
            if (nativeInterface.isPointToPoint()) flags |= OsConstants.IFF_POINTOPOINT;
            if (nativeInterface.supportsMulticast()) flags |= OsConstants.IFF_MULTICAST;
            item.setFlags(flags);
            item.setType(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? Libbox.InterfaceTypeWIFI
                    : caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? Libbox.InterfaceTypeCellular
                    : caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? Libbox.InterfaceTypeEthernet : Libbox.InterfaceTypeOther);
            item.setMetered(!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
            List<String> addresses = new ArrayList<>(), dns = new ArrayList<>(), gateways = new ArrayList<>();
            for (LinkAddress address : links.getLinkAddresses()) addresses.add(address.toString());
            for (java.net.InetAddress address : links.getDnsServers()) dns.add(address.getHostAddress());
            for (RouteInfo route : links.getRoutes()) if (route.isDefaultRoute() && route.getGateway() != null) gateways.add(route.getGateway().getHostAddress());
            item.setAddresses(strings(addresses));
            item.setDNSServer(strings(dns));
            item.setGateway(strings(gateways));
            result.add(item);
        }
        Iterator<io.nekohasekai.libbox.NetworkInterface> iterator = result.iterator();
        return new NetworkInterfaceIterator() {
            public boolean hasNext() { return iterator.hasNext(); }
            public io.nekohasekai.libbox.NetworkInterface next() { return iterator.next(); }
        };
    }

    static StringIterator strings(List<String> values) {
        Iterator<String> iterator = values.iterator();
        return new StringIterator() {
            public int len() { return values.size(); }
            public boolean hasNext() { return iterator.hasNext(); }
            public String next() { return iterator.next(); }
        };
    }
    public boolean underNetworkExtension() { return false; }
    public boolean includeAllNetworks() { return false; }
    public LocalDNSTransport localDNSTransport() { return null; }
    public void clearDNSCache() { }
    public WIFIState readWIFIState() { return null; }
    public void registerMyInterface(String name) { }
    public void sendNotification(io.nekohasekai.libbox.Notification notification) { }
    public void cancelNotification(String id, int type) { }
    public void startNeighborMonitor(NeighborUpdateListener listener) { }
    public void closeNeighborMonitor(NeighborUpdateListener listener) { }
    public boolean usePlatformShell() { return false; }
    public boolean usePlatformBridge() { return false; }
    public void checkPlatformShell() { throw new UnsupportedOperationException(); }
    public ShellSession openShellSession(PlatformUser user, String cmd, StringIterator env, String term, int rows, int cols) { throw new UnsupportedOperationException(); }
    public String readSystemSSHHostKey() { throw new UnsupportedOperationException(); }
    public String lookupSFTPServer() { throw new UnsupportedOperationException(); }
    public PlatformUser lookupUser(String name) { throw new UnsupportedOperationException(); }
    public String tailscaleHostname() { return "PocketKit"; }
    public BridgeSession createBridge(BridgeOptions options) { throw new UnsupportedOperationException(); }
}
