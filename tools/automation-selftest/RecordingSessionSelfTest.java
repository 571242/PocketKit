package tools.automation_selftest;

import cn.xianhua.pocketkit.core.automation.*;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Executable JVM checks, including the exact path sent to the runner backend. */
public final class RecordingSessionSelfTest {
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void rejects(Runnable call){try{call.run();throw new AssertionError("expected rejection");}catch(IllegalArgumentException expected){}}
    public static void main(String[] args)throws Exception {
        Script original=new Script();original.name="原稿";Step old=new Step(Step.Type.WAIT);old.durationMs=50;original.steps.add(old);
        RecordingSession session=new RecordingSession(original,1080,1920);
        Step tap=session.gesture(new int[]{100,200,102,201},120,8);
        require(tap.type==Step.Type.TAP,"small movement stays a tap");
        session.append(tap,1000,1150);
        require(session.snapshot().steps.size()==2,"no artificial wait before first gesture");
        Step longPress=session.gesture(new int[]{120,200},650,8);
        require(longPress.type==Step.Type.LONG_PRESS&&longPress.durationMs==650,"long press duration");
        session.append(longPress,1500,2200);
        Script saved=session.snapshot();require(saved.steps.get(2).type==Step.Type.WAIT&&saved.steps.get(2).durationMs==350,"timing excludes replay time");
        int[] curved={20,40,70,130,160,80,20,40};
        Step swipe=session.gesture(curved,700,8);require(swipe.type==Step.Type.SWIPE&&Arrays.equals(swipe.gesturePath,curved),"curved closed swipe retained");
        session.breakTiming();session.append(swipe,10000,10800);
        require(session.snapshot().steps.size()==5,"paused navigation is not recorded as waiting");
        require(session.undo()&&session.actionCount()==2&&session.snapshot().steps.size()==4,"undo action and its interval atomically");
        session.undo();require(session.snapshot().steps.size()==2,"undo removes preceding recorded wait");
        session.undo();require(session.snapshot().steps.size()==1&&!session.undo(),"undo never removes original steps");
        require(original.width==0&&original.steps.size()==1&&original.name.equals("原稿"),"recording/cancel leaves original untouched");
        require(session.gesture(new int[]{0,0,1079,1919},9999,8).type==Step.Type.SWIPE,"valid screen edges");
        rejects(()->session.gesture(new int[]{0,0,1080,100},100,8));
        rejects(()->session.gesture(new int[]{1,2,3},100,8));
        rejects(()->session.gesture(new int[]{1,2},10001,8));
        Script wrongSize=new Script();wrongSize.width=720;wrongSize.height=1280;rejects(()->new RecordingSession(wrongSize,1080,1920));
        Step broken=swipe.copy();broken.gesturePath=new int[]{1,2,3};rejects(()->broken.validate(0));
        Step outside=swipe.copy();outside.gesturePath=new int[]{1,2,2000,2};Script boundary=new Script();boundary.width=1080;boundary.height=1920;boundary.steps.add(outside);rejects(boundary::validate);
        Step copy=swipe.copy();copy.gesturePath[0]=99;require(swipe.gesturePath[0]==20,"path copy is isolated");
        Step tooLong=new Step(Step.Type.LONG_PRESS);tooLong.durationMs=60001;rejects(()->tooLong.validate(0));
        Script play=new Script();play.steps.add(swipe);CountDownLatch completed=new CountDownLatch(1);Fake backend=new Fake();
        new Runner(backend,new Runner.Listener(){public void onStatus(String value){}public void onFinished(String value){backend.outcome=value;completed.countDown();}}).start(play);
        require(completed.await(3,TimeUnit.SECONDS)&&"completed".equals(backend.outcome),"recorded path executes");
        require(Arrays.equals(backend.path,new int[]{30,60,80,150,170,100,30,60}),"every path point is mapped, not just endpoints");
        require(backend.duration==700,"recorded duration reaches backend");
        System.out.println("RecordingSessionSelfTest PASS: tap/hold/curved-path, timing, pause, atomic undo, cancel isolation, bounds, deep copy, runner path mapping");
    }
    private static final class Fake implements Runner.Backend {
        int[] path;long duration;String outcome;
        public boolean isTargetAvailable(String name){return true;}
        public int[] mapPoint(Script s,int x,int y,Runner.Control control){return new int[]{x+10,y+20};}
        public void tap(int x,int y,long timeout,Runner.Control control){}
        public void longPress(int x,int y,long duration,long timeout,Runner.Control control){}
        public void swipe(int x,int y,int x2,int y2,long duration,long timeout,Runner.Control control){throw new AssertionError("curved path flattened");}
        public void swipePath(int[] path,long duration,long timeout,Runner.Control control){this.path=path;this.duration=duration;}
        public void inputText(String value,long timeout,Runner.Control control){}
        public void globalAction(Step.Type type,long timeout,Runner.Control control){}
        public Match find(Step step,Map<String,String> variables,long timeout,Runner.Control control){return Match.notFound();}
        public String readText(Step step,Map<String,String> variables,long timeout,Runner.Control control){return "";}
    }
}
