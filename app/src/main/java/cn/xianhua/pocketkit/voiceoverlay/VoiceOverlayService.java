package cn.xianhua.pocketkit.voiceoverlay;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.media.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import cn.xianhua.pocketkit.ManagerShareProvider;
import cn.xianhua.pocketkit.ToolUi;
import cn.xianhua.pocketkit.VoiceOverlayActivity;
import cn.xianhua.pocketkit.voice.DspVoiceProcessor;
import cn.xianhua.pocketkit.voice.VoiceProcessor;
import java.io.*;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** User-started foreground local recorder. Never injects audio into another app. */
public final class VoiceOverlayService extends Service {
    private static final String CHANNEL="voice_overlay", STOP="voice_overlay_stop";
    private static final int NOTIFY=989, RATE=24000, LIMIT=60*RATE;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private volatile boolean destroyed, stopCapture;
    private boolean busy, capturing;
    private int preset=1;
    private WindowManager manager;
    private WindowManager.LayoutParams position;
    private LinearLayout panel, expanded, controls;
    private TextView status, ball;
    private int dockSide = -1;
    private Button record, preview, share;
    private MediaPlayer player;
    private File output;
    private boolean collapsed;
    @Override public void onCreate(){
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL,"悬浮录音",NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&STOP.equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}
        try {
            if(intent!=null&&!busy)preset=Math.max(0,Math.min(3,intent.getIntExtra("preset",1)));
            PendingIntent close=PendingIntent.getService(this,NOTIFY,new Intent(this,VoiceOverlayService.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE);
            PendingIntent open=PendingIntent.getActivity(this,NOTIFY,new Intent(this,VoiceOverlayActivity.class).putExtra("tool_id","V03").putExtra("title","悬浮录音变声"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            Notification notification=new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("拾用 · 悬浮录音")
                .setContentText("悬浮控制已开启").setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"关闭",close).build()).build();
            if(Build.VERSION.SDK_INT>=30)startForeground(NOTIFY,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE|ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(NOTIFY,notification);
            if(!Settings.canDrawOverlays(this))throw new IllegalStateException("悬浮窗权限已关闭");
            if(panel==null)createWindow();
        }catch(RuntimeException e){Toast.makeText(this,"悬浮录音未启动："+e.getMessage(),Toast.LENGTH_LONG).show();stopSelf();}
        return START_NOT_STICKY;
    }
    private void createWindow(){
        manager=getSystemService(WindowManager.class);
        panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);
        expanded=new LinearLayout(this);expanded.setOrientation(LinearLayout.VERTICAL);expanded.setPadding(dp(8),dp(4),dp(8),dp(6));
        expanded.setBackground(ToolUi.fill(Color.rgb(23,52,55),dp(14),Color.rgb(101,206,193),dp(1)));
        TextView title=text("变声",14);title.setContentDescription("拖到侧边收起变声窗口");expanded.addView(title,new LinearLayout.LayoutParams(-1,dp(40)));
        status=text("就绪",12);expanded.addView(status);
        controls=new LinearLayout(this);controls.setOrientation(LinearLayout.VERTICAL);expanded.addView(controls);
        record=button("录音",this::toggleRecord);preview=button("试听",this::preview);share=button("分享",this::share);
        button("收起",()->collapse(EdgeDock.nearest(position.x,position.width,screenWidth())));
        button("关闭",this::stopSelf);
        ball=text("变声",12);ball.setGravity(Gravity.CENTER);ball.setContentDescription("变声小球，点击展开，拖动移动");
        ball.setBackground(ToolUi.ripple(this,Color.rgb(23,52,55),dp(24),Color.rgb(101,206,193),dp(1)));
        ball.setOnClickListener(v->expand());
        panel.addView(expanded);
        position=new WindowManager.LayoutParams(Math.min(dp(226),screenWidth()),WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT);
        position.gravity=Gravity.TOP|Gravity.LEFT;position.x=dp(8);position.y=dp(60);
        attachDrag(title);attachDrag(ball);
        manager.addView(panel,position);refresh();
    }
    private int screenWidth(){return getResources().getDisplayMetrics().widthPixels;}
    private int screenHeight(){return getResources().getDisplayMetrics().heightPixels;}
    private void attachDrag(View handle){
        handle.setOnTouchListener(new View.OnTouchListener(){float x,y;int ox,oy;boolean moved;
            @Override public boolean onTouch(View view,MotionEvent event){
                switch(event.getActionMasked()){
                    case MotionEvent.ACTION_DOWN:x=event.getRawX();y=event.getRawY();ox=position.x;oy=position.y;moved=false;return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx=event.getRawX()-x,dy=event.getRawY()-y;
                        if(Math.hypot(dx,dy)>ViewConfiguration.get(VoiceOverlayService.this).getScaledTouchSlop())moved=true;
                        if(moved){position.x=EdgeDock.clamp(ox+(int)dx,screenWidth()-position.width);position.y=EdgeDock.clamp(oy+(int)dy,screenHeight()-panel.getHeight());layout();}return true;
                    case MotionEvent.ACTION_UP:
                        if(moved){int side=EdgeDock.nearEdge(position.x,position.width,screenWidth(),dp(24));if(collapsed)collapse(EdgeDock.nearest(position.x,position.width,screenWidth()));else if(side!=0)collapse(side);}
                        else view.performClick();return true;
                    case MotionEvent.ACTION_CANCEL:return true;
                    default:return false;
                }
            }
        });
    }
    private void collapse(int side){
        dockSide=side;collapsed=true;panel.removeAllViews();panel.addView(ball,new LinearLayout.LayoutParams(-1,-1));
        position.width=position.height=dp(48);position.x=EdgeDock.x(side,position.width,screenWidth());position.y=EdgeDock.clamp(position.y,screenHeight()-position.height);layout();refresh();
    }
    private void expand(){
        if(!collapsed)return;collapsed=false;panel.removeAllViews();panel.addView(expanded);
        position.width=Math.min(dp(226),screenWidth());position.height=WindowManager.LayoutParams.WRAP_CONTENT;
        position.x=EdgeDock.x(dockSide,position.width,screenWidth());position.y=EdgeDock.clamp(position.y,screenHeight()-dp(330));layout();
        panel.post(()->{if(!destroyed){position.y=EdgeDock.clamp(position.y,screenHeight()-panel.getHeight());layout();}});
    }
    private void layout(){if(panel!=null&&panel.isAttachedToWindow())manager.updateViewLayout(panel,position);}
    @Override public void onConfigurationChanged(android.content.res.Configuration config){
        super.onConfigurationChanged(config);if(panel==null)return;
        if(collapsed)collapse(dockSide);else{position.width=Math.min(dp(226),screenWidth());position.x=EdgeDock.clamp(position.x,screenWidth()-position.width);position.y=EdgeDock.clamp(position.y,screenHeight()-panel.getHeight());layout();}
    }
    private void toggleRecord(){
        if(capturing){stopCapture=true;record.setEnabled(false);status.setText("停止中…");return;}
        if(busy)return;
        releasePlayer();stopCapture=false;busy=true;capturing=true;refresh();status.setText("录音中");
        final int chosen=preset;
        worker.execute(()->{
            AudioRecord recorder=null;
            try{
                int minimum=AudioRecord.getMinBufferSize(RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
                if(minimum<=0)throw new IOException("设备不支持 24 kHz 录音");
                if(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED)throw new SecurityException("麦克风权限已关闭");
                recorder=new AudioRecord(MediaRecorder.AudioSource.MIC,RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(minimum*2,8192));
                if(recorder.getState()!=AudioRecord.STATE_INITIALIZED)throw new IOException("麦克风不可用");
                float[] samples=new float[LIMIT];short[] buffer=new short[2048];int count=0;long start=SystemClock.elapsedRealtime();
                recorder.startRecording();
                while(!destroyed&&!stopCapture&&count<LIMIT&&SystemClock.elapsedRealtime()-start<61000){
                    int n=recorder.read(buffer,0,Math.min(buffer.length,LIMIT-count),AudioRecord.READ_NON_BLOCKING);
                    if(n<0)throw new IOException("录音失败："+n);
                    if(n==0){Thread.sleep(12);continue;}
                    for(int i=0;i<n;i++)samples[count++]=buffer[i]/32768f;
                }
                recorder.stop();recorder.release();recorder=null;
                if(destroyed)return;
                if(count<RATE/4)throw new IOException("录音太短，请至少录制片刻");
                double power=0;for(int i=0;i<count;i++)power+=samples[i]*samples[i];
                if(power/count<1e-9)throw new IOException("录音近乎静音；其他应用可能占用麦克风，请结束游戏语音后重试");
                main.post(()->{if(!destroyed){capturing=false;refresh();status.setText("转换中…");}});
                VoiceProcessor.Params params=new VoiceProcessor.Params(VoiceProcessor.Preset.values()[chosen],0f,1f);
                float[] converted=new DspVoiceProcessor().process(Arrays.copyOf(samples,count),RATE,params,()->destroyed);
                if(destroyed)return;
                File dir=new File(getCacheDir(),"manager-exports");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("无法创建分享缓存");
                File next=new File(dir,"voice-"+UUID.randomUUID()+".wav");
                try{writeWav(next,converted);if(destroyed){next.delete();return;}}catch(Exception e){next.delete();throw e;}
                main.post(()->{if(destroyed){next.delete();return;}output=next;busy=false;capturing=false;refresh();status.setText("已完成");});
            }catch(Exception e){main.post(()->{if(!destroyed){busy=false;capturing=false;refresh();status.setText("未完成："+e.getMessage());}});}
            catch(Error e){
                // An OutOfMemoryError from the sample buffer or the DSP used to leave busy and
                // capturing true for good, which silently disabled the whole overlay panel.
                main.post(()->{if(!destroyed){busy=false;capturing=false;refresh();status.setText("未完成：内存不足或处理失败");}});
                throw e;
            }
            finally{if(recorder!=null){try{recorder.stop();}catch(RuntimeException ignored){}recorder.release();}}
        });
    }
    private static void writeWav(File file,float[] samples)throws IOException{
        try(OutputStream stream=new BufferedOutputStream(new FileOutputStream(file))){
            stream.write(new byte[]{'R','I','F','F'});le(stream,36+samples.length*2,4);stream.write(new byte[]{'W','A','V','E','f','m','t',' '});le(stream,16,4);le(stream,1,2);le(stream,1,2);le(stream,RATE,4);le(stream,RATE*2,4);le(stream,2,2);le(stream,16,2);stream.write(new byte[]{'d','a','t','a'});le(stream,samples.length*2,4);
            for(float sample:samples)le(stream,Math.round(Math.max(-1f,Math.min(1f,sample))*32767),2);
        }
    }
    private static void le(OutputStream stream,int value,int bytes)throws IOException{for(int i=0;i<bytes;i++)stream.write((value>>>(i*8))&255);}
    private void preview(){
        if(busy||output==null)return;
        if(player!=null){releasePlayer();refresh();return;}
        try{
            player=new MediaPlayer();player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            player.setDataSource(output.getAbsolutePath());player.setOnCompletionListener(p->{releasePlayer();refresh();});player.setOnErrorListener((p,w,e)->{releasePlayer();refresh();status.setText("试听失败："+w);return true;});
            player.setOnPreparedListener(p->{if(!destroyed&&player==p){p.start();refresh();}});player.prepareAsync();refresh();
        }catch(IOException|RuntimeException e){releasePlayer();refresh();status.setText("试听失败："+e.getMessage());}
    }
    private void share(){
        if(busy||output==null)return;
        releasePlayer();refresh();
        Uri uri=ManagerShareProvider.uri(this,output);
        Intent send=new Intent(Intent.ACTION_SEND).setType("audio/wav").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newUri(getContentResolver(),"变声音频",uri));
        try{startActivity(Intent.createChooser(send,"分享音频").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
        catch(RuntimeException e){status.setText("无法打开分享面板，请返回工具箱导出");}
    }
    private void refresh(){if(record==null)return;record.setText(capturing?"停止录音":"录音");record.setEnabled(!busy||capturing);preview.setEnabled(!busy&&output!=null);share.setEnabled(!busy&&output!=null);preview.setText(player==null?"试听":"停止试听");if(ball!=null){ball.setText(capturing?"录音":busy?"转换":player!=null?"试听":"变声");ball.setTextColor(capturing?Color.rgb(255,180,170):Color.WHITE);}}
    private void releasePlayer(){if(player!=null){player.release();player=null;}}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(Color.WHITE);return v;}
    private Button button(String label,Runnable action){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(14);b.setMinHeight(dp(48));b.setOnClickListener(v->{try{action.run();}catch(RuntimeException e){status.setText("未完成："+e.getMessage());}});controls.addView(b,new LinearLayout.LayoutParams(-1,-2));return b;}
    @Override public void onDestroy(){destroyed=true;stopCapture=true;worker.shutdownNow();releasePlayer();if(panel!=null&&manager!=null){try{manager.removeView(panel);}catch(RuntimeException ignored){}}panel=null;super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
