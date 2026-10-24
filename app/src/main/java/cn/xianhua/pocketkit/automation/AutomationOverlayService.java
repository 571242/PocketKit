package cn.xianhua.pocketkit.automation;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import cn.xianhua.pocketkit.ToolUi;
import cn.xianhua.pocketkit.core.automation.Script;

/** Visible, draggable transport. Collapses to an edge ball that keeps run state and emergency stop. */
public final class AutomationOverlayService extends Service {
    private static final String CHANNEL="automation_controls", STOP="cn.xianhua.pocketkit.automation.STOP", PREPARE="cn.xianhua.pocketkit.automation.PREPARE";
    private static final int NOTIFICATION_ID=981, BALL_DP=52;
    private static final int PANEL=Color.rgb(23,52,55), ACCENT=Color.rgb(101,206,193);
    private static volatile AutomationOverlayService instance;
    private static volatile Script preparedScript;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private AutomationWindows windows;
    private LinearLayout overlay, expanded, ballBody;
    private TextView status, ball;
    private Button play, stop;
    private WindowManager.LayoutParams barParams;
    private boolean collapsed;
    private int screenWidth, screenHeight, ticks;
    private String ballState="";

    public static void prepare(Context context,Script script) {
        script.validate();if(script.steps.isEmpty())throw new IllegalArgumentException("请先添加或录制动作");
        if(!Settings.canDrawOverlays(context))throw new IllegalStateException("请先开启悬浮窗权限");
        if(!AutomationRuntime.isEnabled())throw new IllegalStateException("请先开启无障碍服务");
        AutomationRuntime.stop("准备启动脚本");AutomationPickerService.stop(context);AutomationRecorderService.stop(context);
        remember(script);context.startForegroundService(new Intent(context,AutomationOverlayService.class).setAction(PREPARE));
    }
    static void remember(Script script){preparedScript=script.copy();}
    public static void show(Context context){context.startForegroundService(new Intent(context,AutomationOverlayService.class));}
    public static void stop(Context context){context.stopService(new Intent(context,AutomationOverlayService.class));}
    public static void setCaptureHidden(boolean hidden){AutomationOverlayService value=instance;if(value==null)return;Runnable task=()->{if(value.overlay!=null)value.overlay.setVisibility(hidden?View.INVISIBLE:View.VISIBLE);};if(Looper.myLooper()==Looper.getMainLooper())task.run();else value.handler.post(task);}
    @Override public void onCreate(){super.onCreate();instance=this;windows=new AutomationWindows(this);NotificationChannel channel=new NotificationChannel(CHANNEL,"连点器播放控制",NotificationManager.IMPORTANCE_LOW);getSystemService(NotificationManager.class).createNotificationChannel(channel);}
    @Override public int onStartCommand(Intent intent,int flags,int id) {
        if(intent!=null&&STOP.equals(intent.getAction())){AutomationRuntime.stop("通知栏停止");stopSelf();return START_NOT_STICKY;}
        try {
            Notification notification=notification();
            if(Build.VERSION.SDK_INT>=34)startForeground(NOTIFICATION_ID,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);else startForeground(NOTIFICATION_ID,notification);
            if(!Settings.canDrawOverlays(this))throw new IllegalStateException("悬浮窗权限未开启");
            if(overlay==null)createOverlay();
            boolean preparing=intent!=null&&PREPARE.equals(intent.getAction());
            if(!preparing)AutomationRuntime.controlsReady();
            // A prepared script needs the full controls in view; an already-running one starts out of the way.
            if(preparing)expand();else if(AutomationRuntime.isActive())collapse();
            refresh();
        }catch(RuntimeException error){String reason=error.getMessage()==null?"悬浮控制未启动":error.getMessage();AutomationRuntime.stop(reason);notifyFailure(reason);stopSelf();}
        return START_NOT_STICKY;
    }
    private Notification notification(){PendingIntent close=PendingIntent.getService(this,982,new Intent(this,AutomationOverlayService.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE);Notification.Builder builder=new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("连点器 · 播放控制").setContentText("悬浮窗可开始、暂停、停止；收起后长按小球或按音量下键急停").setOngoing(true).setOnlyAlertOnce(true).addAction(new Notification.Action.Builder(null,"停止并关闭",close).build());if(Build.VERSION.SDK_INT>=31)builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);return builder.build();}
    private void createOverlay() {
        overlay=new LinearLayout(this);overlay.setOrientation(LinearLayout.VERTICAL);
        expanded=new LinearLayout(this);expanded.setOrientation(LinearLayout.VERTICAL);expanded.setPadding(dp(8),0,dp(8),dp(6));expanded.setBackground(ToolUi.fill(PANEL,dp(14),ACCENT,dp(1)));
        TextView drag=text("连点器 · 拖动移动",13);drag.setContentDescription("拖动移动窗口，轻点收起为屏幕边缘小球");expanded.addView(drag,new LinearLayout.LayoutParams(-1,dp(36)));
        status=text("切到目标应用后点开始",13);status.setMinHeight(dp(48));status.setPadding(dp(4),0,dp(4),0);expanded.addView(status);
        LinearLayout controls=new LinearLayout(this);controls.setGravity(Gravity.CENTER_VERTICAL);
        play=button(controls,"开始",this::toggle);stop=button(controls,"停止",()->{AutomationRuntime.stop("用户停止，可重新开始");refresh();});button(controls,"收起",this::collapse);button(controls,"关闭",()->{AutomationRuntime.stop("悬浮栏已关闭");stopSelf();});expanded.addView(controls);
        ballBody=new LinearLayout(this);ballBody.setGravity(Gravity.CENTER);ballBody.setBackground(ToolUi.fill(PANEL,dp(26),ACCENT,dp(1)));
        ball=text("",11);ball.setGravity(Gravity.CENTER);ball.setSingleLine(true);ballBody.addView(ball,new LinearLayout.LayoutParams(-2,-2));
        overlay.addView(expanded);
        barParams=windows.bar();barParams.width=Math.min(dp(248),windows.metrics().widthPixels-dp(16));
        windows.draggable(drag,overlay,barParams,this::snap,null);
        windows.draggable(ballBody,overlay,barParams,this::snap,this::emergencyStop);
        drag.setOnClickListener(v->collapse());ballBody.setOnClickListener(v->expand());
        windows.manager.addView(overlay,barParams);
        DisplayMetrics m=windows.metrics();screenWidth=m.widthPixels;screenHeight=m.heightPixels;
        handler.post(statusTick);
    }
    /** Real shrink: the window itself becomes an edge ball, so the touch area stops covering the screen. */
    private void collapse() {
        if(collapsed||overlay==null||barParams==null)return;
        collapsed=true;
        DisplayMetrics m=windows.metrics();int from=barParams.width,size=dp(BALL_DP);
        overlay.removeAllViews();overlay.addView(ballBody,new LinearLayout.LayoutParams(-1,-1));
        barParams.width=barParams.height=size;
        barParams.x=AutomationWindows.edgeX(AutomationWindows.snapEdge(barParams.x,from,m.widthPixels),size,m.widthPixels);
        barParams.y=AutomationWindows.clampWithin(barParams.y,dp(24),Math.max(dp(24),m.heightPixels-size-dp(24)));
        screenWidth=m.widthPixels;screenHeight=m.heightPixels;
        syncBall();layout();
    }
    private void expand() {
        if(!collapsed||overlay==null||barParams==null)return;
        collapsed=false;
        DisplayMetrics m=windows.metrics();int width=Math.min(dp(248),m.widthPixels-dp(16));
        overlay.removeAllViews();overlay.addView(expanded,new LinearLayout.LayoutParams(-1,-2));
        barParams.width=width;barParams.height=-2;
        barParams.x=AutomationWindows.clampWithin(barParams.x,0,Math.max(0,m.widthPixels-width));
        barParams.y=AutomationWindows.clampWithin(barParams.y,dp(24),Math.max(dp(24),m.heightPixels-dp(200)));
        screenWidth=m.widthPixels;screenHeight=m.heightPixels;
        layout();refresh();
    }
    /** Left/right snap on release, for the ball and the panel alike. */
    private void snap(){if(overlay==null||barParams==null)return;DisplayMetrics m=windows.metrics();int edge=AutomationWindows.edgeFor(barParams.x,barParams.width,m.widthPixels);if(edge<0)return;barParams.x=AutomationWindows.edgeX(edge,barParams.width,m.widthPixels);layout();}
    /** Emergency stop stays reachable while collapsed: long press the ball, no expanding first. */
    private void emergencyStop(){AutomationRuntime.stop("小窗长按急停");handler.post(this::expand);refresh();}
    private void layout(){if(overlay!=null&&barParams!=null&&overlay.isAttachedToWindow())windows.manager.updateViewLayout(overlay,barParams);}
    private void syncBall(){
        boolean active=AutomationRuntime.isActive(),paused=active&&AutomationRuntime.isPaused();
        String state=active?(paused?"paused":"running"):(preparedScript==null?"idle":"ready");
        if(ball==null||state.equals(ballState))return;
        ballState=state;
        ball.setText(active?(paused?"暂停":"运行"):(preparedScript==null?"就绪":"开始"));
        ball.setTextColor(active&&!paused?PANEL:Color.WHITE);
        ballBody.setBackground(ToolUi.fill(active&&!paused?ACCENT:PANEL,dp(26),ACCENT,active&&!paused?0:dp(1)));
        ball.setContentDescription("连点器"+(active?(paused?"已暂停":"运行中"):"待开始")+"，轻点展开，长按急停");
    }
    private void toggle() {
        try {
            if(AutomationRuntime.isActive()){if(AutomationRuntime.isPaused())AutomationRuntime.resume();else AutomationRuntime.pause();}
            else if(preparedScript!=null)AutomationRuntime.start(this,preparedScript);
            else throw new IllegalStateException("请返回连点器选择脚本");
        }catch(RuntimeException error){notifyFailure(error.getMessage()==null?"启动未完成":error.getMessage());}
        refresh();
        // Starting tucks the bar into the edge ball; expanding restores the same controls and a long press still stops.
        if(AutomationRuntime.isActive())handler.post(this::collapse);
    }
    private void refresh(){if(status==null)return;boolean active=AutomationRuntime.isActive();Script script=preparedScript;status.setText(active||script==null?AutomationRuntime.status():script.name+"\n"+AutomationRuntime.status()+" · 点开始可运行");play.setText(active?(AutomationRuntime.isPaused()?"继续":"暂停"):"开始");play.setEnabled(active||script!=null);stop.setEnabled(active);syncBall();}
    private final Runnable statusTick=new Runnable(){@Override public void run(){if(overlay==null)return;refresh();if(++ticks%4==0)settle();handler.postDelayed(this,250);}};
    /** Rotation or a changed visible area must not leave the window off screen. */
    private void settle(){
        if(barParams==null)return;
        DisplayMetrics m=windows.metrics();
        if(m.widthPixels==screenWidth&&m.heightPixels==screenHeight)return;
        screenWidth=m.widthPixels;screenHeight=m.heightPixels;
        if(collapsed){
            barParams.x=AutomationWindows.clampWithin(barParams.x,0,Math.max(0,m.widthPixels-barParams.width));
            barParams.y=AutomationWindows.clampWithin(barParams.y,dp(24),Math.max(dp(24),m.heightPixels-barParams.height-dp(24)));
        }else{
            barParams.width=Math.min(dp(248),m.widthPixels-dp(16));
            barParams.x=AutomationWindows.clampWithin(barParams.x,0,Math.max(0,m.widthPixels-barParams.width));
            barParams.y=AutomationWindows.clampWithin(barParams.y,dp(24),Math.max(dp(24),m.heightPixels-dp(200)));
        }
        layout();
    }
    private void notifyFailure(String reason){Toast.makeText(this,reason,Toast.LENGTH_LONG).show();getSystemService(NotificationManager.class).notify(NOTIFICATION_ID,new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle("连点器未启动").setContentText(reason).setAutoCancel(true).build());}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(Color.WHITE);view.setGravity(Gravity.CENTER_VERTICAL);return view;}
    private Button button(LinearLayout row,String value,Runnable action){Button view=new Button(this);view.setText(value);view.setTextSize(14);view.setAllCaps(false);view.setTextColor(Color.WHITE);view.setPadding(0,0,0,0);view.setMinWidth(0);view.setMinimumWidth(0);view.setBackground(ToolUi.ripple(this,Color.TRANSPARENT,dp(8),Color.TRANSPARENT,0));view.setOnClickListener(v->action.run());row.addView(view,new LinearLayout.LayoutParams(0,dp(48),1));return view;}
    @Override public void onDestroy(){handler.removeCallbacksAndMessages(null);if(windows!=null)windows.remove(overlay);overlay=null;expanded=null;ballBody=null;ball=null;status=null;play=null;stop=null;preparedScript=null;AutomationRuntime.controlsClosed();instance=null;super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
