package ai.prometheus.android;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.database.Cursor;
import android.os.Build;
import android.os.Debug;
import android.os.Process;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only phone evidence; shared by the existing Copy and Termux diagnostics. */
final class RuntimeDiagnostics {
    static JSONObject capture(Context context, ChatRuntime runtime) throws Exception {
        JSONObject report = new JSONObject();
        report.put("application_id",context.getPackageName());
        report.put("queued_message",runtime.queued);
        report.put("app_version", context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName);
        report.put("pid", Process.myPid());
        report.put("phone", Build.MANUFACTURER + " " + Build.MODEL);
        report.put("android_api", Build.VERSION.SDK_INT);
        report.put("model_ready", runtime.ready && runtime.engine.isHealthy());
        report.put("loading", runtime.loading);
        report.put("generating", runtime.generating);
        report.put("generation_started", runtime.started);
        report.put("last_inference", runtime.displayStatus());
        report.put("partial_characters", runtime.partial.length());
        report.put("prompt_tokens",runtime.engine.promptTokens);
        report.put("processed_tokens",runtime.engine.processedTokens);
        report.put("foreground_service_running", PrometheusWorkService.isRunning());
        report.put("foreground_service_holds", PrometheusWorkService.activeHolds());
        report.put("process_cpu_ms", Process.getElapsedCpuTime());
        Debug.MemoryInfo processMemory = new Debug.MemoryInfo();
        Debug.getMemoryInfo(processMemory);
        report.put("process_pss_kib", processMemory.getTotalPss());
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        manager.getMemoryInfo(memory);
        report.put("available_ram_bytes", memory.availMem);
        report.put("system_low_memory", memory.lowMemory);
        report.put("free_app_storage_bytes", context.getFilesDir().getUsableSpace());
        report.put("model_bytes", new File(context.getFilesDir(),"models/prometheus.gguf").length());
        report.put("runtime_revision", LlamaRuntime.REVISION);
        report.put("last_sync", runtime.store.setting("last_sync_summary","No transfer recorded"));
        report.put("last_sync_errors", runtime.store.setting("last_sync_errors",""));
        try (Cursor c = ChatStore.open(context).rawQuery("SELECT stage,started,LENGTH(partial) FROM generation_state WHERE id=1",null)) {
            if(c.moveToFirst()) report.put("saved_incomplete_reply", new JSONObject()
                    .put("stage",c.getString(0)).put("started",c.getLong(1)).put("characters",c.getInt(2)));
        }
        try (Cursor c = ChatStore.open(context).rawQuery("SELECT COUNT(*) FROM book_files",null)) {
            if(c.moveToFirst())report.put("archived_books",c.getInt(0));
        }
        if(Build.VERSION.SDK_INT>=30) {
            JSONArray exits = new JSONArray();
            for(ApplicationExitInfo exit:manager.getHistoricalProcessExitReasons(context.getPackageName(),0,3)) {
                String description=exit.getDescription();
                if(description!=null && description.length()>250)description=description.substring(0,250);
                exits.put(new JSONObject().put("reason",exit.getReason()).put("status",exit.getStatus())
                        .put("timestamp",exit.getTimestamp()).put("pss_kib",exit.getPss()).put("description",description));
            }
            report.put("recent_process_exits",exits);
        }
        try(BufferedReader reader=new BufferedReader(new FileReader("/proc/cpuinfo"))) {
            String line;
            while((line=reader.readLine())!=null)if(line.startsWith("Features")||line.startsWith("flags")) {
                report.put("cpu_features",line.substring(0,Math.min(line.length(),600)));break;
            }
        } catch(Exception unavailable) { report.put("cpu_features","unavailable"); }
        report.put("native_log_tail",runtime.engine.diagnostic());
        return report;
    }
}
