package ai.prometheus.android;

import android.app.*;
import android.os.*;
import android.content.*;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.provider.DocumentsContract;
import android.text.Html;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private ChatRuntime runtime;
    private String draft="";

    private static final int BG=0xff101827, SURFACE=0xff202940, MINT=0xff96e5ff, PINK=0xffffa6de,
            FORE=0xfff6f7ff, MUTED=0xffacbad3, BORDER=0xff48526c, USER=0xff293954;
    private static final String MODEL_URL="https://huggingface.co/mradermacher/Huihui-Qwen3.5-4B-abliterated-GGUF/resolve/main/Huihui-Qwen3.5-4B-abliterated.Q4_K_M.gguf?download=true";
    private static final int PICK_MODEL=51,PICK_BOOKS=52,PICK_PROJECT_FILE=53,PICK_MIGRATION=54,PICK_BOOK_FOLDER=55;
    private final ExecutorService jobs=Executors.newSingleThreadExecutor();
    private final ExecutorService syncJobs=Executors.newSingleThreadExecutor();
    private boolean sharedModelReady=false, sharedGenerating=false, sharedModelLoading=false;
    private String sharedModelPath="";
    private long lastRenderedId=0;
    private final Runnable chatWatcher=new Runnable(){
        @Override public void run(){
            if(runtime==null || isDestroyed())return;
            boolean changed=sharedGenerating!=runtime.generating || sharedModelLoading!=runtime.loading || ready!=runtime.ready;
            sharedGenerating=runtime.generating;sharedModelLoading=runtime.loading;
            sharedModelReady=runtime.ready&&runtime.engine.isHealthy();ready=sharedModelReady;sharedModelPath=runtime.path;
            busy=runtime.generating||runtime.loading;
            latestStage=runtime.stage;lastInferenceStatus=runtime.stage;inferenceStartedAt=runtime.started;
            if(db!=null){
                long newest=lastMessageId();
                if(changed || newest!=lastRenderedId){
                    lastRenderedId=newest;loadSavedMessages();render();
                }
                if(status!=null)status.setText("● "+runtime.stage);
                if("Chat".equals(activeTab) && runtime.generating && streamingBubble!=null){
                    String value=runtime.partial;
                    if(!value.contentEquals(streamingBubble.getText())){streamingBubble.setText(value);scrollBottom();}
                }
                if(ready && !busy && queuedPrompt!=null)deliverQueuedPrompt();
            }
            main.postDelayed(this,200);
        }
    };
    private long lastMessageId(){
        try(Cursor c=db.rawQuery("SELECT COALESCE(MAX(id),0) FROM messages",null)){
            return c.moveToFirst()?c.getLong(0):0;
        }
    }
    private void loadSavedMessages(){
        messages.clear();
        try(Cursor c=db.rawQuery("SELECT role,body FROM (SELECT id,role,body FROM messages WHERE id>? ORDER BY id DESC LIMIT 100) ORDER BY id ASC",new String[]{getSetting("conversation_start","0")})){
            while(c.moveToNext())messages.add(new String[]{c.getString(0),c.getString(1)});
        }
    }
    private void foregroundBegin(String reason){PrometheusWorkService.begin(getApplicationContext(),reason);}
    private void foregroundEnd(){PrometheusWorkService.end(getApplicationContext());}
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ArrayList<String[]> messages=new ArrayList<>();
    private SQLiteDatabase db;
    private LinearLayout page,thread,toolbar;
    private ScrollView scroller;
    private EditText composer;
    private TextView status,bookCount;
    private PrometheusPetView petView;
    private int petHunger=28,petMood=72,petTrust=60;
    private String lastInferenceStatus="Not tested";
    private int chatFontSize=20;
    private EditText profileEdit;
    private int outputTokens=192;
    private int currentProject=-1;
    private boolean ready=false,busy=false;
    private long inferenceStartedAt=0L;
    private String latestStage="Idle";
    private String queuedPrompt=null;
    private volatile boolean cancelRequested=false;
    private TextView streamingBubble;
    private String modelName="No model selected",activeTab="Chat";
    private String profile="Reply naturally and directly. Understand the intended meaning of abstract statements; ask for clarification only when required. Avoid dumping raw book passages. Cite sources if consulted. Accept corrections and adapt future responses.";
    private File modelFile;
    private final java.util.concurrent.atomic.AtomicBoolean termuxSyncRunning = new java.util.concurrent.atomic.AtomicBoolean(false);
    private int selectedBooks=0;

    private int dp(float d){return (int)(d*getResources().getDisplayMetrics().density+.5f);}
    private GradientDrawable bg(int col,int radius,int stroke){
        GradientDrawable g=new GradientDrawable();g.setColor(col);g.setCornerRadius(dp(radius));
        if(stroke!=0)g.setStroke(dp(1),stroke);return g;
    }
    private TextView text(String v,int size,int color,boolean bold){
        TextView t=new TextView(this);t.setText(v);t.setTextSize(size);t.setTextColor(color);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;
    }
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private void pad(View v,int a,int b,int c,int d){v.setPadding(dp(a),dp(b),dp(c),dp(d));}
    private LinearLayout.LayoutParams lp(int w,int h){return new LinearLayout.LayoutParams(w<0?w:dp(w),h<0?h:dp(h));}
    private Button button(String label,Runnable action){
        Button b=new Button(this);b.setText(label);b.setTextSize(13);b.setAllCaps(false);
        b.setTextColor(FORE);b.setBackground(bg(SURFACE,14,BORDER));b.setOnClickListener(v->action.run());return b;
    }
    private void runUI(Runnable r){main.post(()->{if(!isFinishing()&&!isDestroyed())r.run();});}
    private void notify(String msg){runUI(()->Toast.makeText(this,msg,Toast.LENGTH_LONG).show());}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        if(Build.VERSION.SDK_INT>=29)getWindow().setNavigationBarContrastEnforced(false);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        PDFBoxResourceLoader.init(getApplicationContext());
        db=ChatStore.open(getApplicationContext());
        runtime=ChatRuntime.get(getApplicationContext());
        ready=runtime.ready;sharedModelReady=ready;sharedGenerating=runtime.generating;
        sharedModelLoading=runtime.loading;sharedModelPath=runtime.path;
        busy=runtime.generating||runtime.loading;
        draft=getSetting("chat_draft","");
        try(Cursor settings=db.rawQuery("SELECT value FROM settings WHERE key='profile'",null)){
            if(settings.moveToFirst())profile=settings.getString(0);
        }
        try(Cursor settings=db.rawQuery("SELECT value FROM settings WHERE key='font_size'",null)){
            if(settings.moveToFirst())chatFontSize=Math.max(16,Math.min(24,Integer.parseInt(settings.getString(0))));
        }
        try(Cursor settings=db.rawQuery("SELECT value FROM settings WHERE key='tokens'",null)){
            if(settings.moveToFirst())outputTokens=Integer.parseInt(settings.getString(0));
        }
        try(Cursor settings=db.rawQuery("SELECT value FROM settings WHERE key='current_project'",null)){
            if(settings.moveToFirst())currentProject=Integer.parseInt(settings.getString(0));
        }
        try(Cursor c=db.rawQuery("SELECT role,body FROM (SELECT id,role,body FROM messages WHERE id>? ORDER BY id DESC LIMIT 100) ORDER BY id ASC",new String[]{getSetting("conversation_start","0")})){
            while(c.moveToNext()) messages.add(new String[]{c.getString(0),c.getString(1)});
        }
        File models=new File(getFilesDir(),"models");models.mkdirs();
        File current=new File(models,"prometheus.gguf");if(current.isFile())modelFile=current;
        if(modelFile!=null && sharedModelReady && sharedModelPath.equals(modelFile.getAbsolutePath())){
            ready=true;modelName=getPreferences(MODE_PRIVATE).getString("model_label",modelFile.getName());
        }
        queuedPrompt=getSetting("queued_prompt","");
        if(queuedPrompt.isEmpty())queuedPrompt=null;
        loadPetState();
        lastRenderedId=lastMessageId();
        render();
        if(ready && queuedPrompt!=null)main.postDelayed(this::deliverQueuedPrompt,400);

        if(modelFile!=null && !ready && !runtime.loading)loadModel(modelFile);
    }
    @Override protected void onResume(){
        super.onResume();
        main.removeCallbacks(chatWatcher);main.post(chatWatcher);
        // The terminal starts a short-lived loopback sender. Simply opening Prometheus
        // imports the chosen model, books and knowledge without file pickers or pairing.
        if(db!=null && termuxSyncRunning.compareAndSet(false,true)){
            syncJobs.execute(()->{try{syncFromTermuxIfAvailable();}finally{termuxSyncRunning.set(false);}});
        }
    }
    @Override protected void onPause(){
        if(composer!=null)saveSetting("chat_draft",composer.getText().toString());
        main.removeCallbacks(chatWatcher);
        super.onPause();
    }
    @Override public void onDestroy(){
        main.removeCallbacks(chatWatcher);
        // Generation and Termux import continue even if Android recreates this screen.
        // Application-owned work saves replies even after this Activity is destroyed.
        syncJobs.shutdown();jobs.shutdown();super.onDestroy();
    }
    private void installSafeArea(LinearLayout root){
        // API 35+ targets draw under the Android 15/16 bars by default.
        // Insets also keep the composer above the keyboard and gesture navigation.
        if(Build.VERSION.SDK_INT>=30){
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((v,insets)->{
                android.graphics.Insets sys=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
                android.graphics.Insets ime=insets.getInsets(WindowInsets.Type.ime());
                int bottom=Math.max(sys.bottom,ime.bottom);
                v.setPadding(sys.left,sys.top,sys.right,bottom);
                return insets;
            });
            root.requestApplyInsets();
        }else root.setFitsSystemWindows(true);
    }
    private void deliverQueuedPrompt(){
        if(!ready || busy || queuedPrompt==null)return;
        String next=queuedPrompt;queuedPrompt=null;saveSetting("queued_prompt","");
        if(!"Chat".equals(activeTab)){activeTab="Chat";render();}
        if(composer!=null){composer.setText(next);send();}
    }
    private void setBusy(boolean b,String message){
        busy=b;
        if(message!=null)latestStage=message;
        if(status!=null){status.setText(message);status.setTextColor(message.toLowerCase(Locale.ROOT).contains("fail")||message.toLowerCase(Locale.ROOT).contains("error")?PINK:ready?MINT:MUTED);}
        if(petView!=null)petView.setStats(petHunger,petMood,petTrust,b);
    }
    private void recordInference(String detail){
        lastInferenceStatus=detail;latestStage=detail;
        android.util.Log.i("PrometheusInference",detail);
        try{saveSetting("last_inference",detail);}catch(Exception ignored){}
    }
    private String getSetting(String key,String fallback){
        try(Cursor c=db.rawQuery("SELECT value FROM settings WHERE key=?",new String[]{key})){
            if(c.moveToFirst())return c.getString(0);
        }catch(Exception ignored){}
        return fallback;
    }
    private int clamp(int n){return Math.max(0,Math.min(100,n));}
    private void loadPetState(){
        try{petHunger=clamp(Integer.parseInt(getSetting("pet_hunger","28")));}catch(Exception ignored){}
        try{petMood=clamp(Integer.parseInt(getSetting("pet_mood","72")));}catch(Exception ignored){}
        try{petTrust=clamp(Integer.parseInt(getSetting("pet_trust","60")));}catch(Exception ignored){}
        long last=System.currentTimeMillis();
        try{last=Long.parseLong(getSetting("pet_last",String.valueOf(last)));}catch(Exception ignored){}
        long elapsed=Math.max(0,System.currentTimeMillis()-last);
        long days=Math.min(7,elapsed/(24L*60*60*1000));
        petHunger=clamp(petHunger+(int)days*4);
        petMood=clamp(petMood-(int)days*2);
        persistPet();
    }
    private void persistPet(){
        saveSetting("pet_hunger",String.valueOf(petHunger));
        saveSetting("pet_mood",String.valueOf(petMood));
        saveSetting("pet_trust",String.valueOf(petTrust));
        saveSetting("pet_last",String.valueOf(System.currentTimeMillis()));
        if(petView!=null)petView.setStats(petHunger,petMood,petTrust,busy);
    }
    private String lastAssistantAnswer(){
        for(int i=messages.size()-1;i>=0;i--)if("assistant".equals(messages.get(i)[0]))return messages.get(i)[1];
        return "";
    }
    private void petAction(String action){
        switch(action){
            case "feed":petHunger=clamp(petHunger-32);petMood=clamp(petMood+6);break;
            case "praise":{
                petMood=clamp(petMood+19);petTrust=clamp(petTrust+9);
                String prev=lastAssistantAnswer();
                if(!prev.isEmpty()){
                    String excerpt=prev.substring(0,Math.min(170,prev.length())).replace('\n',' ');
                    db.execSQL("INSERT INTO memory(kind,body) VALUES(?,?)",new Object[]{"reward","The user liked this response style: "+excerpt});
                }
                break;
            }
            case "discipline":petMood=clamp(petMood-24);petTrust=clamp(petTrust-12);petHunger=clamp(petHunger+4);break;
            case "rest":petMood=clamp(petMood+5);break;
        }
        persistPet();if(petView!=null)petView.react(action);
    }
    private void disciplinePet(){
        petAction("discipline");
        EditText lesson=new EditText(this);lesson.setTextColor(FORE);lesson.setTextSize(18);
        lesson.setHint("What should Prometheus do differently?");lesson.setHintTextColor(MUTED);
        lesson.setMinLines(2);pad(lesson,12,8,12,8);
        new AlertDialog.Builder(this).setTitle("Discipline the virtual pet")
          .setMessage("It will look sad for a while; the assistant does not experience suffering. To improve its answers, describe what was wrong.")
          .setView(lesson).setNegativeButton("Only pet reaction",(d,w)->{})
          .setPositiveButton("Save correction",(d,w)->{
              String correction=lesson.getText().toString().trim();
              if(!correction.isEmpty()){
                  db.execSQL("INSERT INTO memory(kind,body) VALUES(?,?)",new Object[]{"correction",correction});
                  notify("Correction saved for future chats");
              }
          }).show();
    }
    private void showPetDialog(){
        LinearLayout layout=column();pad(layout,20,12,20,10);
        PrometheusPetView big=new PrometheusPetView(this);
        big.setStats(petHunger,petMood,petTrust,busy);
        layout.addView(big,lp(-1,165));
        TextView stats=text("",15,FORE,false);stats.setGravity(Gravity.CENTER);layout.addView(stats,lp(-1,62));
        Runnable refresh=()->{stats.setText("Hunger "+petHunger+" / 100   ·   Mood "+petMood+" / 100\nTrust "+petTrust+" / 100");
            big.setStats(petHunger,petMood,petTrust,busy);};
        refresh.run();
        LinearLayout actions=row();layout.addView(actions,lp(-1,58));
        for(String action:new String[]{"Feed","Praise","Discipline"}){
            Button b=button(action,()->{
                if("Discipline".equals(action)){disciplinePet();}
                else petAction(action.toLowerCase(Locale.ROOT));
                big.react(action.toLowerCase(Locale.ROOT));refresh.run();
            });
            b.setTextSize(12);actions.addView(b,new LinearLayout.LayoutParams(0,dp(52),1));
        }
        new AlertDialog.Builder(this).setTitle("Prometheus Companion · virtual pet")
            .setView(layout).setNeutralButton("Rest",(d,w)->petAction("rest"))
            .setPositiveButton("Close",(d,w)->{}).show();
    }
    private void openNavigation(){
        final String[] entries={"Chat","Library","Projects","Settings","Diagnostics","New chat"};
        new AlertDialog.Builder(this).setTitle("Prometheus").setItems(entries,(dlg,which)->{
            if(which==4){showDiagnostics();return;}
            if(which==5){new AlertDialog.Builder(this).setTitle("Start new chat?").setMessage("The current conversation will be archived. Your books and other saved data will remain.")
                .setNegativeButton("Cancel",null).setPositiveButton("New chat",(d,w)->{if(runtime.generating){notify("Stop the active response first");return;}runtime.store.archiveChat();messages.clear();activeTab="Chat";render();}).show();return;}
            activeTab=entries[which];render();
        }).show();
    }
    private void showDiagnostics(){
        String mode=ready?"Ready":busy?"Busy / loading":"No model ready";
        String f=modelFile==null?"None":modelFile.getName()+" ("+(modelFile.length()/1024/1024)+" MB)";
        String report="Runtime: llama.cpp "+LlamaRuntime.REVISION+"\nModel: "+mode+"\nFile: "+f+"\nCurrent stage: "+latestStage+"\nElapsed: "+(inferenceStartedAt>0?(System.currentTimeMillis()-inferenceStartedAt)/1000+" s":"Not generating")+"\nLast inference stage: "+lastInferenceStatus+"\nSaved: "+getSetting("last_inference","None")+"\n\nIf stuck during prompt processing, it may be slow CPU inference. If you see ERROR, copy that exact message.";
        new AlertDialog.Builder(this).setTitle("Native engine diagnostics").setMessage(report)
            .setNeutralButton("Copy",(d,w)->{
                android.content.ClipboardManager cb=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                cb.setPrimaryClip(ClipData.newPlainText("Prometheus diagnostics",report));
                notify("Diagnostics copied");
            }).setPositiveButton("Close",null).show();
    }
    private void render(){
        boolean editing=composer!=null&&composer.hasFocus();
        int selection=composer==null?0:composer.getSelectionStart();
        if(composer!=null){draft=composer.getText().toString();saveSetting("chat_draft",draft);composer=null;}
        LinearLayout root=column();root.setBackgroundColor(BG);root.setFocusableInTouchMode(true);setContentView(root);installSafeArea(root);
        LinearLayout header=row();pad(header,10,5,10,5);root.addView(header,lp(-1,67));
        ImageView mark=new ImageView(this);mark.setImageResource(R.drawable.prometheus_mark);
        mark.setScaleType(ImageView.ScaleType.FIT_CENTER);mark.setContentDescription("Prometheus Nova symbol");
        mark.setOnClickListener(v->showPetDialog());header.addView(mark,lp(50,50));
        LinearLayout title=column();pad(title,10,0,0,0);
        header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        TextView titleText=text("PROMETHEUS",17,FORE,true);
        titleText.setLetterSpacing(.07f);title.addView(titleText);
        status=text(ready?"● Model loaded locally":busy?"● Working locally…":"○ Import offline GGUF",12,ready?0xff59eab4:MUTED,false);
        status.setSingleLine(true);status.setEllipsize(android.text.TextUtils.TruncateAt.END);title.addView(status);
        petView=new PrometheusPetView(this);petView.setStats(petHunger,petMood,petTrust,busy);
        petView.setContentDescription("Open interactive companion · Feed or praise");
        petView.setOnClickListener(v->showPetDialog());header.addView(petView,lp(44,44));
        Button menu=button("☰",this::openNavigation);menu.setContentDescription("Open menu and diagnostics");
        menu.setTextSize(20);header.addView(menu,lp(45,44));
        View hairline=new View(this);
        hairline.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{0xff30d7f2,0xff8e68ed,0xffff8cd7}));
        root.addView(hairline,lp(-1,1));
        page=column();pad(page,11,7,11,8);root.addView(page,new LinearLayout.LayoutParams(-1,0,1));
        if(activeTab.equals("Chat"))showChat();
        else if(activeTab.equals("Library"))showLibrary();
        else if(activeTab.equals("Projects"))showProjects();
        else showSettings();
        LinearLayout bottom=row();pad(bottom,3,4,3,3);root.addView(bottom,lp(-1,55));
        for(String[] nav:new String[][]{{"Chat","◉"},{"Library","▤"},{"Projects","▣"},{"Settings","⚙"}}){
            boolean selected=activeTab.equals(nav[0]);
            Button tab=button(nav[1]+"\n"+nav[0],()->{if(!activeTab.equals(nav[0])){activeTab=nav[0];render();}});
            tab.setTextSize(11);tab.setAllCaps(false);
            tab.setTextColor(selected?MINT:MUTED);
            if(selected){
                GradientDrawable active=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xff1b3659,0xff493060});
                active.setCornerRadius(dp(15));active.setStroke(dp(1),0xff93a6f6);tab.setBackground(active);
            }else{tab.setBackgroundColor(Color.TRANSPARENT);}
            LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(0,dp(48),1f);
            bp.setMargins(dp(2),0,dp(2),0);bottom.addView(tab,bp);
        }
        if(editing&&composer!=null){composer.requestFocus();composer.setSelection(Math.max(0,Math.min(selection,composer.length())));}
    }
    private void showChat(){
        try(Cursor c=db.rawQuery("SELECT prompt,partial,stage FROM generation_state WHERE id=1",null)){
            if(c.moveToFirst()){
                final String question=c.getString(0), partial=c.getString(1), phase=c.getString(2);
                TextView state=text(sharedGenerating?"● Generating in background: "+phase:
                    "● Interrupted reply saved. You can retry it.",13,MINT,false);
                pad(state,5,4,5,6);page.addView(state,lp(-1,-2));
                if(!sharedGenerating){
                    if(partial!=null && !partial.isEmpty()){
                        TextView saved=text("Recovered partial answer:\n"+partial,13,MUTED,false);
                        pad(saved,5,4,5,7);page.addView(saved,lp(-1,-2));
                    }
                    page.addView(button("Retry unfinished question",()->{
                        runtime.store.finish(partial==null?"":partial,"Previous response was interrupted.",false);
                        if(composer!=null){composer.setText(question);send();}
                    }),lp(-1,43));
                }
            }
        }catch(Exception ignored){}
        LinearLayout companionBar=row();pad(companionBar,9,2,5,5);
        companionBar.setBackground(bg(0xff1a2540,14,0xff47456d));
        TextView petMoodText=text("✦ Promie  ·  "+(petMood>65?"Happy":petMood>35?"Calm":"Needs attention"),13,PINK,true);
        companionBar.addView(petMoodText,new LinearLayout.LayoutParams(0,dp(35),1f));
        Button feedBtn=button("Feed",()->{petAction("feed");petMoodText.setText("✦ Promie  ·  Happy");});feedBtn.setTextSize(12);
        companionBar.addView(feedBtn,lp(61,35));
        Button praiseBtn=button("♡",()->{petAction("praise");petMoodText.setText("✦ Promie  ·  Happy");});praiseBtn.setTextSize(17);praiseBtn.setContentDescription("Praise your companion");
        companionBar.addView(praiseBtn,lp(43,35));
        page.addView(companionBar,lp(-1,42));
        scroller=new ScrollView(this);scroller.setFillViewport(true);
        thread=column();pad(thread,0,5,0,9);scroller.addView(thread);
        page.addView(scroller,new LinearLayout.LayoutParams(-1,0,1));
        if(messages.isEmpty()){
            TextView empty=text("Ask anything.\nPrometheus works privately on your phone.\n\nTap the flame to feed your companion.",17,MUTED,false);
            empty.setGravity(Gravity.CENTER);pad(empty,18,76,18,25);thread.addView(empty,lp(-1,-2));
        }else for(String[] m:messages)addBubble(m[0],m[1]);
        if(sharedGenerating){
            String partial="";
            try(Cursor current=db.rawQuery("SELECT partial FROM generation_state WHERE id=1",null)){
                if(current.moveToFirst())partial=current.getString(0);
            }catch(Exception ignored){}
            streamingBubble=addBubble("assistant",partial==null?"":partial);
        }else streamingBubble=null;
        LinearLayout sendbar=row();
        GradientDrawable composerBg=new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{0xff1a294b,0xff252443,0xff432a5d});
        composerBg.setCornerRadius(dp(20));composerBg.setStroke(dp(2),0xff789fe4);
        sendbar.setBackground(composerBg);pad(sendbar,8,6,7,6);
        page.addView(sendbar,lp(-1,-2));
        composer=new EditText(this);composer.setTextColor(FORE);composer.setHintTextColor(MUTED);
        composer.setTextSize(chatFontSize);composer.setHint("Message Prometheus…");composer.setText(draft);
        composer.setBackgroundColor(Color.TRANSPARENT);
        composer.setFocusable(true);composer.setFocusableInTouchMode(true);
        composer.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_YES);
        composer.setMinLines(1);composer.setMaxLines(4);composer.setInputType(0x00020001|0x00020000);
        sendbar.addView(composer,new LinearLayout.LayoutParams(0,-2,1));
        Button stop=button("■",()->{cancelRequested=true;runtime.stop();});
        stop.setContentDescription("Stop generation");stop.setTextColor(PINK);stop.setTextSize(18);
        sendbar.addView(stop,lp(40,45));
        Button send=button("➤",this::send);send.setContentDescription("Send message to offline model");
        send.setTextSize(21);send.setTextColor(FORE);
        GradientDrawable sendBg=new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{0xff2bdcf5,0xff9558e5,0xffff88d5});
        sendBg.setCornerRadius(dp(15));send.setBackground(sendBg);
        sendbar.addView(send,lp(41,45));
        scrollBottom();
    }
    private TextView addBubble(String role,String msg){
        LinearLayout wrapper=column();pad(wrapper,3,4,3,8);thread.addView(wrapper,lp(-1,-2));
        TextView who=text(role.equals("user")?"You":"Prometheus",12,role.equals("user")?MINT:MUTED,true);
        wrapper.addView(who,lp(-1,-2));
        TextView body=text(msg,chatFontSize,FORE,false);body.setTextIsSelectable(true);body.setLineSpacing(dp(3),1f);
        pad(body,14,12,14,12);
        GradientDrawable bubbleBg=new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            role.equals("user")?new int[]{0xff482751,0xff38315b}:new int[]{0xff152d4a,0xff242b48});
        bubbleBg.setCornerRadius(dp(17));bubbleBg.setStroke(dp(1),role.equals("user")?0xffdc83cf:0xff69d6ef);
        body.setBackground(bubbleBg);
        LinearLayout.LayoutParams p=lp(-1,-2);p.topMargin=dp(5);wrapper.addView(body,p);
        return body;
    }
    private void scrollBottom(){if(scroller!=null)scroller.post(()->{if(thread!=null)scroller.scrollTo(0,thread.getHeight());});}
    private void send(){
        if(composer==null)return;
        String q=composer.getText().toString().trim();if(q.isEmpty())return;
        if(!ready){
            queuedPrompt=q;saveSetting("queued_prompt",q);
            File stored=new File(new File(getFilesDir(),"models"),"prometheus.gguf");
            if(!busy && stored.isFile())loadModel(stored);
            notify(stored.isFile()?"Message queued until the model finishes loading.":"No model installed yet. Run prometheus-sync in Termux and reopen the app.");
            if(status!=null)status.setText("● Message queued — loading offline model");
            return;
        }
        if(busy||runtime.generating||runtime.loading){
            queuedPrompt=q;saveSetting("queued_prompt",q);composer.setText("");
            notify("Message saved. It will send when the active operation finishes.");
            if(status!=null)status.setText("● Message queued");
            return;
        }
        composer.setText("");
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(composer.getWindowToken(),0);composer.clearFocus();
        if(q.startsWith("/remember ") || q.startsWith("/correct ")){
            String kind=q.startsWith("/correct ")?"correction":"fact";
            String note=q.substring(q.indexOf(' ')+1).trim();
            if(!note.isEmpty())db.execSQL("INSERT INTO memory(kind,body) VALUES(?,?)",new Object[]{kind,note});
            messages.add(new String[]{"user",q});
            messages.add(new String[]{"assistant","Saved locally."});
            db.execSQL("INSERT INTO messages(role,body) VALUES('user',?)",new Object[]{q});
            db.execSQL("INSERT INTO messages(role,body) VALUES('assistant',?)",new Object[]{"Saved locally."});
            render();return;
        }
        if(q.startsWith("/project ") || q.equals("/project")){
            String answer=handleProjectCommand(q);
            messages.add(new String[]{"user",q});messages.add(new String[]{"assistant",answer});
            db.execSQL("INSERT INTO messages(role,body) VALUES('user',?)",new Object[]{q});
            db.execSQL("INSERT INTO messages(role,body) VALUES('assistant',?)",new Object[]{answer});
            render();return;
        }
        if(!runtime.send(q,profile,outputTokens)){
            composer.setText(q);notify("The offline runtime is busy. Try again when it is ready.");return;
        }
        draft="";saveSetting("chat_draft","");
        sharedGenerating=true;busy=true;
        loadSavedMessages();lastRenderedId=lastMessageId();render();
    }
    private String searchBooks(String q){
        String[] words=q.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]+"," ").trim().split("\\s+");
        StringBuilder match=new StringBuilder();
        for(String word:words){if(word.length()<4)continue;if(match.length()>0)match.append(" OR ");match.append(word);if(match.length()>180)break;}
        if(match.length()==0)return "";
        StringBuilder out=new StringBuilder();HashMap<String,Integer> per=new HashMap<>();
        try(Cursor c=db.rawQuery("SELECT book,body FROM passages WHERE passages MATCH ? LIMIT 50",new String[]{match.toString()})){
            while(c.moveToNext() && out.length()<5600){String book=c.getString(0);
                if(per.getOrDefault(book,0)>=3)continue;per.put(book,per.getOrDefault(book,0)+1);
                out.append("[Source: ").append(book).append("] ").append(c.getString(1)).append("\n\n");
            }
        }catch(Exception ignored){}
        return out.toString();
    }
    private void showLibrary(){
        page.addView(text("Your research library",22,FORE,true));
        TextView desc=text("Imported originals and indexed passages stay on your phone.",14,MUTED,false);
        pad(desc,0,9,0,16);page.addView(desc);
        TextView terminalHelp=text("Book and memory setup is controlled entirely from Termux. Run prometheus-sync, then return to this app. For books only: prometheus-sync --books-only. Original files are preserved.",16,MINT,false);
        pad(terminalHelp,8,16,8,20);terminalHelp.setBackground(bg(SURFACE,14,BORDER));page.addView(terminalHelp);
        bookCount=text("",14,MINT,false);pad(bookCount,5,17,0,15);page.addView(bookCount);
        updateBookCount();
        TextView help=text("Imported originals are archived privately on this phone. Book importing remains accessible through Termux. Scanned PDFs require OCR.",14,MUTED,false);
        page.addView(help);
    }
    private void updateBookCount(){if(bookCount==null)return;
        int archived=0,sources=0,passages=0;
        try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM book_files",null)){if(c.moveToFirst())archived=c.getInt(0);}
        try(Cursor c=db.rawQuery("SELECT COUNT(DISTINCT book),COUNT(*) FROM passages",null)){
            if(c.moveToFirst()){sources=c.getInt(0);passages=c.getInt(1);}
        }
        bookCount.setText(archived+" archived originals · "+sources+" searchable sources\n"+passages+" searchable passages (including imported Termux knowledge)");
    }
    private void selectBooks(){
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
        startActivityForResult(intent,PICK_BOOKS);
    }
    private void modelDialog(){
        String message="The AI runs inside this APK. No Termux or server. Import your 4B GGUF from Android Downloads/Files. You can import your existing abliterated 4B GGUF or download the same 4B model directly.\n\nCurrent: "+modelName;
        new AlertDialog.Builder(this).setTitle("Local AI model").setMessage(message)
        .setPositiveButton("Choose your 4B GGUF",(d,w)->{
            Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.setType("*/*");intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent,PICK_MODEL);
        }).setNeutralButton("Download abliterated 4B",(d,w)->downloadModel()).setNegativeButton("Cancel",null).show();
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(result!=RESULT_OK || data==null)return;
        if(request==PICK_MODEL && data.getData()!=null)importModel(data.getData());
        if(request==PICK_MIGRATION && data.getData()!=null)importMigration(data.getData());
        if(request==PICK_BOOK_FOLDER && data.getData()!=null)importBookFolder(data.getData());
        if(request==PICK_BOOKS){
            ArrayList<Uri> list=new ArrayList<>();
            if(data.getClipData()!=null) for(int i=0;i<data.getClipData().getItemCount();i++)list.add(data.getClipData().getItemAt(i).getUri());
            else if(data.getData()!=null)list.add(data.getData());
            if(list.isEmpty())return;
            setBusy(true,"● Indexing books…");
            jobs.execute(()->{
                int done=0;String last="";
                for(Uri u:list){try{indexBook(u);done++;}catch(Exception ex){last=ex.getMessage();}}
                final int ok=done;final String error=last;
                runUI(()->{setBusy(false,ready?"● Model ready":"● Choose a model");updateBookCount();
                    Toast.makeText(this,"Indexed "+ok+" of "+list.size()+" books"+(error.isEmpty()?"":"; last error: "+error),Toast.LENGTH_LONG).show();});
            });
        }
    }
    private String name(Uri uri){
        if("file".equals(uri.getScheme()) && uri.getLastPathSegment()!=null) return uri.getLastPathSegment();
        try(Cursor c=getContentResolver().query(uri,null,null,null,null)){
            if(c!=null && c.moveToFirst()){int i=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(i>=0)return c.getString(i);}
        }catch(Exception ignored){}
        return "document";
    }
    private void importModel(Uri uri){
        String filename=name(uri);
        if(!filename.toLowerCase(Locale.ROOT).contains("abliterat") && !filename.toLowerCase(Locale.ROOT).contains("obliterat")){
            notify("Choose your Huihui abliterated GGUF (other models do not meet your chosen model requirement)");return;
        }
        if(!filename.toLowerCase(Locale.ROOT).endsWith(".gguf")){notify("Choose a .gguf model file");return;}
        setBusy(true,"● Importing model…");
        jobs.execute(()->{
            try(InputStream in=getContentResolver().openInputStream(uri)){
                if(in==null)throw new IOException("Cannot read selected model");
                File target=new File(new File(getFilesDir(),"models"),"prometheus.gguf");
                File part=new File(target.getPath()+".partial");
                try(FileOutputStream out=new FileOutputStream(part)){byte[] b=new byte[1024*1024];int n;
                    while((n=in.read(b))!=-1)out.write(b,0,n);out.getFD().sync();}
                verifyGGUF(part);activateModel(part,target);
                getPreferences(MODE_PRIVATE).edit().putString("model_label",filename).apply();
                loadModelOnWorker(target);
            }catch(Exception ex){runUI(()->{setBusy(false,"● Model import failed");notify("Model import failed: "+ex.getMessage());});}
        });
    }
    private void downloadModel(){
        new AlertDialog.Builder(this).setTitle("Download local model?").setMessage("Downloads the 4B abliterated model (~2.7 GB) once. Inference is completely offline afterward. Your phone needs several gigabytes of free storage and available RAM.")
        .setNegativeButton("Cancel",null).setPositiveButton("Download",(d,w)->startDownload()).show();
    }
    private void startDownload(){setBusy(true,"● Downloading model…");
        jobs.execute(()->{
            HttpURLConnection conn=null;
            try{
                conn=(HttpURLConnection)new URL(MODEL_URL).openConnection();conn.setConnectTimeout(20000);conn.setReadTimeout(60000);
                conn.setInstanceFollowRedirects(true);conn.setRequestProperty("User-Agent","PrometheusAndroid/1.0");
                if(conn.getResponseCode()!=200)throw new IOException("Download HTTP "+conn.getResponseCode());
                long total=conn.getContentLengthLong(),read=0;long last=0;
                File target=new File(new File(getFilesDir(),"models"),"prometheus.gguf");
                File part=new File(target.getPath()+".partial");
                try(InputStream in=conn.getInputStream();FileOutputStream out=new FileOutputStream(part)){
                    byte[] buf=new byte[1024*1024];int n;
                    while((n=in.read(buf))!=-1){out.write(buf,0,n);read+=n;
                        if(System.currentTimeMillis()-last>2000){last=System.currentTimeMillis();
                            String progress=total>0?"● Download "+(read*100/total)+"%":"● Download "+(read/1024/1024)+" MB";
                            runUI(()->{if(status!=null)status.setText(progress);});}
                    }
                    out.getFD().sync();
                }
                verifyGGUF(part);activateModel(part,target);
            }catch(Exception ex){runUI(()->{setBusy(false,"● Model download failed");notify("Download failed: "+ex.getMessage());});}
            finally{if(conn!=null)conn.disconnect();}
        });
    }
    private void verifyGGUF(File f)throws IOException{
        if(f.length()<100_000_000)throw new IOException("Downloaded model is unexpectedly small");
        try(FileInputStream in=new FileInputStream(f)){
            byte[] a=new byte[4];if(in.read(a)!=4 || a[0]!='G' || a[1]!='G' || a[2]!='U' || a[3]!='F')
                throw new IOException("This is not a valid GGUF model");
        }
    }
    private void loadModel(File f){
        modelFile=f;
        modelName=getPreferences(MODE_PRIVATE).getString("model_label",f.getName());
        try{runtime.load(f);}catch(Exception error){notify(error.getMessage());}
    }
    private void loadModelOnWorker(File f){
        try{
            runtime.load(f).get();
            if(!runtime.ready)throw new IOException(runtime.stage);
            modelFile=f;modelName=getPreferences(MODE_PRIVATE).getString("model_label",f.getName());
            sharedModelReady=true;ready=true;sharedModelLoading=false;sharedModelPath=f.getAbsolutePath();
        }catch(Exception error){
            sharedModelReady=false;ready=false;sharedModelLoading=runtime.loading;
            throw new IllegalStateException("Model loading failed: "+error.getMessage(),error);
        }
    }
    private void activateModel(File staging,File target)throws Exception{
        runtime.beginModelReplacement();
        File backup=null;
        try{
            if(target.exists()){
                backup=new File(target.getParentFile(),"prometheus-previous-"+System.currentTimeMillis()+".gguf");
                java.nio.file.Files.move(target.toPath(),backup.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            }
            try{
                java.nio.file.Files.move(staging.toPath(),target.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            }catch(Exception failure){
                if(backup!=null)java.nio.file.Files.move(backup.toPath(),target.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                throw failure;
            }
        }finally{runtime.endModelReplacement();}
        loadModelOnWorker(target);
    }
    private String readAll(InputStream in,int cap)throws IOException{
        ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] chunk=new byte[8192];int n;
        while((n=in.read(chunk))!=-1){if(b.size()+n>cap)throw new IOException("Document exceeds extraction limit");b.write(chunk,0,n);}
        return b.toString("UTF-8");
    }
    private String zipText(InputStream in,String ext)throws IOException{
        StringBuilder sb=new StringBuilder();
        try(ZipInputStream zip=new ZipInputStream(in)){ZipEntry entry;
            while((entry=zip.getNextEntry())!=null){String key=entry.getName().toLowerCase(Locale.ROOT);
                boolean wanted=ext.equals("epub")?(key.endsWith(".html")||key.endsWith(".xhtml")||key.endsWith(".htm")):
                      ext.equals("docx")?key.equals("word/document.xml"):key.equals("content.xml");
                if(!wanted)continue;
                String raw=readAll(zip,8_000_000);
                raw=raw.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>"," ").replaceAll("(?i)</(p|div|h[1-6]|w:p|text:p)[^>]*>","\n");
                sb.append(Html.fromHtml(raw,Html.FROM_HTML_MODE_LEGACY).toString()).append("\n");
                if(sb.length()>2_000_000)break;
            }
        }
        return sb.toString();
    }
    private void indexBook(Uri uri)throws Exception{
        String file=name(uri);String lower=file.toLowerCase(Locale.ROOT);
        String ext=lower.contains(".")?lower.substring(lower.lastIndexOf('.')+1):"";
        if(!Arrays.asList("pdf","epub","docx","odt","txt","md","py","java","kt","js","csv","fb2","rtf").contains(ext))
            throw new IOException("This is not a supported book: "+file);
        File books=new File(getFilesDir(),"books");if(!books.exists()&&!books.mkdirs())throw new IOException("Cannot create book storage");
        File temp=File.createTempFile("incoming-",".part",books);
        String digest;
        try(InputStream original=getContentResolver().openInputStream(uri);
            FileOutputStream save=new FileOutputStream(temp)){
            if(original==null)throw new IOException("Cannot open "+file);
            java.security.MessageDigest sha=java.security.MessageDigest.getInstance("SHA-256");
            byte[] chunk=new byte[65536];int count;long total=0;
            while((count=original.read(chunk))!=-1){total+=count;
                if(total>256L*1024*1024)throw new IOException("Book exceeds 256 MB import limit: "+file);
                sha.update(chunk,0,count);save.write(chunk,0,count);
            }
            save.getFD().sync();
            StringBuilder hex=new StringBuilder();for(byte v:sha.digest())hex.append(String.format(Locale.ROOT,"%02x",v&255));
            digest=hex.toString();
        }catch(Exception e){temp.delete();throw e;}
        try(Cursor c=db.rawQuery("SELECT id FROM book_files WHERE sha256=?",new String[]{digest})){
            if(c.moveToFirst()){temp.delete();return;}
        }
        File archive=new File(books,digest+"."+ext);
        if(!temp.renameTo(archive)){temp.delete();throw new IOException("Cannot finish local book archive");}
        String extracted;
        try(InputStream stream=new FileInputStream(archive)){
            if(stream==null)throw new IOException("Cannot access "+file);
            switch(ext){
                case "pdf": try(PDDocument document=PDDocument.load(stream)){
                    PDFTextStripper stripper=new PDFTextStripper();stripper.setEndPage(document.getNumberOfPages());
                    extracted=stripper.getText(document);
                }break;
                case "epub":case "docx":case "odt":extracted=zipText(stream,ext);break;
                case "txt":case "md":case "py":case "java":case "kt":case "js":case "csv":
                    extracted=readAll(stream,5_000_000);break;
                case "fb2":case "rtf":extracted=readAll(stream,5_000_000).replaceAll("(?s)<[^>]+>"," ");break;
                default:throw new IOException("Unsupported format: ."+ext);
            }
        }
        extracted=extracted.replaceAll("[\\t\\r ]+"," ").replaceAll("\\n{4,}","\n\n").trim();
        if(extracted.length()<150)throw new IOException("Original stored, but no text was extracted from "+file+". This file may need OCR.");
        db.beginTransaction();
        try{
            db.execSQL("INSERT OR IGNORE INTO book_files(title,sha256,path,indexed_at) VALUES(?,?,?,?)",
                new Object[]{file,digest,archive.getAbsolutePath(),System.currentTimeMillis()});
            db.execSQL("DELETE FROM passages WHERE book=?",new Object[]{file});
            for(int pos=0;pos<extracted.length();pos+=1250){int end=Math.min(pos+1550,extracted.length());
                db.execSQL("INSERT INTO passages(book,body) VALUES(?,?)",new Object[]{file,extracted.substring(pos,end)});
            }
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }

    private void saveSetting(String k,String v){
        db.execSQL("INSERT OR REPLACE INTO settings(key,value) VALUES(?,?)",new Object[]{k,v});
    }
    private String handleProjectCommand(String q){
        String[] pieces=q.split(" ",3);
        if(pieces.length==1)return "Projects: /project create NAME, /project use ID, /project note TEXT, /project task TEXT, /project list";
        String op=pieces[1].toLowerCase(Locale.ROOT),val=pieces.length>=3?pieces[2].trim():"";
        if(op.equals("create")&&!val.isEmpty()){
            db.execSQL("INSERT OR IGNORE INTO projects(name,description,created) VALUES(?,?,?)",new Object[]{val,"",System.currentTimeMillis()});
            try(Cursor c=db.rawQuery("SELECT id FROM projects WHERE name=?",new String[]{val})){if(c.moveToFirst())currentProject=c.getInt(0);}
            saveSetting("current_project",String.valueOf(currentProject));return "Project created and activated: "+val;
        }
        if(op.equals("use")){try{int id=Integer.parseInt(val);
            try(Cursor c=db.rawQuery("SELECT name FROM projects WHERE id=?",new String[]{val})){
                if(c.moveToFirst()){currentProject=id;saveSetting("current_project",val);return "Active project: "+c.getString(0);}
            }
        }catch(Exception ignored){}return "Project ID not found.";}
        if(op.equals("list")){
            StringBuilder b=new StringBuilder();try(Cursor c=db.rawQuery("SELECT id,name FROM projects ORDER BY id DESC",null)){
                while(c.moveToNext())b.append(c.getInt(0)).append(": ").append(c.getString(1)).append("\n");}
            return b.length()==0?"No projects yet.":b.toString();
        }
        if(op.equals("note")||op.equals("task")){
            if(currentProject<0)return "Select a project first with /project create NAME or /project use ID";
            if(val.isEmpty())return "Provide some text for the "+op;
            db.execSQL("INSERT INTO project_items(project_id,kind,body,created) VALUES(?,?,?,?)",new Object[]{currentProject,op,val,System.currentTimeMillis()});
            return "Saved "+op+" to the current project.";
        }
        return "Unknown project action. Try /project list, /project create NAME, /project use ID, /project note TEXT, /project task TEXT";
    }
    private void showProjects(){
        page.addView(text("Projects & ongoing work",22,FORE,true));
        TextView intro=text("Keep notes, decisions, tasks, and corrections across conversations. Proposals to edit app code require your review.",15,MUTED,false);
        pad(intro,0,6,0,15);page.addView(intro);
        page.addView(button("＋ Create project",()->{
            EditText input=new EditText(this);input.setSingleLine(true);input.setHint("Project name");
            new AlertDialog.Builder(this).setTitle("New project").setView(input)
                .setPositiveButton("Create",(dialog,which)->{String n=input.getText().toString().trim();if(!n.isEmpty()){handleProjectCommand("/project create "+n);render();}})
                .setNegativeButton("Cancel",null).show();
        }),lp(-1,52));
        ScrollView scroll=new ScrollView(this);LinearLayout listing=column();scroll.addView(listing);
        page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        try(Cursor c=db.rawQuery("SELECT id,name FROM projects ORDER BY id DESC LIMIT 100",null)){
            while(c.moveToNext()){
                int id=c.getInt(0);String n=c.getString(1);
                Button b=button((id==currentProject?"● ":"○ ")+n,()->{currentProject=id;saveSetting("current_project",String.valueOf(id));render();});
                LinearLayout.LayoutParams p=lp(-1,54);p.topMargin=dp(8);listing.addView(b,p);
                if(id==currentProject){
                    try(Cursor notes=db.rawQuery("SELECT kind,body FROM project_items WHERE project_id=? ORDER BY id DESC LIMIT 8",new String[]{String.valueOf(id)})){
                        while(notes.moveToNext()){TextView item=text(notes.getString(0)+": "+notes.getString(1),15,MUTED,false);pad(item,12,6,6,6);listing.addView(item);}
                    }
                }
            }
        }
        page.addView(button("Add note to active project",()->addProjectNote("note")),lp(-1,49));
        page.addView(button("Add task to active project",()->addProjectNote("task")),lp(-1,49));
    }
    private void addProjectNote(String kind){
        if(currentProject<0){notify("Create or select a project first");return;}
        EditText input=new EditText(this);input.setSingleLine(false);input.setMinLines(2);input.setHint(kind);
        new AlertDialog.Builder(this).setTitle("New "+kind).setView(input)
            .setPositiveButton("Save",(d,w)->{handleProjectCommand("/project "+kind+" "+input.getText());render();})
            .setNegativeButton("Cancel",null).show();
    }
    private void showSettings(){
        page.addView(text("Personalize Prometheus",22,FORE,true));
        ScrollView scroll=new ScrollView(this);LinearLayout content=column();scroll.addView(content);
        page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView info=text("Your profile and preferences are saved on your phone.",15,MUTED,false);
        pad(info,0,10,0,16);content.addView(info);
        content.addView(text("Your permanent profile",17,PINK,true));
        profileEdit=new EditText(this);profileEdit.setTextColor(FORE);profileEdit.setHintTextColor(MUTED);
        profileEdit.setTextSize(17);profileEdit.setMinLines(5);profileEdit.setBackground(bg(SURFACE,14,BORDER));
        pad(profileEdit,12,12,12,12);profileEdit.setText(profile);
        content.addView(profileEdit,lp(-1,-2));
        content.addView(button("Save profile",()->{profile=profileEdit.getText().toString().trim();saveSetting("profile",profile);notify("Profile saved locally");}),lp(-1,49));
        content.addView(text("Chat text size",17,MINT,true));
        SeekBar fontSlider=new SeekBar(this);fontSlider.setMax(8);fontSlider.setProgress(chatFontSize-16);
        fontSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar bar,int progress,boolean fromUser){
                if(fromUser){chatFontSize=16+progress;saveSetting("font_size",String.valueOf(chatFontSize));}
            }
            @Override public void onStartTrackingTouch(SeekBar s){}
            @Override public void onStopTrackingTouch(SeekBar s){}
        });content.addView(fontSlider);
        content.addView(text("Response length",17,MINT,true));
        RadioGroup lengths=new RadioGroup(this);lengths.setOrientation(RadioGroup.VERTICAL);
        for(int t:new int[]{192,384,640}){
            RadioButton b=new RadioButton(this);b.setText(t==192?"Fast · shorter replies":t==384?"Balanced · normal chat":"Detailed · complex projects");
            b.setTextSize(16);b.setTextColor(FORE);b.setId(t);lengths.addView(b);
        }
        lengths.check(outputTokens);
        lengths.setOnCheckedChangeListener((g,id)->{outputTokens=id;saveSetting("tokens",String.valueOf(outputTokens));});
        content.addView(lengths);
        content.addView(text("Active model",17,MINT,true));
        content.addView(text(modelName,15,FORE,false));
        TextView commands=text("All installation and maintenance happens in Termux:\nprometheus-sync                  (everything)\nprometheus-sync --model-only   (GGUF)\nprometheus-sync --books-only   (books)\nprometheus-sync --memory-only (chats, corrections, projects)\n\nOpen Prometheus again after starting the Termux command. No picker, server pairing, or setup screens.",15,MINT,false);
        pad(commands,10,14,10,14);commands.setBackground(bg(SURFACE,14,BORDER));content.addView(commands);
        content.addView(text("Offline runtime: upstream llama.cpp packaged in this APK. Conversations need no Termux or internet connection.",14,MUTED,false));
    }

    private void importMigration(Uri uri){
        setBusy(true,"● Restoring Termux knowledge…");
        jobs.execute(()->importMigrationWork(uri));
    }
    private boolean importMigrationWork(Uri uri){
            File tmp=null;int imported=0;
            try{
                tmp=File.createTempFile("prometheus-migration-",".jsonl",getCacheDir());
                java.security.MessageDigest sha=java.security.MessageDigest.getInstance("SHA-256");
                try(InputStream in=getContentResolver().openInputStream(uri); FileOutputStream out=new FileOutputStream(tmp)){
                    if(in==null)throw new IOException("Cannot open migration file");
                    byte[] buf=new byte[65536];int n;long bytes=0;
                    while((n=in.read(buf))!=-1){bytes+=n;if(bytes>1024L*1024*1024)throw new IOException("Migration exceeds 1 GB");
                        sha.update(buf,0,n);out.write(buf,0,n);}
                }
                StringBuilder h=new StringBuilder();for(byte b:sha.digest())h.append(String.format(Locale.ROOT,"%02x",b&255));
                String hash=h.toString();
                try(Cursor c=db.rawQuery("SELECT 1 FROM imported_migrations WHERE sha256=?",new String[]{hash})){
                    if(c.moveToFirst())return true; // Safe repeated Termux sync
                }
                db.beginTransaction();
                try(BufferedReader br=new BufferedReader(new InputStreamReader(new FileInputStream(tmp),StandardCharsets.UTF_8),32768)){
                    String line;
                    while((line=br.readLine())!=null){
                        if(line.length()>1000000)throw new IOException("Migration line too long");
                        if(line.isEmpty())continue;
                        JSONObject o=new JSONObject(line);String type=o.optString("type","");
                        if("manifest".equals(type))continue;
                        byte[] fpBytes=java.security.MessageDigest.getInstance("SHA-256").digest(o.toString().getBytes(StandardCharsets.UTF_8));
                        StringBuilder fpHex=new StringBuilder();
                        for(byte byteValue:fpBytes)fpHex.append(String.format(Locale.ROOT,"%02x",byteValue&255));
                        String fingerprint=fpHex.toString();
                        try(Cursor dupe=db.rawQuery("SELECT 1 FROM imported_rows WHERE fingerprint=?",new String[]{fingerprint})){
                            if(dupe.moveToFirst())continue;
                        }
                        int before=imported;
                        switch(type){
                            case "message":{
                                String role=o.optString("role","");String body=o.optString("body","");
                                if((role.equals("user")||role.equals("assistant"))&&!body.isEmpty()){
                                    db.execSQL("INSERT INTO messages(role,body) VALUES(?,?)",new Object[]{role,body});imported++;}
                                break;
                            }
                            case "memory":{
                                String body=o.optString("body","");
                                if(!body.isEmpty()){db.execSQL("INSERT INTO memory(kind,body) VALUES(?,?)",
                                    new Object[]{o.optString("kind","fact"),body});imported++;}
                                break;
                            }
                            case "passage":{
                                String title=o.optString("book","Imported book"),body=o.optString("body","");
                                if(!body.isEmpty()){db.execSQL("INSERT INTO passages(book,body) VALUES(?,?)",new Object[]{title,body});imported++;}
                                break;
                            }
                            case "project":{
                                String name=o.optString("name","");
                                if(!name.isEmpty()){db.execSQL("INSERT OR IGNORE INTO projects(name,description,created) VALUES(?,?,?)",
                                    new Object[]{name,o.optString("description",""),System.currentTimeMillis()});imported++;}
                                break;
                            }
                            case "project_item":{
                                String name=o.optString("project",""),body=o.optString("body","");
                                if(name.isEmpty()||body.isEmpty())break;
                                try(Cursor pc=db.rawQuery("SELECT id FROM projects WHERE name=?",new String[]{name})){
                                    if(pc.moveToFirst()){
                                        db.execSQL("INSERT INTO project_items(project_id,kind,body,done,created) VALUES(?,?,?,?,?)",
                                            new Object[]{pc.getInt(0),o.optString("kind","note"),body,o.optInt("done",0),System.currentTimeMillis()});imported++;
                                    }
                                }
                                break;
                            }
                        }
                        if(imported>before)db.execSQL("INSERT OR IGNORE INTO imported_rows(fingerprint) VALUES(?)",new Object[]{fingerprint});
                    }
                    db.execSQL("INSERT INTO imported_migrations(sha256,imported) VALUES(?,?)",new Object[]{hash,System.currentTimeMillis()});
                    db.setTransactionSuccessful();
                }finally{db.endTransaction();}
                final int count=imported;
                runUI(()->{messages.clear();
                    try(Cursor c=db.rawQuery("SELECT role,body FROM (SELECT id,role,body FROM messages ORDER BY id DESC LIMIT 100) ORDER BY id ASC",null)){
                        while(c.moveToNext())messages.add(new String[]{c.getString(0),c.getString(1)});
                    }
                    setBusy(false,ready?"● Model ready":"● Choose a model");render();
                    notify("Restored "+count+" knowledge and chat records");});
                return true;
            }catch(Exception ex){String err=ex.getMessage();runUI(()->{setBusy(false,"● Migration failed");notify("Migration: "+err);});return false;}
            finally{if(tmp!=null)tmp.delete();
                if("file".equals(uri.getScheme()) && uri.getPath()!=null){
                    File staged=new File(uri.getPath());
                    if(getCacheDir().equals(staged.getParentFile()) && staged.getName().startsWith("prometheus-sync-")) staged.delete();
                }
            }
    }

    private void importBookFolder(Uri treeUri){
        setBusy(true,"● Importing selected book folder…");
        jobs.execute(()->{
            int scanned=0,done=0,failed=0;
            try{
                Deque<String> folders=new ArrayDeque<>();
                folders.add(DocumentsContract.getTreeDocumentId(treeUri));
                while(!folders.isEmpty() && scanned<4000){
                    String parent=folders.removeFirst();
                    Uri childUri=DocumentsContract.buildChildDocumentsUriUsingTree(treeUri,parent);
                    try(Cursor children=getContentResolver().query(childUri,
                        new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_MIME_TYPE},null,null,null)){
                        if(children==null)continue;
                        while(children.moveToNext() && scanned<4000){
                            scanned++;String id=children.getString(0),name=children.getString(1),mime=children.getString(2);
                            if(DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)){
                                if(folders.size()<1000)folders.addLast(id);
                                continue;
                            }
                            String lower=name==null?"":name.toLowerCase(Locale.ROOT);
                            if(!lower.matches("(?s).*\\.(pdf|epub|docx|odt|txt|md|fb2|rtf)$"))continue;
                            try{indexBook(DocumentsContract.buildDocumentUriUsingTree(treeUri,id));done++;}
                            catch(Exception ignored){failed++;}
                        }
                    }
                }
            }catch(Exception ex){String err=ex.getMessage();runUI(()->notify("Folder scan failed: "+err));}
            final int ok=done,failedCount=failed,examined=scanned;
            runUI(()->{setBusy(false,ready?"● Model ready":"● Choose model");updateBookCount();
                notify("Archived and indexed "+ok+" books from "+examined+" files; "+failedCount+" need attention");});
        });
    }

    // ===== Termux automatic offline sync (one-time localhost data handoff) =====
    // A Termux command starts a temporary local-only HTTP sender on port 18765.
    // Opening the native APK initiates transfer and then the sender exits.
    // All generation and storage remains native after this one-time setup.
    private static final String TERMUX_BRIDGE="http://127.0.0.1:18765";
    private static final long MAX_NATIVE_MODEL=12L*1024*1024*1024;

    private HttpURLConnection syncConnection(String endpoint,int connectTimeout,int readTimeout)throws IOException{
        URL u=new URL(TERMUX_BRIDGE+endpoint);
        if(!"127.0.0.1".equals(u.getHost()))throw new IOException("Non-loopback setup address rejected");
        HttpURLConnection c=(HttpURLConnection)u.openConnection();
        c.setConnectTimeout(connectTimeout);c.setReadTimeout(readTimeout);
        c.setUseCaches(false);c.setInstanceFollowRedirects(false);
        return c;
    }
    private String sha256(File f)throws Exception{
        java.security.MessageDigest m=java.security.MessageDigest.getInstance("SHA-256");
        try(InputStream in=new FileInputStream(f)){byte[] buf=new byte[131072];int n;
            while((n=in.read(buf))!=-1)m.update(buf,0,n);
        }
        StringBuilder h=new StringBuilder();for(byte b:m.digest())h.append(String.format(Locale.ROOT,"%02x",b&255));
        return h.toString();
    }
    private void syncSendDiagnostics(){
        HttpURLConnection conn=null;
        try{
            conn=syncConnection("/diagnostics",1000,3000);
            conn.setRequestMethod("POST");conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type","application/json");
            JSONObject report=new JSONObject();
            report.put("model_ready",runtime.ready&&runtime.engine.isHealthy());
            report.put("model_name",modelName);
            report.put("last_inference",getSetting("last_inference","Not yet attempted"));
            report.put("timestamp",System.currentTimeMillis());
            report.put("runtime_revision",LlamaRuntime.REVISION);
            report.put("native_log_tail",runtime.engine.diagnostic());
            byte[] body=report.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(body.length);
            try(OutputStream out=conn.getOutputStream()){out.write(body);}
            conn.getResponseCode();
        }catch(Exception ignored){}finally{if(conn!=null)conn.disconnect();}
    }
    private void syncFinish(String body){
        HttpURLConnection c=null;
        try{
            c=syncConnection("/done",1000,2000);c.setRequestMethod("POST");c.setDoOutput(true);
            c.setRequestProperty("Content-Type","application/json");
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(bytes.length);
            try(OutputStream o=c.getOutputStream()){o.write(bytes);}
            c.getResponseCode();
        }catch(Exception e){android.util.Log.w("PrometheusSync","Cannot acknowledge sender",e);}
        finally{if(c!=null)c.disconnect();}
    }
    private File syncTransfer(JSONObject item,File target)throws Exception{
        String id=item.getString("id"),expected=item.getString("sha256");
        long size=item.getLong("size");
        if(!id.matches("[0-9]{1,5}") || !expected.matches("[a-f0-9]{64}"))throw new IOException("Invalid transfer metadata");
        if(size<1 || size>MAX_NATIVE_MODEL)throw new IOException("Transfer size rejected");
        File dir=target.getParentFile();if(!dir.exists()&&!dir.mkdirs())throw new IOException("Cannot create storage folder");
        if(dir.getUsableSpace()<size+64L*1024*1024)throw new IOException("Insufficient free storage for "+item.optString("name","file"));
        File partial=new File(target.getPath()+".partial");
        HttpURLConnection c=null;long read=0;long last=0;
        try{
            c=syncConnection("/item/"+id,5000,120000);
            if(c.getResponseCode()!=200)throw new IOException("Termux sender HTTP "+c.getResponseCode());
            java.security.MessageDigest md=java.security.MessageDigest.getInstance("SHA-256");
            try(InputStream in=c.getInputStream();FileOutputStream out=new FileOutputStream(partial)){
                byte[] b=new byte[131072];int n;
                while((n=in.read(b))!=-1){if(Thread.currentThread().isInterrupted())throw new IOException("Interrupted");
                    read+=n;if(read>size)throw new IOException("File exceeds advertised length");
                    md.update(b,0,n);out.write(b,0,n);
                    if(System.currentTimeMillis()-last>1800){last=System.currentTimeMillis();
                        String progress="● Termux → Prometheus: "+item.optString("name","data")+" "+(100*read/size)+"%";
                        runUI(()->{if(status!=null)status.setText(progress);});
                    }
                }
                out.getFD().sync();
            }
            if(read!=size)throw new IOException("Incomplete transfer of "+item.optString("name","file"));
            StringBuilder h=new StringBuilder();for(byte v:md.digest())h.append(String.format(Locale.ROOT,"%02x",v&255));
            if(!expected.equals(h.toString()))throw new IOException("Checksum mismatch: "+item.optString("name","file"));
            if(target.exists()&&!target.delete())throw new IOException("Cannot replace staging file");
            if(!partial.renameTo(target))throw new IOException("Cannot finalize staging file");
            return target;
        }finally{if(c!=null)c.disconnect();if(partial.exists())partial.delete();}
    }
    private void syncFromTermuxIfAvailable(){
        HttpURLConnection c=null;boolean foregroundHeld=false;
        try{
            c=syncConnection("/manifest",1100,2500);
            if(c.getResponseCode()!=200)return;
            String body;
            try(InputStream in=c.getInputStream()){body=readAll(in,2_000_000);}
            JSONObject manifest=new JSONObject(body);
            if(!"prometheus-local-transfer-v1".equals(manifest.optString("protocol")))return;
            foregroundBegin("Importing model and knowledge from Termux");foregroundHeld=true;
            syncSendDiagnostics();
            org.json.JSONArray items=manifest.getJSONArray("items");
            if(items.length()>3000)throw new IOException("Too many transfer files");
            runUI(()->{if(status!=null)status.setText("● Termux sync active — model and books are being checked");});
            int books=0,memories=0,models=0,failures=0;
            for(int i=0;i<items.length();i++){
                JSONObject item=items.getJSONObject(i);
                String kind=item.optString("kind"),filename=item.optString("name","");
                String lower=filename.toLowerCase(Locale.ROOT);
                if(filename.length()>200 || filename.contains("/") || filename.contains("\\") || filename.contains("..")){
                    failures++;continue;
                }
                try{
                    if("model".equals(kind)){
                        if(!lower.endsWith(".gguf") || !(lower.contains("abliterat")||lower.contains("obliterat")))
                            throw new IOException("Only abliterated GGUF models are supported by this setup");
                        File target=new File(new File(getFilesDir(),"models"),"prometheus.gguf");
                        String expected=item.getString("sha256");
                        if(target.isFile() && target.length()==item.getLong("size") && sha256(target).equals(expected)){
                            models++;
                            if(!ready && !sharedModelLoading)loadModelOnWorker(target);
                            continue;
                        }
                        File staging=new File(new File(getFilesDir(),"models"),"sync-abliterated.gguf");
                        syncTransfer(item,staging);verifyGGUF(staging);
                        activateModel(staging,target);
                        getPreferences(MODE_PRIVATE).edit().putString("model_label",filename).apply();
                        models++;
                    }else if("migration".equals(kind)){
                        if(!lower.endsWith(".jsonl") || item.getLong("size")>1024L*1024*1024)throw new IOException("Invalid memory file");
                        File stage=new File(getCacheDir(),"prometheus-sync-migration.jsonl");
                        syncTransfer(item,stage);
                        if(importMigrationWork(Uri.fromFile(stage)))memories++;
                        else failures++;
                    }else if("book".equals(kind)){
                        if(!lower.matches("(?s).*\\.(pdf|epub|docx|odt|txt|md|fb2|rtf)$") || item.getLong("size")>256L*1024*1024)
                            throw new IOException("Unsupported book format or size");
                        File stageDir=new File(getCacheDir(),"book-sync-"+item.getString("id"));
                        if(!stageDir.exists()&&!stageDir.mkdirs())throw new IOException("Cannot create book staging directory");
                        File stage=new File(stageDir,filename);
                        syncTransfer(item,stage);
                        try{indexBook(Uri.fromFile(stage));books++;}
                        finally{stage.delete();stageDir.delete();}
                    }
                }catch(Exception e){failures++;android.util.Log.e("PrometheusSync","Item skipped: "+filename,e);}
            }
            if(manifest.optBoolean("probe",false)){
                String testAnswer;
                boolean testOk=false;
                try{
                    File model=new File(new File(getFilesDir(),"models"),"prometheus.gguf");
                    if(!runtime.ready && model.isFile())loadModelOnWorker(model);
                    if(!runtime.ready || !runtime.engine.isHealthy())throw new IOException("Offline runtime is not ready: "+runtime.stage);
                    if(runtime.generating)throw new IOException("A chat response is still generating. Retry the test afterward.");
                    testAnswer=runtime.probe();
                    testOk=!testAnswer.trim().isEmpty();
                    if(testAnswer.length()>250)testAnswer=testAnswer.substring(0,250);
                    recordInference("Native model test: "+(testOk?"answer generated":"failed")+"; "+testAnswer.length()+" characters");
                }catch(Throwable ex){testAnswer="Probe failed: "+ex.toString();recordInference(testAnswer);}
                syncSendDiagnostics();
                syncFinish("{\"success\":"+testOk+",\"probe\":"+JSONObject.quote(testAnswer)+"}");
                return;
            }
            final int restoredBooks=books,restoredModels=models,restoredMem=memories,errors=failures;
            syncFinish("{\"success\":"+(failures==0)+",\"models\":"+models+",\"books\":"+books+",\"migrations\":"+memories+",\"errors\":"+failures+"}");
            runUI(()->{updateBookCount();if(!sharedGenerating && !sharedModelLoading)setBusy(false,ready?"● Model ready":"● Preparing model…");
                Toast.makeText(this,"Termux sync: "+restoredModels+" model, "+restoredBooks+" books, "+restoredMem+" migration; "+errors+" errors",Toast.LENGTH_LONG).show();});
            File model=new File(new File(getFilesDir(),"models"),"prometheus.gguf");
            if(model.isFile() && !ready && !sharedModelLoading)loadModelOnWorker(model);
            syncSendDiagnostics();
        }catch(java.net.ConnectException ignored){/* No sender: ordinary offline chat, no delay. */}
        catch(Exception e){android.util.Log.e("PrometheusSync","Local transfer failed",e);
            final String error=e.getMessage();syncFinish("{\"success\":false,\"message\":"+JSONObject.quote(error==null?"Unknown":error)+"}");
            runUI(()->{setBusy(false,ready?"● Model ready":"● Termux sync error");notify("Termux setup: "+error);});
        }finally{if(c!=null)c.disconnect();if(foregroundHeld)foregroundEnd();}
    }
}
