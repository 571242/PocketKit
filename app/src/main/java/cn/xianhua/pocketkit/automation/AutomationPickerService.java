package cn.xianhua.pocketkit.automation;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.concurrent.atomic.AtomicBoolean;
import cn.xianhua.pocketkit.AutomationToolsActivity;
import cn.xianhua.pocketkit.ToolUi;
import cn.xianhua.pocketkit.core.automation.Script;
import cn.xianhua.pocketkit.core.automation.Step;

/** A visible ready bar is mandatory; notifications are never the only way to start picking. */
public final class AutomationPickerService extends Service {
    public static final String RESULT_ACTION="cn.xianhua.pocketkit.automation.PICK_RESULT";
    private static final String BEGIN="cn.xianhua.pocketkit.automation.PICK_BEGIN", CANCEL="cn.xianhua.pocketkit.automation.PICK_CANCEL";
    private static final String CHANNEL="automation_picker";
    private static final int NOTIFICATION_ID=983;
    private static volatile AutomationPickerService instance;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancelled=new AtomicBoolean();
    private AutomationWindows windows;
    private SelectionView overlay;
    private LinearLayout readyStrip;
    private WindowManager.LayoutParams barParams;
    private TextView message;
    private Button begin, save;
    private String scriptId, stepId, mode;
    private Script script;
    private Step step;
    private boolean selected, resultSent, busy;
    private volatile int generation;
    private Thread captureWorker;
    private int sx,sy,ex,ey;
    private long downAt;

    public static void start(Context context,String scriptId,String stepId,String mode) {
        if(!Settings.canDrawOverlays(context))throw new IllegalStateException("请先开启悬浮窗权限，再返回取点");
        AutomationRuntime.stop("切换到目标采集");AutomationOverlayService.stop(context);AutomationRecorderService.stop(context);
        context.startForegroundService(new Intent(context,AutomationPickerService.class).putExtra("scriptId",scriptId).putExtra("stepId",stepId).putExtra("mode",mode));
    }
    public static boolean isPicking(){AutomationPickerService picker=instance;return picker!=null&&!picker.resultSent;}
    public static void stop(Context context){context.stopService(new Intent(context,AutomationPickerService.class));}
    public static void setCaptureHidden(boolean hidden) {
        AutomationPickerService value=instance;if(value==null)return;
        Runnable task=()->{if(value.overlay!=null)value.overlay.setVisibility(hidden?View.INVISIBLE:View.VISIBLE);if(value.readyStrip!=null)value.readyStrip.setVisibility(hidden?View.INVISIBLE:View.VISIBLE);};
        if(Looper.myLooper()==Looper.getMainLooper())task.run();else value.handler.post(task);
    }
    @Override public void onCreate(){super.onCreate();instance=this;NotificationChannel channel=new NotificationChannel(CHANNEL,"连点器屏幕取点",NotificationManager.IMPORTANCE_LOW);getSystemService(NotificationManager.class).createNotificationChannel(channel);}
    @Override public int onStartCommand(Intent intent,int flags,int id) {
        if(intent!=null&&CANCEL.equals(intent.getAction())){finish("cancelled","已取消取点",true);return START_NOT_STICKY;}
        if(intent!=null&&BEGIN.equals(intent.getAction())){beginCapture();return START_NOT_STICKY;}
        try {
            scriptId=intent==null?null:intent.getStringExtra("scriptId");stepId=intent==null?null:intent.getStringExtra("stepId");mode=intent==null?null:intent.getStringExtra("mode");
            Notification notification=notification("切到目标应用，在悬浮条点开始取点");
            if(Build.VERSION.SDK_INT>=34)startForeground(NOTIFICATION_ID,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);else startForeground(NOTIFICATION_ID,notification);
            if(!Settings.canDrawOverlays(this))throw new IllegalStateException("悬浮窗权限未开启");
            if(!"point".equals(mode)&&!"swipe".equals(mode)&&!"region".equals(mode)&&!"template".equals(mode))throw new IllegalArgumentException("未知取点模式");
            script=new ScriptStore(this).load(scriptId);step=find(script.steps,stepId);if(step==null)throw new IllegalArgumentException("动作不存在");
            windows=new AutomationWindows(this);createReadyStrip();
        }catch(Exception error){showError(safeMessage(error));finish("error",safeMessage(error),false);}
        return START_NOT_STICKY;
    }
    private void createReadyStrip() {
        readyStrip=new LinearLayout(this);readyStrip.setOrientation(LinearLayout.VERTICAL);readyStrip.setPadding(dp(8),0,dp(8),dp(6));
        readyStrip.setBackground(ToolUi.fill(Color.rgb(23,52,55),dp(14),Color.rgb(101,206,193),dp(1)));
        TextView drag=text("屏幕取点 · 拖动移动",13);readyStrip.addView(drag,new LinearLayout.LayoutParams(-1,dp(36)));
        message=text("切到目标应用后点开始。取点不会点击目标应用。",13);message.setMinHeight(dp(52));readyStrip.addView(message);
        LinearLayout actions=new LinearLayout(this);begin=button(actions,"开始取点",this::beginCapture);save=button(actions,"保存",this::commit);save.setEnabled(false);button(actions,"取消",()->finish("cancelled","已取消取点",true));readyStrip.addView(actions);
        barParams=windows.bar();windows.draggable(drag,readyStrip,barParams);windows.manager.addView(readyStrip,barParams);
    }
    private void beginCapture() {
        if(busy||script==null)return;
        try {
            if(!Settings.canDrawOverlays(this))throw new IllegalStateException("悬浮窗权限已关闭");
            DisplayMetrics m=windows.metrics();
            if(script.width>0&&(script.width!=m.widthPixels||script.height!=m.heightPixels))throw new IllegalStateException("屏幕尺寸已变化，请新建脚本取点，避免混入旧坐标");
            script.width=m.widthPixels;script.height=m.heightPixels;selected=false;save.setEnabled(false);cancelled.set(false);generation++;
            if(overlay==null){overlay=new SelectionView(this);windows.remove(readyStrip);windows.manager.addView(overlay,windows.fullscreen());windows.manager.addView(readyStrip,barParams);}
            overlay.invalidate();begin.setText("重选");message.setText(instruction());updateNotification("正在取点，选择后在悬浮条保存");
        }catch(Exception error){showError(safeMessage(error));}
    }
    private String instruction(){if("point".equals(mode))return "触摸目标位置后松手，再点保存。";if("swipe".equals(mode))return "从起点滑到终点，再点保存。";return "拖动框选目标区域，再点保存。";}
    private boolean touch(MotionEvent event) {
        if(busy)return true;
        if(event.getPointerCount()>1){selected=false;save.setEnabled(false);message.setText("请使用单指取点。");return true;}
        switch(event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:sx=ex=Math.round(event.getRawX());sy=ey=Math.round(event.getRawY());downAt=event.getEventTime();selected=false;return true;
            case MotionEvent.ACTION_MOVE:ex=Math.round(event.getRawX());ey=Math.round(event.getRawY());overlay.invalidate();message.setText(instruction()+"\n"+sx+", "+sy+" → "+ex+", "+ey);return true;
            case MotionEvent.ACTION_UP:
                ex=Math.max(0,Math.min(script.width-1,Math.round(event.getRawX())));ey=Math.max(0,Math.min(script.height-1,Math.round(event.getRawY())));
                sx=Math.max(0,Math.min(script.width-1,sx));sy=Math.max(0,Math.min(script.height-1,sy));
                if("point".equals(mode)){step.x=ex;step.y=ey;step.pointPending=false;step.gesturePath=new int[0];}
                else if("swipe".equals(mode)){step.x=sx;step.y=sy;step.x2=ex;step.y2=ey;step.gesturePath=new int[0];step.durationMs=Math.max(30,Math.min(10000,event.getEventTime()-downAt));}
                else{step.left=Math.min(sx,ex);step.top=Math.min(sy,ey);step.right=Math.max(sx,ex);step.bottom=Math.max(sy,ey);}
                selected="point".equals(mode)||"swipe".equals(mode)||step.right>step.left&&step.bottom>step.top;
                save.setEnabled(selected);message.setText(selected?("point".equals(mode)?"已选 "+ex+", "+ey:"已选 "+sx+", "+sy+" → "+ex+", "+ey)+"\n确认后点保存，或重新选择。":"区域太小，请重新拖动框选。");overlay.invalidate();return true;
            case MotionEvent.ACTION_CANCEL:selected=false;save.setEnabled(false);return true;
            default:return true;
        }
    }
    private void commit() {
        if(!selected||busy){if(message!=null)message.setText("尚未选择目标，请先触摸或拖动。");return;}
        if("template".equals(mode)){captureTemplate(generation);return;}
        try{script.updatedAt=System.currentTimeMillis();new ScriptStore(this).saveDraft(script);finish("saved","位置已保存",true);}catch(Exception error){showError("保存失败："+safeMessage(error));}
    }
    private void captureTemplate(int token) {
        AccessibilityService service=AutomationRuntime.service();
        if(service==null){showError("请先开启无障碍服务后再截图取模板。");return;}
        Rect region=new Rect(step.left,step.top,step.right,step.bottom);busy=true;save.setEnabled(false);begin.setEnabled(false);
        AutomationOverlayService.setCaptureHidden(true);setCaptureHidden(true);
        captureWorker=new Thread(()->{
            Bitmap frame=null;String savedId=null;
            try {
                frame=ScreenCapture.capture(service,5000);
                if(cancelled.get()||token!=generation)return;
                Rect safe=new Rect(Math.max(0,region.left),Math.max(0,region.top),Math.min(frame.getWidth(),region.right),Math.min(frame.getHeight(),region.bottom));
                if(safe.width()<=0||safe.height()<=0)throw new IllegalArgumentException("区域超出截图");
                Bitmap crop=Bitmap.createBitmap(frame,safe.left,safe.top,safe.width(),safe.height());
                try{savedId=new TemplateStore(this).save(crop);}finally{crop.recycle();}
                String templateId=savedId;
                handler.post(()->{
                    if(cancelled.get()||token!=generation){discardTemplate(templateId);return;}
                    String old=step.templateId;
                    try{step.templateId=templateId;script.updatedAt=System.currentTimeMillis();new ScriptStore(this).saveDraft(script);finish("saved","模板已保存",true);}
                    catch(Exception error){step.templateId=old;discardTemplate(templateId);restoreCapture(token);showError("保存模板失败："+safeMessage(error));}
                });
            }catch(Exception error){if(savedId!=null)discardTemplate(savedId);handler.post(()->{if(!cancelled.get()&&token==generation){restoreCapture(token);showError("截图失败："+safeMessage(error));}});}
            finally{if(frame!=null)frame.recycle();captureWorker=null;AutomationOverlayService.setCaptureHidden(false);}
        },"PocketKit-TemplateCapture");captureWorker.start();
    }
    private void discardTemplate(String id){try{new TemplateStore(this).delete(id);}catch(Exception error){android.util.Log.w("PocketKit","Temporary template cleanup failed",error);}}
    private void restoreCapture(int token){if(token!=generation)return;busy=false;save.setEnabled(selected);begin.setEnabled(true);setCaptureHidden(false);}
    private void finish(String status,String text,boolean openEditor) {
        if(resultSent)return;resultSent=true;cancelWork();
        getSharedPreferences("automation_picker",MODE_PRIVATE).edit().putString("scriptId",scriptId).putString("stepId",stepId).putString("status",status).putString("message",text).apply();
        sendBroadcast(new Intent(RESULT_ACTION).setPackage(getPackageName()).putExtra("scriptId",scriptId).putExtra("stepId",stepId));
        if(openEditor)try{startActivity(new Intent(this,AutomationToolsActivity.class).putExtra("tool_id","C01").putExtra("script_id",scriptId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));}catch(RuntimeException ignored){Toast.makeText(this,"已结束，请返回连点器查看",Toast.LENGTH_SHORT).show();}
        stopSelf();
    }
    private Notification notification(String text) {
        PendingIntent begin=PendingIntent.getService(this,984,new Intent(this,AutomationPickerService.class).setAction(BEGIN),PendingIntent.FLAG_IMMUTABLE);
        PendingIntent cancel=PendingIntent.getService(this,985,new Intent(this,AutomationPickerService.class).setAction(CANCEL),PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder=new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_crop).setContentTitle("连点器 · 屏幕取点").setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null,"开始采集",begin).build()).addAction(new Notification.Action.Builder(null,"取消",cancel).build());
        if(Build.VERSION.SDK_INT>=31)builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return builder.build();
    }
    private void updateNotification(String text){getSystemService(NotificationManager.class).notify(NOTIFICATION_ID,notification(text));}
    private void showError(String text){if(message!=null)message.setText(text);Toast.makeText(this,text,Toast.LENGTH_LONG).show();updateNotification(text);}
    private String safeMessage(Exception error){return error.getMessage()==null?"取点未能完成":error.getMessage();}
    private void cancelWork(){cancelled.set(true);generation++;if(captureWorker!=null)captureWorker.interrupt();AutomationOverlayService.setCaptureHidden(false);}
    private Step find(java.util.List<Step> list,String id){if(list==null||id==null)return null;for(Step s:list){if(id.equals(s.id))return s;Step found=find(s.children,id);if(found!=null)return found;found=find(s.otherwise,id);if(found!=null)return found;}return null;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(Color.WHITE);view.setGravity(Gravity.CENTER_VERTICAL);return view;}
    private Button button(LinearLayout row,String text,Runnable action){Button view=new Button(this);view.setText(text);view.setTextSize(14);view.setAllCaps(false);view.setTextColor(Color.WHITE);view.setPadding(0,0,0,0);view.setMinimumWidth(0);view.setMinWidth(0);view.setBackground(ToolUi.ripple(this,Color.TRANSPARENT,dp(8),Color.TRANSPARENT,0));view.setOnClickListener(v->action.run());row.addView(view,new LinearLayout.LayoutParams(0,dp(48),1));return view;}
    private final class SelectionView extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int[] location=new int[2];
        SelectionView(Context context){super(context);paint.setColor(Color.rgb(101,206,193));paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));setOnTouchListener((v,event)->touch(event));setContentDescription("屏幕位置选择层");}
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);getLocationOnScreen(location);float x=ex-location[0],y=ey-location[1],startX=sx-location[0],startY=sy-location[1];if(!selected)return;if("point".equals(mode)){canvas.drawCircle(x,y,dp(18),paint);canvas.drawLine(x-dp(26),y,x+dp(26),y,paint);canvas.drawLine(x,y-dp(26),x,y+dp(26),paint);}else if("swipe".equals(mode)){canvas.drawCircle(startX,startY,dp(9),paint);canvas.drawLine(startX,startY,x,y,paint);canvas.drawCircle(x,y,dp(14),paint);}else canvas.drawRect(Math.min(startX,x),Math.min(startY,y),Math.max(startX,x),Math.max(startY,y),paint);}
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration configuration){super.onConfigurationChanged(configuration);if(overlay!=null){windows.remove(overlay);overlay=null;}selected=false;if(save!=null){save.setEnabled(false);message.setText("屏幕方向已变化，请取消后重新取点。");}}
    @Override public void onDestroy(){cancelWork();if(windows!=null){windows.remove(overlay);windows.remove(readyStrip);}if(!resultSent){resultSent=true;getSharedPreferences("automation_picker",MODE_PRIVATE).edit().putString("scriptId",scriptId).putString("stepId",stepId).putString("status","cancelled").putString("message","取点已结束").apply();}instance=null;super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
