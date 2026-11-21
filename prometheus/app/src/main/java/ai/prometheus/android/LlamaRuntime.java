package ai.prometheus.android;

import android.content.Context;
import android.os.SystemClock;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * In-process upstream llama-server, bound exclusively to authenticated loopback.
 * No executable, model tokenizer, sampling loop, or chat-template implementation of our own.
 */
final class LlamaRuntime {
    static final String REVISION = "afe77e18fadc2e673c6a6299495a443e17378ce4";
    static final int CONTEXT = 3072;
    private static native int serve(String[] args);
    private static native void terminate();

    interface Stream {
        void stage(String text);
        void content(String text);
    }
    private final Context context;
    private final ExecutorService serverThread = Executors.newSingleThreadExecutor();
    private Future<Integer> server;
    private int port;
    private String key;
    private File model;
    private volatile HttpURLConnection activeRequest;
    private volatile boolean healthy;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final File log;

    LlamaRuntime(Context context) {
        this.context = context.getApplicationContext();
        log = new File(context.getFilesDir(), "llama-runtime.log");
    }
    synchronized void load(File file) throws Exception {
        if (healthy && file.equals(model) && server != null && !server.isDone()) return;
        stopServer();
        // Load failure is surfaced through a saved diagnostic, never a static initializer crash.
        System.loadLibrary("prometheus");
        try (ServerSocket available = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
            port = available.getLocalPort();
        }
        byte[] random = new byte[32]; new SecureRandom().nextBytes(random);
        StringBuilder secret = new StringBuilder();
        for (byte b : random) secret.append(String.format(Locale.ROOT, "%02x", b & 255));
        key = secret.toString();
        int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
        // Bounded CPU-only defaults work on baseline ARM64; no unsupported global ISA flags.
        String[] args = {"prometheus", "--model", file.getAbsolutePath(),
                "--host", "127.0.0.1", "--port", Integer.toString(port), "--api-key", key,
                "--ctx-size", Integer.toString(CONTEXT), "--parallel", "1",
                "--batch-size", "128", "--ubatch-size", "128",
                "--threads", Integer.toString(threads), "--threads-batch", Integer.toString(threads),
                "--n-gpu-layers", "0", "--cache-ram", "0", "--no-warmup",
                "--jinja", "--reasoning", "off", "--no-webui",
                "--log-file", log.getAbsolutePath()};
        if (log.length() > 2_000_000) {
            File previous = new File(log.getPath() + ".previous");
            if (previous.exists()) previous.delete();
            log.renameTo(previous);
        }
        server = serverThread.submit(() -> serve(args));
        long deadline = SystemClock.elapsedRealtime() + 600_000;
        Exception last = null;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (server.isDone()) throw new IOException("llama.cpp exited during loading (code " + server.get() + "). " + diagnostic());
            try {
                // /props is authenticated. A different listener cannot be mistaken for our runtime.
                JSONObject props = json("/props", null, 1500);
                if (props.has("default_generation_settings")) {
                    model = file; healthy = true; return;
                }
            } catch (Exception notReady) { last = notReady; }
            Thread.sleep(150);
        }
        throw new IOException("Local model loading timed out. " + diagnostic(), last);
    }
    synchronized void stopServer() throws Exception {
        healthy = false;
        if (server != null && !server.isDone()) {
            terminate();
            try { server.get(30, TimeUnit.SECONDS); }
            catch (TimeoutException ex) {
                throw new IOException("The local runtime has not stopped; the existing model was left untouched", ex);
            }
        }
        server = null;
        model = null;
    }
    boolean isHealthy() { return healthy && server != null && !server.isDone(); }
    void resetCancellation() { cancelled.set(false); }
    boolean isCancelled() { return cancelled.get(); }
    void cancel() {
        cancelled.set(true);
        HttpURLConnection request = activeRequest;
        if (request != null) request.disconnect();
    }
    private HttpURLConnection connection(String path, int readTimeout) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path)
                .openConnection(Proxy.NO_PROXY);
        c.setConnectTimeout(2000);
        c.setReadTimeout(readTimeout);
        c.setUseCaches(false);
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Authorization", "Bearer " + key);
        return c;
    }
    private void post(HttpURLConnection c, JSONObject data) throws Exception {
        c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] bytes = data.toString().getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
    }
    private static String read(InputStream input, int limit) throws IOException {
        if (input == null) return "";
        try (InputStream stream = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int n;
            while ((n = stream.read(buffer)) != -1) {
                if (out.size() + n > limit) throw new IOException("Local response exceeds limit");
                out.write(buffer, 0, n);
            }
            return out.toString("UTF-8");
        }
    }
    JSONObject json(String path, JSONObject body, int timeout) throws Exception {
        HttpURLConnection c = connection(path, timeout);
        try {
            if (body != null) post(c, body);
            int code = c.getResponseCode();
            String text = read(code == 200 ? c.getInputStream() : c.getErrorStream(), 2_000_000);
            if (code != 200) throw new IOException("Local runtime HTTP " + code + ": " + text);
            return new JSONObject(text);
        } finally { c.disconnect(); }
    }
    private JSONObject payload(JSONArray messages, int output) throws Exception {
        return new JSONObject().put("messages", messages).put("stream", true)
                .put("max_tokens", output).put("temperature", 0.65).put("top_p", 0.92)
                .put("reasoning_effort", "none")
                .put("chat_template_kwargs", new JSONObject().put("enable_thinking", false));
    }
    private JSONArray fit(JSONArray history, int output) throws Exception {
        JSONArray messages = new JSONArray(history.toString());
        while (!cancelled.get()) {
            JSONObject body = payload(messages, output);
            String prompt = json("/apply-template", body, 30_000).getString("prompt");
            JSONObject tokens = json("/tokenize", new JSONObject().put("content", prompt)
                    .put("add_special", true).put("parse_special", true), 30_000);
            int count = tokens.getJSONArray("tokens").length();
            if (count + output + 32 <= CONTEXT) return messages;
            // Remove oldest complete turns. The database retains the full text.
            if (messages.length() <= 2) throw new IOException(
                    "This message is too long for the phone's " + CONTEXT + "-token context. Shorten it and try again.");
            messages.remove(1);
            while (messages.length() > 2 && !"user".equals(messages.getJSONObject(1).getString("role")))
                messages.remove(1);
        }
        throw new InterruptedIOException("Stopped");
    }
    void generate(JSONArray history, int output, Stream stream) throws Exception {
        if (!isHealthy()) throw new IOException("The offline model is not ready. " + diagnostic());
        stream.stage("Reading conversation on device…");
        JSONArray messages = fit(history, output);
        if (cancelled.get()) return;
        HttpURLConnection c = connection("/v1/chat/completions", 600_000);
        activeRequest = c;
        try {
            if (cancelled.get()) return;
            post(c, payload(messages, output));
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("Local runtime HTTP " + code + ": " + read(c.getErrorStream(), 32_000));
            stream.stage("Generating answer on device…");
            SseReader.read(c.getInputStream(), data -> {
                if (cancelled.get()) return false;
                if ("[DONE]".equals(data)) return false;
                JSONObject event = new JSONObject(data);
                if (event.has("error")) throw new IOException(event.get("error").toString());
                JSONArray choices = event.optJSONArray("choices");
                if (choices != null && choices.length() > 0) {
                    JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
                    // Upstream parses thought/tool tokens. Only visible answer text is displayed.
                    if (delta != null && !delta.isNull("content")) {
                        String text = delta.optString("content", "");
                        if (!text.isEmpty()) stream.content(text);
                    }
                }
                return true;
            });
        } finally { activeRequest = null; c.disconnect(); }
    }
    String diagnostic() {
        try (RandomAccessFile in = new RandomAccessFile(log, "r")) {
            long start = Math.max(0, in.length() - 6000); in.seek(start);
            byte[] bytes = new byte[(int) (in.length() - start)]; in.readFully(bytes);
            return new String(bytes, StandardCharsets.UTF_8).replaceAll("(?i)Bearer [a-f0-9]{64}", "Bearer [private]");
        } catch (IOException ex) { return "No native log available yet."; }
    }
}
