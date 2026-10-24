package cn.xianhua.pocketkit;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

/** Grants read-only access only to explicitly staged, disposable manager export files. */
public final class ManagerShareProvider extends ContentProvider {
    public static Uri uri(Context c,File file){return new Uri.Builder().scheme("content").authority(c.getPackageName()+".manager.share").appendPath(file.getName()).build();}
    private File file(Uri uri)throws FileNotFoundException {
        try{
            if(!uri.getAuthority().equals(getContext().getPackageName()+".manager.share")||uri.getPathSegments().size()!=1)throw new IOException("无效 URI");
            String name=ManagerAlgorithms.safeName(uri.getLastPathSegment());
            File root=new File(getContext().getCacheDir(),"manager-exports").getCanonicalFile();File file=new File(root,name).getCanonicalFile();
            if(!root.equals(file.getParentFile())||!file.isFile())throw new IOException("分享文件已过期");return file;
        }catch(IOException e){throw new FileNotFoundException(e.getMessage());}
    }
    @Override public boolean onCreate(){return true;}
    @Override public String getType(Uri uri){try{return ManagerStore.mime(file(uri).getName());}catch(FileNotFoundException e){return "application/octet-stream";}}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){
        try{File f=file(uri);String[] cols=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;MatrixCursor result=new MatrixCursor(cols);Object[] values=new Object[cols.length];for(int i=0;i<cols.length;i++){if(OpenableColumns.DISPLAY_NAME.equals(cols[i]))values[i]=f.getName();else if(OpenableColumns.SIZE.equals(cols[i]))values[i]=f.length();}result.addRow(values);return result;}catch(FileNotFoundException e){throw new IllegalArgumentException(e);}
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{if(!"r".equals(mode))throw new FileNotFoundException("分享文件只读");return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY);}
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException("只读");}
    @Override public int update(Uri uri,ContentValues values,String where,String[] args){throw new UnsupportedOperationException("只读");}
    @Override public int delete(Uri uri,String where,String[] args){throw new UnsupportedOperationException("只读");}
}
