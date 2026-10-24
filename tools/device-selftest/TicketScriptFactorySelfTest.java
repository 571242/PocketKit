import cn.xianhua.pocketkit.automation.TicketScriptFactory;
import cn.xianhua.pocketkit.core.TicketClickPlan;
import cn.xianhua.pocketkit.core.automation.Script;
import cn.xianhua.pocketkit.core.automation.Step;
import cn.xianhua.pocketkit.device.TicketRecipe;

/** Pure conversion assertions for the legacy 071 recipe adapter. */
public final class TicketScriptFactorySelfTest {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static TicketRecipe recipe(int interval) {
        TicketRecipe recipe = new TicketRecipe();
        recipe.packageName = "com.example.ticket";
        recipe.width = 1080;
        recipe.height = 1920;
        recipe.intervalMs = interval;
        recipe.timeoutSeconds = 30;
        return recipe;
    }

    public static void main(String[] args) {
        TicketRecipe one = recipe(250);
        one.points.add(new TicketClickPlan.Point(10, 20));
        one.labels.add("");
        Script repeated = TicketScriptFactory.fromRecipe(one, 1_000L, 400L, true);
        check(repeated.startDelayMs == 600L, "calibrated delay becomes monotonic startDelayMs");
        check(repeated.timeoutMs == 30_000L, "timeout remains the post-delay run window");
        check(repeated.steps.size() == 1 && repeated.steps.get(0).type == Step.Type.LOOP, "repeat creates one shared loop");
        check(repeated.steps.get(0).children.size() == 2, "single-point repeat includes trailing interval");
        check(repeated.steps.get(0).children.get(0).type == Step.Type.TAP, "empty first label keeps coordinate tap");
        check(!repeated.steps.get(0).children.get(0).useLastMatch, "empty first label does not use stale match");
        check(repeated.steps.get(0).children.get(1).durationMs == 250L, "single-point repeat interval is retained");

        TicketRecipe labeled = recipe(400);
        labeled.points.add(new TicketClickPlan.Point(30, 40));
        labeled.points.add(new TicketClickPlan.Point(50, 60));
        labeled.labels.add(null);
        labeled.labels.add("购买按钮");
        Script once = TicketScriptFactory.fromRecipe(labeled, 2_000L, 2_000L, false);
        check(once.steps.size() == 5, "two points include two trailing waits and one label wait");
        check(once.steps.get(2).type == Step.Type.WAIT_TEXT, "non-empty label becomes a wait/recognition step");
        check("购买按钮".equals(once.steps.get(2).text), "label text is preserved");
        check("nodes".equals(once.steps.get(2).textSource), "legacy visible label uses accessibility nodes");
        check(once.steps.get(3).type == Step.Type.TAP && once.steps.get(3).useLastMatch, "labeled point taps the recognized match");
        check(once.steps.get(4).type == Step.Type.WAIT && once.steps.get(4).durationMs == 400L, "last point keeps selected interval");
        System.out.println("PASS: TicketScriptFactory checks=" + checks + " old recipe shape preserved");
    }
}
