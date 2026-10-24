package cn.xianhua.pocketkit.automation;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import cn.xianhua.pocketkit.core.automation.AdSkipRules;
import cn.xianhua.pocketkit.core.automation.Match;
import cn.xianhua.pocketkit.core.automation.Runner;
import cn.xianhua.pocketkit.core.automation.Script;
import cn.xianhua.pocketkit.core.automation.Step;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/** Accessibility adapter; every framework call is posted to main and awaited off-main. */
public final class AccessibilityBackend implements Runner.Backend {
    private final AccessibilityService service; private final Handler main=new Handler(Looper.getMainLooper()); private final AtomicLong generation=new AtomicLong(); private volatile Script activeScript;
    public AccessibilityBackend(AccessibilityService value){service=value;}
    /** Shared package boundary for passive accessibility actions; user-authored scripts keep their own target guard. */
    public static boolean isAdSkipPackageAllowed(String packageName,String ownPackage){return AdSkipRules.isAllowedPackage(packageName,ownPackage);}
    public boolean isTargetAvailable(String targetPackage){if(targetPackage==null||targetPackage.isEmpty())return true;if(service==null)return false;try{return submit(()->targetPackage.equals(activePackage()),500,new NoopControl());}catch(Exception ex){return false;}}
    public void beforeAction(Script script,Runner.Control control)throws Exception{activeScript=script;submit(()->{if(service==null)throw new IllegalStateException("无障碍服务未连接");KeyguardManager keyguard=service.getSystemService(KeyguardManager.class);if(keyguard!=null&&keyguard.isKeyguardLocked())throw new IllegalStateException("屏幕已锁定，不会操作锁屏");String target=script.targetPackage;if(target!=null&&!target.isEmpty()&&!target.equals(activePackage()))throw new IllegalStateException("目标 App 不在前台，已停止");DisplayMetrics m=new DisplayMetrics();service.getSystemService(WindowManager.class).getDefaultDisplay().getRealMetrics(m);if(script.width>0&&((script.width>script.height)!=(m.widthPixels>m.heightPixels)))throw new IllegalStateException("屏幕方向已变化，请重新录制点位");return null;},500,control);}
    public int[] mapPoint(Script script,int x,int y,Runner.Control control)throws Exception{if(script.width==0||script.height==0)return new int[]{x,y};return submit(()->{DisplayMetrics m=new DisplayMetrics();service.getSystemService(WindowManager.class).getDefaultDisplay().getRealMetrics(m);if((script.width>script.height)!=(m.widthPixels>m.heightPixels))throw new IllegalStateException("屏幕方向已变化，请重新录制点位");return new int[]{Math.round(x*(m.widthPixels/(float)script.width)),Math.round(y*(m.heightPixels/(float)script.height))};},500,control);}
    @Override public int[] mapPath(Script script,int[] points,Runner.Control control)throws Exception {
        if(script.width==0||script.height==0)return points.clone();
        return submit(()->{
            DisplayMetrics m=new DisplayMetrics();service.getSystemService(WindowManager.class).getDefaultDisplay().getRealMetrics(m);
            if((script.width>script.height)!=(m.widthPixels>m.heightPixels))throw new IllegalStateException("屏幕方向已变化，请重新录制点位");
            int[] mapped=new int[points.length];
            for(int i=0;i<points.length;i+=2){mapped[i]=Math.round(points[i]*(m.widthPixels/(float)script.width));mapped[i+1]=Math.round(points[i+1]*(m.heightPixels/(float)script.height));}
            return mapped;
        },500,control);
    }
    @Override public void swipePath(int[] points,long duration,long timeoutMs,Runner.Control control)throws Exception {
        Path path=new Path();path.moveTo(points[0],points[1]);
        for(int i=2;i<points.length;i+=2)path.lineTo(points[i],points[i+1]);
        gesture(path,duration,timeoutMs,control);
    }
    public void tap(int x,int y,long timeoutMs,Runner.Control control)throws Exception{gesture(x,y,x,y,30,timeoutMs,control);}
    public void longPress(int x,int y,long durationMs,long timeoutMs,Runner.Control control)throws Exception{gesture(x,y,x,y,durationMs,timeoutMs,control);}
    public void swipe(int x,int y,int x2,int y2,long durationMs,long timeoutMs,Runner.Control control)throws Exception{gesture(x,y,x2,y2,durationMs,timeoutMs,control);}
    private void gesture(int x,int y,int x2,int y2,long duration,long timeoutMs,Runner.Control control)throws Exception{Path path=new Path();path.moveTo(x,y);if(x2!=x||y2!=y)path.lineTo(x2,y2);gesture(path,duration,timeoutMs,control);}
    private void gesture(Path path,long duration,long timeoutMs,Runner.Control control)throws Exception{if(service==null)throw new IllegalStateException("无障碍服务未连接");final long token=generation.incrementAndGet();CountDownLatch done=new CountDownLatch(1);boolean[]accepted={false},ok={false};Throwable[]error={null};Runnable[]dispatch=new Runnable[1];dispatch[0]=()->{try{if(token!=generation.get()||control.isCancelled())throw new InterruptedException("手势已取消");if(control.isPaused()){main.postDelayed(dispatch[0],25);return;}guardCurrentOnMain();GestureDescription g=new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path,0,Math.max(1,duration))).build();accepted[0]=service.dispatchGesture(g,new AccessibilityService.GestureResultCallback(){public void onCompleted(GestureDescription d){if(token==generation.get()&&!control.isCancelled())ok[0]=true;done.countDown();}public void onCancelled(GestureDescription d){done.countDown();}},main);if(!accepted[0])done.countDown();}catch(Throwable t){error[0]=t;done.countDown();}};main.post(dispatch[0]);await(done,timeoutMs,control);if(error[0]!=null)throwAs(error[0]);if(!accepted[0]||!ok[0])throw new IllegalStateException("系统拒绝或取消手势");}
    public void inputText(String text,long timeoutMs,Runner.Control control)throws Exception{submit(()->{guardCurrentOnMain();AccessibilityNodeInfo root=service.getRootInActiveWindow();AccessibilityNodeInfo node=null;try{node=root==null?null:root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);if(node==null||!node.isEditable())throw new IllegalStateException("请先点击并聚焦可编辑输入框");Bundle b=new Bundle();b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text);if(!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,b))throw new IllegalStateException("输入控件拒绝操作");return null;}finally{if(node!=null&&node!=root)node.recycle();if(root!=null)root.recycle();}},timeoutMs,control);}
    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo root){if(root==null)return null;ArrayDeque<AccessibilityNodeInfo>queue=new ArrayDeque<>();queue.add(AccessibilityNodeInfo.obtain(root));int seen=0;try{while(!queue.isEmpty()&&seen++<400){AccessibilityNodeInfo node=queue.removeFirst();if(node.isEditable()&&!node.isPassword()){AccessibilityNodeInfo result=AccessibilityNodeInfo.obtain(node);node.recycle();while(!queue.isEmpty())queue.removeFirst().recycle();return result;}for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo child=node.getChild(i);if(child!=null)queue.add(child);}node.recycle();}while(!queue.isEmpty())queue.removeFirst().recycle();return null;}catch(RuntimeException ex){while(!queue.isEmpty())queue.removeFirst().recycle();throw ex;}}
    public void globalAction(Step.Type type,long timeoutMs,Runner.Control control)throws Exception{submit(()->{guardCurrentOnMain();int action=type==Step.Type.BACK?AccessibilityService.GLOBAL_ACTION_BACK:type==Step.Type.HOME?AccessibilityService.GLOBAL_ACTION_HOME:AccessibilityService.GLOBAL_ACTION_RECENTS;if(!service.performGlobalAction(action))throw new IllegalStateException("系统拒绝全局操作");return null;},timeoutMs,control);}
    public Match find(Step step,Map<String,String>variables,long timeoutMs,Runner.Control control)throws Exception{if(step.type==Step.Type.WAIT_TEXT||step.type==Step.Type.IF_TEXT){submit(()->{guardCurrentOnMain();return null;},Math.min(timeoutMs,500),control);return AutomationVision.findText(service,step.text,step.textSource,step.matchMode,region(step),timeoutMs);}if(step.templateId.isEmpty())return Match.notFound();submit(()->{guardCurrentOnMain();return null;},Math.min(timeoutMs,500),control);android.graphics.Bitmap b=new TemplateStore(service).load(step.templateId);try{return AutomationVision.findImage(service,b,region(step),step.threshold,timeoutMs);}finally{if(b!=null)b.recycle();}}
    public String readText(Step step,Map<String,String>variables,long timeoutMs,Runner.Control control)throws Exception{submit(()->{guardCurrentOnMain();return null;},Math.min(timeoutMs,500),control);return AutomationVision.readText(service,region(step),timeoutMs);}
    public void cancelPending(){generation.incrementAndGet();activeScript=null;}
    private void guardCurrentOnMain() throws Exception { Script s=activeScript;if(service==null)throw new IllegalStateException("无障碍服务未连接");KeyguardManager keyguard=service.getSystemService(KeyguardManager.class);if(keyguard!=null&&keyguard.isKeyguardLocked())throw new IllegalStateException("屏幕已锁定，不会操作锁屏");if(s!=null&&s.targetPackage!=null&&!s.targetPackage.isEmpty()&&!s.targetPackage.equals(activePackage()))throw new IllegalStateException("目标 App 不在前台，已停止");if(s!=null&&s.width>0){DisplayMetrics m=new DisplayMetrics();service.getSystemService(WindowManager.class).getDefaultDisplay().getRealMetrics(m);if((s.width>s.height)!=(m.widthPixels>m.heightPixels))throw new IllegalStateException("屏幕方向已变化，请重新录制点位");}}
    private Match findNodes(String query,String mode,Rect requested){Pattern pattern="regex".equals(mode)?Pattern.compile(query):null;AccessibilityNodeInfo root=service.getRootInActiveWindow();if(root==null)return Match.notFound();ArrayDeque<AccessibilityNodeInfo>queue=new ArrayDeque<>();queue.add(root);int seen=0;try{while(!queue.isEmpty()&&seen++<400){AccessibilityNodeInfo node=queue.removeFirst();try{if(node.isVisibleToUser()&&!node.isPassword()){Rect b=new Rect();node.getBoundsInScreen(b);if(requested==null||Rect.intersects(requested,b)){CharSequence value=node.getText();if(value==null||value.length()==0)value=node.getContentDescription();String text=value==null?"":value.toString();boolean hit="exact".equals(mode)?text.equals(query):"regex".equals(mode)?pattern.matcher(text).find():text.contains(query);if(hit)return new Match(true,b.left,b.top,b.right,b.bottom,text,1d);}}for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo child=node.getChild(i);if(child!=null)queue.add(child);}}finally{node.recycle();}}}finally{while(!queue.isEmpty())queue.removeFirst().recycle();}return Match.notFound();}
    private String activePackage(){AccessibilityNodeInfo root=service.getRootInActiveWindow();if(root==null)return"";try{return String.valueOf(root.getPackageName());}finally{root.recycle();}}
    private Rect region(Step s){return s.left==0&&s.top==0&&s.right==0&&s.bottom==0?null:new Rect(s.left,s.top,s.right,s.bottom);}
    private <T>T submit(Callable<T>task,long timeoutMs,Runner.Control control)throws Exception{final long token=generation.get();CountDownLatch done=new CountDownLatch(1);Object[]result={null};Throwable[]error={null};Runnable[]gate=new Runnable[1];gate[0]=()->{try{if(token!=generation.get()||control.isCancelled())throw new InterruptedException("队列操作已取消");if(control.isPaused()){main.postDelayed(gate[0],25);return;}result[0]=task.call();}catch(Throwable t){error[0]=t;done.countDown();return;}done.countDown();};main.post(gate[0]);await(done,timeoutMs,control);if(error[0]!=null)throwAs(error[0]);return(T)result[0];}
    private void await(CountDownLatch done,long timeoutMs,Runner.Control control)throws Exception{long end=System.nanoTime()+Math.max(1,timeoutMs)*1_000_000L;while(System.nanoTime()<end){control.awaitIfPaused();if(control.isCancelled()){generation.incrementAndGet();throw new InterruptedException("cancelled");}if(done.await(25,TimeUnit.MILLISECONDS))return;}generation.incrementAndGet();throw new java.util.concurrent.TimeoutException("操作超时");}
    private static void throwAs(Throwable t)throws Exception{if(t instanceof Exception)throw(Exception)t;if(t instanceof Error)throw(Error)t;throw new IllegalStateException(t);}
    private static final class NoopControl implements Runner.Control{public boolean isCancelled(){return false;}public boolean isPaused(){return false;}public void awaitIfPaused(){} }
}
