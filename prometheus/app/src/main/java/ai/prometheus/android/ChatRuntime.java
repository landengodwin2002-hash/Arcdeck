package ai.prometheus.android;

import android.content.Context;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.*;

/** Application-owned work. Holds no Activity, View, or screen callback. */
final class ChatRuntime {
    private static ChatRuntime instance;
    static synchronized ChatRuntime get(Context context) {
        if (instance == null) instance = new ChatRuntime(context.getApplicationContext());
        return instance;
    }
    final ChatStore store;
    final LlamaRuntime engine;
    private final Context context;
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    volatile boolean loading, generating, ready;
    volatile String stage = "Idle", partial = "", path = "";
    volatile long started;
    private Future<?> load;
    private boolean replacing;
    private volatile boolean probing;

    private ChatRuntime(Context context) {
        this.context = context;
        store = new ChatStore(context);
        engine = new LlamaRuntime(context);
    }
    private void stage(String detail) {
        stage = detail;
        store.putSetting("last_inference", detail);
        if (generating && !probing) store.partial(partial, detail);
        Log.i("PrometheusInference", detail);
    }
    synchronized Future<?> load(File file) {
        if (ready && path.equals(file.getAbsolutePath()) && engine.isHealthy())
            return CompletableFuture.completedFuture(null);
        if (loading && path.equals(file.getAbsolutePath())) return load;
        if (generating || loading || replacing) throw new IllegalStateException("Wait for the active conversation or model installation to finish");
        loading = true; ready = false; path = file.getAbsolutePath();
        stage("Loading on-device model…");
        PrometheusWorkService.begin(context, "Loading local model");
        load = work.submit(() -> {
            try {
                engine.load(file); ready = true; stage("Model ready");
            } catch (Throwable error) {
                ready = false; stage("ERROR: Model loading failed: " + error);
                Log.e("PrometheusRuntime", stage, error);
            } finally { loading = false; PrometheusWorkService.end(context); }
        });
        return load;
    }
    synchronized boolean send(String question, String profile, int output) {
        if (generating || loading || replacing || !ready) return false;
        // Keep an interrupted reply until the user explicitly retries it.
        try (android.database.Cursor c = ChatStore.open(context).rawQuery("SELECT partial FROM generation_state WHERE id=1", null)) {
            if (c.moveToFirst() && c.getString(0) != null && !c.getString(0).trim().isEmpty())
                store.finish(c.getString(0), "Previous response was interrupted.", false);
        }
        store.begin(question);
        partial = ""; started = System.currentTimeMillis(); generating = true;
        engine.resetCancellation();
        stage("Preparing response…");
        PrometheusWorkService.begin(context, "Responding offline");
        work.execute(() -> {
            PowerManager.WakeLock wake = ((PowerManager) context.getSystemService(Context.POWER_SERVICE))
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Prometheus:conversation");
            StringBuilder answer = new StringBuilder();
            String failure = null;
            long[] savedAt = {0};
            try {
                wake.acquire(30 * 60 * 1000L);
                engine.generate(store.history(profile), Math.max(48, Math.min(output, 640)), new LlamaRuntime.Stream() {
                    public void stage(String text) { ChatRuntime.this.stage(text); }
                    public void content(String text) {
                        answer.append(text);
                        partial = answer.toString();
                        long now = SystemClock.elapsedRealtime();
                        if (now - savedAt[0] >= 500) {
                            savedAt[0] = now;
                            store.partial(partial, "Streaming response");
                        }
                    }
                });
                if (answer.toString().trim().isEmpty() && !engine.isCancelled())
                    throw new IOException("The model finished without visible answer text. " + engine.diagnostic());
            } catch (Throwable error) {
                if (!engine.isCancelled()) {
                    failure = "ERROR: " + error.getMessage();
                    Log.e("PrometheusInference", failure, error);
                }
            } finally {
                try {
                    store.finish(answer.toString(), failure, engine.isCancelled());
                    stage(failure != null ? failure : engine.isCancelled() ? "Stopped" : "Response saved");
                } catch (Throwable saveError) {
                    // Retain generation_state if a durable commit fails.
                    Log.e("PrometheusStore", "Could not save completed response", saveError);
                    stage = "ERROR: Could not save response. Its recovery record is retained.";
                }
                generating = false; started = 0;
                ready = engine.isHealthy();
                if (wake.isHeld()) wake.release();
                PrometheusWorkService.end(context);
            }
        });
        return true;
    }
    void stop() { engine.cancel(); }
    synchronized void beginModelReplacement() throws Exception {
        if (generating || loading || replacing) throw new IOException("Wait for the active model operation to finish before replacing it");
        replacing = true;
        try { engine.stopServer(); ready = false; path = ""; }
        catch (Exception error) { replacing = false; throw error; }
    }
    synchronized void endModelReplacement() { replacing = false; }
    String probe() throws Exception {
        if (generating || loading || replacing || !ready) throw new IOException("The model is busy or not ready");
        Future<String> task;
        synchronized (this) {
            if (generating || loading || replacing || !ready) throw new IOException("The model is busy or not ready");
            generating = true; probing = true;
            engine.resetCancellation();
            task = work.submit(() -> {
                StringBuilder text = new StringBuilder();
                try {
                    org.json.JSONArray messages = new org.json.JSONArray()
                            .put(new org.json.JSONObject().put("role", "user").put("content", "Reply with one short greeting."));
                    engine.generate(messages, 48, new LlamaRuntime.Stream() {
                        public void stage(String detail) { ChatRuntime.this.stage(detail); }
                        public void content(String chunk) { text.append(chunk); }
                    });
                    if (text.toString().trim().isEmpty()) throw new IOException("No visible probe answer");
                    return text.toString();
                } finally { generating = false; probing = false; }
            });
        }
        return task.get(10, TimeUnit.MINUTES);
    }
}
