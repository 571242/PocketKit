package cn.xianhua.pocketkit.remote;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Stores remote sessions, with passwords encrypted by a key that never leaves the keystore.
 *
 * <p>No new dependency: {@code androidx.security:security-crypto} would add a few hundred KB for
 * behaviour that is a dozen lines against the platform keystore. A password is sealed with AES/GCM
 * under a non-exportable key, and the ciphertext plus its IV are what actually get written to
 * preferences.
 *
 * <p>When the keystore is unavailable (a broken or emulated device), saving is refused rather than
 * silently falling back to plaintext.
 */
public final class RemoteStore {

    private static final String PREFS = "pocketkit_remote_sessions";
    private static final String KEY_SESSIONS = "sessions";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "pocketkit_remote_credentials";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int TAG_BITS = 128;

    private final Context context;

    public RemoteStore(Context context) {
        this.context = context.getApplicationContext();
    }

    /** A saved session and whatever secrets are known for it. */
    public static final class Saved {
        public final RemoteSession session;
        public final String password;

        Saved(RemoteSession session, String password) {
            this.session = session;
            this.password = password;
        }
    }

    public List<Saved> load() {
        List<Saved> out = new ArrayList<>();
        String raw = prefs().getString(KEY_SESSIONS, "");
        if (raw == null || raw.isEmpty()) return out;
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject row = array.optJSONObject(i);
                if (row == null) continue;
                RemoteSession session = new RemoteSession();
                session.id = row.optString("id", session.id);
                session.name = row.optString("name", "");
                session.protocol = RemoteSession.Protocol.of(row.optString("protocol", "SFTP"));
                session.host = row.optString("host", "");
                session.port = row.optInt("port", session.protocol.defaultPort);
                session.username = row.optString("username", "");
                session.startPath = row.optString("startPath", "");
                session.share = row.optString("share", "");
                session.basePath = row.optString("basePath", "");
                session.secure = row.optBoolean("secure", false);
                session.hostKey = row.optString("hostKey", "");
                String sealed = row.optString("password", "");
                out.add(new Saved(session, open(sealed)));
            }
        } catch (Exception ignored) {
            // A corrupt entry must not make every saved session disappear.
        }
        return out;
    }

    /**
     * Replaces the stored list.
     *
     * @return false when the credentials could not be sealed, in which case nothing is written.
     */
    public boolean save(List<Saved> sessions) {
        JSONArray array = new JSONArray();
        try {
            for (Saved saved : sessions) {
                String sealed = seal(saved.password);
                if (saved.password != null && !saved.password.isEmpty() && sealed.isEmpty()) return false;
                array.put(new JSONObject()
                        .put("id", saved.session.id)
                        .put("name", saved.session.name == null ? "" : saved.session.name)
                        .put("protocol", saved.session.protocol.name())
                        .put("host", saved.session.host == null ? "" : saved.session.host)
                        .put("port", saved.session.port)
                        .put("username", saved.session.username == null ? "" : saved.session.username)
                        .put("startPath", saved.session.startPath == null ? "" : saved.session.startPath)
                        .put("share", saved.session.share == null ? "" : saved.session.share)
                        .put("basePath", saved.session.basePath == null ? "" : saved.session.basePath)
                        .put("secure", saved.session.secure)
                        .put("hostKey", saved.session.hostKey == null ? "" : saved.session.hostKey)
                        .put("password", sealed));
            }
        } catch (Exception e) {
            return false;
        }
        return prefs().edit().putString(KEY_SESSIONS, array.toString()).commit();
    }

    public void remove(String id) {
        List<Saved> kept = new ArrayList<>();
        for (Saved saved : load()) {
            if (!saved.session.id.equals(id)) kept.add(saved);
        }
        save(kept);
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        java.security.Key existing = store.getKey(ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // No user authentication: a file manager has to reconnect without a prompt every
                // time the process restarts, and the key is already scoped to this app.
                .setUserAuthenticationRequired(false)
                .build());
        return generator.generateKey();
    }

    /** Returns "" for an empty password and for a failure; the caller treats both as "no secret". */
    private String seal(String password) {
        if (password == null || password.isEmpty()) return "";
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] encrypted = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
                    + Base64.encodeToString(encrypted, Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    private String open(String sealed) {
        if (sealed == null || sealed.isEmpty()) return "";
        int split = sealed.indexOf(':');
        if (split <= 0) return "";
        try {
            byte[] iv = Base64.decode(sealed.substring(0, split), Base64.NO_WRAP);
            byte[] data = Base64.decode(sealed.substring(split + 1), Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(data), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
