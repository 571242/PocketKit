package cn.xianhua.pocketkit;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;

/** Device-only checks for the real Context, ManagerStore and ManagerActivity. */
public final class ManagerPermissionInstrumentation extends Instrumentation {
    private final List<String> passed=new ArrayList<>();
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();Activity activity=null;File fixture=null;
        try{
            Context context=getTargetContext();ManagerStore store=new ManagerStore(context);
            fixture=new File(context.getExternalFilesDir(null),"manager-permission-instrumentation");
            if(!fixture.exists()&&!fixture.mkdirs())throw new IOException("cannot create app-owned fixture: "+fixture);
            File child=new File(fixture,"visible.txt");try(FileOutputStream out=new FileOutputStream(child)){out.write(1);}
            ManagerStore.Node root=store.local(fixture);List<ManagerStore.Node> listed=store.list(root);
            require(listed.size()==1&&listed.get(0).name.equals("visible.txt")&&listed.get(0).parent==root,"real local directory lists and preserves navigation parent");

            String denied=ManagerAlgorithms.localListFailure(new File(fixture,"missing"),android.os.Environment.getExternalStorageDirectory(),android.os.Environment.isExternalStorageManager()?"已授予":"未授予",android.os.Environment.getExternalStorageState(fixture));
            require(denied.contains(fixture.getAbsolutePath())&&denied.contains("所有文件访问："),"device diagnostic includes actual path and all-files state");

            activity=startActivitySync(new Intent(context,ManagerActivity.class).putExtra("tool_id","M01").putExtra("title","管理器权限自检").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();SystemClock.sleep(800);
            String expected=(Build.VERSION.SDK_INT>=30&&android.os.Environment.isExternalStorageManager()?android.os.Environment.getExternalStorageDirectory():context.getExternalFilesDir(null)).toString();
            require(allText(activity.getWindow().getDecorView()).contains(expected),"manager opens at the appropriate external-storage root");
            Button up=button(activity.getWindow().getDecorView(),"上级");runOnMainSync(up::performClick);waitForIdleSync();SystemClock.sleep(200);
            String after=allText(activity.getWindow().getDecorView());
            require(after.contains(expected)&&!after.contains("file:///data\n"),"up stays at the selected local source boundary");
            runOnMainSync(activity::finish);activity=null;
            if(!child.delete()||!fixture.delete())throw new IOException("cannot remove app-owned fixture");fixture=null;
            result.putString("stream","PASS "+passed.size()+" manager permission instrumentation checks\n"+String.join("\n",passed)+"\n");finish(Activity.RESULT_OK,result);
        }catch(Throwable error){
            if(activity!=null){Activity current=activity;runOnMainSync(current::finish);}
            if(fixture!=null){File child=new File(fixture,"visible.txt");child.delete();fixture.delete();}
            result.putString("stream","FAIL after "+passed.size()+" checks: "+error+"\n"+String.join("\n",passed)+"\n");finish(Activity.RESULT_CANCELED,result);
        }
    }
    private void require(boolean value,String label){if(!value)throw new AssertionError(label);passed.add(label);}
    private Button button(View view,String label){
        if(view instanceof Button&&((Button)view).getText().toString().contains(label))return (Button)view;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){Button found=buttonOrNull(group.getChildAt(i),label);if(found!=null)return found;}}
        throw new AssertionError("button not found: "+label);
    }
    private Button buttonOrNull(View view,String label){try{return button(view,label);}catch(AssertionError ignored){return null;}}
    private String allText(View view){
        StringBuilder out=new StringBuilder();if(view instanceof TextView)out.append(((TextView)view).getText()).append('\n');
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)out.append(allText(group.getChildAt(i)));}
        return out.toString();
    }
}
