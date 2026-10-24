package cn.xianhua.pocketkit.proxy;

/**
 * Test-only stand-in for ProxyState, compiled instead of the real one so the persistence test does
 * not depend on the VPN service / libbox native classes. Kept out of the app source tree.
 */
public final class ProxyState {
    public enum Mode {
        RULE("Rule"), GLOBAL("Global"), DIRECT("Direct");
        public final String coreValue;
        Mode(String coreValue) { this.coreValue = coreValue; }
    }
}
