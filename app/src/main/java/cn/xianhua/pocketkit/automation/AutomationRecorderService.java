package cn.xianhua.pocketkit.automation;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import cn.xianhua.pocketkit.AutomationToolsActivity;
import cn.xianhua.pocketkit.ToolUi;
import cn.xianhua.pocketkit.core.automation.RecordingSession;
import cn.xianhua.pocketkit.core.automation.Script;
import cn.xianhua.pocketkit.core.automation.Step;
import java.util.ArrayList;
import java.util.UUID;

/** Explicit single-finger capture, followed by a visible, user-requested gesture replay. */
public final class AutomationRecorderService extends Service {
    private static final String CHANNEL="automation_recording", CANCEL="recording.cancel";
    private static final int NOTIFICATION_ID=986;
    private static volatile AutomationRecorderService instance;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ArrayList<Integer> points=new ArrayList<>();
    private AutomationWindows windows;
    private LinearLayout bar;
    private View capture;
    private TextView message;
    private Button toggle, undo, save, back, home;
    private ScriptStore store;
    private RecordingSession session;
    private String scriptId, recoveryId, targetPackage;
    private WindowManager.LayoutParams barParams;
    private boolean paused=true, busy, resultSent, multiTouch;
    private int width, height, generation;
    private long downAt, lastSampleAt, cancelAt;

    public static boolean isRecording() { AutomationRecorderService recorder=instance;return recorder!=null&&!recorder.resultSent; }
    public static void start(Context context,String id) {
        if (!Settings.canDrawOverlays(context)) throw new IllegalStateException("请先开启悬浮窗权限");
        if (!AutomationRuntime.isEnabled()) throw new IllegalStateException("请先开启无障碍服务");
        if (isRecording()) throw new IllegalStateException("正在录制，请先在悬浮栏完成或取消");
        AutomationRuntime.stop("开始录制");
        AutomationOverlayService.stop(context);
        AutomationPickerService.stop(context);
        context.startForegroundService(new Intent(context,AutomationRecorderService.class).putExtra("scriptId",id));
    }
    public static void stop(Context context) { context.stopService(new Intent(context,AutomationRecorderService.class)); }
    public static void setCaptureHidden(boolean hidden) {
        AutomationRecorderService value=instance;
        if(value!=null)value.handler.post(()->{if(value.bar!=null)value.bar.setVisibility(hidden?View.INVISIBLE:View.VISIBLE);if(value.capture!=null)value.capture.setVisibility(hidden?View.INVISIBLE:View.VISIBLE);});
    }
    @Override public void onCreate() {
        super.onCreate(); instance=this;
        NotificationChannel channel=new NotificationChannel(CHANNEL,"连点器操作录制",NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
    @Override public int onStartCommand(Intent intent,int flags,int id) {
        if(intent!=null&&CANCEL.equals(intent.getAction())) { finishRecording(false,true); return START_NOT_STICKY; }
        try {
            scriptId=intent==null?null:intent.getStringExtra("scriptId");
            Notification notification=notification("准备录制：请切到目标应用后点开始");
            if(Build.VERSION.SDK_INT>=34)startForeground(NOTIFICATION_ID,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(NOTIFICATION_ID,notification);
            if(!Settings.canDrawOverlays(this)||!AutomationRuntime.isEnabled())throw new IllegalStateException("录制需要悬浮窗和无障碍权限");
            windows=new AutomationWindows(this); DisplayMetrics m=windows.metrics(); width=m.widthPixels; height=m.heightPixels;
            store=new ScriptStore(this);Script original=store.load(scriptId);targetPackage=original.targetPackage;session=new RecordingSession(original,width,height);
            recoveryId=UUID.randomUUID().toString();
            createBar();
        } catch(Exception ex) { sendResult("error",safeMessage(ex)); Toast.makeText(this,safeMessage(ex),Toast.LENGTH_LONG).show(); stopSelf(); }
        return START_NOT_STICKY;
    }
    private Notification notification(String text) {
        PendingIntent stop=PendingIntent.getService(this,987,new Intent(this,AutomationRecorderService.class).setAction(CANCEL),PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open=PendingIntent.getActivity(this,988,editorIntent(),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder=new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_edit)
                .setContentTitle("连点器 · 正在录制").setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
                .setContentIntent(open).addAction(new Notification.Action.Builder(null,"取消录制",stop).build());
        if(Build.VERSION.SDK_INT>=31)builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return builder.build();
    }
    private void createBar() {
        bar=new LinearLayout(this);bar.setOrientation(LinearLayout.VERTICAL);bar.setPadding(dp(8),0,dp(8),dp(6));
        bar.setBackground(ToolUi.fill(Color.rgb(23,52,55),dp(14),Color.rgb(101,206,193),dp(1)));
        TextView drag=text("录制操作 · 拖动移动",13);bar.addView(drag,new LinearLayout.LayoutParams(-1,dp(36)));
        message=text("切到目标应用后开始。单指操作松手后会实际执行。",13);message.setMinHeight(dp(52));bar.addView(message);
        LinearLayout first=row();toggle=button(first,"开始录制",this::toggleRecording);undo=button(first,"撤销",this::undo);save=button(first,"完成",()->finishRecording(true,true));bar.addView(first);
        LinearLayout second=row();back=button(second,"返回",()->global(Step.Type.BACK));home=button(second,"主页",()->global(Step.Type.HOME));button(second,"取消",this::confirmCancel);bar.addView(second);
        barParams=windows.bar();windows.draggable(drag,bar,barParams);windows.manager.addView(bar,barParams);refreshControls();
    }
    private void toggleRecording() {
        if(busy)return;
        if(!paused){paused=true;session.breakTiming();removeCapture();setMessage("已暂停，可自由操作。继续后只记录新动作。");}
        else {try {guard();paused=false;session.breakTiming();installCapture();setMessage("录制中：松手后执行，等“已录”后再操作。");}catch(Exception ex){pauseWithError(safeMessage(ex));}}
        refreshControls();
    }
    private void installCapture() {
        if(capture==null){capture=new View(this);capture.setBackgroundColor(Color.TRANSPARENT);capture.setOnTouchListener((v,e)->touch(e));capture.setContentDescription("操作录制触摸层");}
        if(!capture.isAttachedToWindow()) {
            windows.remove(bar); windows.manager.addView(capture,windows.fullscreen()); windows.manager.addView(bar,barParams);
            WindowManager.LayoutParams p=(WindowManager.LayoutParams)bar.getLayoutParams(); windows.draggable(bar.getChildAt(0),bar,p);
        }
    }
    private void removeCapture() { if(windows!=null)windows.remove(capture); }
    private boolean touch(MotionEvent event) {
        if(paused||busy)return true;
        switch(event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downAt=event.getEventTime();lastSampleAt=0;multiTouch=false;points.clear();addPoint(event);return true;
            case MotionEvent.ACTION_POINTER_DOWN:multiTouch=true;return true;
            case MotionEvent.ACTION_MOVE:
                if(event.getPointerCount()>1)multiTouch=true;
                if(event.getEventTime()-lastSampleAt>=16)addPoint(event);return true;
            case MotionEvent.ACTION_UP:
                addPoint(event);
                if(multiTouch){pauseWithError("暂不支持多指录制，本次手势未执行。");return true;}
                try {
                    guard();int[] path=new int[points.size()];for(int i=0;i<path.length;i++)path[i]=points.get(i);
                    Step action=session.gesture(path,event.getEventTime()-downAt,ViewConfiguration.get(this).getScaledTouchSlop());
                    replay(action);
                } catch(Exception ex){pauseWithError(safeMessage(ex));}
                return true;
            case MotionEvent.ACTION_CANCEL:points.clear();return true;
            default:return true;
        }
    }
    private void addPoint(MotionEvent event) {
        int x=Math.max(0,Math.min(width-1,Math.round(event.getRawX()))), y=Math.max(0,Math.min(height-1,Math.round(event.getRawY())));
        if(points.size()>=RecordingSession.MAX_PATH_VALUES){ArrayList<Integer> smaller=new ArrayList<>();for(int i=0;i<points.size();i+=4){smaller.add(points.get(i));smaller.add(points.get(i+1));}points.clear();points.addAll(smaller);}
        if(points.size()<2||points.get(points.size()-2)!=x||points.get(points.size()-1)!=y){points.add(x);points.add(y);}
        lastSampleAt=event.getEventTime();
    }
    private void replay(Step action) {
        if(session.actionCount()>=RecordingSession.MAX_ACTIONS){pauseWithError("已录制500个动作，请先完成保存。");return;}
        busy=true;removeCapture();setMessage("正在执行本次手势…");refreshControls();final int token=++generation;
        handler.postDelayed(()->{
            if(token!=generation||resultSent)return;
            try {
                guard();AccessibilityService service=AutomationRuntime.service();final long startedAt=SystemClock.uptimeMillis();
                if(action.type==Step.Type.BACK||action.type==Step.Type.HOME){
                    int global=action.type==Step.Type.BACK?AccessibilityService.GLOBAL_ACTION_BACK:AccessibilityService.GLOBAL_ACTION_HOME;
                    if(!service.performGlobalAction(global))throw new IllegalStateException("系统拒绝此操作");
                    handler.postDelayed(()->complete(action,startedAt,token),250);return;
                }
                Path path=new Path();path.moveTo(action.x,action.y);
                for(int i=2;i<action.gesturePath.length;i+=2)path.lineTo(action.gesturePath[i],action.gesturePath[i+1]);
                long duration=action.type==Step.Type.TAP?30:action.durationMs;
                GestureDescription gesture=new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path,0,duration)).build();
                if(!service.dispatchGesture(gesture,new AccessibilityService.GestureResultCallback(){
                    @Override public void onCompleted(GestureDescription value){complete(action,startedAt,token);}
                    @Override public void onCancelled(GestureDescription value){if(token==generation)pauseWithError("系统取消了手势，本次没有计入录制。");}
                },handler))throw new IllegalStateException("系统未接受手势，请重新开启无障碍服务");
                handler.postDelayed(()->{if(token==generation&&busy)pauseWithError("手势回执超时，录制已暂停，请核对目标应用。");},duration+2500);
            }catch(Exception ex){if(token==generation)pauseWithError(safeMessage(ex));}
        },80);
    }
    private void complete(Step action,long startedAt,int token) {
        if(token!=generation||resultSent)return;
        try {
            session.append(action,startedAt,SystemClock.uptimeMillis());
            Script recovery=session.snapshot();recovery.id=recoveryId;recovery.name=recovery.name+" · 未完成录制";store.saveDraft(recovery);
            busy=false;
            if(!paused)installCapture();
            setMessage("已录 "+session.actionCount()+" 个动作"+(paused?" · 已暂停":" · 继续操作或点完成"));refreshControls();
        } catch(Exception ex){pauseWithError("动作已执行，但草稿写入未完成："+safeMessage(ex)+"。请点完成重试保存。");}
    }
    private void global(Step.Type type) {
        if(busy)return;
        try{guard();Step action=new Step(type);action.label=type==Step.Type.BACK?"录制返回":"录制主页";replay(action);}
        catch(Exception ex){pauseWithError(safeMessage(ex));}
    }
    private void undo() { if(!busy&&session.undo()){setMessage("已撤销最后一个动作和它的等待间隔。");try{Script recovery=session.snapshot();recovery.id=recoveryId;recovery.name+=" · 未完成录制";store.saveDraft(recovery);}catch(Exception ex){pauseWithError(safeMessage(ex));}refreshControls();} }
    private void confirmCancel() {
        if(session.actionCount()==0||SystemClock.uptimeMillis()-cancelAt<3000){finishRecording(false,true);return;}
        cancelAt=SystemClock.uptimeMillis();paused=true;session.breakTiming();removeCapture();setMessage("再次点击取消将放弃本次录制；点完成可保存。");refreshControls();
    }
    private void finishRecording(boolean saveChanges,boolean returnToEditor) {
        if(resultSent)return;
        if(saveChanges&&busy){setMessage("请等待当前手势结束后保存。");return;}
        try {
            if(saveChanges){if(session==null||session.actionCount()==0){setMessage("请先录制至少一个动作。");return;}store.saveDraft(session.snapshot());}
            if(store!=null&&recoveryId!=null)try{store.delete(recoveryId);}catch(Exception cleanup){android.util.Log.w("PocketKit","Recovery draft cleanup failed",cleanup);Toast.makeText(this,"恢复草稿暂未清除，可在脚本列表手动删除",Toast.LENGTH_LONG).show();}
            sendResult(saveChanges?"saved":"cancelled",saveChanges?"录制已保存":"已取消，原脚本未改动");
            if(returnToEditor)try{startActivity(editorIntent());}catch(RuntimeException blocked){Toast.makeText(this,"已结束，请返回连点器查看脚本",Toast.LENGTH_LONG).show();}
            stopSelf();
        }catch(Exception ex){pauseWithError("保存未完成："+safeMessage(ex)+"，录制仍保留，请重试。");}
    }
    private void guard() {
        AccessibilityService service=AutomationRuntime.service();
        if(service==null)throw new IllegalStateException("无障碍服务已断开，请重新开启后继续");
        if(!Settings.canDrawOverlays(this))throw new IllegalStateException("悬浮窗权限已关闭");
        KeyguardManager keyguard=getSystemService(KeyguardManager.class);
        if(keyguard!=null&&keyguard.isKeyguardLocked())throw new IllegalStateException("屏幕已锁定，录制已暂停");
        DisplayMetrics m=windows.metrics();if(m.widthPixels!=width||m.heightPixels!=height)throw new IllegalStateException("屏幕尺寸或方向已变化，请完成保存后新建录制");
        String target=targetPackage;
        if(!target.isEmpty()) {
            android.view.accessibility.AccessibilityNodeInfo root=service.getRootInActiveWindow();
            try{if(root==null||!target.equals(String.valueOf(root.getPackageName())))throw new IllegalStateException("请切到脚本绑定的目标应用再操作");}
            finally{if(root!=null)root.recycle();}
        }
    }
    private void pauseWithError(String text) { generation++;busy=false;paused=true;removeCapture();if(session!=null)session.breakTiming();setMessage(text);refreshControls();Toast.makeText(this,text,Toast.LENGTH_LONG).show(); }
    private void refreshControls() {
        if(toggle==null)return;
        toggle.setText(paused?(session.actionCount()==0?"开始录制":"继续录制"):"暂停");toggle.setEnabled(!busy);
        undo.setEnabled(!busy&&session.actionCount()>0);save.setEnabled(!busy&&session.actionCount()>0);back.setEnabled(!busy);home.setEnabled(!busy);
    }
    private void setMessage(String text) { if(message!=null)message.setText(text);getSystemService(NotificationManager.class).notify(NOTIFICATION_ID,notification(text)); }
    private void sendResult(String status,String message) { if(resultSent)return;resultSent=true;generation++;getSharedPreferences("automation_recorder",MODE_PRIVATE).edit().putString("scriptId",scriptId).putString("status",status).putString("message",message).apply(); }
    private Intent editorIntent() { return new Intent(this,AutomationToolsActivity.class).putExtra("tool_id","C01").putExtra("script_id",scriptId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT); }
    private String safeMessage(Exception ex) { return ex.getMessage()==null?"录制未能完成":ex.getMessage(); }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private LinearLayout row(){LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);return row;}
    private TextView text(String value,int size){TextView view=new TextView(this);view.setText(value);view.setTextColor(Color.WHITE);view.setTextSize(size);view.setGravity(Gravity.CENTER_VERTICAL);return view;}
    private Button button(LinearLayout row,String value,Runnable action){Button button=new Button(this);button.setText(value);button.setAllCaps(false);button.setTextSize(14);button.setTextColor(Color.WHITE);button.setPadding(0,0,0,0);button.setMinWidth(0);button.setMinimumWidth(0);button.setBackground(ToolUi.ripple(this,Color.TRANSPARENT,dp(8),Color.TRANSPARENT,0));button.setOnClickListener(v->action.run());row.addView(button,new LinearLayout.LayoutParams(0,dp(48),1));return button;}
    @Override public void onConfigurationChanged(android.content.res.Configuration configuration){super.onConfigurationChanged(configuration);if(session!=null)pauseWithError("屏幕方向已变化，录制已暂停。请完成保存后新建录制。");}
    @Override public void onDestroy(){generation++;handler.removeCallbacksAndMessages(null);if(windows!=null){windows.remove(capture);windows.remove(bar);}if(!resultSent)sendResult("error","录制已中断；已完成动作可从“未完成录制”草稿恢复。");instance=null;super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
