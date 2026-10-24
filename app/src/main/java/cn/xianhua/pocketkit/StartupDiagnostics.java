package cn.xianhua.pocketkit;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Build;
import android.util.Log;
import android.widget.*;

/** A launch error is visible and copyable, never reported as a successful home screen. */
final class StartupDiagnostics {
    static void show(Activity activity, Throwable failure) {
        Log.e("PocketKit", "Home initialization failed", failure);
        String report="拾用 " + BuildConfig.VERSION_NAME + " 启动未完成\n"
                + Build.MANUFACTURER + " / " + Build.MODEL + "\nAndroid " + Build.VERSION.RELEASE
                + " / API " + Build.VERSION.SDK_INT + "\nABI " + String.join(",", Build.SUPPORTED_ABIS)
                + "\n\n" + Log.getStackTraceString(failure);
        LinearLayout panel=new LinearLayout(activity); panel.setOrientation(LinearLayout.VERTICAL);
        int padding=(int)(16*activity.getResources().getDisplayMetrics().density); panel.setPadding(padding,padding,padding,padding);
        TextView title=new TextView(activity); title.setText("启动未完成 · 请复制诊断"); title.setTextSize(20); panel.addView(title);
        TextView hint=new TextView(activity); hint.setText("这是启动错误，不是正常首页。无需卸载或清空数据；诊断不含账本、文本或照片，不会自动上传。"); panel.addView(hint);
        Button copy=new Button(activity); copy.setText("复制启动诊断"); panel.addView(copy);
        copy.setOnClickListener(v->{
            ClipboardManager clipboard=(ClipboardManager)activity.getSystemService(Activity.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("拾用启动诊断", report));
            Toast.makeText(activity,"已复制",Toast.LENGTH_SHORT).show();
        });
        ScrollView scroll=new ScrollView(activity); TextView text=new TextView(activity);
        text.setText(report); text.setTextSize(14); scroll.addView(text); panel.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        activity.setContentView(panel);
    }
}
