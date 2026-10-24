package cn.xianhua.pocketkit.media;

import android.app.Activity;
import android.net.Uri;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import cn.xianhua.pocketkit.BaseToolActivity;

/**
 * Bridge between the media tools (in this subpackage) and their host Activity.
 *
 * <p>Exists because the entry contract puts Activities in the {@code cn.xianhua.pocketkit} package
 * root while the implementations live in {@code .media}, and the protected members of
 * {@link BaseToolActivity} are not reachable across packages.
 */
public interface Host {

    Activity activity();

    LinearLayout body();

    int dp(float n);

    /** Adds a note line to the body and returns it, so a caller can keep updating it. */
    TextView note(String text);

    EditText field(String label, String hint);

    Spinner select(String label, String... choices);

    /** A labelled checkbox; returns it so the caller can read {@code isChecked()}. */
    android.widget.CheckBox checkbox(String label, boolean checkedByDefault);

    Button button(String label, Runnable action);

    void show(String text);

    void fail(Throwable ex);

    /** Runs {@code job} on the shared worker; the base class reports progress and failures. */
    void background(BaseToolActivity.Work job);

    /** Runs {@code action} on the UI thread; the only safe way to touch views from a worker. */
    void post(Runnable action);

    /**
     * Opens the system picker for one document and hands back the URI, or null if dismissed.
     * Uses ACTION_OPEN_DOCUMENT: the user grants one file, not the whole disk.
     */
    void pickDocument(String mimeType, UriCallback onResult);

    /** Multi-select picker; used by merge and image-to-PDF, where the selection order matters. */
    void pickDocuments(String mimeType, MultiUriCallback onResult);

    /** Creates a document the user names and places; the app never picks a path itself. */
    void createDocument(String suggestedName, String mimeType, UriCallback onResult);

    /**
     * Registers a runnable that completes the currently running job's cancel flag.
     * The host re-arms it for every job so a second press cannot cancel a stale job.
     */
    void onCancel(Runnable canceller);

    /** Shows or hides the "取消" affordance while a job is running. */
    void setRunning(boolean running);

    interface UriCallback {
        void onResult(Uri uri);
    }

    /** Receives the picked documents in the order the user selected them. */
    interface MultiUriCallback {
        void onResult(java.util.List<Uri> uris);
    }
}
