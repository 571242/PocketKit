"""Exercise the real cloud transport with fake Android networks, without a device or real TLS bypass."""
import os
import pathlib
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = ROOT / '.scratch/cloud-transport-selftest'
JDK = pathlib.Path(os.environ.get('JAVA_HOME', 'F:/mlbuild/tools/jdk-17.0.20.1+1'))
ANDROID = pathlib.Path('F:/mlbuild/sdk/platforms/android-36/android.jar')
JSON = ROOT / '.scratch/proxy-repair/org.json.jar'

SOURCES = {
'android/content/Context.java': r'''
package android.content;
public abstract class Context {
    public static final int MODE_PRIVATE=0;
    public Context getApplicationContext(){return this;}
    public abstract <T> T getSystemService(Class<T> type);
    public SharedPreferences getSharedPreferences(String n,int m){throw new UnsupportedOperationException();}
    public java.io.File getFilesDir(){throw new UnsupportedOperationException();}
    public android.content.res.AssetManager getAssets(){throw new UnsupportedOperationException();}
}
''',
'android/net/Network.java': r'''
package android.net;
public abstract class Network {
    public abstract java.net.URLConnection openConnection(java.net.URL url) throws java.io.IOException;
}
''',
'android/net/NetworkCapabilities.java': r'''
package android.net;
public final class NetworkCapabilities {
    public static final int NET_CAPABILITY_NOT_VPN=15, NET_CAPABILITY_INTERNET=12;
    private final boolean physical;
    public NetworkCapabilities(boolean physical){this.physical=physical;}
    public boolean hasCapability(int c){return c==NET_CAPABILITY_INTERNET || (c==NET_CAPABILITY_NOT_VPN && physical);}
}
''',
'android/net/ConnectivityManager.java': r'''
package android.net;
public abstract class ConnectivityManager {
    public abstract Network getActiveNetwork();
    public abstract Network[] getAllNetworks();
    public abstract NetworkCapabilities getNetworkCapabilities(Network n);
}
''',
'cn/xianhua/pocketkit/proxy/CloudTransportSelfTest.java': r'''
package cn.xianhua.pocketkit.proxy;
import android.content.Context;
import android.net.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

public final class CloudTransportSelfTest {
    static int physicalCalls, activeCalls, checks;
    static String scenario="ok", location="";
    static byte[] body="{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
    static final String HUB="https://hub.example/api/check-update";
    static final Network physical=new Network(){public URLConnection openConnection(URL u)throws IOException{
        physicalCalls++;
        if(scenario.equals("reset")||scenario.equals("both-reset"))throw new SocketException("Connection reset");
        return connection(u);
    }};
    static final Network vpn=new Network(){public URLConnection openConnection(URL u){throw new AssertionError("VPN chosen as physical");}};
    static HttpURLConnection connection(URL u){return new HttpURLConnection(u){
        public void connect(){} public void disconnect(){} public boolean usingProxy(){return false;}
        public int getResponseCode(){return location.isEmpty()?200:302;}
        public String getHeaderField(String k){return "Location".equals(k)?location:null;}
        public InputStream getInputStream(){
            if(scenario.equals("partial-reset"))return new InputStream(){int n;
                public int read()throws IOException{if(n++<5)return 'x';throw new SocketException("Connection reset");}};
            return new ByteArrayInputStream(body);
        }
    };}
    static final ConnectivityManager manager=new ConnectivityManager(){
        public Network getActiveNetwork(){return vpn;}
        public Network[] getAllNetworks(){return new Network[]{vpn,physical};}
        public NetworkCapabilities getNetworkCapabilities(Network n){return new NetworkCapabilities(n==physical);}
    };
    static final Context context=new Context(){public <T>T getSystemService(Class<T>type){return type.cast(manager);}};
    static void reset(String s){scenario=s;location="";physicalCalls=activeCalls=0;body="{\"ok\":true}".getBytes(StandardCharsets.UTF_8);}
    static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    interface Work{void run()throws Exception;}
    static void rejects(Work work,String label)throws Exception{try{work.run();throw new AssertionError(label);}catch(IOException expected){checks++;}}
    public static void main(String[]args)throws Exception{
        URL.setURLStreamHandlerFactory(protocol->"https".equals(protocol)?new URLStreamHandler(){
            protected URLConnection openConnection(URL u)throws IOException{activeCalls++;
                if(scenario.equals("both-reset"))throw new SocketException("Connection reset");
                return connection(u);
            }
        }:null);
        ProxyRulesStore store=new ProxyRulesStore(context);
        reset("ok");check(store.fetchManifest(HUB).getBoolean("ok"),"manifest decoded");
        check(physicalCalls==1&&activeCalls==0,"physical network first, VPN not selected");
        reset("reset");check(store.fetchManifest(HUB).getBoolean("ok"),"reset recovers via active network");
        check(physicalCalls==1&&activeCalls==1,"exactly one fresh fallback");
        reset("both-reset");rejects(()->store.fetchManifest(HUB),"persistent reset must fail");
        check(physicalCalls==1&&activeCalls==1,"persistent reset is bounded");
        reset("ok");location="https://evil.example/api/check-update";
        rejects(()->store.fetchManifest(HUB),"cross origin redirect refused");
        check(activeCalls==0,"security rejection is not retried");
        reset("ok");location=HUB;rejects(()->store.fetchManifest(HUB),"redirect loop bounded");
        check(physicalCalls==6&&activeCalls==0,"redirect budget maintained");
        reset("ok");rejects(()->store.fetchManifest("http://hub.example/api/check-update"),"plaintext refused");
        check(physicalCalls==0&&activeCalls==0,"plaintext never reaches network");
        reset("ok");body="{\"schemaVersion\":1,\"rules\":{\"direct\":{\"domain_suffix\":[\"baidu.com\"]},\"proxy\":{}}}".getBytes(StandardCharsets.UTF_8);
        String sha=ProxyRules.sha256Hex(body),url="https://hub.example/rules/"+sha+".json";
        check(store.download(HUB,url,sha,body.length,1).rules.directSuffix.contains("baidu.com"),"verified rules decode");
        int length=body.length;
        rejects(()->store.download(HUB,url,sha,length+1,1),"wrong length rejected");
        String wrong="0".repeat(64);
        rejects(()->store.download(HUB,"https://hub.example/rules/"+wrong+".json",wrong,length,1),"wrong digest rejected");
        reset("partial-reset");rejects(()->store.fetchManifest(HUB),"partial reset never accepted as data");
        check(physicalCalls==1&&activeCalls==1,"partial reset retries only once");
        reset("ok");body=new byte[ProxyRules.MAX_BYTES+1];
        rejects(()->store.fetchManifest(HUB),"oversized payload rejected");
        check(activeCalls==0,"size rejection is not retried");
        System.out.println("Cloud transport self-test passed ("+checks+" checks)");
    }
}
'''
}


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    sources = []
    for name, text in SOURCES.items():
        path = OUT / 'src' / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding='utf-8')
        sources.append(str(path))
    classes = OUT / 'classes'
    classes.mkdir(exist_ok=True)
    source_dir = ROOT / 'app/src/main/java/cn/xianhua/pocketkit/proxy'
    cp = os.pathsep.join([str(JSON), str(ANDROID)])
    subprocess.run([str(JDK / 'bin/javac.exe'), '-encoding', 'UTF-8', '-cp', cp,
                    '-d', str(classes), *sources, str(source_dir / 'ProxyRules.java'),
                    str(source_dir / 'ProxyRulesStore.java')], check=True)
    subprocess.run([str(JDK / 'bin/java.exe'), '-cp', os.pathsep.join([str(classes), cp]),
                    'cn.xianhua.pocketkit.proxy.CloudTransportSelfTest'], check=True)


if __name__ == '__main__':
    main()
