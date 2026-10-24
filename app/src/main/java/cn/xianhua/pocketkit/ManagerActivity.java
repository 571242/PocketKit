package cn.xianhua.pocketkit;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.*;
import android.provider.*;
import android.text.*;
import android.text.style.ForegroundColorSpan;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import org.json.*;

/** Native two-pane manager. No MT code, credentials, or private application access is embedded. */
public final class ManagerActivity extends BaseToolActivity {
    private ManagerStore store;
    private final Pane[] panes=new Pane[2];
    private int active=0,pickPane=0,pendingLocalPane=-1;
    private boolean busy=false;
    private TextView status;
    private EditText filter;
    private ManagerStore.Node diffFirst;
    private String pendingMode="";
    private final List<ManagerStore.Node> incoming=new ArrayList<>();
    private ManagerStore.Node pendingIncomingTarget;
    private cn.xianhua.pocketkit.remote.RemotePanel remotePanel;
    private final List<Button> controls=new ArrayList<>();
    private static final int TREE=710,FILE=711;
    public static boolean supports(String id){return Arrays.asList("M01","M02","M03","M04","M05","M06").contains(id);}
    public static String limitation(String id){return "远程支持 SFTP、SMB、FTP/FTPS、WebDAV；云盘与 Termux 走系统文档提供器；不绕过应用私有目录权限。ZIP/APK 内为只读浏览和提取。";}
    private final class Pane {
        ManagerStore.Node dir;
        List<ManagerStore.Node> nodes=new ArrayList<>(),visible=new ArrayList<>();
        final Set<String> selected=new HashSet<>();String highlight;
        TextView path;ListView list;ArrayAdapter<String> adapter;
    }
    @Override protected void onCreate(Bundle state){
        // External callers cannot control the tool selector or private paths through extras.
        if(!getPackageName().equals(getCallingPackage()) && (Intent.ACTION_VIEW.equals(getIntent().getAction())||Intent.ACTION_SEND.equals(getIntent().getAction())||Intent.ACTION_SEND_MULTIPLE.equals(getIntent().getAction())))getIntent().removeExtra("tool_id");
        getIntent().putExtra("title","管理器");if(!getIntent().hasExtra("tool_id"))getIntent().putExtra("tool_id","M01");
        super.onCreate(state);store=new ManagerStore(this);
        root.removeViewAt(1);
        LinearLayout area=new LinearLayout(this);area.setOrientation(LinearLayout.VERTICAL);root.addView(area,new LinearLayout.LayoutParams(-1,0,1));
        status=new TextView(this);ToolUi.text(status,muted,13);status.setPadding(0,dp(4),0,dp(4));area.addView(status);
        filter=new EditText(this);filter.setHint("筛选当前窗口 · 长按条目多选");ToolUi.text(filter,ink,14);filter.setSingleLine(true);area.addView(filter,new LinearLayout.LayoutParams(-1,dp(48)));
        LinearLayout columns=new LinearLayout(this);area.addView(columns,new LinearLayout.LayoutParams(-1,0,1));
        for(int i=0;i<2;i++){
            final int index=i;Pane p=new Pane();panes[i]=p;LinearLayout column=new LinearLayout(this);column.setOrientation(LinearLayout.VERTICAL);column.setPadding(dp(2),0,dp(2),0);columns.addView(column,new LinearLayout.LayoutParams(0,-1,1));
            LinearLayout bar=new LinearLayout(this);column.addView(bar);
            small(bar,"位置",()->sources(index));small(bar,"上级",()->up(index));
            p.path=new TextView(this);ToolUi.text(p.path,ink,12);p.path.setMinHeight(dp(48));p.path.setMaxLines(3);p.path.setPadding(dp(6),dp(6),dp(6),dp(6));p.path.setOnClickListener(v->{if(!busy){active=index;render();}});column.addView(p.path);
            p.list=new ListView(this);p.list.setDividerHeight(dp(1));p.list.setBackgroundColor(surface);column.addView(p.list,new LinearLayout.LayoutParams(-1,0,1));
            p.adapter=new ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,new ArrayList<>()){
                @Override public View getView(int position,View convert,ViewGroup parent){TextView v=(TextView)super.getView(position,convert,parent);ToolUi.text(v,ink,14);v.setMinHeight(dp(56));v.setMaxLines(3);v.setPadding(dp(8),dp(6),dp(4),dp(6));ManagerStore.Node n=position<p.visible.size()?p.visible.get(position):null;boolean highlighted=n!=null&&Objects.equals(p.highlight,n.location());v.setBackgroundColor(highlighted?Color.argb(38,Color.red(accent),Color.green(accent),Color.blue(accent)):Color.TRANSPARENT);return v;}
            };p.list.setAdapter(p.adapter);
            p.list.setOnItemClickListener((a,v,pos,id)->{if(busy)return;active=index;ManagerStore.Node n=p.visible.get(pos);if(!p.selected.isEmpty()){toggle(p,n);return;}if(n.directory)navigate(index,n);else menu(n,index);});
            p.list.setOnItemLongClickListener((a,v,pos,id)->{if(!busy){active=index;toggle(p,p.visible.get(pos));}return true;});
        }
        HorizontalScrollView actions=new HorizontalScrollView(this);LinearLayout row=new LinearLayout(this);actions.addView(row);area.addView(actions);
        small(row,"复制 → 另一窗",()->transfer(false));small(row,"移动 → 另一窗",()->transfer(true));small(row,"全选/取消",()->{Pane p=panes[active];if(p.selected.isEmpty())for(ManagerStore.Node n:p.visible)p.selected.add(n.location());else p.selected.clear();render();});
        small(row,"所选操作",()->{List<ManagerStore.Node> s=selected();if(s.size()==1)menu(s.get(0),active);else status.setText("请选择一个条目进行重命名或查看属性");});small(row,"删除",this::deleteSelection);small(row,"新建",this::create);small(row,"刷新",this::refresh);small(row,"工具",this::toolsMenu);
        filter.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){} public void onTextChanged(CharSequence s,int st,int b,int c){render();}public void afterTextChanged(Editable e){}});
        status.setText("左/右窗口独立浏览；长按选择，点路径切换操作窗口");
        job(()->{
            for(int i=0;i<2;i++){
                String saved=state==null?getPreferences(0).getString("pane"+i,""):state.getString("pane"+i,"");
                try{panes[i].dir=restoreLocation(saved);panes[i].nodes=store.list(panes[i].dir);}
                catch(Exception e){panes[i].dir=store.local(defaultLocalDirectory());panes[i].nodes=store.list(panes[i].dir);}
            }
        },()->{render();if(!hasLocalStorageAccess())status.setText("当前位于应用专用外部目录；要浏览共享存储，请点‘位置 → 本地共享存储’授权\n路径："+defaultLocalDirectory());handleIncoming(getIntent());if(state==null&&incoming.isEmpty())launchShortcut();});
    }
    private void small(LinearLayout row,String label,Runnable action){Button b=new Button(this);b.setText(label);ToolUi.text(b,accent,13);b.setAllCaps(false);b.setMinHeight(dp(48));b.setOnClickListener(v->{if(!busy)try{action.run();}catch(Exception e){error(e);}});row.addView(b);controls.add(b);}
    private void toggle(Pane p,ManagerStore.Node n){if(!p.selected.add(n.location()))p.selected.remove(n.location());render();}
    private void render(){
        if(panes[0]==null||panes[1]==null)return;
        String q=filter.getText().toString().toLowerCase(Locale.ROOT);
        for(int i=0;i<2;i++){
            Pane p=panes[i];if(p.dir==null)continue;p.path.setText((i==active?"● ":"")+(i==0?"左":"右")+" · "+p.dir.name+"\n"+p.dir.location());p.path.setBackground(ToolUi.fill(surface,dp(6),i==active?accent:Color.TRANSPARENT,dp(1)));
            p.visible.clear();p.adapter.setNotifyOnChange(false);p.adapter.clear();for(ManagerStore.Node n:p.nodes)if(i!=active||q.isEmpty()||n.name.toLowerCase(Locale.ROOT).contains(q)){
                String marker=p.selected.contains(n.location())?"☑ ":Objects.equals(p.highlight,n.location())?"★ ":n.directory?"▸ ":"  ";
                p.visible.add(n);p.adapter.add(marker+n.name+(n.directory?"/":"\n"+(n.size<0?"大小未知":android.text.format.Formatter.formatFileSize(this,n.size))));
            }p.adapter.notifyDataSetChanged();
        }
    }
    private void job(Work work,Runnable done){
        if(busy)return;busy=true;status.setText("正在处理…请保持管理器打开");for(Button b:controls)b.setEnabled(false);
        workers.execute(()->{Exception failure=null;try{work.run();}catch(Exception e){failure=e;}final Exception error=failure;
            runOnUiThread(()->{if(isDestroyed()||isFinishing())return;busy=false;for(Button b:controls)b.setEnabled(true);status.setText(error==null?"就绪 · 长按多选，点路径切换窗口":"未完成 · "+ManagerAlgorithms.errorCategory(error)+"："+error.getMessage());if(error!=null)error(error);else if(done!=null)done.run();});});
    }
    private boolean closing;
    @Override public void finish(){if(busy&&!closing){new AlertDialog.Builder(this).setTitle("操作仍在进行").setMessage("退出会中断当前操作，已完成的项目会保留。请勿在移动时同时从其他应用修改源目录。").setPositiveButton("停止并退出",(d,w)->{closing=true;super.finish();}).setNegativeButton("继续等待",null).show();}else super.finish();}
    private void error(Throwable e){if(isFinishing()||isDestroyed())return;new AlertDialog.Builder(this).setTitle("操作未完成 · "+ManagerAlgorithms.errorCategory(e)).setMessage(e.getMessage()==null?e.toString():e.getMessage()).setPositiveButton("知道了",null).show();}
    private void navigate(int index,ManagerStore.Node dir){active=index;job(()->{List<ManagerStore.Node> list=store.list(dir);panes[index].dir=dir;panes[index].nodes=list;panes[index].selected.clear();panes[index].highlight=null;},()->{filter.setText("");saveLocations();render();});}
    private ManagerStore.Node restoreLocation(String saved)throws Exception{
        if(saved.isEmpty())return store.local(defaultLocalDirectory());
        if(!saved.startsWith("[")){ManagerStore.Node only=store.resolve(Uri.parse(saved));return migratePrivateRoot(only);}
        JSONArray chain=new JSONArray(saved);ManagerStore.Node current=null;String scheme=null,authority=null;
        for(int i=0;i<chain.length();i++){ManagerStore.Node next=store.resolve(Uri.parse(chain.getString(i)));if(scheme!=null&&!ManagerAlgorithms.sameStorageSource(scheme,authority,next.uri.getScheme(),next.uri.getAuthority()))throw new IOException("保存的位置混合了本地路径和系统文档授权，请重新选择位置");scheme=next.uri.getScheme();authority=next.uri.getAuthority();next.parent=current;current=next;}
        return current==null?store.local(defaultLocalDirectory()):migratePrivateRoot(current);
    }
    /** 授权后旧会话仍停在本应用私有目录，看起来像"有权限却读不到"；这里换成共享存储根。 */
    private ManagerStore.Node migratePrivateRoot(ManagerStore.Node saved){
        if(saved==null||saved.archive!=null||!"file".equals(saved.uri.getScheme())||!hasLocalStorageAccess())return saved;
        try{
            String path=new File(saved.uri.getPath()).getCanonicalPath();
            String external=getExternalFilesDir(null)==null?"":getExternalFilesDir(null).getCanonicalPath();
            String internal=getFilesDir().getCanonicalPath();
            if(!external.isEmpty()&&path.startsWith(external)||path.startsWith(internal))return store.local(Environment.getExternalStorageDirectory());
        }catch(IOException ignored){}
        return saved;
    }
    private String locationState(ManagerStore.Node dir){List<String> chain=new ArrayList<>();for(ManagerStore.Node n=dir;n!=null;n=n.parent)if(n.archive==null)chain.add(n.uri.toString());Collections.reverse(chain);return new JSONArray(chain).toString();}
    private void saveLocations(){SharedPreferences.Editor e=getPreferences(0).edit();for(int i=0;i<2;i++)if(panes[i].dir!=null&&panes[i].dir.archive==null)e.putString("pane"+i,locationState(panes[i].dir));e.apply();}
    @Override protected void onSaveInstanceState(Bundle state){for(int i=0;i<2;i++)if(panes[i]!=null&&panes[i].dir!=null)state.putString("pane"+i,locationState(panes[i].dir));super.onSaveInstanceState(state);}
    private void refresh(){job(this::reload, this::render);}
    private void reload()throws Exception{for(Pane p:panes)if(p.dir!=null){p.nodes=store.list(p.dir);p.selected.clear();}}
    private void up(int index){Pane p=panes[index];if(p.dir.parent!=null){navigate(index,p.dir.parent);return;}status.setText("已到"+("content".equals(p.dir.uri.getScheme())?"系统文档授权根目录":"本地来源根目录")+"；用‘位置’切换来源");}
    private void sources(int index){
        active=index;String[] labels={"本地共享存储","远程连接（SFTP / SMB / FTP / WebDAV）","添加云盘 / Termux / 文档目录","拾用工作目录","收藏当前位置","已收藏的位置","打开单个文件","接入说明"};
        new AlertDialog.Builder(this).setTitle(index==0?"左窗口位置":"右窗口位置").setItems(labels,(d,w)->{try{switch(w){
            case 0:localStorage(index);break;
            case 1:remotePane();break;
            case 2:pickPane=index;startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION),TREE);break;
            case 3:navigate(index,store.local(getFilesDir()));break;
            case 4:bookmark(panes[index].dir);break;
            case 5:bookmarks(index);break;
            case 6:pick("");break;
            default:new AlertDialog.Builder(this).setTitle("位置说明").setMessage("本地共享存储：手机内部存储与 SD 卡，需要所有文件访问权限。\n\n远程连接：内置 SFTP、SMB（Windows 共享与 NAS）、FTP/FTPS、WebDAV 四种协议，密码用本机密钥库加密保存。\n\n云盘与 Termux：安装并登录支持系统文档提供器的应用后，从系统选择器侧栏选目录授权。\n\n远程目录与本地目录是两个独立界面：远程侧可浏览、上传、下载、重命名、新建文件夹、删除；本地双窗之间可以互相复制。已撤销授权的位置需要重新添加。").setPositiveButton("明白",null).show();
        }}catch(Exception e){error(e);}}).show();
    }
    /** Opens the standalone remote browser; it owns its own connections and worker thread. */
    private void remotePane(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);
        remotePanel=new cn.xianhua.pocketkit.remote.RemotePanel(this,ink,muted,surface,accent);
        remotePanel.attach(box);
        new AlertDialog.Builder(this).setTitle("远程连接").setView(box).setNegativeButton("关闭",null)
                .setOnDismissListener(d->{if(remotePanel!=null){remotePanel.release();remotePanel=null;}}).show();
    }
    private boolean hasLocalStorageAccess(){
        if(Build.VERSION.SDK_INT>=30)return Environment.isExternalStorageManager();
        return checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED&&checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED;
    }
    private File defaultLocalDirectory(){
        if(hasLocalStorageAccess())return Environment.getExternalStorageDirectory();
        File external=getExternalFilesDir(null);return external==null?getFilesDir():external;
    }
    private void localStorage(int index)throws Exception{
        if(!hasLocalStorageAccess()){
            if(Build.VERSION.SDK_INT>=30){
                new AlertDialog.Builder(this).setTitle("授权本地文件管理").setMessage("需要在系统设置允许拾用管理所有文件。不会因此获得其他应用私有目录，以及 Android/data、Android/obb 中其他应用的内容。也可取消后使用系统目录授权。\n\n目标路径："+Environment.getExternalStorageDirectory()+"\n所有文件访问：未授予").setPositiveButton("去设置",(d,w)->{try{pendingLocalPane=index;startActivityForResult(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,Uri.parse("package:"+getPackageName())),712);}catch(Exception e){pendingLocalPane=-1;error(e);}}).setNegativeButton("取消",null).show();
            }else{pendingLocalPane=index;requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE,android.Manifest.permission.WRITE_EXTERNAL_STORAGE},713);}
            return;
        }
        navigate(index,store.local(Environment.getExternalStorageDirectory()));
    }
    private void resumeLocalStorageAccess(){
        if(pendingLocalPane<0||Build.VERSION.SDK_INT<30)return;
        if(!hasLocalStorageAccess()){pendingLocalPane=-1;status.setText("未获得本地共享存储授权\n目标路径："+Environment.getExternalStorageDirectory()+"\n所有文件访问：未授予；也可用‘添加云盘 / Termux / 文档目录’选择 SAF 目录");return;}
        if(busy){status.postDelayed(this::resumeLocalStorageAccess,200);return;}
        int index=pendingLocalPane;pendingLocalPane=-1;try{navigate(index,store.local(Environment.getExternalStorageDirectory()));}catch(Exception e){error(e);}
    }
    @Override protected void onResume(){super.onResume();if(status!=null)status.post(this::resumeLocalStorageAccess);}
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){
        super.onRequestPermissionsResult(r,p,g);if(r!=713)return;boolean granted=g.length>0;for(int value:g)granted&=value==PackageManager.PERMISSION_GRANTED;int index=pendingLocalPane;pendingLocalPane=-1;
        if(granted&&index>=0)try{navigate(index,store.local(Environment.getExternalStorageDirectory()));}catch(Exception e){error(e);}else status.setText("未获得本地共享存储读写权限；可用系统文档目录授权代替");
    }
    private JSONArray savedBookmarks(){try{return new JSONArray(getPreferences(0).getString("bookmarks","[]"));}catch(Exception e){return new JSONArray();}}
    private void bookmark(ManagerStore.Node n){if(n.archive!=null){status.setText("请收藏压缩包所在目录");return;}try{JSONArray a=savedBookmarks();for(int i=0;i<a.length();i++)if(a.getJSONObject(i).getString("uri").equals(n.uri.toString()))return;a.put(new JSONObject().put("name",n.name).put("uri",n.uri.toString()));getPreferences(0).edit().putString("bookmarks",a.toString()).apply();status.setText("已收藏："+n.name);}catch(Exception e){error(e);}}
    private void bookmarks(int index){JSONArray a=savedBookmarks();String[] names=new String[a.length()];for(int i=0;i<a.length();i++)names[i]=a.optJSONObject(i).optString("name")+"\n"+a.optJSONObject(i).optString("uri");AlertDialog d=new AlertDialog.Builder(this).setTitle("位置收藏（长按移除）").setItems(names,(x,w)->job(()->{ManagerStore.Node n=store.resolve(Uri.parse(a.optJSONObject(w).optString("uri")));List<ManagerStore.Node> list=store.list(n);panes[index].dir=n;panes[index].nodes=list;panes[index].selected.clear();},()->{saveLocations();render();})).create();d.setOnShowListener(v->d.getListView().setOnItemLongClickListener((av,view,pos,id)->{a.remove(pos);getPreferences(0).edit().putString("bookmarks",a.toString()).apply();d.dismiss();bookmarks(index);return true;}));d.show();}
    private List<ManagerStore.Node> selected(){Pane p=panes[active];List<ManagerStore.Node> nodes=new ArrayList<>();for(ManagerStore.Node n:p.nodes)if(p.selected.contains(n.location()))nodes.add(n);return nodes;}
    private void transfer(boolean move){List<ManagerStore.Node> nodes=selected();if(nodes.isEmpty()){status.setText("先长按选择文件或目录");return;}ManagerStore.Node target=panes[1-active].dir;new AlertDialog.Builder(this).setTitle(move?"移动到另一窗口":"复制到另一窗口").setMessage(nodes.size()+" 项 → "+target.name+"\n同名保留两份。"+(move?"移动会校验复制件后删除源文件。":"")+"\n中途失败时，已完成的项目会保留。").setPositiveButton("开始",(d,w)->job(()->{for(ManagerStore.Node n:nodes)store.copy(n,target,move);reload();},this::render)).setNegativeButton("取消",null).show();}
    private void deleteSelection(){List<ManagerStore.Node> list=selected();if(list.isEmpty()){status.setText("先长按选择需要删除的条目");return;}new AlertDialog.Builder(this).setTitle("永久删除 "+list.size()+" 项？").setMessage("目录将连同内容一起删除，无回收站。\n"+list.get(0).name+(list.size()>1?" 等":"")).setPositiveButton("删除",(d,w)->job(()->{for(ManagerStore.Node n:list)store.delete(n);reload();},this::render)).setNegativeButton("取消",null).show();}
    private void prompt(String title,String initial,java.util.function.Consumer<String> accept){EditText input=new EditText(this);input.setSingleLine(true);input.setText(initial);new AlertDialog.Builder(this).setTitle(title).setView(input).setPositiveButton("确定",(d,w)->accept.accept(input.getText().toString())).setNegativeButton("取消",null).show();}
    private void create(){new AlertDialog.Builder(this).setTitle("新建").setItems(new String[]{"文件夹","空文本文件"},(d,w)->prompt("名称",w==0?"新建文件夹":"新建文件.txt",name->{ManagerStore.Node dir=panes[active].dir;job(()->{store.create(dir,name,w==0,"text/plain");reload();},this::render);})).show();}
    private void menu(ManagerStore.Node n,int index){
        active=index;render();String[] items={"文本查看","Hex 十六进制","计算校验值","浏览 ZIP / APK","设为对比原文件","与原文件对比","其他应用打开","打开所在位置","复制到另一窗口","重命名","属性","选择此项"};
        new AlertDialog.Builder(this).setTitle(n.name).setItems(items,(d,w)->{switch(w){
            case 0:readText(n);break;case 1:hex(n,0);break;case 2:hash(n);break;
            case 3:job(()->{ManagerStore.Node dir=store.archive(n);List<ManagerStore.Node> list=store.list(dir);panes[index].dir=dir;panes[index].nodes=list;panes[index].selected.clear();},this::render);break;
            case 4:diffFirst=n;status.setText("已选择原文件，再选择另一份文本进行对比");break;
            case 5:compare(n);break;
            case 6:openOther(n);break;
            case 7:locateFolderOf(n,index);break;
            case 8:ManagerStore.Node target=panes[1-index].dir;job(()->{store.copy(n,target,false);reload();},this::render);break;
            case 9:prompt("重命名",n.name,name->job(()->{store.rename(n,name);reload();},this::render));break;
            case 10:display("属性",n.name+"\n"+n.location()+"\n类型："+n.mime+"\n字节："+n.size+"\n修改时间："+(n.modified>0?new Date(n.modified):"未知")+"\n来源能力标志："+n.flags);break;
            case 11:toggle(panes[index],n);break;
        }}).show();
    }
    /**
     * Jumps to the folder holding a file, the way a desktop file manager's "open containing
     * folder" behaves. Falls back to the nearest readable ancestor and says so.
     */
    private void locateFolderOf(ManagerStore.Node n,int index){
        ManagerStore.Location[] found={null};String[] failure={""};
        job(()->{try{found[0]=store.locateNearestReadableParent(n);}catch(Exception e){failure[0]=e.getMessage()==null?e.toString():e.getMessage();}},
            ()->{if(found[0]==null)showIncomingFallback(n,failure[0].isEmpty()?"该文件所在目录不可读或不在已授权范围内":failure[0]);
                 else if(found[0].target!=null)showIncomingLocation(found[0]);
                 else showIncomingNearest(found[0],n);});
    }
    private void display(String title,CharSequence text){TextView view=new TextView(this);ToolUi.text(view,ink,14);view.setTypeface(Typeface.MONOSPACE);view.setTextIsSelectable(true);view.setPadding(dp(12),dp(12),dp(12),dp(12));view.setText(text);ScrollView scroll=new ScrollView(this);scroll.addView(view);new AlertDialog.Builder(this).setTitle(title).setView(scroll).setPositiveButton("关闭",null).show();}
    private void readText(ManagerStore.Node n){String[] text={""};job(()->{try(InputStream in=store.input(n)){text[0]=ManagerAlgorithms.utf8(in);}},()->display(n.name,text[0]));}
    private void hash(ManagerStore.Node n){String[] text={""};job(()->{try(InputStream in=store.input(n)){text[0]=ManagerAlgorithms.hashes(in);}},()->display(n.name,text[0]));}
    private void hex(ManagerStore.Node n,long offset){String[] text={""};job(()->{try(InputStream in=store.input(n)){text[0]=ManagerAlgorithms.hexPage(in,offset);}},()->{
        TextView v=new TextView(this);ToolUi.text(v,ink,12);v.setTypeface(Typeface.MONOSPACE);v.setTextIsSelectable(true);v.setText(text[0]);HorizontalScrollView h=new HorizontalScrollView(this);ScrollView s=new ScrollView(this);s.addView(v);h.addView(s);
        new AlertDialog.Builder(this).setTitle(n.name+" · 偏移 "+offset).setView(h).setPositiveButton("下一页",(d,w)->hex(n,offset+4096)).setNegativeButton("关闭",null).setNeutralButton("跳转",(d,w)->prompt("字节偏移（十进制）",String.valueOf(offset),value->{try{hex(n,Long.parseLong(value));}catch(Exception e){error(e);}})).show();
    });}
    private void compare(ManagerStore.Node n){if(diffFirst==null){diffFirst=n;pendingMode="diff-second";pick("diff-second");return;}ManagerStore.Node first=diffFirst;String[] text={""};job(()->{try(InputStream a=store.input(first);InputStream b=store.input(n)){text[0]=ManagerAlgorithms.diff(ManagerAlgorithms.utf8(a),ManagerAlgorithms.utf8(b));}},()->{SpannableString value=new SpannableString(text[0]);int offset=0;for(String line:text[0].split("\n",-1)){if(line.startsWith("+ ")||line.startsWith("− "))value.setSpan(new ForegroundColorSpan(line.startsWith("+ ")?accent:Color.rgb(190,55,45)),offset,offset+line.length(),0);offset+=line.length()+1;}display("文本差异",value);});}
    private void openOther(ManagerStore.Node n){Uri[] uri={null};job(()->{File file=store.stage(n,"manager-exports",ManagerStore.ARCHIVE_LIMIT);uri[0]=ManagerShareProvider.uri(this,file);},()->{try{Intent view=new Intent(Intent.ACTION_VIEW).setDataAndType(uri[0],n.mime==null?ManagerStore.mime(n.name):n.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);view.setClipData(ClipData.newRawUri(n.name,uri[0]));startActivity(Intent.createChooser(view,"选择打开方式"));}catch(Exception e){error(e);}});}
    private void toolsMenu(){new AlertDialog.Builder(this).setTitle("快捷工具").setItems(new String[]{"打开 ZIP / APK","应用提取（含拆分包、图标）","文本对比","Hex 查看","文件校验","导入外部收到的文件","清理临时缓存"},(d,w)->{switch(w){case 0:pick("zip");break;case 1:applications();break;case 2:diffFirst=null;pick("diff-first");break;case 3:pick("hex");break;case 4:pick("hash");break;case 5:importIncoming();break;case 6:clearCache();break;}}).show();}
    private void clearCache(){new AlertDialog.Builder(this).setTitle("清理管理器临时缓存？").setMessage("将关闭压缩包浏览，移除 ZIP 下载缓存与分享副本；不会删除原文件。其他应用正在读取的分享副本也会失效。").setPositiveButton("清理",(d,w)->job(()->{for(Pane p:panes)if(p.dir.archive!=null)p.dir=store.local(defaultLocalDirectory());diffFirst=null;incoming.removeIf(n->n.archive!=null);store.clearCache();reload();},this::render)).setNegativeButton("取消",null).show();}
    private void launchShortcut(){switch(toolId){case "M02":pick("zip");break;case "M03":applications();break;case "M04":pick("diff-first");break;case "M05":pick("hex");break;case "M06":pick("hash");break;}}
    private void pick(String mode){pendingMode=mode;startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),FILE);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);
        if(request==712){resumeLocalStorageAccess();return;}
        if(result!=RESULT_OK||data==null||data.getData()==null){if(request==TREE)pendingIncomingTarget=null;return;}Uri uri=data.getData();
        if(request==TREE||request==FILE){try{if((data.getFlags()&Intent.FLAG_GRANT_WRITE_URI_PERMISSION)!=0)getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);else getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(SecurityException ignored){status.setText("此来源只提供临时授权");}}
        if(request==TREE){ManagerStore.Node requested=pendingIncomingTarget;pendingIncomingTarget=null;ManagerStore.Location[] location={null};job(()->{ManagerStore.Node n=store.resolve(uri);if(requested==null){List<ManagerStore.Node> list=store.list(n);panes[pickPane].dir=n;panes[pickPane].nodes=list;panes[pickPane].selected.clear();panes[pickPane].highlight=null;}else{location[0]=store.locateInDirectory(n,requested);if(location[0]==null)throw new IOException("所选目录不是该文件可验证的直接父目录，请重新授权正确目录");}},()->{if(location[0]!=null)showIncomingLocation(location[0]);bookmark(panes[pickPane].dir);saveLocations();render();});}
        else if(request==FILE){ManagerStore.Node[] n={null};job(()->n[0]=store.resolve(uri),()->dispatchFile(n[0]));}
    }
    private void dispatchFile(ManagerStore.Node n){String mode=pendingMode;pendingMode="";switch(mode){case "hex":hex(n,0);break;case "hash":hash(n);break;case "zip":int index=active;job(()->{ManagerStore.Node dir=store.archive(n);List<ManagerStore.Node> list=store.list(dir);panes[index].dir=dir;panes[index].nodes=list;},this::render);break;case "diff-first":diffFirst=n;pick("diff-second");break;case "diff-second":compare(n);break;default:incoming.add(n);menu(n,active);}}
    private void handleIncoming(Intent intent){
        String action=intent.getAction();if(!Intent.ACTION_VIEW.equals(action)&&!Intent.ACTION_SEND.equals(action)&&!Intent.ACTION_SEND_MULTIPLE.equals(action))return;
        List<Uri> uris=new ArrayList<>();if(intent.getData()!=null)uris.add(intent.getData());
        if(Intent.ACTION_SEND.equals(action)){Uri u=intent.getParcelableExtra(Intent.EXTRA_STREAM);if(u!=null&&!uris.contains(u))uris.add(u);}
        if(Intent.ACTION_SEND_MULTIPLE.equals(action)){ArrayList<Uri> list=intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);if(list!=null)for(Uri u:list)if(u!=null&&!uris.contains(u))uris.add(u);}
        if(intent.getClipData()!=null)for(int i=0;i<intent.getClipData().getItemCount();i++){Uri u=intent.getClipData().getItemAt(i).getUri();if(u!=null&&!uris.contains(u))uris.add(u);}
        if(uris.isEmpty())return;if(uris.size()>100){error(new IOException("一次最多接收 100 个文件"));return;}
        ManagerStore.Location[] location={null};String[] limitation={"该来源只授予单个文件，未提供可验证且可读取的父目录"};
        job(()->{incoming.clear();for(Uri u:uris){
            if("file".equals(u.getScheme())){File file=new File(u.getPath()).getCanonicalFile(),root=Environment.getExternalStorageDirectory().getCanonicalFile();if(!ManagerAlgorithms.withinRoot(root,file)||ManagerAlgorithms.androidPrivateDirectory(root,file))throw new SecurityException("外部文件只能来自可访问的共享存储，不能来自其他应用私有目录");if(!file.exists()||!file.canRead())throw new SecurityException("系统拒绝读取该外部文件");}
            else if(!"content".equals(u.getScheme())||checkUriPermission(u,android.os.Process.myPid(),android.os.Process.myUid(),Intent.FLAG_GRANT_READ_URI_PERMISSION)!=PackageManager.PERMISSION_GRANTED)throw new SecurityException("发送方未授予文件读取权限，请重新通过系统分享/打开方式发送");
            incoming.add(store.resolve(u));
        }
            if(incoming.size()==1)try{location[0]=store.locateNearestReadableParent(incoming.get(0));}catch(Exception e){limitation[0]=e.getMessage()==null?e.toString():e.getMessage();}
        },()->{if(incoming.size()!=1)importIncoming();else if(location[0]==null)showIncomingFallback(incoming.get(0),limitation[0]);else if(location[0].target!=null)showIncomingLocation(location[0]);else showIncomingNearest(location[0],incoming.get(0));});
    }
    private void showIncomingLocation(ManagerStore.Location location){
        Pane p=panes[active];filter.setText("");p.dir=location.directory;p.nodes=location.children;p.selected.clear();p.highlight=location.target.location();saveLocations();render();int position=p.nodes.indexOf(location.target);if(position>=0)p.list.setSelection(position);status.setText("已定位并高亮："+location.target.name+"\n父目录："+location.directory.location());
    }
    /**
     * Jumps to whatever ancestor could be read. Says so plainly: the target is not highlighted
     * because it is not a child of the directory shown, and pretending otherwise would be a lie.
     */
    private void showIncomingNearest(ManagerStore.Location location,ManagerStore.Node target){
        Pane p=panes[active];filter.setText("");p.dir=location.directory;p.nodes=location.children;p.selected.clear();p.highlight=null;saveLocations();render();
        status.setText("已跳到最近的上级可读目录，未精确定位到文件本身\n目录："+location.directory.location()
                +"\n文件："+target.name+"（系统只授权了这一个文件，父目录列表不可读）");
    }
    private void showIncomingFallback(ManagerStore.Node target,String reason){
        status.setText("已读取外部文件，但无法安全定位原父目录");new AlertDialog.Builder(this).setTitle("无法定位原父目录").setMessage(reason+"\n\nAndroid 常只授权被打开的单个文件；拾用不会复制文件来冒充原位置。可授权实际父目录，或只查看外部文件详情。").setPositiveButton("授权父目录",(d,w)->authorizeIncomingParent(target)).setNeutralButton("文件详情",(d,w)->display("外部文件详情",target.name+"\n类型："+target.mime+"\n字节："+(target.size<0?"未知":target.size)+"\nURI："+target.uri+"\n\n父目录：未获授权或来源未提供可验证关系")).setNegativeButton("关闭",null).show();
    }
    private void authorizeIncomingParent(ManagerStore.Node target){
        pickPane=active;pendingIncomingTarget=target;Intent tree=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);if("content".equals(target.uri.getScheme()))tree.putExtra(DocumentsContract.EXTRA_INITIAL_URI,target.uri);try{startActivityForResult(tree,TREE);}catch(Exception e){pendingIncomingTarget=null;error(e);}
    }
    private void importIncoming(){if(incoming.isEmpty()){status.setText("没有外部文件；可从其他应用选择“用拾用管理器打开”");return;}ManagerStore.Node target=panes[active].dir;new AlertDialog.Builder(this).setTitle("复制收到的文件").setMessage(incoming.size()+" 项 → "+target.name+"\n不改动发送方原文件").setPositiveButton("复制",(d,w)->job(()->{for(ManagerStore.Node n:incoming)store.copy(n,target,false);reload();},this::render)).setNegativeButton("取消",null).show();}
    private void applications(){List<ApplicationInfo> apps=new ArrayList<>();job(()->{apps.addAll(getPackageManager().getInstalledApplications(0));apps.sort(Comparator.comparing(a->a.loadLabel(getPackageManager()).toString(),String.CASE_INSENSITIVE_ORDER));},()->{
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);EditText search=new EditText(this);search.setHint("搜索应用名称或包名");box.addView(search);ListView list=new ListView(this);box.addView(list,new LinearLayout.LayoutParams(-1,dp(360)));List<ApplicationInfo> visible=new ArrayList<>(apps);ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_list_item_1);list.setAdapter(adapter);Runnable update=()->{String q=search.getText().toString().toLowerCase(Locale.ROOT);visible.clear();adapter.clear();for(ApplicationInfo a:apps){String label=a.loadLabel(getPackageManager())+"\n"+a.packageName;if(label.toLowerCase(Locale.ROOT).contains(q)){visible.add(a);adapter.add(label);}}};update.run();search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){update.run();}public void afterTextChanged(Editable e){}});
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("提取到当前窗口："+panes[active].dir.name).setView(box).setNegativeButton("取消",null).create();list.setOnItemClickListener((a,v,pos,id)->{ApplicationInfo app=visible.get(pos);dialog.dismiss();ManagerStore.Node target=panes[active].dir;new AlertDialog.Builder(this).setTitle("导出 "+app.loadLabel(getPackageManager())).setMessage("将 base.apk、所有 split APK、图标和版本信息复制到：\n"+target.name+"\n不包含用户数据；拆分包需配套安装器安装。").setPositiveButton("导出",(d,w)->exportApplication(app,target)).setNegativeButton("取消",null).show();});dialog.show();
    });}
    private void exportApplication(ApplicationInfo app,ManagerStore.Node target){job(()->{
        ManagerStore.Node folder=store.create(target,app.packageName,true,"application/octet-stream");
        try{
            List<String> paths=new ArrayList<>();paths.add(app.sourceDir);if(app.splitSourceDirs!=null)paths.addAll(Arrays.asList(app.splitSourceDirs));
            for(int i=0;i<paths.size();i++){ManagerStore.Node source=store.local(new File(paths.get(i)));source.name=i==0?"base.apk":"split-"+i+".apk";store.copy(source,folder,false);}
            PackageInfo info=getPackageManager().getPackageInfo(app.packageName,0);JSONObject meta=new JSONObject().put("package",app.packageName).put("label",app.loadLabel(getPackageManager()).toString()).put("versionName",info.versionName).put("versionCode",Build.VERSION.SDK_INT>=28?info.getLongVersionCode():info.versionCode).put("apkCount",paths.size());
            ManagerStore.Node json=store.create(folder,"package-info.json",false,"application/json");try(OutputStream out=store.output(json)){out.write(meta.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            Drawable icon=app.loadIcon(getPackageManager());Bitmap bitmap=Bitmap.createBitmap(192,192,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);icon.setBounds(0,0,192,192);icon.draw(canvas);ManagerStore.Node png=store.create(folder,"icon.png",false,"image/png");try(OutputStream out=store.output(png)){if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("图标导出失败");}finally{bitmap.recycle();}
        }catch(Exception e){throw new IOException("提取未完成，已生成的文件保留在 "+folder.name+"："+e.getMessage(),e);}
        reload();
    },()->{render();status.setText("提取完成："+app.packageName);});}
    @Override protected void onDestroy(){
        // Remote connections hold sockets and a worker thread; they must not outlive the screen.
        if(remotePanel!=null){remotePanel.release();remotePanel=null;}
        super.onDestroy();
    }
}
