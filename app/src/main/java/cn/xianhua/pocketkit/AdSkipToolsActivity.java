package cn.xianhua.pocketkit;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Switch;
import android.widget.TextView;
import cn.xianhua.pocketkit.automation.AdSkipController;
import cn.xianhua.pocketkit.device.TicketTapService;

public final class AdSkipToolsActivity extends BaseToolActivity {
    private Switch enabled;
    private TextView serviceState,resultState,diagnostic;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Runnable update=new Runnable(){public void run(){refresh();main.postDelayed(this,750);}};
    public static boolean supports(String id){return "C02".equals(id);}
    public static String limitation(String id){return "无障碍监听窗口，匹配跳过按钮并点击；哔哩哔哩/网易云音乐适配，支持排除应用与诊断。";}
    @Override protected void onCreate(Bundle state){
        getIntent().putExtra("title","开屏广告跳过");super.onCreate(state);
        note("监听 → 匹配 → 点击 → 确认。需同时打开下方开关与系统无障碍服务。哔哩哔哩按开屏按钮 ID，网易云音乐按短跳过文案；支持带倒计时的按钮。所有处理在本机完成。");
        enabled=new Switch(this);enabled.setText("自动跳过开屏广告");enabled.setTextColor(ink);enabled.setMinHeight(dp(52));enabled.setChecked(AdSkipController.isEnabled(this));
        enabled.setOnCheckedChangeListener((b,checked)->{AdSkipController.setEnabled(this,checked);refresh();});body.addView(enabled);
        serviceState=note("");resultState=note("");resultState.setTextColor(accent);
        button("打开无障碍设置",()->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        android.widget.EditText exclusions=field("排除应用包名（每行一个）","com.example.app");exclusions.setText(AdSkipController.exclusions(this));
        button("保存排除应用",()->{AdSkipController.setExclusions(this,exclusions.getText().toString());show("已保存排除应用");});
        note("不会点击系统权限、支付或密码页面，不匹配泛化关闭/叉号。节点点击被拒绝时只对重新确认的目标节点做手势点击；连点器运行时让路。按钮未暴露无障碍节点时会记录未命中，不盲点屏幕。");
        note("如果没有反应：开启后退出这里，重新进入哔哩哔哩或网易云，待广告出现再返回。下方会显示是否收到了事件、规则是否匹配、点击是否被拒绝。更新安装后若仍无事件，请关闭并重新开启系统无障碍服务。");
        diagnostic=note("");diagnostic.setTextIsSelectable(true);
        button("复制诊断",()->{((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText("开屏跳过诊断",AdSkipController.diagnostics(this)));show("诊断已复制");});
        button("导出诊断",()->startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain").putExtra(Intent.EXTRA_TITLE,"PocketKit-adskip-diagnostic.txt"),7401));
        refresh();
    }
    @Override protected void onResume(){super.onResume();if(enabled!=null)enabled.setChecked(AdSkipController.isEnabled(this));main.removeCallbacks(update);main.post(update);}
    @Override protected void onPause(){main.removeCallbacks(update);super.onPause();}
    @Override protected void onDestroy(){main.removeCallbacksAndMessages(null);super.onDestroy();}
    private void refresh(){if(serviceState==null)return;serviceState.setText("系统无障碍服务："+(TicketTapService.isEnabled()?"已连接":"未连接"));resultState.setText(AdSkipController.status());if(diagnostic!=null)diagnostic.setText(AdSkipController.diagnostics(this));}
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(request!=7401||result!=RESULT_OK||data==null||data.getData()==null)return;
        android.net.Uri uri=data.getData();String report=AdSkipController.diagnostics(this);
        background(()->{try(java.io.OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new java.io.IOException("无法写入诊断文件");out.write(report.getBytes(java.nio.charset.StandardCharsets.UTF_8));}show("诊断已导出");});
    }
}
