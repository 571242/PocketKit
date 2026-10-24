package cn.xianhua.pocketkit.automation;


/**
 * Pure-JVM assertions for the overlay edge/visibility geometry. Compiled against the SDK android.jar only for
 * types; every asserted method is plain int math. No device, WindowManager or touch claim is made here.
 */
public final class OverlayGeometrySelfTest {
    public static void main(String[] args) {
        int width = 1080, ball = 130;
        // A released window snaps only near an edge; the middle stays free.
        require(AutomationWindows.edgeFor(8, 248, width) == 0, "left edge snap");
        require(AutomationWindows.edgeFor(width - 248 - 8, 248, width) == 1, "right edge snap");
        require(AutomationWindows.edgeFor(400, 248, width) == -1, "middle must not snap");
        // A wide panel collapsing to a ball picks the nearer side and lands fully on screen.
        require(AutomationWindows.snapEdge(700, 248, width) == 1, "right-half panel collapses right");
        require(AutomationWindows.snapEdge(20, 248, width) == 0, "left-half panel collapses left");
        require(AutomationWindows.edgeX(1, ball, width) == width - ball, "ball flush with right edge");
        require(AutomationWindows.edgeX(0, ball, width) == 0, "ball flush with left edge");
        // Clamp never returns an off-screen or inverted range, even for a window wider than the screen.
        require(AutomationWindows.clampWithin(-200, 0, 900) == 0, "clamp low");
        require(AutomationWindows.clampWithin(5000, 0, 900) == 900, "clamp high");
        require(AutomationWindows.clampWithin(5, 900, 100) == 900, "inverted range keeps minimum");
        require(AutomationWindows.clampWithin(400, 0, 900) == 400, "clamp keeps valid value");
        // Rotation: a ball parked at the old right edge must stay visible in the narrower width.
        int rotated = 400;
        require(AutomationWindows.clampWithin(width - ball, 0, Math.max(0, rotated - ball)) == rotated - ball,
                "rotated clamp keeps ball visible");
        System.out.println("OverlayGeometrySelfTest PASS: snap/edge/ball/clamp/rotation");
    }
    private static void require(boolean ok, String label) { if (!ok) throw new AssertionError(label); }
}
