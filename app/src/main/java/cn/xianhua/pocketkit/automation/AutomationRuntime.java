package cn.xianhua.pocketkit.automation;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import cn.xianhua.pocketkit.core.automation.Runner;
import cn.xianhua.pocketkit.core.automation.Script;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Process-local coordinator with a visible-control gate and generation-isolated callbacks. */
public final class AutomationRuntime {
    private static final Object LOCK=new Object(); private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static AccessibilityService service; private static Runner runner; private static Script pending; private static long generation; private static String status="未连接无障碍服务";
    private static final ArrayDeque<String> LOGS=new ArrayDeque<>(); private static final ArrayList<Runnable> LISTENERS=new ArrayList<>();
    private AutomationRuntime(){}
    public static void attach(AccessibilityService value){synchronized(LOCK){service=value;status="无障碍服务已连接";notifyListeners();}}
    public static void detach(AccessibilityService value){synchronized(LOCK){if(service==value){pending=null;stopLocked("无障碍服务已断开");service=null;AutomationRecorderService.stop(value);AutomationPickerService.stop(value);AutomationOverlayService.stop(value);notifyListeners();}}}
    public static boolean isEnabled(){synchronized(LOCK){return service!=null;}}
    public static void start(Context context,Script script){if(context==null||script==null)throw new IllegalArgumentException("上下文和脚本不能为空");script.validate();synchronized(LOCK){if(service==null)throw new IllegalStateException("请先开启无障碍服务");AutomationPickerService.stop(context);AutomationRecorderService.stop(context);stopLocked("新任务已替换");pending=script.copy();AutomationOverlayService.remember(script);status="等待悬浮控制";append("脚本已预约，等待可见控制入口");AutomationOverlayService.show(context.getApplicationContext());notifyListeners();}}
    public static void controlsReady(){synchronized(LOCK){if(pending!=null&&service!=null){Script s=pending;pending=null;startRunnerLocked(s);}else{status="控制已就绪";}notifyListeners();}}
    public static void controlsClosed(){synchronized(LOCK){pending=null;stopLocked("悬浮控制已关闭，自动任务已停止");notifyListeners();}}
    public static void pause(){synchronized(LOCK){if(runner!=null)runner.pause();status="已暂停";notifyListeners();}}
    public static void resume(){synchronized(LOCK){if(runner!=null)runner.resume();status="运行中";notifyListeners();}}
    public static void stop(String reason){synchronized(LOCK){pending=null;stopLocked(reason==null?"已停止":reason);notifyListeners();}}
    public static boolean isActive(){synchronized(LOCK){return pending!=null||(runner!=null&&runner.isActive());}}
    public static boolean isPaused(){synchronized(LOCK){return runner!=null&&runner.isPaused();}}
    public static AccessibilityService service(){synchronized(LOCK){return service;}}
    public static String status(){synchronized(LOCK){return status;}}
    public static List<String> logs(){synchronized(LOCK){return new ArrayList<>(LOGS);}}
    public static void addListener(Runnable listener){if(listener==null)return;synchronized(LOCK){if(!LISTENERS.contains(listener))LISTENERS.add(listener);}}
    public static void removeListener(Runnable listener){synchronized(LOCK){LISTENERS.remove(listener);}}
    private static void startRunnerLocked(Script script){final long token=++generation;status="运行中";append("脚本开始执行");runner=new Runner(new AccessibilityBackend(service),new Runner.Listener(){public void onStatus(String value){synchronized(LOCK){if(token!=generation)return;status=display(value);if(value!=null&&value.startsWith("step:"))append("执行步骤: "+value.substring(5));notifyListeners();}}public void onFinished(String value){synchronized(LOCK){if(token!=generation)return;status=display(value);runner=null;append("脚本结束: "+status);notifyListeners();}}});runner.start(script);}
    private static void stopLocked(String reason){generation++;if(runner!=null){Runner old=runner;runner=null;old.cancel(reason);}status=reason;append(reason);}
    private static String display(String value){if(value==null)return"未知状态";if(value.startsWith("step:"))return"执行中";if("scheduled".equals(value))return"等待预约";if("running".equals(value))return"运行中";if("paused".equals(value))return"已暂停";if("completed".equals(value))return"已完成";if("cancelled".equals(value))return"已取消";if(value.startsWith("failed:"))return"失败："+value.substring(7);return value;}
    private static void append(String line){LOGS.addLast(line);while(LOGS.size()>200)LOGS.removeFirst();}
    private static void notifyListeners(){final ArrayList<Runnable> snapshot;synchronized(LOCK){snapshot=new ArrayList<>(LISTENERS);}if(!Looper.getMainLooper().isCurrentThread()){MAIN.post(()->dispatch(snapshot));}else dispatch(snapshot);}
    private static void dispatch(List<Runnable>listeners){for(Runnable listener:listeners){try{listener.run();}catch(RuntimeException ignored){}}}
}
