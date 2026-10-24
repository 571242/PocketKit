package cn.xianhua.pocketkit.device;

import cn.xianhua.pocketkit.core.MiniJson;
import cn.xianhua.pocketkit.core.ToolException;
import java.io.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Hash-checked installation of bundled data; never replaces an existing file. */
public final class LexiconInstaller {
    public static final long MAX_BYTES=200L*1024*1024, MAX_META=64*1024;
    private LexiconInstaller() {}
    public interface Source { InputStream open() throws IOException; }
    public static synchronized Path install(Path dir, String prefix, Source gzip, Source meta) throws IOException {
        if (!prefix.matches("[a-z0-9-]{1,40}")) throw new IOException("无效词典文件前缀");
        Metadata m=metadata(meta);
        Files.createDirectories(dir);
        Path target=dir.resolve(prefix+"-"+m.sha+".db");
        if (Files.exists(target,LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS) || Files.size(target)!=m.bytes || !hex(digest(target)).equals(m.sha))
                throw new IOException("已有词典大小或SHA校验失败，未覆盖原文件");
            return target;
        }
        Path tmp=Files.createTempFile(dir,prefix+"-", ".tmp");
        try {
            MessageDigest md=sha(); long bytes=0;
            try(InputStream raw=gzip.open(); GZIPInputStream in=new GZIPInputStream(raw);
                OutputStream out=new BufferedOutputStream(Files.newOutputStream(tmp))) {
                byte[] b=new byte[32768]; int count;
                while((count=in.read(b))!=-1) {
                    bytes+=count;
                    if(bytes>m.bytes || bytes>MAX_BYTES) throw new IOException("词典超过声明大小上限");
                    md.update(b,0,count); out.write(b,0,count);
                }
                out.flush();
            }
            if(bytes!=m.bytes) throw new IOException("词典大小校验失败");
            if(!hex(md.digest()).equals(m.sha)) throw new IOException("词典SHA校验失败");
            try(FileChannel channel=FileChannel.open(tmp,StandardOpenOption.WRITE)) { channel.force(true); }
            if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)) throw new IOException("目标已经存在，未覆盖");
            try { Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE); }
            catch(AtomicMoveNotSupportedException e) { throw new IOException("不支持词典原子发布",e); }
            return target;
        } finally { Files.deleteIfExists(tmp); }
    }
    private static byte[] digest(Path path)throws IOException {
        MessageDigest md=sha();
        try(InputStream in=Files.newInputStream(path)) { byte[] b=new byte[32768]; int n; while((n=in.read(b))!=-1) md.update(b,0,n); }
        return md.digest();
    }
    private static MessageDigest sha()throws IOException { try{return MessageDigest.getInstance("SHA-256");}catch(java.security.NoSuchAlgorithmException e){throw new IOException(e);} }
    private static String hex(byte[] bytes) { StringBuilder out=new StringBuilder(); for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255)); return out.toString(); }
    private static Metadata metadata(Source source)throws IOException {
        try(InputStream in=source.open()) {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream(); byte[] buffer=new byte[4096]; int n;
            while((n=in.read(buffer))!=-1) { if(bytes.size()+n>MAX_META)throw new IOException("metadata超过大小上限"); bytes.write(buffer,0,n); }
            Map<String,Object> root=MiniJson.asObject(MiniJson.parse(bytes.toString("UTF-8")));
            Object hash=root.get("sqlite_sha256"),size=root.get("sqlite_bytes");
            if(!(hash instanceof String) || !((String)hash).matches("[0-9a-fA-F]{64}") || !(size instanceof java.math.BigDecimal) || ((java.math.BigDecimal)size).scale()!=0)
                throw new IOException("metadata缺失有效SHA或正整数大小");
            long length;
            try { length=((java.math.BigDecimal)size).longValueExact(); }
            catch(ArithmeticException e) { throw new IOException("metadata大小不是合法整数",e); }
            if(length<=0 || length>MAX_BYTES) throw new IOException("metadata大小超过允许范围");
            return new Metadata(((String)hash).toLowerCase(Locale.ROOT),length);
        } catch(ToolException e) { throw new IOException("metadata不是合法JSON",e); }
    }
    private static final class Metadata { final String sha; final long bytes; Metadata(String sha,long bytes){this.sha=sha;this.bytes=bytes;} }
}
