package cn.xianhua.pocketkit.device;
public final class ScanSessionSelfTest {
    public static void main(String[] args) {
        ScanSession session = new ScanSession();
        ScanSession.Token first = session.start();
        check(session.isCurrent(first));
        ScanSession.Token second = session.start();
        check(first.isCancelled()); check(!session.isCurrent(first)); check(session.isCurrent(second));
        session.cancel(); check(second.isCancelled()); check(!session.isCurrent(second));
        ScanSession.Token third = session.start();
        check(second.isCancelled()); check(session.isCurrent(third));
        Thread.currentThread().interrupt(); check(third.isCancelled()); Thread.interrupted();
        System.out.println("scan-session selftest: PASS (restart, stale confirmation, stop, interruption)");
    }
    static void check(boolean value) { if (!value) throw new AssertionError(); }
}
