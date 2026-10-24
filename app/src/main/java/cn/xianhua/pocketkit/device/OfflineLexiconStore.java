package cn.xianhua.pocketkit.device;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;

/** Read-only Android adapter; metadata and cached data must always validate. */
public final class OfflineLexiconStore implements Closeable {
    public static final String ASSET_DB_GZ="moe-revised.pack", ASSET_META="moe-revised-meta.json";
    private final SQLiteDatabase db;
    private OfflineLexiconStore(SQLiteDatabase db){this.db=db;}
    public static synchronized OfflineLexiconStore open(Context context)throws IOException {
        Path path=LexiconInstaller.install(context.getFilesDir().toPath().resolve("moe-revised"),"moe-revised",
                ()->context.getAssets().open(ASSET_DB_GZ),()->context.getAssets().open(ASSET_META));
        SQLiteDatabase database=null;
        try {
            database=SQLiteDatabase.openDatabase(path.toString(),null,SQLiteDatabase.OPEN_READONLY|SQLiteDatabase.NO_LOCALIZED_COLLATORS);
            try(Cursor check=database.rawQuery("SELECT ordinal,title,payload FROM entries LIMIT 0",null)) { check.getColumnCount(); }
            return new OfflineLexiconStore(database);
        } catch(RuntimeException e) {
            if(database!=null)database.close();
            throw new IOException("词典SQLite损坏或格式不受支持",e);
        }
    }
    public Cursor query(String title){return db.query("entries",new String[]{"ordinal","title","payload"},"title = ?",new String[]{title},null,null,"ordinal ASC");}
    @Override public void close(){db.close();}
}
