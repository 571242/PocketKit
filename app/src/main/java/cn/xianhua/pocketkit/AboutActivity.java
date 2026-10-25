package cn.xianhua.pocketkit;

import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import java.io.*;
import java.nio.charset.StandardCharsets;

public final class AboutActivity extends BaseToolActivity {
    @Override protected void onCreate(Bundle state) {
        getIntent().putExtra("title","关于拾用"); getIntent().putExtra("tool_id","about"); super.onCreate(state);
        note("拾用是一组独立运行的小工具，帮助你在手机上处理图片、文字、计算与日常记录。界面会跟随系统浅色或深色模式。");
        note("文件处理与记录默认保存在应用本机。联网工具只在你主动使用时发起请求；需要上传图片时，工具会在操作前说明用途。");
        note("记录保存在应用私有目录，应用已关闭系统自动备份；卸载会清除这些记录，请使用工具内的导出功能自行备份。");
        note("清理前会扫描你授权的范围，删除操作需要再次确认。传感器读数会受手机型号、校准与环境影响，不替代专业测量仪器。");
        Button details=new Button(this); details.setText("查看数据、权限与第三方说明"); ToolUi.text(details,accent,15); details.setAllCaps(false); ToolUi.touch(details,dp(48)); details.setBackground(ToolUi.fill(Color.TRANSPARENT,dp(12),accent,dp(1))); body.addView(details);
        TextViewHolder holder=new TextViewHolder(); details.setOnClickListener(v->{ if(holder.view==null){ holder.view=note("应用只申请各工具所需的系统权限，例如相机、存储、通知或传感器；权限由系统在使用前询问。OCR模型随安装包提供。Google ML Kit 等第三方 SDK 可能产生诊断通信，系统文字转语音是否联网取决于你选择的引擎。许可文本如下："); try { StringBuilder licenses=new StringBuilder(); for(String notice:new String[]{"additional-notices.txt","third-party-notices.txt","moe-original-readme.txt","toolbox-011-notices.txt"}) try(BufferedReader reader=new BufferedReader(new InputStreamReader(getAssets().open(notice),StandardCharsets.UTF_8))){String line;while((line=reader.readLine())!=null)licenses.append(line).append('\n');} holder.view.append("\n\n"+licenses.toString()); } catch(IOException ex){fail(new IOException("第三方声明未能读取："+ex.getMessage(),ex));} } else holder.view.setVisibility(holder.view.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE); });
    }
    private static final class TextViewHolder { android.widget.TextView view; }
}

