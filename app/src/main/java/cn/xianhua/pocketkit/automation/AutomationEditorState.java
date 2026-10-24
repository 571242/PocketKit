package cn.xianhua.pocketkit.automation;

import android.content.Context;
import android.os.Bundle;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONObject;

/** Private editor checkpoint. Binder saved-state contains its UUID, never image payloads. */
public final class AutomationEditorState {
    private static final int MAX_BYTES = 48 * 1024 * 1024;
    private AutomationEditorState() { }

    public static String save(Context context, String token, Bundle state) {
        String id = token == null ? UUID.randomUUID().toString() : token;
        File target = file(context, id);
        File temp = new File(target.getParentFile(), id + ".tmp");
        try {
            byte[] bytes = encode(state).toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("编辑状态过大，请减小脚本或模板。");
            try (FileOutputStream output = new FileOutputStream(temp)) { output.write(bytes); output.getFD().sync(); }
            if (!temp.renameTo(target)) throw new IllegalStateException("编辑状态提交失败。");
            return id;
        } catch (Exception ex) { throw new IllegalStateException("无法保存编辑状态。", ex); }
        finally { if (temp.exists()) temp.delete(); }
    }

    public static Bundle load(Context context, String token) {
        if (token == null) return null;
        File target = file(context, token);
        try (FileInputStream input = new FileInputStream(target); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAX_BYTES) throw new IllegalStateException("编辑状态过大。");
                output.write(buffer, 0, count);
            }
            return decode(new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8)));
        } catch (Exception ex) { throw new IllegalStateException("无法恢复编辑状态；已保存脚本仍可从列表打开。", ex); }
    }

    private static File file(Context context, String id) {
        if (!id.matches("[0-9a-fA-F-]{36}")) throw new IllegalArgumentException("编辑状态标识无效。");
        File directory = new File(context.getFilesDir(), "automation/editor-state");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("无法建立编辑状态目录。");
        return new File(directory, id + ".json");
    }

    private static JSONObject encode(Bundle bundle) throws Exception {
        JSONObject object = new JSONObject();
        for (String key : bundle.keySet()) {
            Object value = bundle.get(key);
            if (value instanceof Bundle) object.put(key, encode((Bundle) value));
            else if (value == null) object.put(key, JSONObject.NULL);
            else if (value instanceof String || value instanceof Boolean || value instanceof Number) object.put(key, value);
            else throw new IllegalArgumentException("编辑状态字段无效。");
        }
        return object;
    }

    private static Bundle decode(JSONObject object) throws Exception {
        Bundle bundle = new Bundle();
        java.util.Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next(); Object value = object.get(key);
            if (value == JSONObject.NULL) bundle.putString(key, null);
            else if (value instanceof JSONObject) bundle.putBundle(key, decode((JSONObject) value));
            else if (value instanceof String) bundle.putString(key, (String) value);
            else if (value instanceof Boolean) bundle.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) bundle.putInt(key, (Integer) value);
            else if (value instanceof Number) bundle.putLong(key, ((Number) value).longValue());
            else throw new IllegalArgumentException("编辑状态字段无效。");
        }
        return bundle;
    }
}
