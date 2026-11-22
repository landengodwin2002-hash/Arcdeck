package ai.prometheus.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.MotionEvent;
import android.widget.EditText;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class OfflineConversationTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();
    interface Condition { boolean done() throws Exception; }
    private void await(String why, Condition condition, long seconds) throws Exception {
        long cpuAtStart=android.os.Process.getElapsedCpuTime();
        long deadline = SystemClock.elapsedRealtime() + seconds * 1000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition.done()) return;
            if (ChatRuntime.get(context).stage.startsWith("ERROR:"))
                fail(why+"; "+ChatRuntime.get(context).stage+"\n"+ChatRuntime.get(context).engine.diagnostic());
            SystemClock.sleep(100);
        }
        android.os.Debug.MemoryInfo memory=new android.os.Debug.MemoryInfo();
        android.os.Debug.getMemoryInfo(memory);
        android.util.Log.e("PrometheusAcceptance",why+" cpu_ms="+(android.os.Process.getElapsedCpuTime()-cpuAtStart)
                +" pss_kib="+memory.getTotalPss()+" native_heap_bytes="+android.os.Debug.getNativeHeapAllocatedSize());
        fail(why + "; " + ChatRuntime.get(context).stage + "\n" + ChatRuntime.get(context).engine.diagnostic());
    }
    private Activity foreground() {
        AtomicReference<Activity> activity = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            for (Activity a : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
                if (a instanceof MainActivity) activity.set(a);
        });
        return activity.get();
    }
    private EditText composer(View view) {
        if (view instanceof EditText && ((EditText)view).getHint()!=null && "Message Prometheus…".contentEquals(((EditText)view).getHint()))
            return (EditText)view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i=0;i<group.getChildCount();i++) {
                EditText found=composer(group.getChildAt(i));if(found!=null)return found;
            }
        }
        return null;
    }
    private boolean visibleText(View view,String prefix) {
        if(view instanceof android.widget.TextView && view.isShown()
                && ((android.widget.TextView)view).getText().toString().startsWith(prefix))return true;
        if(view instanceof ViewGroup){
            ViewGroup group=(ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++)if(visibleText(group.getChildAt(i),prefix))return true;
        }
        return false;
    }
    private View action(View view,String description) {
        if (view.getContentDescription()!=null && description.contentEquals(view.getContentDescription())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++){
                View found=action(group.getChildAt(i),description);if(found!=null)return found;
            }
        }
        return null;
    }
    private void send(String text) {
        Activity activity=foreground(); assertNotNull(activity);
        int[] point=new int[2];
        AtomicReference<EditText> originalInput=new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            EditText input=composer(activity.getWindow().getDecorView());assertNotNull(input);originalInput.set(input);
            input.getLocationOnScreen(point);point[0]+=input.getWidth()/2;point[1]+=input.getHeight()/2;
            input.setText("");
        });
        long now=SystemClock.uptimeMillis();
        instrumentation.sendPointerSync(MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,point[0],point[1],0));
        instrumentation.sendPointerSync(MotionEvent.obtain(now,now+50,MotionEvent.ACTION_UP,point[0],point[1],0));
        instrumentation.waitForIdleSync();
        instrumentation.sendStringSync(text);
        instrumentation.runOnMainSync(() -> {
            View root=activity.getWindow().getDecorView();
            EditText input=composer(root);assertNotNull(input);assertTrue(input.hasFocus());
            assertSame("A runtime update replaced the active composer",originalInput.get(),input);
            assertEquals("Keyboard input did not reach the composer",text,input.getText().toString());
            View send=action(root,"Send message to offline model");assertNotNull(send);assertTrue(send.isShown());
            assertTrue(send.performClick());
        });
    }
    private void page(String name) {
        Activity current=foreground();assertNotNull(current);
        instrumentation.runOnMainSync(()->{
            View button=action(current.getWindow().getDecorView(),"Open "+name+" page");
            assertNotNull("Missing navigation button for "+name,button);assertTrue(button.performClick());
        });
        instrumentation.waitForIdleSync();
    }
    private long replies(SQLiteDatabase db) {
        try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM messages WHERE role='assistant'",null)){
            c.moveToFirst();return c.getLong(0);
        }
    }
    private String lastAssistant(SQLiteDatabase db) {
        try(Cursor c=db.rawQuery("SELECT body FROM messages WHERE role='assistant' ORDER BY id DESC LIMIT 1",null)){
            return c.moveToFirst()?c.getString(0):"";
        }
    }

    @Test public void realModelStreamsAndSurvivesScreenRecreationAndHome() throws Exception {
        SQLiteDatabase db=ChatStore.open(context);
        ChatRuntime runtime=ChatRuntime.get(context);
        assertEquals("ai.prometheus.offline.chat",context.getPackageName());
        // These are the same tables/paths as the installed Nova application.
        db.execSQL("INSERT INTO memory(kind,body) VALUES('test','migration sentinel')");
        db.execSQL("INSERT OR IGNORE INTO book_files(title,sha256,path,indexed_at) VALUES('sentinel book','test-upgrade-sentinel','preserve/original.txt',1)");
        runtime.store.archiveChat();
        Intent open=new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        java.io.File model=new java.io.File(context.getFilesDir(),"models/prometheus.gguf");
        java.io.File deferred=new java.io.File(context.getFilesDir(),"models/prometheus.gguf.acceptance-loading");
        assertTrue(model.isFile());
        // Force a queued keyboard send before loading, without depending on model-load timing.
        assertTrue(model.renameTo(deferred));
        long sentAt=SystemClock.elapsedRealtime();
        try {
            instrumentation.startActivitySync(open);
            send("Remember this exact code: cobalt-7319. Count from 1 to 30, separated by spaces.");
            assertTrue("The loading message was not owned by the application worker",runtime.queued);
        } finally {
            assertTrue("The test model was not restored",deferred.renameTo(model));
        }
        instrumentation.runOnMainSync(()->runtime.load(model));
        instrumentation.getUiAutomation().executeShellCommand("input keyevent KEYCODE_HOME").close();
        await("Queued message did not start without the Chat screen",()->runtime.engine.promptProgressReceived,600);
        assertTrue(runtime.generating);
        assertTrue("Foreground hold was lost when loading handed off to generation",PrometheusWorkService.activeHolds()>0);
        instrumentation.runOnMainSync(()->context.startActivity(open));
        await("Could not reopen after queued handoff",()->foreground()!=null,30);
        android.util.Log.i("PrometheusAcceptance","COLD_QUEUE_HOME_HANDOFF_PASS");
        long generationId=runtime.started;
        assertTrue(runtime.generating);
        page("Library");
        Activity library=foreground();
        instrumentation.runOnMainSync(()->{
            assertTrue("Imported book title is absent from Library",visibleText(library.getWindow().getDecorView(),"Legacy original"));
            assertTrue("Indexed Termux book is absent from Library",visibleText(library.getWindow().getDecorView(),"termux-original.txt"));
        });
        page("Settings");page("Projects");page("Chat");
        assertEquals("Page switching restarted the reply",generationId,runtime.started);
        assertTrue("Page switching stopped generation",runtime.generating);
        instrumentation.getUiAutomation().executeShellCommand("input keyevent KEYCODE_HOME").close();
        SystemClock.sleep(1000);
        instrumentation.runOnMainSync(()->context.startActivity(open));
        await("Could not return during prompt processing",()->foreground()!=null,30);
        assertEquals(generationId,runtime.started);
        assertTrue(runtime.generating);
        android.util.Log.i("PrometheusAcceptance","PREFILL_TABS_HOME_NO_RESTART_PASS");
        await("No streamed answer",()->runtime.generating&&!runtime.partial.trim().isEmpty(),600);
        String firstChunk=runtime.partial;
        android.util.Log.i("PrometheusAcceptance","FIRST_CONTENT_MS="+(SystemClock.elapsedRealtime()-sentAt));
        assertFalse(firstChunk.contains("\uFFFD"));
        // Incoming text must not steal focus from someone composing the next message.
        Activity writing=foreground();
        int[] at=new int[2];
        instrumentation.runOnMainSync(()->{
            EditText input=composer(writing.getWindow().getDecorView());input.getLocationOnScreen(at);
            at[0]+=input.getWidth()/2;at[1]+=input.getHeight()/2;
        });
        long touch=SystemClock.uptimeMillis();
        instrumentation.sendPointerSync(MotionEvent.obtain(touch,touch,MotionEvent.ACTION_DOWN,at[0],at[1],0));
        instrumentation.sendPointerSync(MotionEvent.obtain(touch,touch+50,MotionEvent.ACTION_UP,at[0],at[1],0));
        instrumentation.sendStringSync("draft survives");
        SystemClock.sleep(600);
        instrumentation.runOnMainSync(()->{
            EditText input=composer(writing.getWindow().getDecorView());
            assertTrue("Streaming stole keyboard focus",input.hasFocus());
            assertTrue("Streamed content did not appear in the chat",visibleText(writing.getWindow().getDecorView(),firstChunk));
            assertEquals("draft survives",input.getText().toString());
        });
        page("Library");page("Settings");page("Chat");
        assertEquals("Page switching lost the active generation",generationId,runtime.started);
        assertTrue(runtime.generating);
        Activity afterPages=foreground();
        instrumentation.runOnMainSync(()->assertEquals("draft survives",composer(afterPages.getWindow().getDecorView()).getText().toString()));
        instrumentation.runOnMainSync(afterPages::recreate);
        instrumentation.getUiAutomation().executeShellCommand("input keyevent KEYCODE_HOME").close();
        await("Background reply did not finish",()->!runtime.generating,900);
        String answer=lastAssistant(db);
        android.util.Log.i("PrometheusAcceptance","BACKGROUND_REPLY_CHARS="+answer.length());
        assertTrue("Expected readable text: "+answer,answer.trim().length()>3);
        assertFalse(answer.contains("<think>"));
        assertFalse(answer.contains("<|im_"));
        assertFalse(answer.contains("\uFFFD"));
        try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM generation_state",null)){c.moveToFirst();assertEquals(0,c.getInt(0));}
        instrumentation.runOnMainSync(()->context.startActivity(open));
        await("The screen did not reopen",()->foreground()!=null,30);
        assertEquals(answer,lastAssistant(db));
        Activity restored=foreground();
        instrumentation.runOnMainSync(()->assertEquals("draft survives",composer(restored.getWindow().getDecorView()).getText().toString()));
        try(Cursor c=db.rawQuery("SELECT body FROM memory WHERE kind='test'",null)){assertTrue(c.moveToFirst());assertEquals("migration sentinel",c.getString(0));}
        try(Cursor c=db.rawQuery("SELECT path FROM book_files WHERE sha256='test-upgrade-sentinel'",null)){assertTrue(c.moveToFirst());assertEquals("preserve/original.txt",c.getString(0));}

        // A second turn proves this is an ongoing conversation, not just a one-shot probe.
        long beforeFollowup=replies(db);
        send("What exact code did I give you? Reply with only that code.");
        await("Follow-up was not saved",()->runtime.generating,10);
        await("Follow-up did not complete",()->!runtime.generating,600);
        assertEquals(beforeFollowup+1,replies(db));
        assertTrue("Conversation context was lost: "+lastAssistant(db),lastAssistant(db).toLowerCase().contains("cobalt-7319"));

        android.util.Log.i("PrometheusAcceptance","CONTEXT_FOLLOWUP_PASS");

        // Cancellation must leave the application usable for another real turn.
        send("Write a very long numbered list of facts about the ocean.");
        await("Stop test never began streaming",()->runtime.generating&&!runtime.partial.isEmpty(),600);
        Activity current=foreground();
        instrumentation.runOnMainSync(()->action(current.getWindow().getDecorView(),"Stop generation").performClick());
        await("Stop did not complete",()->!runtime.generating,60);
        assertTrue(runtime.ready);
        android.util.Log.i("PrometheusAcceptance","STOP_PASS");
        long beforeRestartedReply=replies(db);
        send("Say hello.");
        await("A message after Stop was rejected",()->runtime.generating,10);
        await("A message after Stop failed",()->!runtime.generating,600);
        assertEquals("No fresh reply was saved after Stop",beforeRestartedReply+1,replies(db));
        assertFalse(lastAssistant(db).trim().isEmpty());
        android.util.Log.i("PrometheusAcceptance","REAL_MODEL_STREAM_BACKGROUND_RECREATE_FOLLOWUP_STOP_PASS");
    }
}
