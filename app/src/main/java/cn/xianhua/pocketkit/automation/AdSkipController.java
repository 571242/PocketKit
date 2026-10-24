package cn.xianhua.pocketkit.automation;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.content.Context;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import cn.xianhua.pocketkit.core.automation.AdSkipRules;
import cn.xianhua.pocketkit.core.automation.AdSkipSession;
import java.util.ArrayDeque;

/** Foreground events -> bounded node match -> click -> observable disappearance. No fixed coordinates. */
public final class AdSkipController {
    private static final String PREFS="ad_skip";
    private static final ArrayDeque<String> TRACE=new ArrayDeque<>();
    private static volatile String status="尚未收到无障碍事件";
    private static long events,biliEvents,musicEvents;
    private final AccessibilityService service;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final AdSkipSession session=new AdSkipSession();
    private boolean queued,closed;
    private long lastScan,lastLog,retryUntil;
    private String activity="",activityPackage="",lastReason="";
    private String pendingKey="";
    private long verifyAt;
    private boolean pendingAccepted,pendingTrusted;
    private int nodesSeen;
    private final Runnable scanTask=()->{queued=false;scan();};

    public AdSkipController(AccessibilityService service){this.service=service;trace("SERVICE_CONNECTED","");}
    public static boolean isEnabled(Context c){return c.getSharedPreferences(PREFS,0).getBoolean("enabled",false);}
    public static void setEnabled(Context c,boolean enabled){c.getSharedPreferences(PREFS,0).edit().putBoolean("enabled",enabled).apply();status=enabled?"已开启，等待应用窗口事件":"主动跳过已关闭";}
    public static String status(){return status;}
    public static String exclusions(Context c){return c.getSharedPreferences(PREFS,0).getString("excluded","");}
    public static void setExclusions(Context c,String value){
        StringBuilder clean=new StringBuilder();for(String name:value.split("[\\s,，;；]+")){
            if(name.isEmpty())continue;if(!name.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))throw new IllegalArgumentException("无效包名："+name);
            if(clean.length()>0)clean.append('\n');clean.append(name);
        }c.getSharedPreferences(PREFS,0).edit().putString("excluded",clean.toString()).apply();
    }
    public static synchronized String diagnostics(Context c){
        StringBuilder out=new StringBuilder("开屏跳过诊断 v2\n开启=").append(isEnabled(c)).append("\n事件=").append(events).append(" 哔哩哔哩=").append(biliEvents).append(" 网易云=").append(musicEvents).append("\n状态=").append(status).append('\n');
        if(TRACE.isEmpty())out.append(c.getSharedPreferences(PREFS,0).getString("trace","尚无日志；请确认无障碍服务已连接"));else for(String line:TRACE)out.append(line).append('\n');
        return out.toString();
    }
    private void trace(String stage,String detail){
        long now=SystemClock.elapsedRealtime();String line=now+" "+stage+" "+detail;
        synchronized(AdSkipController.class){TRACE.addLast(line);while(TRACE.size()>100)TRACE.removeFirst();service.getSharedPreferences(PREFS,0).edit().putString("trace",String.join("\n",TRACE)).apply();}
    }
    private void reason(String code,String text){status=text;long now=SystemClock.elapsedRealtime();if(!code.equals(lastReason)||now-lastLog>2000){lastReason=code;lastLog=now;trace(code,text);}}
    private boolean excluded(String pkg){for(String name:exclusions(service).split("\\n"))if(name.equals(pkg))return true;return false;}
    public void suspend(){session.suspend();pendingKey="";main.removeCallbacks(scanTask);queued=false;reason("PAUSED","自动化运行，跳过器暂停");}
    public void close(){closed=true;main.removeCallbacksAndMessages(null);pendingKey="";trace("SERVICE_DISCONNECTED","");}
    public void onAccessibilityEvent(AccessibilityEvent event){
        if(closed||event==null)return;
        String pkg=event.getPackageName()==null?"":event.getPackageName().toString();
        synchronized(AdSkipController.class){events++;if(AdSkipRules.BILI.equals(pkg))biliEvents++;if(AdSkipRules.MUSIC.equals(pkg))musicEvents++;}
        if(!isEnabled(service)){status="主动跳过已关闭";return;}
        if(AutomationRuntime.isActive()){suspend();return;}
        long now=SystemClock.elapsedRealtime();
        if(event.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED){
            String cl=event.getClassName()==null?"":event.getClassName().toString();
            if(!cl.startsWith("android.")&&!cl.startsWith("androidx.")&&!pkg.isEmpty()){activity=cl;activityPackage=pkg;}
            retryUntil=now+1500;
        }
        schedule(Math.max(0,120-(now-lastScan)));
    }
    private void schedule(long delay){if(!closed&&!queued){queued=true;main.postDelayed(scanTask,delay);}}
    private boolean safeNow(String pkg){
        KeyguardManager keyguard=service.getSystemService(KeyguardManager.class);
        return !closed&&isEnabled(service)&&!AutomationRuntime.isActive()&&(keyguard==null||!keyguard.isKeyguardLocked())
                &&AdSkipRules.isAllowedPackage(pkg,service.getPackageName())&&!excluded(pkg);
    }
    private void scan(){
        if(closed||!isEnabled(service))return;
        if(AutomationRuntime.isActive()){suspend();return;}
        lastScan=SystemClock.elapsedRealtime();AccessibilityNodeInfo root=service.getRootInActiveWindow();
        if(root==null){reason("ROOT_NULL","收到事件，但当前窗口暂未提供节点");if(lastScan<retryUntil)schedule(180);return;}
        Candidate candidate=null;
        try{
            String pkg=String.valueOf(root.getPackageName());
            if(session.observe(pkg,lastScan)){pendingKey="";trace("WINDOW",pkg);}
            if(!safeNow(pkg)){session.suspend();reason("EXCLUDED","当前窗口被排除、受保护或屏幕已锁定");return;}
            String act=pkg.equals(activityPackage)?activity:"";
            if(AdSkipRules.excludedActivity(pkg,act)){session.suspend();reason("ACTIVITY_EXCLUDED",pkg+" "+act);return;}
            boolean trustedWindow=AdSkipRules.BILI.equals(pkg);
            if(!session.canInspect(pkg,lastScan,trustedWindow)&&pendingKey.isEmpty()){reason("WINDOW_DONE",pkg+"：窗口到期或已用完尝试次数");return;}
            candidate=find(root,pkg,act,session.withinWindow(lastScan)||!pendingKey.isEmpty());
            if(!pendingKey.isEmpty()){
                if(lastScan<verifyAt){schedule(verifyAt-lastScan);return;}
                if(candidate==null||!pendingKey.equals(candidate.key)){
                    if(pendingAccepted){session.confirmed();trace("NODE_DISAPPEARED",pkg+" 点击后匹配节点已消失");status="点击后跳过节点已消失";}
                    else trace("TARGET_CHANGED",pkg+" 目标已变化，未计为跳过成功");
                    pendingKey="";pendingAccepted=false;
                }else{
                    trace("VERIFY_STILL_PRESENT",pkg+" 按钮仍在，准备受限重试");pendingKey="";
                }
            }
            if(candidate==null){reason("NO_MATCH",pkg+"：已扫描 "+nodesSeen+" 个节点，未找到跳过按钮");if(session.withinWindow(lastScan))schedule(250);return;}
            boolean trusted=candidate.score>=100;
            if(!session.canInspect(pkg,lastScan,trusted)||!session.canAttempt(lastScan)){if(session.withinWindow(lastScan))schedule(250);return;}
            if(!candidate.node.refresh()){reason("STALE_NODE","节点已变化，重新读取");schedule(150);return;}
            // Recheck after refresh, not against a stale rectangle from the original event.
            Rect fresh=new Rect();candidate.node.getBoundsInScreen(fresh);DisplayMetrics metrics=metrics();
            int score=AdSkipRules.score(pkg,act,candidate.node.getViewIdResourceName(),candidate.node.getText(),candidate.node.getContentDescription(),fresh.left,fresh.top,fresh.right,fresh.bottom,metrics.widthPixels,metrics.heightPixels,session.withinWindow(lastScan));
            if(score==0||!candidate.node.isVisibleToUser()||!candidate.node.isEnabled()||candidate.node.isEditable()||candidate.node.isPassword()){schedule(150);return;}
            session.attempted(lastScan);pendingKey=candidate.key;pendingTrusted=trusted;pendingAccepted=false;verifyAt=lastScan+700;
            trace("MATCH",pkg+" rule="+(trusted?"bili-id":AdSkipRules.MUSIC.equals(pkg)?"music-text":"skip-text")+" id="+candidate.node.getViewIdResourceName()+" bounds="+fresh.toShortString()+" attempt="+session.attempts());
            // If ACTION_CLICK returned true but did nothing, a retry uses a fresh-node gesture instead.
            boolean clicked=session.attempts()==1&&clickNodeOrParent(candidate.node,pkg,fresh);
            if(clicked){pendingAccepted=true;trace("CLICK_ACCEPTED",pkg+" ACTION_CLICK=true；等待确认");status="已发送节点点击，等待按钮消失";schedule(700);}
            else gesture(pkg,candidate.key,fresh);
        }catch(RuntimeException e){reason("ERROR",e.getClass().getSimpleName()+": "+e.getMessage());if(lastScan<retryUntil)schedule(250);}
        finally{if(candidate!=null)candidate.node.recycle();root.recycle();}
    }
    private DisplayMetrics metrics(){DisplayMetrics m=new DisplayMetrics();service.getSystemService(WindowManager.class).getDefaultDisplay().getRealMetrics(m);return m;}
    private Candidate find(AccessibilityNodeInfo root,String pkg,String act,boolean within){
        DisplayMetrics m=metrics();ArrayDeque<AccessibilityNodeInfo> queue=new ArrayDeque<>();queue.add(AccessibilityNodeInfo.obtain(root));Candidate best=null;nodesSeen=0;
        try{
            while(!queue.isEmpty()&&nodesSeen++<1200){AccessibilityNodeInfo node=queue.removeFirst();try{
                if(node.isVisibleToUser()&&node.isEnabled()&&!node.isPassword()&&!node.isEditable()&&pkg.equals(String.valueOf(node.getPackageName()))){
                    Rect b=new Rect();node.getBoundsInScreen(b);int score=AdSkipRules.score(pkg,act,node.getViewIdResourceName(),node.getText(),node.getContentDescription(),b.left,b.top,b.right,b.bottom,m.widthPixels,m.heightPixels,within);
                    if(score>0&&(best==null||score>best.score)){if(best!=null)best.node.recycle();best=new Candidate(AccessibilityNodeInfo.obtain(node),score,pkg+"/"+(node.getViewIdResourceName()==null?"text-skip":node.getViewIdResourceName()));}
                }
                for(int i=0;i<node.getChildCount()&&queue.size()<1200;i++){AccessibilityNodeInfo child=node.getChild(i);if(child!=null)queue.add(child);}
            }finally{node.recycle();}}
            return best;
        }catch(RuntimeException e){if(best!=null)best.node.recycle();throw e;}
        finally{while(!queue.isEmpty())queue.removeFirst().recycle();}
    }
    private boolean clickNodeOrParent(AccessibilityNodeInfo node,String pkg,Rect target){
        if(node.isClickable()&&node.performAction(AccessibilityNodeInfo.ACTION_CLICK))return true;
        AccessibilityNodeInfo parent=node.getParent();DisplayMetrics m=metrics();
        for(int depth=0;parent!=null&&depth<3;depth++){
            AccessibilityNodeInfo current=parent;parent=null;
            try{Rect b=new Rect();current.getBoundsInScreen(b);
                if(!pkg.equals(String.valueOf(current.getPackageName()))||current.isEditable()||current.isPassword()||!b.contains(target)||!AdSkipRules.smallBounds(b.left,b.top,b.right,b.bottom,m.widthPixels,m.heightPixels))return false;
                if(current.isVisibleToUser()&&current.isEnabled()&&current.isClickable()&&current.performAction(AccessibilityNodeInfo.ACTION_CLICK))return true;
                parent=current.getParent();
            }finally{current.recycle();}
        }if(parent!=null)parent.recycle();return false;
    }
    private void gesture(String pkg,String key,Rect rect){
        if(!safeNow(pkg)){pendingKey="";return;}
        AccessibilityNodeInfo current=service.getRootInActiveWindow();
        try{if(current==null||!pkg.equals(String.valueOf(current.getPackageName()))){pendingKey="";trace("GESTURE_ABORTED","前台已变化");return;}}
        finally{if(current!=null)current.recycle();}
        Path path=new Path();path.moveTo(rect.exactCenterX(),rect.exactCenterY());
        boolean accepted=service.dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path,0,45)).build(),new AccessibilityService.GestureResultCallback(){
            @Override public void onCompleted(GestureDescription gesture){if(closed||!key.equals(pendingKey))return;pendingAccepted=true;verifyAt=SystemClock.elapsedRealtime()+400;trace("GESTURE_COMPLETED",pkg+" 等待按钮消失");schedule(420);}
            @Override public void onCancelled(GestureDescription gesture){if(closed||!key.equals(pendingKey))return;pendingAccepted=false;trace("GESTURE_CANCELLED",pkg);schedule(700);}
        },main);
        trace("GESTURE_DISPATCH",pkg+" accepted="+accepted);status=accepted?"已发送匹配节点手势，等待确认":"系统拒绝手势，稍后重试";schedule(750);
    }
    private static final class Candidate{final AccessibilityNodeInfo node;final int score;final String key;Candidate(AccessibilityNodeInfo n,int s,String k){node=n;score=s;key=k;}}
}
