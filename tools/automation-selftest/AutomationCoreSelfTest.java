package tools.automation_selftest;

import cn.xianhua.pocketkit.core.automation.Match;
import cn.xianhua.pocketkit.core.automation.Runner;
import cn.xianhua.pocketkit.core.automation.Script;
import cn.xianhua.pocketkit.core.automation.Step;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Pure JVM assertions for the non-Android state machine. */
public final class AutomationCoreSelfTest {
    private static final class Fake implements Runner.Backend {
        int taps; int finds; int before; boolean cancelledPending; Match next=Match.notFound();
        int lastTapX,lastTapY; boolean allowRawTap;
        public boolean isTargetAvailable(String p){return true;}
        public void beforeAction(Script s,Runner.Control c){before++;}
        public int[] mapPoint(Script s,int x,int y,Runner.Control c){return new int[]{x+10,y+20};}
        public void cancelPending(){cancelledPending=true;}
        public void tap(int x,int y,long t,Runner.Control c){if(!allowRawTap&&(x!=17||y!=29))throw new AssertionError("coordinate mapping lost");lastTapX=x;lastTapY=y;taps++;}
        public void longPress(int x,int y,long d,long t,Runner.Control c){taps++;}
        public void swipe(int x,int y,int x2,int y2,long d,long t,Runner.Control c){taps++;}
        public void inputText(String s,long t,Runner.Control c){if(s.contains("secret"))throw new AssertionError("input leaked to fake log");}
        public void globalAction(Step.Type t,long timeout,Runner.Control c){ }
        public Match find(Step s,Map<String,String> v,long t,Runner.Control c){finds++;Match m=next;next=Match.notFound();return m;}
        public String readText(Step s,Map<String,String> v,long t,Runner.Control c){return "read";}
    }
    private static Runner run(Script s,Fake fake)throws Exception{return run(s,fake,"completed");}
    private static Runner run(Script s,Fake fake,String expected)throws Exception{CountDownLatch done=new CountDownLatch(1);Runner[] holder=new Runner[1];holder[0]=new Runner(fake,new Runner.Listener(){public void onStatus(String value){}public void onFinished(String value){done.countDown();}});holder[0].start(s);if(!done.await(5,TimeUnit.SECONDS))throw new AssertionError("runner did not finish: "+holder[0].status());String actual=holder[0].status();if(expected!=null&&!actual.equals(expected)&&!(expected.endsWith("*")&&actual.startsWith(expected.substring(0,expected.length()-1))))throw new AssertionError("expected status "+expected+", got "+actual);return holder[0];}
    private static void rejects(Runnable action,String name){try{action.run();throw new AssertionError("expected rejection: "+name);}catch(IllegalArgumentException expected){}}
    public static void main(String[]args)throws Exception{
        Fake fake=new Fake();
        Script nested=new Script();Step setChoice=new Step(Step.Type.SET_VARIABLE);setChoice.variable="choice";setChoice.value="yes";nested.steps.add(setChoice);Step branch=new Step(Step.Type.IF_VARIABLE);branch.variable="choice";branch.operator="equals";branch.value="yes";Step tap=new Step(Step.Type.TAP);tap.x=7;tap.y=9;branch.children.add(tap);branch.otherwise.add(new Step(Step.Type.HOME));Step loop=new Step(Step.Type.LOOP);loop.count=2;loop.children.add(branch);nested.steps.add(loop);run(nested,fake);if(fake.taps!=2)throw new AssertionError("nested branch/loop taps="+fake.taps);
        Script matchScript=new Script();Step ifText=new Step(Step.Type.IF_TEXT);ifText.text="go";Step matchedTap=new Step(Step.Type.TAP);matchedTap.useLastMatch=true;ifText.children.add(matchedTap);matchScript.steps.add(ifText);fake.next=new Match(true,10,20,24,38,"go",1);run(matchScript,fake);if(fake.taps!=3)throw new AssertionError("IF lastMatch not used");
        Script fail=new Script();Step waitImage=new Step(Step.Type.WAIT_IMAGE);waitImage.templateId="template";waitImage.timeoutMs=120;waitImage.intervalMs=10;fail.steps.add(waitImage);Runner failed=run(fail,fake,"failed:*");if(failed.lastMatch().found)throw new AssertionError("recognition failure retained old match");
        Script timed=new Script();timed.timeoutMs=100;Step wait=new Step(Step.Type.WAIT);wait.durationMs=1000;timed.steps.add(wait);Runner timeout=run(timed,fake,"failed:*");if(!timeout.status().contains("超时"))throw new AssertionError("timeout status="+timeout.status());
        Script cancellable=new Script();Step longWait=new Step(Step.Type.WAIT);longWait.durationMs=3000;cancellable.steps.add(longWait);CountDownLatch stopped=new CountDownLatch(1);Runner r=new Runner(fake,new Runner.Listener(){public void onStatus(String value){}public void onFinished(String value){stopped.countDown();}});r.start(cancellable);Thread.sleep(40);r.pause();if(!r.isPaused())throw new AssertionError("pause not visible");r.resume();r.cancel("test cancel");if(!stopped.await(2,TimeUnit.SECONDS)||!fake.cancelledPending)throw new AssertionError("cancel did not invalidate pending action");
        Script recover=new Script();Step recoverWait=new Step(Step.Type.WAIT);recoverWait.durationMs=180;recover.steps.add(recoverWait);Step recoverTap=new Step(Step.Type.TAP);recoverTap.x=7;recoverTap.y=9;recover.steps.add(recoverTap);CountDownLatch recovered=new CountDownLatch(1);Runner recoveredRunner=new Runner(fake,new Runner.Listener(){public void onStatus(String value){}public void onFinished(String value){if(!"completed".equals(value))throw new AssertionError("resume status="+value);recovered.countDown();}});recoveredRunner.start(recover);Thread.sleep(25);recoveredRunner.pause();Thread.sleep(120);if(!recoveredRunner.isPaused()||!recoveredRunner.isActive())throw new AssertionError("pause lost queued script");recoveredRunner.resume();if(!recovered.await(2,TimeUnit.SECONDS))throw new AssertionError("resume did not finish queued script");
        Script pauseTimeout=new Script();pauseTimeout.timeoutMs=100;Step pauseWait=new Step(Step.Type.WAIT);pauseWait.durationMs=1000;pauseTimeout.steps.add(pauseWait);runPausedTimeout(pauseTimeout,fake);
        Script incomplete=new Script();Step incompleteImage=new Step(Step.Type.WAIT_IMAGE);incomplete.steps.add(incompleteImage);try{new Runner(fake,null).start(incomplete);throw new AssertionError("incomplete runtime script was accepted");}catch(IllegalArgumentException expected){}
        Script input=new Script();Step set=new Step(Step.Type.SET_VARIABLE);set.variable="v";set.value="safe";input.steps.add(set);Step text=new Step(Step.Type.INPUT_TEXT);text.text="$"+"{v}";input.steps.add(text);run(input,fake);
        Script compare=new Script();Step setNumber=new Step(Step.Type.SET_VARIABLE);setNumber.variable="n";setNumber.value="5";compare.steps.add(setNumber);Step greater=new Step(Step.Type.IF_VARIABLE);greater.variable="n";greater.operator="greater";greater.value="3";Step gtTap=new Step(Step.Type.TAP);gtTap.x=7;gtTap.y=9;greater.children.add(gtTap);compare.steps.add(greater);run(compare,fake);
        Script loopWait=new Script();Step infinite=new Step(Step.Type.LOOP);infinite.count=3;infinite.intervalMs=20;Step loopChild=new Step(Step.Type.SET_VARIABLE);loopChild.variable="x";loopChild.value="1";infinite.children.add(loopChild);loopWait.steps.add(infinite);long start=System.nanoTime();run(loopWait,fake);if((System.nanoTime()-start)/1_000_000<35)throw new AssertionError("loop interval was skipped");
        Script appointment=new Script();appointment.startDelayMs=160;appointment.timeoutMs=50;Step appointmentTap=new Step(Step.Type.TAP);appointmentTap.x=7;appointmentTap.y=9;appointment.steps.add(appointmentTap);long appointmentStart=System.nanoTime();run(appointment,fake);if((System.nanoTime()-appointmentStart)/1_000_000<140)throw new AssertionError("appointment budget started too early");
        Script bad=new Script();Step badStep=new Step(Step.Type.IF_TEXT);badStep.text="x";badStep.operator="bad";bad.steps.add(badStep);rejects(bad::validate,"unknown operator");Step dup=new Step(Step.Type.WAIT);dup.id="same";Script dupScript=new Script();dupScript.steps.add(dup);Step dup2=new Step(Step.Type.WAIT);dup2.id="same";dupScript.steps.add(dup2);rejects(dupScript::validate,"duplicate id");
        repeatRounds(fake);clickMatchActions(fake);repeatBounds();
        System.out.println("AutomationCoreSelfTest PASS: appointment budget, IF lastMatch, cancellation gate, mapping, loop interval, comparisons, repeats, click-match, strict validation");
    }
    private static void repeatRounds(Fake fake)throws Exception{
        fake.taps=0;
        Script once=new Script();Step onceTap=new Step(Step.Type.TAP);onceTap.x=7;onceTap.y=9;once.steps.add(onceTap);run(once,fake);if(fake.taps!=1)throw new AssertionError("default repeatCount must run once, taps="+fake.taps);
        fake.taps=0;
        Script three=new Script();three.repeatCount=3;three.repeatIntervalMs=40;Step threeTap=new Step(Step.Type.TAP);threeTap.x=7;threeTap.y=9;three.steps.add(threeTap);
        long started=System.nanoTime();run(three,fake);long elapsed=(System.nanoTime()-started)/1_000_000;if(fake.taps!=3)throw new AssertionError("repeatCount=3 taps="+fake.taps);if(elapsed<75)throw new AssertionError("repeatIntervalMs was skipped: "+elapsed+"ms");
        fake.taps=0;
        Script spinning=new Script();spinning.repeatCount=0;spinning.repeatIntervalMs=100;Step spinTap=new Step(Step.Type.TAP);spinTap.x=7;spinTap.y=9;spinning.steps.add(spinTap);
        CountDownLatch spinDone=new CountDownLatch(1);Runner spin=new Runner(fake,new Runner.Listener(){public void onStatus(String value){}public void onFinished(String value){spinDone.countDown();}});
        spin.start(spinning);Thread.sleep(360);spin.cancel("stop infinite");if(!spinDone.await(2,TimeUnit.SECONDS))throw new AssertionError("infinite repeat did not stop");if(fake.taps<2||fake.taps>6)throw new AssertionError("infinite repeat busy-looped or stalled, taps="+fake.taps+" in 360ms");
        fake.taps=0;
        Script limited=new Script();limited.repeatCount=0;limited.repeatIntervalMs=20;limited.timeoutMs=150;Step limitedTap=new Step(Step.Type.TAP);limitedTap.x=7;limitedTap.y=9;limited.steps.add(limitedTap);
        Runner deadline=run(limited,fake,"failed:*");if(!deadline.status().contains("超时"))throw new AssertionError("infinite repeat ignored the total budget: "+deadline.status());
        Script stale=new Script();stale.repeatCount=2;stale.repeatIntervalMs=1;
        Step roundOne=new Step(Step.Type.IF_VARIABLE);roundOne.variable="v";roundOne.operator="notEquals";roundOne.value="2";
        Step onlyOnce=new Step(Step.Type.WAIT_IMAGE);onlyOnce.templateId="t";onlyOnce.timeoutMs=200;onlyOnce.intervalMs=10;roundOne.children.add(onlyOnce);
        Step markDone=new Step(Step.Type.SET_VARIABLE);markDone.variable="v";markDone.value="2";roundOne.children.add(markDone);
        stale.steps.add(roundOne);
        Step staleTap=new Step(Step.Type.TAP);staleTap.useLastMatch=true;stale.steps.add(staleTap);
        fake.taps=0;fake.allowRawTap=true;fake.next=new Match(true,100,200,140,260,"hit",1);
        Runner staleRunner=run(stale,fake,"failed:*");
        fake.allowRawTap=false;
        if(!staleRunner.status().contains("识别失败"))throw new AssertionError("round two reused the previous round's match: "+staleRunner.status());
        if(fake.taps!=1)throw new AssertionError("round two still tapped the stale match, taps="+fake.taps);
    }
    private static void clickMatchActions(Fake fake)throws Exception{
        fake.taps=0;fake.before=0;fake.allowRawTap=true;
        Script waitClick=new Script();Step waitText=new Step(Step.Type.WAIT_TEXT);waitText.text="go";waitText.timeoutMs=200;waitText.intervalMs=10;waitText.clickMatch=true;waitClick.steps.add(waitText);Step after=new Step(Step.Type.TAP);after.x=7;after.y=9;after.useLastMatch=true;waitClick.steps.add(after);
        fake.next=new Match(true,100,200,140,260,"go",1);run(waitClick,fake);
        if(fake.taps!=2)throw new AssertionError("WAIT_TEXT clickMatch taps="+fake.taps);
        if(fake.lastTapX!=120||fake.lastTapY!=230)throw new AssertionError("hit center was tapped as-is, got "+fake.lastTapX+","+fake.lastTapY);
        if(fake.before<2)throw new AssertionError("clickMatch skipped the safety prepare");
        fake.taps=0;
        Script ifImage=new Script();Step ifStep=new Step(Step.Type.IF_IMAGE);ifStep.templateId="t";ifStep.timeoutMs=100;ifStep.clickMatch=true;ifStep.children.add(new Step(Step.Type.HOME));ifImage.steps.add(ifStep);
        fake.next=new Match(true,0,0,10,10,"",1);run(ifImage,fake);if(fake.taps!=1)throw new AssertionError("IF_IMAGE hit did not click, taps="+fake.taps);
        fake.taps=0;fake.next=Match.notFound();run(ifImage,fake);if(fake.taps!=0)throw new AssertionError("IF_IMAGE miss still clicked");
        fake.allowRawTap=false;
    }
    private static void repeatBounds(){
        Script negative=new Script();negative.repeatCount=-1;rejects(negative::validate,"negative repeatCount");
        Script badInterval=new Script();badInterval.repeatIntervalMs=-5;rejects(badInterval::validate,"negative repeatIntervalMs");
        Script huge=new Script();huge.repeatIntervalMs=8L*24*3600_000;rejects(huge::validate,"oversized repeatIntervalMs");
        Script wrongType=new Script();Step tap=new Step(Step.Type.TAP);tap.clickMatch=true;wrongType.steps.add(tap);rejects(wrongType::validate,"clickMatch on a tap");
        Script emptySpin=new Script();emptySpin.repeatCount=0;rejects(emptySpin::validate,"empty script that repeats until stopped");
        new Script().validate();
    }
    private static void runPausedTimeout(Script s,Fake fake)throws Exception{CountDownLatch done=new CountDownLatch(1);Runner r=new Runner(fake,new Runner.Listener(){public void onStatus(String value){}public void onFinished(String value){if(!value.contains("超时"))throw new AssertionError("paused timeout status="+value);done.countDown();}});r.start(s);Thread.sleep(25);r.pause();if(!done.await(2,TimeUnit.SECONDS))throw new AssertionError("paused total budget did not expire");}
    private static Script loopWaitScript(Script s){Step child=s.steps.get(0);child.variable="x";child.value="1";return add(s,child);}
    private static Script add(Script s,Step child){s.steps.add(child);return s;}
}
