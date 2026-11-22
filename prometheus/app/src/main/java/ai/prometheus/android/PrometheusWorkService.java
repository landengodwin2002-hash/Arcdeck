package ai.prometheus.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.content.Context;


public final class PrometheusWorkService extends Service {
    private static int holds;
    private static volatile boolean running;
    static synchronized int activeHolds(){return holds;}
    static boolean isRunning(){return running;}
    static synchronized void begin(Context context,String reason){
        if(++holds!=1)return;
        try{context.startForegroundService(new Intent(context,PrometheusWorkService.class).putExtra("reason",reason));}
        catch(RuntimeException error){holds--;throw error;}
    }
    static synchronized void end(Context context){
        if(holds>0)holds--;
        if(holds==0)context.stopService(new Intent(context,PrometheusWorkService.class));
    }
    private static final String CHANNEL="prometheus_background_ai";
    @Override public void onCreate(){
        super.onCreate();
        NotificationChannel channel=new NotificationChannel(CHANNEL,
            "Prometheus offline activity",NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shows active local model processing and knowledge import.");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        Intent open=new Intent(this,MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent click=PendingIntent.getActivity(this,0,open,
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        String reason=intent==null?"Working offline":intent.getStringExtra("reason");
        if(reason==null)reason="Working offline";
        Notification n=new Notification.Builder(this,CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Prometheus")
            .setContentText(reason)
            .setContentIntent(click)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build();
        startForeground(2155,n);running=true;
        return START_NOT_STICKY;
    }
    @Override public void onDestroy(){running=false;super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
