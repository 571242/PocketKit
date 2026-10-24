package cn.xianhua.pocketkit.automation;

/**
 * Test-only stand-in for the real coordinator: AutomationWindows only asks it for the attached service, and a
 * self-test has none. Never compiled into the app; see run.sh.
 */
final class AutomationRuntime {
    private AutomationRuntime() { }
    static android.accessibilityservice.AccessibilityService service() { return null; }
}
