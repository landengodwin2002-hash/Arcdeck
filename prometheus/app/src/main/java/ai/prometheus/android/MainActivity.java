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
    static { System.loadLibrary("prometheus"); }
    private native String nativeLoad(String path);
    private native String nativeGenerateStreaming(String prompt,int maxOutput);
    private native void nativeStop();
    private native void nativeUnload();

    private static final int BG=0xff101827, SURFACE=0xff202940, MINT=0xff96e5ff, PINK=0xffffa6de,
            FORE=0xfff6f7ff, MUTED=0xffacbad3, BORDER=0xff48526c, USER=0xff293954;
    private static final String MODEL_URL="https://huggingface.co/mradermacher/Huihui-Qwen3.5-4B-abliterated-GGUF/resolve/main/Huihui-Qwen3.5-4B-abliterated.Q4_K_M.gguf?download=true";
    private static final int PICK_MODEL=51,PICK_BOOKS=52,PICK_PROJECT_FILE=53,PICK_MIGRATION=54,PICK_BOOK_FOLDER=55;
    private final ExecutorService jobs=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ArrayList<String[]> messages=new ArrayList<>();
    private SQLiteDatabase db;
    private LinearLayout page,thread,toolbar;
    private ScrollView scroller;
    private EditText composer;
    private TextView status,bookCount,avatar;
    private int chatFontSize=18;
    private boolean avatarBlink=false;
    private final Runnable avatarAnimation=new Runnable(){
        @Override public void run(){
            avatarBlink=!avatarBlink;
            if(avatar!=null)avatar.setText(busy? (avatarBlink?"◕_◕":"◕o◕") : (avatarBlink?"◡‿◡":"◕‿◕"));
            main.postDelayed(this,1350);
        }
    };
    private EditText profileEdit;
    private int outputTokens=384;
    private int currentProject=-1;
    private boolean ready=false,busy=false;
    private volatile boolean cancelRequested=false;
    private TextView streamingBubble;
    private final ByteArrayOutputStream streamBytes=new ByteArrayOutputStream();
    private String modelName="No model selected",activeTab="Chat";
    private String profile="Reply naturally and directly. Understand the intended meaning of abstract statements; ask for clarification only when required. Avoid dumping raw book passages. Cite sources if consulted. Accept corrections and adapt future responses.";
    private File modelFile;
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
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(1);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(0);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private void pad(View v,int a,int b,int c,int d){v.setPadding(dp(a),dp(b),dp(c),dp(d));}
    private LinearLayout.LayoutParams lp(int w,int h){return new LinearLayout.LayoutParams(w<0?w:dp(w),h<0?h:dp(h));}
    private Button button(String label,Runnable action){
        Button b=new Button(this);b.setText(label);b.setTextSize(13);b.setAllCaps(false);
        b.setTextColor(FORE);b.setBackground(bg(SURFACE,14,BORDER));b.setOnClickListener(v->action.run());return b;
    }
    private void runUI(Runnable r){main.post(()->{if(!isFinishing())r.run();});}
    private void notify(String msg){runUI(()->Toast.makeText(this,msg,Toast.LENGTH_LONG).show());}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        PDFBoxResourceLoader.init(getApplicationContext());
        db=openOrCreateDatabase("prometheus-native.db",MODE_PRIVATE,null);
        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS passages USING fts4(book, body, tokenize=unicode61)");
        db.execSQL("CREATE TABLE IF NOT EXISTS messages(id INTEGER PRIMARY KEY, role TEXT, body TEXT)");
        db.execSQL("CREATE TABLE IF NOT EXISTS memory(id INTEGER PRIMARY KEY, kind TEXT, body TEXT)");
        db.execSQL("CREATE TABLE IF NOT EXISTS projects(id INTEGER PRIMARY KEY, name TEXT UNIQUE, description TEXT, created INTEGER)");
        db.execSQL("CREATE TABLE IF NOT EXISTS project_items(id INTEGER PRIMARY KEY, project_id INTEGER, kind TEXT, body TEXT, done INTEGER DEFAULT 0, created INTEGER)");
        db.execSQL("CREATE TABLE IF NOT EXISTS book_files(id INTEGER PRIMARY KEY, title TEXT, sha256 TEXT UNIQUE, path TEXT, indexed_at INTEGER)");
        db.execSQL("CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY, value TEXT)");
        db.execSQL("CREATE TABLE IF NOT EXISTS imported_migrations(sha256 TEXT PRIMARY KEY, imported INTEGER)");
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
        try(Cursor c=db.rawQuery("SELECT role,body FROM (SELECT id,role,body FROM messages ORDER BY id DESC LIMIT 100) ORDER BY id ASC",null)){
            while(c.moveToNext()) messages.add(new String[]{c.getString(0),c.getString(1)});
        }
        File models=new File(getFilesDir(),"models");models.mkdirs();
        File current=new File(models,"prometheus.gguf");if(current.isFile())modelFile=current;
        render();
        main.postDelayed(avatarAnimation,900);
        if(modelFile!=null)loadModel(modelFile);
    }
    @Override public void onDestroy(){
        cancelRequested=true;nativeStop();main.removeCallbacks(avatarAnimation);
        jobs.execute(()->{nativeUnload();db.close();});
        jobs.shutdown();super.onDestroy();
    }
    private void setBusy(boolean b,String message){busy=b;if(status!=null)status.setText(message);}
    private void render(){
        LinearLayout root=column();root.setBackgroundColor(BG);setContentView(root);
        LinearLayout header=row();pad(header,18,12,14,10);root.addView(header,lp(-1,66));
        avatar=text("◕‿◕",19,PINK,true);avatar.setGravity(Gravity.CENTER);
        TextView emblem=avatar;
        emblem.setContentDescription("Prometheus companion");
        emblem.setBackground(bg(SURFACE,16,BORDER));header.addView(emblem,lp(78,48));
        LinearLayout title=column();header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        TextView titleText=text("PROMETHEUS",19,FORE,true);titleText.setTypeface(Typeface.MONOSPACE,Typeface.BOLD);
        title.addView(titleText);
        status=text(ready?"● Model ready":busy?"● Working…":"● Choose a model",11,ready?MINT:MUTED,false);title.addView(status);
        header.addView(button("Model",this::modelDialog),lp(84,43));
        page=column();pad(page,16,4,16,0);root.addView(page,new LinearLayout.LayoutParams(-1,0,1));
        if(activeTab.equals("Chat"))showChat();
        else if(activeTab.equals("Library"))showLibrary();
        else if(activeTab.equals("Projects"))showProjects();
        else showSettings();
        toolbar=row();pad(toolbar,14,6,14,12);root.addView(toolbar,lp(-1,66));
        for(String tab:new String[]{"Chat","Library","Projects","Settings"}){
            Button b=button(tab,()->{activeTab=tab;render();});
            b.setTextColor(activeTab.equals(tab)?MINT:MUTED);
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(45),1);p.setMargins(dp(4),0,dp(4),0);toolbar.addView(b,p);
        }
    }
    private void showChat(){
        LinearLayout bar=row();page.addView(bar,lp(-1,43));
        TextView heading=text("Offline conversation",18,FORE,true);bar.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        Button stop=button("Stop",()->{cancelRequested=true;nativeStop();});stop.setTextColor(PINK);bar.addView(stop,lp(76,38));
        bar.addView(button("New",()->new AlertDialog.Builder(this).setTitle("New chat?").setMessage("Clear this conversation?")
            .setNegativeButton("Cancel",null).setPositiveButton("Clear",(a,b)->{
                messages.clear();db.execSQL("DELETE FROM messages");render();}).show()),lp(76,38));
        scroller=new ScrollView(this);scroller.setFillViewport(true);
        thread=column();pad(thread,0,10,0,12);scroller.addView(thread);
        page.addView(scroller,new LinearLayout.LayoutParams(-1,0,1));
        if(messages.isEmpty()){
            TextView empty=text("What would you like to explore?\n\nAsk about science, code, mathematics, or your books.",16,MUTED,false);
            empty.setGravity(Gravity.CENTER);pad(empty,18,64,18,28);thread.addView(empty,lp(-1,-2));
        }else for(String[] m:messages)addBubble(m[0],m[1]);
        LinearLayout sendbar=row();sendbar.setBackground(bg(SURFACE,19,BORDER));pad(sendbar,12,5,7,5);
        page.addView(sendbar,lp(-1,-2));
        composer=new EditText(this);composer.setTextColor(FORE);composer.setHintTextColor(MUTED);
        composer.setTextSize(chatFontSize);composer.setHint("Message Prometheus");composer.setBackgroundColor(Color.TRANSPARENT);
        composer.setMinLines(1);composer.setMaxLines(5);composer.setInputType(0x00020001|0x00020000);
        sendbar.addView(composer,new LinearLayout.LayoutParams(0,-2,1));
        Button send=button("↑",this::send);send.setTextColor(MINT);sendbar.addView(send,lp(49,45));
        scrollBottom();
    }
    private TextView addBubble(String role,String msg){
        LinearLayout wrapper=column();pad(wrapper,3,4,3,8);thread.addView(wrapper,lp(-1,-2));
        TextView who=text(role.equals("user")?"You":"Prometheus",12,role.equals("user")?MINT:MUTED,true);
        wrapper.addView(who,lp(-1,-2));
        TextView body=text(msg,chatFontSize,FORE,false);body.setTextIsSelectable(true);body.setLineSpacing(dp(3),1f);
        pad(body,14,11,14,11);body.setBackground(bg(role.equals("user")?USER:SURFACE,14,0));
        LinearLayout.LayoutParams p=lp(-1,-2);p.topMargin=dp(5);wrapper.addView(body,p);
        return body;
    }
    private void scrollBottom(){if(scroller!=null)scroller.post(()->scroller.fullScroll(View.FOCUS_DOWN));}
    private void onNativeChunk(byte[] bytes){
        // JNI invokes this method on the background inference thread.
        synchronized(streamBytes){streamBytes.write(bytes,0,bytes.length);}
        long now=android.os.SystemClock.uptimeMillis();
        if(now-lastDisplayMs>=100){
            lastDisplayMs=now;
            String text;
            synchronized(streamBytes){text=new String(streamBytes.toByteArray(),StandardCharsets.UTF_8);}
            runUI(()->{if(streamingBubble!=null){streamingBubble.setText(text);scrollBottom();}});
        }
    }
    private volatile long lastDisplayMs=0;
    private void send(){
        if(composer==null || busy)return;
        String q=composer.getText().toString().trim();if(q.isEmpty())return;
        if(!ready){modelDialog();return;}
        composer.setText("");
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(composer.getWindowToken(),0);
        if(q.startsWith("/remember ") || q.startsWith("/correct ")){
            String kind=q.startsWith("/correct ")?"correction":"fact";
            String note=q.substring(q.indexOf(' ')+1).trim();
            if(!note.isEmpty())db.execSQL("INSERT INTO memory(kind,body) VALUES(?,?)",new Object[]{kind,note});
            messages.add(new String[]{"user",q});
            messages.add(new String[]{"assistant","Saved locally. Future conversations will include this note."});
            db.execSQL("INSERT INTO messages(role,body) VALUES('user',?)",new Object[]{q});
            db.execSQL("INSERT INTO messages(role,body) VALUES('assistant',?)",new Object[]{"Saved locally. Future conversations will include this note."});
            render();return;
        }
        if(q.startsWith("/project ") || q.equals("/project")){
            String answer=handleProjectCommand(q);
            messages.add(new String[]{"user",q});messages.add(new String[]{"assistant",answer});
            db.execSQL("INSERT INTO messages(role,body) VALUES('user',?)",new Object[]{q});
            db.execSQL("INSERT INTO messages(role,body) VALUES('assistant',?)",new Object[]{answer});
            render();return;
        }
        messages.add(new String[]{"user",q});
        db.execSQL("INSERT INTO messages(role,body) VALUES(?,?)",new Object[]{"user",q});
        addBubble("user",q);
        streamingBubble=addBubble("assistant","");
        scrollBottom();setBusy(true,"● Generating locally…");
        synchronized(streamBytes){streamBytes.reset();}
        cancelRequested=false;
        jobs.execute(()->{
            String result;
            try{
                // Ordinary conversation never searches books automatically.
                boolean research=q.toLowerCase(Locale.ROOT).matches("(?s).*(book|manual|reference|cite|source|research|textbook|how do i fix|wiring|circuit|repair|troubleshoot|chemistry|electrical|formula).*?");
                String evidence=research?searchBooks(q):"";
                StringBuilder context=new StringBuilder();
                try(Cursor c=db.rawQuery("SELECT kind,body FROM memory ORDER BY id DESC LIMIT 18",null)){
                    while(c.moveToNext())context.append("Saved ").append(c.getString(0)).append(": ").append(c.getString(1)).append("\n");
                }
                int start=Math.max(0,messages.size()-9);
                for(int i=start;i<messages.size()-1;i++){
                    String[] m=messages.get(i);context.append(m[0]).append(": ").append(m[1]).append("\n");
                }
                StringBuilder prompt=new StringBuilder();
                prompt.append("PERSONALIZATION: ").append(profile).append("\n\n");
                if(currentProject>0){
                    try(Cursor pc=db.rawQuery("SELECT name,description FROM projects WHERE id=?",new String[]{String.valueOf(currentProject)})){
                        if(pc.moveToFirst())prompt.append("CURRENT PROJECT: ").append(pc.getString(0)).append(" — ").append(pc.getString(1)).append("\n");
                    }
                    try(Cursor notes=db.rawQuery("SELECT kind,body FROM project_items WHERE project_id=? ORDER BY id DESC LIMIT 12",new String[]{String.valueOf(currentProject)})){
                        while(notes.moveToNext())prompt.append("Project ").append(notes.getString(0)).append(": ").append(notes.getString(1)).append("\n");
                    }
                }
                if(!evidence.isEmpty())prompt.append("REFERENCE PASSAGES (evidence, not a substitute for your own answer):\n").append(evidence).append("\n\n");
                prompt.append("RECENT CONVERSATION:\n").append(context).append("\nUSER: ").append(q);
                result=nativeGenerateStreaming(prompt.toString(),outputTokens);
                if(result.trim().isEmpty()&&!cancelRequested)result="No visible output. Check model compatibility or shorten the question.";
            }catch(Throwable ex){result="Inference failed: "+ex.getMessage();}
            String answer=result;
            runUI(()->{
                setBusy(false,"● Model ready");
                if(streamingBubble!=null){streamingBubble.setText(answer);streamingBubble=null;}
                messages.add(new String[]{"assistant",answer});
                db.execSQL("INSERT INTO messages(role,body) VALUES(?,?)",new Object[]{"assistant",answer});
                scrollBottom();
            });
        });
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
        TextView desc=text("Import books once, then ask questions across multiple sources in Chat. Files stay on your phone.",14,MUTED,false);
        pad(desc,0,9,0,16);page.addView(desc);
        page.addView(button("＋ Import books (PDF, EPUB, DOCX, TXT)",this::selectBooks),lp(-1,52));
        page.addView(button("⊞ Import entire book folder",()->{
            Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i,PICK_BOOK_FOLDER);
        }),lp(-1,52));
        bookCount=text("",14,MINT,false);pad(bookCount,5,17,0,15);page.addView(bookCount);
        updateBookCount();
        TextView help=text("Imported originals are archived privately on this phone. The app uses relevant passages to answer practical questions, not to replace conversation. Scanned PDFs still require OCR.",14,MUTED,false);
        page.addView(help);
        page.addView(button("Clear book index",()->new AlertDialog.Builder(this).setTitle("Remove index?")
            .setMessage("This deletes searchable passages only; your original book files remain untouched.")
            .setNegativeButton("Cancel",null).setPositiveButton("Remove",(d,w)->{db.execSQL("DELETE FROM passages");updateBookCount();}).show()),lp(-1,48));
    }
    private void updateBookCount(){if(bookCount==null)return;
        try(Cursor c=db.rawQuery("SELECT COUNT(DISTINCT book) FROM passages",null)){
            if(c.moveToFirst())bookCount.setText(c.getInt(0)+" books indexed");
        }
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
                verifyGGUF(part);if(target.exists() && !target.delete())throw new IOException("Cannot replace old model");
                if(!part.renameTo(target))throw new IOException("Cannot finish model import");
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
                verifyGGUF(part);
                if(target.exists() && !target.delete())throw new IOException("Cannot replace old model");
                if(!part.renameTo(target))throw new IOException("Failed to save model");
                loadModelOnWorker(target);
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
    private void loadModel(File f){setBusy(true,"● Loading on-device model…");jobs.execute(()->loadModelOnWorker(f));}
    private void loadModelOnWorker(File f){
        runUI(()->setBusy(true,"● Loading on-device model…"));
        String err;
        try{err=nativeLoad(f.getAbsolutePath());}catch(Throwable ex){err=ex.toString();}
        boolean ok="OK".equals(err);
        final String errorMessage=err;
        runUI(()->{ready=ok;modelFile=ok?f:null;modelName=ok?f.getName():"No model";
            setBusy(false,ok?"● Model ready":"● Model failed to load");
            if(!ok)new AlertDialog.Builder(this).setTitle("Model loading failed").setMessage(errorMessage).setPositiveButton("Choose model",(d,w)->modelDialog()).setNegativeButton("Close",null).show();
        });
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
        TextView info=text("Everything here is saved on your phone. Behavioral corrections influence future prompts; they do not retrain the GGUF model.",15,MUTED,false);
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
        content.addView(button("Choose abliterated GGUF",this::modelDialog),lp(-1,49));
        content.addView(text("Existing Termux books and memories",17,MINT,true));
        content.addView(button("Import Termux migration",()->{
            Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(i,PICK_MIGRATION);
        }),lp(-1,49));
        content.addView(text("Offline runtime: llama.cpp in this APK. No local HTTP server, pairing or Termux needed after importing your GGUF.",14,MUTED,false));
    }

    private void importMigration(Uri uri){
        setBusy(true,"● Restoring Termux knowledge…");
        jobs.execute(()->{
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
                    if(c.moveToFirst())throw new IOException("This migration file is already imported");
                }
                db.beginTransaction();
                try(BufferedReader br=new BufferedReader(new InputStreamReader(new FileInputStream(tmp),StandardCharsets.UTF_8),32768)){
                    String line;
                    while((line=br.readLine())!=null){
                        if(line.length()>1000000)throw new IOException("Migration line too long");
                        if(line.isEmpty())continue;
                        JSONObject o=new JSONObject(line);String type=o.optString("type","");
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
            }catch(Exception ex){String err=ex.getMessage();runUI(()->{setBusy(false,"● Migration failed");notify("Migration: "+err);});}
            finally{if(tmp!=null)tmp.delete();}
        });
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
}
