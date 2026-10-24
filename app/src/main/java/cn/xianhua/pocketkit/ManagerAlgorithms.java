package cn.xianhua.pocketkit;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Bounded, platform-independent file primitives, also exercised by the host self-test. */
public final class ManagerAlgorithms {
    private ManagerAlgorithms() {}
    public static String safeName(String value) throws IOException {
        if (value == null || value.trim().isEmpty() || value.equals(".") || value.equals("..") || value.contains("/") || value.contains("\\") || value.indexOf(0)>=0)
            throw new IOException("名称不能为空，也不能含路径分隔符");
        return value;
    }
    public static boolean safeZipPath(String path) {
        if (path.isEmpty() || path.startsWith("/") || path.contains("\\") || path.contains("//") || path.indexOf(0)>=0 || path.contains(":")) return false;
        for (String part:path.split("/")) if (part.equals("..") || part.equals(".")) return false;
        return true;
    }
    public static boolean withinRoot(File root,File path)throws IOException{
        String base=root.getCanonicalPath(),candidate=path.getCanonicalPath();
        return candidate.equals(base)||candidate.startsWith(base+File.separator);
    }
    public static boolean androidPrivateDirectory(File sharedRoot,File path)throws IOException{
        if(!withinRoot(sharedRoot,path))return false;
        String base=sharedRoot.getCanonicalPath(),candidate=path.getCanonicalPath();
        String relative=candidate.substring(base.length()).replace(File.separatorChar,'/');
        if(relative.startsWith("/"))relative=relative.substring(1);
        return relative.equals("Android/data")||relative.startsWith("Android/data/")||relative.equals("Android/obb")||relative.startsWith("Android/obb/");
    }
    public static boolean sameStorageSource(String scheme,String authority,String nextScheme,String nextAuthority){
        return Objects.equals(scheme,nextScheme)&&(!"content".equals(scheme)||Objects.equals(authority,nextAuthority));
    }
    public static String primaryDocumentRelativePath(String documentId)throws IOException{
        if(documentId==null||!documentId.startsWith("primary:"))return null;
        String path=documentId.substring("primary:".length());
        if(path.startsWith("/")||path.contains("\\")||path.indexOf(0)>=0)throw new IOException("系统文档路径不安全");
        if(!path.isEmpty())for(String part:path.split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals(".."))throw new IOException("系统文档路径不安全");
        return path;
    }
    /**
     * Splits a document id of the form {@code volume:relative/path} into its volume and path.
     *
     * <p>{@link #primaryDocumentRelativePath} only understands the internal volume, so a file opened
     * from an SD card or a USB drive never resolved to a real directory. Returning the volume as
     * well lets the caller look up its mount point instead of giving up.
     */
    public static String[] documentVolumeAndPath(String documentId)throws IOException{
        if(documentId==null)return null;
        int colon=documentId.indexOf(':');
        if(colon<=0)return null;
        String volume=documentId.substring(0,colon),path=documentId.substring(colon+1);
        if(volume.contains("/")||volume.contains("\\")||volume.indexOf(0)>=0)throw new IOException("系统文档路径不安全");
        if(path.startsWith("/")||path.contains("\\")||path.indexOf(0)>=0)throw new IOException("系统文档路径不安全");
        if(!path.isEmpty())for(String part:path.split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals(".."))throw new IOException("系统文档路径不安全");
        return new String[]{volume,path};
    }
    public static String localListFailure(File directory,File sharedRoot,String accessState,String storageState)throws IOException{
        String reason;
        if(!directory.exists())reason="目录已不存在";
        else if(!directory.isDirectory())reason="目标不是目录";
        else if(androidPrivateDirectory(sharedRoot,directory))reason="Android 限制访问其他应用的 Android/data 或 Android/obb；所有文件访问也不会开放该目录";
        else if(!directory.canRead())reason="系统拒绝读取该本地目录";
        else reason="系统未返回目录条目；存储可能未挂载、正忙或被系统策略限制";
        return reason+"\n路径："+directory.getAbsolutePath()+"\n所有文件访问："+accessState+"\n存储状态："+storageState;
    }
    public static String errorCategory(Throwable error){
        for(Throwable current=error;current!=null;current=current.getCause()){
            if(current instanceof InterruptedIOException)return "操作已取消";
            if(current instanceof SecurityException)return "权限拒绝";
            if(current instanceof FileNotFoundException)return "路径不存在";
        }
        return error instanceof IOException?"存储或来源错误":"操作错误";
    }
    public static long transfer(InputStream in, OutputStream out, long limit) throws IOException {
        byte[] buffer=new byte[65536]; long count=0; int n;
        while ((n=in.read(buffer))!=-1) {
            if(Thread.currentThread().isInterrupted()) throw new InterruptedIOException("操作已取消");
            count+=n; if(count>limit) throw new IOException("超过本次操作的大小上限，未删除源文件");
            out.write(buffer,0,n);
        }
        return count;
    }
    public static InputStream zipInput(File file,String name,long limit)throws IOException {
        java.util.zip.ZipFile zip=new java.util.zip.ZipFile(file);
        java.util.zip.ZipEntry entry=zip.getEntry(name);
        if(entry==null||entry.isDirectory()){zip.close();throw new IOException("压缩包条目不存在或为目录");}
        if(entry.getSize()>limit){zip.close();throw new IOException("压缩条目超过读取大小上限");}
        try{return new FilterInputStream(zip.getInputStream(entry)){
            final java.util.zip.CRC32 crc=new java.util.zip.CRC32();long bytes;
            private void check(int count)throws IOException{
                if(count<0){if((entry.getSize()>=0&&bytes!=entry.getSize())||(entry.getCrc()>=0&&crc.getValue()!=entry.getCrc()))throw new IOException("压缩条目损坏：长度或 CRC 不匹配");return;}
                bytes+=count;if(bytes>limit)throw new IOException("解压数据超过读取上限");
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
            }
            @Override public int read()throws IOException{int b=in.read();if(b>=0)crc.update(b);check(b<0?-1:1);return b;}
            @Override public int read(byte[] b,int off,int len)throws IOException{int n=in.read(b,off,len);if(n>0)crc.update(b,off,n);check(n);return n;}
            @Override public long skip(long n)throws IOException{byte[] buffer=new byte[8192];long total=0;while(total<n){int size=read(buffer,0,(int)Math.min(buffer.length,n-total));if(size<0)break;total+=size;}return total;}
            @Override public void close()throws IOException{try{super.close();}finally{zip.close();}}
        };}catch(Exception e){zip.close();throw e;}
    }
    public static String hashes(InputStream in) throws Exception {
        MessageDigest[] digests={MessageDigest.getInstance("MD5"),MessageDigest.getInstance("SHA-1"),MessageDigest.getInstance("SHA-256")};
        byte[] buffer=new byte[65536]; int n; long bytes=0;
        while((n=in.read(buffer))!=-1) { if(Thread.currentThread().isInterrupted()) throw new InterruptedIOException(); bytes+=n; for(MessageDigest d:digests)d.update(buffer,0,n); }
        StringBuilder text=new StringBuilder("字节数：").append(bytes);
        for(MessageDigest d:digests)text.append('\n').append(d.getAlgorithm()).append(": ").append(hex(d.digest()));
        return text.toString();
    }
    private static String hex(byte[] data) { StringBuilder s=new StringBuilder(); for(byte b:data)s.append(String.format(Locale.ROOT,"%02x",b&255)); return s.toString(); }
    public static String hexPage(InputStream in,long offset) throws IOException {
        if(offset<0)throw new IOException("偏移不能为负数");
        long left=offset; while(left>0){long n=in.skip(left);if(n==0){if(in.read()==-1)return "已到文件末尾";n=1;}left-=n;}
        byte[] page=new byte[4096];int size=0,n;while(size<page.length&&(n=in.read(page,size,page.length-size))!=-1)size+=n;
        StringBuilder s=new StringBuilder();
        for(int i=0;i<size;i+=16){s.append(String.format(Locale.ROOT,"%08X  ",offset+i));StringBuilder ascii=new StringBuilder();for(int j=0;j<16;j++){if(i+j<size){int b=page[i+j]&255;s.append(String.format(Locale.ROOT,"%02X ",b));ascii.append(b>=32&&b<127?(char)b:'.');}else s.append("   ");}s.append(" ").append(ascii).append('\n');}
        return size==0?"已到文件末尾":s.toString();
    }
    public static String utf8(InputStream in) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();transfer(in,out,262144);
        try{return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(out.toByteArray())).toString();}
        catch(java.nio.charset.CharacterCodingException e){throw new IOException("不是有效 UTF-8 文本，请用十六进制查看",e);}
    }
    public static String diff(String before,String after) throws IOException {
        String[] a=before.split("\n",-1),b=after.split("\n",-1);
        if(a.length>1600||b.length>1600)throw new IOException("逐行对比最多支持每份 1600 行；请先拆分大文件");
        int[][] lcs=new int[a.length+1][b.length+1];
        for(int i=a.length-1;i>=0;i--)for(int j=b.length-1;j>=0;j--)lcs[i][j]=a[i].equals(b[j])?1+lcs[i+1][j+1]:Math.max(lcs[i+1][j],lcs[i][j+1]);
        StringBuilder out=new StringBuilder("− 原文件 / + 新文件（UTF-8，保留换行差异）\n");int i=0,j=0;
        while(i<a.length||j<b.length){if(i<a.length&&j<b.length&&a[i].equals(b[j])){out.append("  ").append(a[i++]);j++;}else if(j<b.length&&(i==a.length||lcs[i][j+1]>lcs[i+1][j]))out.append("+ ").append(b[j++]);else out.append("− ").append(a[i++]);out.append('\n');}
        return out.toString();
    }
}
