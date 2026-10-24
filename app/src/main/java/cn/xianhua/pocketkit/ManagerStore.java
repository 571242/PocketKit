package cn.xianhua.pocketkit;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;

/** One file model for local storage, SAF providers (including Termux/cloud), and read-only ZIPs. */
public final class ManagerStore {
    private final Context context;
    private final ContentResolver resolver;
    public static final long ARCHIVE_LIMIT=512L*1024*1024;
    public static final class Node {
        public Uri uri; public String name,mime; public boolean directory; public long size,modified,flags;
        public Node parent; public File archive; public String entry;
        public String location(){return archive!=null?archive.getName()+"!/"+entry:uri.toString();}
    }
    public static final class Location {
        public final Node directory,target;public final List<Node> children;
        private Location(Node directory,List<Node> children,Node target){this.directory=directory;this.children=children;this.target=target;}
    }
    public ManagerStore(Context context){this.context=context;resolver=context.getContentResolver();}
    public Node local(File file) throws IOException {
        if(!file.exists())throw new FileNotFoundException(file.toString());
        Node n=new Node();n.uri=Uri.fromFile(file);n.name=file.getName();if(n.name.isEmpty())n.name=file.toString();n.directory=file.isDirectory();n.size=file.length();n.modified=file.lastModified();n.mime=mime(n.name);return n;
    }
    public Node resolve(Uri uri) throws Exception {
        if("file".equals(uri.getScheme()))return local(new File(uri.getPath()));
        if(!"content".equals(uri.getScheme()))throw new IOException("只支持文件和系统文档 URI");
        if(DocumentsContract.isTreeUri(uri)&&!DocumentsContract.isDocumentUri(context,uri))uri=DocumentsContract.buildDocumentUriUsingTree(uri,DocumentsContract.getTreeDocumentId(uri));
        Node n=new Node();n.uri=uri;n.name="未命名文件";n.size=-1;n.mime=resolver.getType(uri);
        try(Cursor c=resolver.query(uri,null,null,null,null)){
            if(c==null||!c.moveToFirst())throw new IOException("文件不存在或授权已失效，请重新添加位置"+safDiagnostic(uri));
            n.name=string(c,OpenableColumns.DISPLAY_NAME,n.name);n.size=number(c,OpenableColumns.SIZE,-1);n.modified=number(c,DocumentsContract.Document.COLUMN_LAST_MODIFIED,0);n.flags=number(c,DocumentsContract.Document.COLUMN_FLAGS,0);
            n.mime=string(c,DocumentsContract.Document.COLUMN_MIME_TYPE,n.mime);
        }catch(SecurityException e){throw new SecurityException("系统拒绝读取文档，请重新通过‘添加云盘 / Termux / 文档目录’授权"+safDiagnostic(uri),e);}
        n.directory=DocumentsContract.Document.MIME_TYPE_DIR.equals(n.mime);return n;
    }
    public Location locateParent(Node target)throws Exception{
        File local=localFile(target.uri);
        if(local!=null){
            // Anchor at the volume the file actually lives on: hardcoding the primary root meant a
            // file on an SD card always failed withinRoot and fell through to "cannot locate".
            File root=volumeOf(local),parent=local.getParentFile();
            if(root==null||parent==null||!ManagerAlgorithms.withinRoot(root,parent)||ManagerAlgorithms.androidPrivateDirectory(root,local))return null;
            Node directory=local(parent),child=directory;
            for(File ancestor=parent.getParentFile();ancestor!=null&&ManagerAlgorithms.withinRoot(root,ancestor);ancestor=ancestor.getParentFile()){
                if(!ancestor.canRead())break;Node next=local(ancestor);child.parent=next;child=next;
            }
            return locateInDirectory(directory,target);
        }
        if(!"content".equals(target.uri.getScheme())||!DocumentsContract.isTreeUri(target.uri)||!DocumentsContract.isDocumentUri(context,target.uri))return null;
        DocumentsContract.Path path;
        try{path=DocumentsContract.findDocumentPath(resolver,target.uri);}catch(FileNotFoundException|IllegalArgumentException|SecurityException e){return null;}
        List<String> ids=path==null?null:path.getPath();if(ids==null||ids.size()<2)return null;
        Node parent=null;
        for(int i=0;i<ids.size()-1;i++){Node next=resolve(DocumentsContract.buildDocumentUriUsingTree(target.uri,ids.get(i)));next.parent=parent;parent=next;}
        return locateInDirectory(parent,target);
    }
    /**
     * Locates the folder that contains a target, falling back to the nearest readable ancestor.
     *
     * <p>Providers routinely refuse {@code list()} on the immediate parent of a single granted
     * document, which is why "open with" so often ended at "cannot locate". Walking up until a
     * directory is actually listable turns most of those cases into a usable jump.
     */
    public Location locateNearestReadableParent(Node target)throws Exception{
        Location exact=locateParent(target);
        if(exact!=null)return exact;
        File local=localFile(target.uri);
        if(local==null||local.getParentFile()==null)return null;
        File root=volumeOf(local);
        if(root==null)return null;
        for(File dir=local.getParentFile();dir!=null&&ManagerAlgorithms.withinRoot(root,dir);dir=dir.getParentFile()){
            if(!dir.canRead())continue;
            Node candidate=local(dir);
            List<Node> children;
            try{children=list(candidate);}catch(Exception e){continue;}
            // target stays null: this is an ancestor, not the file, and the caller must say so.
            return new Location(candidate,children,null);
        }
        return null;
    }
    public Location locateInDirectory(Node directory,Node target)throws Exception{
        List<Node> children=list(directory);for(Node child:children)if(sameTarget(child,target))return new Location(directory,children,child);return null;
    }
    private boolean sameTarget(Node first,Node second)throws Exception{
        File a=localFile(first.uri),b=localFile(second.uri);if(a!=null&&b!=null)return a.equals(b);
        if("content".equals(first.uri.getScheme())&&"content".equals(second.uri.getScheme())&&Objects.equals(first.uri.getAuthority(),second.uri.getAuthority())&&DocumentsContract.isDocumentUri(context,first.uri)&&DocumentsContract.isDocumentUri(context,second.uri))return Objects.equals(DocumentsContract.getDocumentId(first.uri),DocumentsContract.getDocumentId(second.uri));
        return first.uri.equals(second.uri);
    }
    private File localFile(Uri uri)throws Exception{
        if("file".equals(uri.getScheme()))return new File(uri.getPath()).getCanonicalFile();
        if("content".equals(uri.getScheme())&&"media".equals(uri.getAuthority())){
            // Gallery, camera and audio providers hand out MediaStore ids, and the relative path is
            // recoverable from them. Without this, "open with" from the gallery could never locate
            // the original folder, which is the common case for photos.
            return mediaStoreFile(uri);
        }
        if(!"content".equals(uri.getScheme())||!"com.android.externalstorage.documents".equals(uri.getAuthority())||!DocumentsContract.isDocumentUri(context,uri))return null;
        String[] split=ManagerAlgorithms.documentVolumeAndPath(DocumentsContract.getDocumentId(uri));
        if(split==null)return null;
        File volume=volumeRoot(split[0]);
        if(volume==null)return null;
        File root=volume.getCanonicalFile(),file=new File(root,split[1]).getCanonicalFile();
        if(!ManagerAlgorithms.withinRoot(root,file))throw new SecurityException("系统文档路径超出所在存储卷");return file;
    }
    /** Maps an externalstorage volume id ("primary", "1F2E-3A4B") to its mounted directory. */
    private File volumeRoot(String volume)throws Exception{
        if("primary".equalsIgnoreCase(volume))return android.os.Environment.getExternalStorageDirectory();
        String uuid=volume.toUpperCase(Locale.ROOT);
        for(File dir:context.getExternalFilesDirs(null)){
            if(dir==null)continue;
            // /storage/1F2E-3A4B/Android/data/<pkg>/files → take the volume segment before Android.
            String path=dir.getAbsolutePath();
            int marker=path.indexOf("/Android/");
            if(marker<0)continue;
            String segment=path.substring(0,marker);
            int slash=segment.lastIndexOf('/');
            if(slash<0)continue;
            if(segment.substring(slash+1).equalsIgnoreCase(uuid))return new File(segment);
        }
        return null;
    }
    /** Finds the storage volume a local file belongs to, primary first. */
    private File volumeOf(File file)throws Exception{
        File primary=android.os.Environment.getExternalStorageDirectory().getCanonicalFile();
        if(ManagerAlgorithms.withinRoot(primary,file))return primary;
        for(File dir:context.getExternalFilesDirs(null)){
            if(dir==null)continue;
            String path=dir.getAbsolutePath();
            int marker=path.indexOf("/Android/");
            if(marker<0)continue;
            File root=new File(path.substring(0,marker)).getCanonicalFile();
            if(ManagerAlgorithms.withinRoot(root,file))return root;
        }
        return null;
    }
    /** Reads a MediaStore document's real path; returns null when the provider is not MediaStore. */
    private File mediaStoreFile(Uri uri)throws Exception{
        String[] projection={android.provider.MediaStore.MediaColumns.DATA};
        try(Cursor c=resolver.query(uri,projection,null,null,null)){
            if(c==null||!c.moveToFirst())return null;
            String path=string(c,android.provider.MediaStore.MediaColumns.DATA,null);
            if(path==null||path.isEmpty())return null;
            File file=new File(path).getCanonicalFile();
            File root=android.os.Environment.getExternalStorageDirectory().getCanonicalFile();
            return ManagerAlgorithms.withinRoot(root,file)?file:null;
        }catch(SecurityException|IllegalArgumentException e){return null;}
    }
    private static String string(Cursor c,String key,String fallback){int i=c.getColumnIndex(key);return i<0||c.isNull(i)?fallback:c.getString(i);}
    private static long number(Cursor c,String key,long fallback){int i=c.getColumnIndex(key);return i<0||c.isNull(i)?fallback:c.getLong(i);}
    private String safDiagnostic(Uri uri){
        boolean persisted=false;
        for(UriPermission permission:resolver.getPersistedUriPermissions()){
            if(!permission.isReadPermission()||!Objects.equals(permission.getUri().getAuthority(),uri.getAuthority()))continue;
            if(permission.getUri().equals(uri)){persisted=true;break;}
            try{if(DocumentsContract.isTreeUri(permission.getUri())&&DocumentsContract.isTreeUri(uri)&&Objects.equals(DocumentsContract.getTreeDocumentId(permission.getUri()),DocumentsContract.getTreeDocumentId(uri))){persisted=true;break;}}catch(IllegalArgumentException ignored){}
        }
        return "\nURI："+uri+"\n持久读取授权："+(persisted?"已持有":"未找到或已撤销");
    }
    private String localAccessState(){
        if(android.os.Build.VERSION.SDK_INT>=30)return android.os.Environment.isExternalStorageManager()?"已授予":"未授予";
        return context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)==android.content.pm.PackageManager.PERMISSION_GRANTED?"已授予（传统权限）":"未授予（传统权限）";
    }
    private String storageState(File directory){try{return android.os.Environment.getExternalStorageState(directory);}catch(IllegalArgumentException e){return android.os.Environment.getExternalStorageState();}}
    public static String mime(String name){int i=name.lastIndexOf('.');String type=i<0?null:MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(i+1).toLowerCase(Locale.ROOT));return type==null?"application/octet-stream":type;}
    public List<Node> list(Node dir) throws Exception {
        if(!dir.directory)throw new IOException("不是目录");
        List<Node> result=new ArrayList<>();
        if(dir.archive!=null)return zipList(dir);
        if("file".equals(dir.uri.getScheme())){
            File directory=new File(dir.uri.getPath());File[] files=directory.listFiles();
            if(files==null)throw new IOException(ManagerAlgorithms.localListFailure(directory,android.os.Environment.getExternalStorageDirectory(),localAccessState(),storageState(directory)));
            if(files.length>50000)throw new IOException("目录条目过多，请使用更小目录");
            for(File f:files)try{Node n=local(f);n.parent=dir;result.add(n);}catch(FileNotFoundException ignored){/* Entry disappeared between listFiles and metadata read. */}
        }else{
            Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(dir.uri,DocumentsContract.getDocumentId(dir.uri));
            String[] projection={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_LAST_MODIFIED,DocumentsContract.Document.COLUMN_FLAGS};
            try(Cursor c=resolver.query(children,projection,null,null,null)){
                if(c==null)throw new IOException("提供器未返回目录"+safDiagnostic(dir.uri));
                if(c.getExtras().getBoolean(DocumentsContract.EXTRA_LOADING,false))throw new IOException("云端目录正在加载，请稍后刷新"+safDiagnostic(dir.uri));
                while(c.moveToNext()){
                    Node n=new Node();n.uri=DocumentsContract.buildDocumentUriUsingTree(dir.uri,c.getString(0));n.name=c.getString(1);n.mime=c.getString(2);n.directory=DocumentsContract.Document.MIME_TYPE_DIR.equals(n.mime);n.size=c.isNull(3)?-1:c.getLong(3);n.modified=c.getLong(4);n.flags=c.getLong(5);n.parent=dir;result.add(n);
                    if(result.size()>50000)throw new IOException("目录条目过多");
                }
                String error=c.getExtras().getString(DocumentsContract.EXTRA_ERROR);if(error!=null)throw new IOException(error+safDiagnostic(dir.uri));
            }catch(SecurityException e){throw new SecurityException("系统文档目录授权已失效，请重新添加位置"+safDiagnostic(dir.uri),e);}
        }
        sort(result);return result;
    }
    private static void sort(List<Node> nodes){nodes.sort(Comparator.comparing((Node n)->!n.directory).thenComparing(n->n.name,String.CASE_INSENSITIVE_ORDER));}
    public InputStream input(Node n) throws Exception {
        if(n.directory)throw new IOException("请选择文件");
        if(n.archive!=null){
            return ManagerAlgorithms.zipInput(n.archive,n.entry,ARCHIVE_LIMIT);
        }
        InputStream in="file".equals(n.uri.getScheme())?new FileInputStream(n.uri.getPath()):resolver.openInputStream(n.uri);
        if(in==null)throw new IOException("提供器不支持读取此文件（可能是虚拟文档）");return in;
    }
    public OutputStream output(Node n) throws Exception {
        if(n.archive!=null)throw new IOException("压缩包内为只读，请先提取");
        OutputStream out="file".equals(n.uri.getScheme())?new FileOutputStream(n.uri.getPath()):resolver.openOutputStream(n.uri,"wt");
        if(out==null)throw new IOException("该来源不支持写入");return out;
    }
    public Node create(Node dir,String name,boolean folder,String type) throws Exception {
        ManagerAlgorithms.safeName(name);if(dir.archive!=null)throw new IOException("不能写入压缩包，请选择普通目录");
        Set<String> names=new HashSet<>();for(Node n:list(dir))names.add(n.name);
        String candidate=name;for(int i=1;names.contains(candidate);i++)candidate=i+"_"+name;
        Node n;
        if("file".equals(dir.uri.getScheme())){
            File f=new File(dir.uri.getPath(),candidate);boolean ok=folder?f.mkdir():f.createNewFile();if(!ok)throw new IOException("无法创建："+candidate);n=local(f);
        }else{
            Uri uri=DocumentsContract.createDocument(resolver,dir.uri,folder?DocumentsContract.Document.MIME_TYPE_DIR:type,candidate);
            if(uri==null)throw new IOException("提供器不支持创建文件");n=resolve(uri);
        }
        n.parent=dir;return n;
    }
    public void rename(Node n,String name) throws Exception {
        ManagerAlgorithms.safeName(name);if(n.archive!=null)throw new IOException("压缩包条目不可重命名");
        if(n.parent!=null)for(Node sibling:list(n.parent))if(sibling.name.equals(name))throw new IOException("同名项目已存在");
        if("file".equals(n.uri.getScheme())){
            File old=new File(n.uri.getPath()),to=new File(old.getParentFile(),name);Files.move(old.toPath(),to.toPath());n.uri=Uri.fromFile(to);n.name=name;
        }else{Uri renamed=DocumentsContract.renameDocument(resolver,n.uri,name);if(renamed==null)throw new IOException("提供器不支持重命名");n.uri=renamed;n.name=name;}
    }
    public void delete(Node n) throws Exception {
        if(n.archive!=null)throw new IOException("压缩包为只读");
        if("file".equals(n.uri.getScheme()))deleteLocal(new File(n.uri.getPath()),0);
        else if(!DocumentsContract.deleteDocument(resolver,n.uri))throw new IOException("提供器拒绝删除");
    }
    private void deleteLocal(File f,int depth)throws IOException{
        if(depth>128)throw new IOException("目录嵌套过深");if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
        if(!Files.isSymbolicLink(f.toPath())&&f.isDirectory()){File[] children=f.listFiles();if(children==null)throw new IOException("目录不可读");for(File child:children)deleteLocal(child,depth+1);}
        if(!f.delete())throw new IOException("删除失败："+f.getName());
    }
    private String identity(Node n)throws Exception{
        if(n.archive!=null)return n.archive.getCanonicalPath()+"!"+n.entry;
        if("file".equals(n.uri.getScheme()))return new File(n.uri.getPath()).getCanonicalPath();
        if(DocumentsContract.isDocumentUri(context,n.uri)){
            String documentId=DocumentsContract.getDocumentId(n.uri);
            if("com.android.externalstorage.documents".equals(n.uri.getAuthority())&&documentId.startsWith("primary:"))return new File(android.os.Environment.getExternalStorageDirectory(),documentId.substring(8)).getCanonicalPath();
            return n.uri.getAuthority()+":"+documentId;
        }
        return n.uri.toString();
    }
    private boolean sharedAncestor(Node a,Node b)throws Exception{
        Set<String> ids=new HashSet<>();for(Node n=a;n!=null;n=n.parent)ids.add(identity(n));for(Node n=b;n!=null;n=n.parent)if(ids.contains(identity(n)))return true;return false;
    }
    public Node copy(Node source,Node target,boolean move)throws Exception{
        if(!target.directory||target.archive!=null)throw new IOException("目标必须是可写目录");
        String id=identity(source);for(Node p=target;p!=null;p=p.parent)if(id.equals(identity(p)))throw new IOException("不能复制到自身或子目录");
        String targetId=identity(target);
        if(source.archive==null&&new File(id).isAbsolute()&&new File(targetId).isAbsolute()){
            if(targetId.equals(id)||targetId.startsWith(id+File.separator))throw new IOException("不能复制到自身目录（含本地与文档授权的同目录别名）");
        }
        if(source.directory&&source.archive==null&&"content".equals(source.uri.getScheme())&&source.uri.getAuthority().equals(target.uri.getAuthority())){
            // Independent SAF roots may alias a descendant; ask the owning provider before creating anything.
            if(!sharedAncestor(source,target)){
                if(android.os.Build.VERSION.SDK_INT<29)throw new IOException("旧版安卓无法确认两个独立授权目录的关系，请在两窗口选择同一个授权根目录再浏览到目标");
                try{if(DocumentsContract.isChildDocument(resolver,source.uri,target.uri))throw new IOException("不能复制到自身子目录");}
                catch(UnsupportedOperationException e){throw new IOException("该提供器不支持目录关系检查，请从同一个授权根目录进入两窗口",e);}
            }
        }
        if(move&&source.archive!=null)throw new IOException("压缩包为只读，请使用复制提取");
        if(move&&source.parent!=null&&identity(source.parent).equals(identity(target)))throw new IOException("源目录与目标目录相同，无需移动");
        if(move&&"file".equals(source.uri.getScheme())&&"file".equals(target.uri.getScheme())){
            File from=new File(source.uri.getPath()),to=new File(target.uri.getPath(),source.name);
            int suffix=1;while(to.exists())to=new File(target.uri.getPath(),suffix+++"_"+source.name);
            try{Files.move(from.toPath(),to.toPath());Node moved=local(to);moved.parent=target;return moved;}
            catch(java.nio.file.FileSystemException e){if(!from.exists()||to.exists())throw e;/* Different filesystems: verified copy below. */}
        }
        if(move&&source.parent!=null&&"content".equals(source.uri.getScheme())&&source.uri.getAuthority().equals(target.uri.getAuthority())&&(source.flags&DocumentsContract.Document.FLAG_SUPPORTS_MOVE)!=0){
            // A provider owns atomicity. Never retry a failed provider move as copy/delete: effects may be unknown.
            for(Node n:list(target))if(n.name.equals(source.name))throw new IOException("目标存在同名项目，请先重命名再移动");
            Uri uri=DocumentsContract.moveDocument(resolver,source.uri,source.parent.uri,target.uri);if(uri==null)throw new IOException("提供器未确认移动，请刷新两个窗口核实");Node moved=resolve(uri);moved.parent=target;return moved;
        }
        Map<String,String> snapshot=move&&source.directory?fingerprints(source):null;
        Node created=copyTree(source,target,new HashSet<>(),new long[]{0,0},0);
        if(move){
            if(source.directory){
                if(!snapshot.equals(fingerprints(created))||!snapshot.equals(fingerprints(source)))throw new IOException("目录内容在移动期间发生变化，原目录和复制件均保留");
            }else{
                String first,second;try(InputStream in=input(source)){first=ManagerAlgorithms.hashes(in);}try(InputStream in=input(created)){second=ManagerAlgorithms.hashes(in);}
                if(!first.equals(second))throw new IOException("移动校验不一致，原文件与复制件均保留");
            }
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("已取消，源文件保留");
            delete(source);
        }
        return created;
    }
    private Map<String,String> fingerprints(Node root)throws Exception{
        Map<String,String> result=new TreeMap<>();fingerprints(root,"",result,new HashSet<>(),0);return result;
    }
    private void fingerprints(Node node,String path,Map<String,String> result,Set<String> ancestors,int depth)throws Exception{
        if(depth>128||result.size()>=50000)throw new IOException("目录过深或条目过多");
        String id=identity(node);if(!ancestors.add(id))throw new IOException("循环目录");
        if(node.archive==null&&"file".equals(node.uri.getScheme())&&Files.isSymbolicLink(new File(node.uri.getPath()).toPath()))throw new IOException("不跨来源移动符号链接，请选择实际文件");
        if(node.directory){result.put(path,"directory");for(Node child:list(node))fingerprints(child,path+"/"+ManagerAlgorithms.safeName(child.name),result,ancestors,depth+1);}
        else try(InputStream in=input(node)){result.put(path,ManagerAlgorithms.hashes(in));}
        ancestors.remove(id);
    }
    private Node copyTree(Node source,Node target,Set<String> ancestors,long[] budget,int depth)throws Exception{
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
        if(depth>128||++budget[0]>50000)throw new IOException("目录过深或条目过多");
        String id=identity(source);if(!ancestors.add(id))throw new IOException("检测到循环目录");
        if(source.archive==null&&"file".equals(source.uri.getScheme())&&Files.isSymbolicLink(new File(source.uri.getPath()).toPath()))throw new IOException("不递归复制符号链接，请选择实际文件");
        Node dest=create(target,source.name,source.directory,source.mime==null?mime(source.name):source.mime);
        try{
            if(source.directory){for(Node child:list(source))copyTree(child,dest,ancestors,budget,depth+1);}
            else try(InputStream in=input(source);OutputStream out=output(dest)){
                long count=ManagerAlgorithms.transfer(in,out,source.archive!=null?ARCHIVE_LIMIT-budget[1]:Long.MAX_VALUE);
                budget[1]+=count;if(source.size>=0&&source.size!=count)throw new IOException("文件长度发生变化，未删除原文件");
            }
        }catch(Exception e){try{delete(dest);}catch(Exception cleanup){e.addSuppressed(cleanup);}throw e;}
        finally{ancestors.remove(id);}
        return dest;
    }
    public File stage(Node n,String subdir,long limit)throws Exception{
        File dir=new File(context.getCacheDir(),subdir);if(!dir.exists()&&!dir.mkdirs())throw new IOException("无法创建缓存目录");
        long used=0;for(String cache:new String[]{"manager-archives","manager-exports"}){File[] files=new File(context.getCacheDir(),cache).listFiles();if(files!=null)for(File f:files)used+=f.length();}
        long available=1024L*1024*1024-used;if(available<=0)throw new IOException("管理器临时缓存已达 1 GiB，请从工具菜单清理缓存");
        limit=Math.min(limit,available);
        File file=File.createTempFile("file-","-"+ManagerAlgorithms.safeName(n.name),dir);
        try(InputStream in=input(n);OutputStream out=new FileOutputStream(file)){ManagerAlgorithms.transfer(in,out,limit);}
        catch(Exception e){file.delete();throw e;}return file;
    }
    public void clearCache()throws Exception{
        for(String name:new String[]{"manager-archives","manager-exports"}){
            File[] files=new File(context.getCacheDir(),name).listFiles();if(files!=null)for(File file:files){if(name.equals("manager-exports"))context.revokeUriPermission(ManagerShareProvider.uri(context,file),Intent.FLAG_GRANT_READ_URI_PERMISSION);if(!file.delete())throw new IOException("部分缓存仍被占用，请稍后重试");}
        }
    }
    public Node archive(Node file)throws Exception{
        File zip=stage(file,"manager-archives",ARCHIVE_LIMIT);
        try(ZipFile ignored=new ZipFile(zip)){Node n=new Node();n.archive=zip;n.entry="";n.name=file.name;n.directory=true;n.uri=file.uri;n.parent=file.parent;return n;}
        catch(Exception e){zip.delete();throw e;}
    }
    private List<Node> zipList(Node dir)throws Exception{
        Map<String,Node> children=new LinkedHashMap<>();Set<String> paths=new HashSet<>();int count=0;
        try(ZipFile zip=new ZipFile(dir.archive)){
            Enumeration<? extends ZipEntry> entries=zip.entries();
            while(entries.hasMoreElements()){
                if(++count>100000)throw new IOException("压缩包条目超过 100000 条");
                ZipEntry entry=entries.nextElement();String path=entry.getName();
                if(!ManagerAlgorithms.safeZipPath(path)||!paths.add(path))throw new IOException("压缩包含不安全或重复路径，已拒绝浏览与提取");
                if(!path.startsWith(dir.entry)||path.equals(dir.entry))continue;
                String rest=path.substring(dir.entry.length());int slash=rest.indexOf('/');String name=slash<0?rest:rest.substring(0,slash);if(name.isEmpty())continue;
                boolean folder=slash>=0||entry.isDirectory();Node n=children.get(name);
                if(n==null){n=new Node();n.uri=dir.uri;n.archive=dir.archive;n.parent=dir;n.name=name;n.directory=folder;n.entry=dir.entry+name+(folder?"/":"");n.size=folder?-1:entry.getSize();n.mime=mime(name);n.modified=entry.getTime();children.put(name,n);}
                else if(n.directory!=folder)throw new IOException("压缩包含冲突路径");
            }
        }
        List<Node> result=new ArrayList<>(children.values());sort(result);return result;
    }
}
