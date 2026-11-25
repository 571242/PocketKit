# Standalone JVM regression. All generated files stay under .scratch; no APK is built.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$jdk = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'F:\mlbuild\tools\jdk-17.0.20.1+1' }
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { 'F:\mlbuild\sdk' }
$androidJar = Join-Path $sdk 'platforms\android-36\android.jar'
$work = Join-Path $root '.scratch\proxy-bypass-test'
$pm = Join-Path $work 'stub\android\content\pm\PackageManager.java'
$vpn = Join-Path $work 'stub\android\net\VpnService.java'
$classes = Join-Path $work 'classes'
New-Item -ItemType Directory -Force -Path (Split-Path $pm), (Split-Path $vpn), $classes | Out-Null
@'
package android.content.pm;
public class PackageManager {
    public static class NameNotFoundException extends Exception {
        public NameNotFoundException(String message) { super(message); }
    }
}
'@ | Set-Content -LiteralPath $pm -Encoding ASCII
@'
package android.net;
import android.content.pm.PackageManager;
import java.util.HashSet;
import java.util.Set;
public class VpnService {
    public static class Builder {
        public final Set<String> applied = new HashSet<>();
        public Builder addDisallowedApplication(String name) throws PackageManager.NameNotFoundException {
            if (name.equals("missing.app")) throw new PackageManager.NameNotFoundException(name);
            if (name.equals("broken.app")) throw new IllegalStateException("unexpected failure");
            applied.add(name);
            return this;
        }
    }
}
'@ | Set-Content -LiteralPath $vpn -Encoding ASCII
& (Join-Path $jdk 'bin\javac.exe') -encoding UTF-8 -cp $androidJar -d $classes $pm $vpn `
    (Join-Path $root 'app\src\main\java\cn\xianhua\pocketkit\proxy\ProxyBypass.java') `
    (Join-Path $PSScriptRoot 'ProxyBypassSelfTest.java')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& (Join-Path $jdk 'bin\java.exe') -cp "$classes;$androidJar" cn.xianhua.pocketkit.proxy.ProxyBypassSelfTest
exit $LASTEXITCODE
