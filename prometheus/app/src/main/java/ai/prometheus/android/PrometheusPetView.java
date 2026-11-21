package ai.prometheus.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/** An original, local simulated companion. Nothing here implies that the model feels pain. */
public final class PrometheusPetView extends View {
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    private int hunger=25,mood=75,trust=60;
    private boolean busy=false;
    private long reactionUntil=0;
    private String reaction="";
    public PrometheusPetView(Context c){super(c);setLayerType(View.LAYER_TYPE_SOFTWARE,null);}
    public void setStats(int h,int m,int t,boolean working){hunger=h;mood=m;trust=t;busy=working;invalidate();}
    public void react(String action){reaction=action;reactionUntil=SystemClock.uptimeMillis()+2800;invalidate();}
    private void color(int c){p.setColor(c);p.setStyle(Paint.Style.FILL);p.setShader(null);p.setStrokeWidth(2);}
    private void oval(Canvas c,float a,float b,float d,float e,int cl){color(cl);c.drawOval(a,b,d,e,p);}
    private void circle(Canvas c,float x,float y,float r,int cl){color(cl);c.drawCircle(x,y,r,p);}
    @Override protected void onDraw(Canvas original){
        super.onDraw(original);
        int save=original.save();original.scale(getWidth()/100f,getHeight()/100f);
        Canvas c=original;
        long frame=SystemClock.uptimeMillis();boolean blink=(frame/2400)%8==0;
        boolean sad=mood<40 || hunger>=80 || "discipline".equals(reaction) && frame<reactionUntil;
        boolean happy=mood>=65 && hunger<75;
        // Dark glass tile with a soft cyan halo.
        color(0xff1b2942);c.drawRoundRect(new RectF(1,1,99,99),24,24,p);
        p.setShader(new LinearGradient(12,18,86,86,0xff97e7f8,0xffff9cdb,Shader.TileMode.CLAMP));
        c.drawRoundRect(new RectF(20,24,80,84),28,28,p);p.setShader(null);
        oval(c,26,12,42,37,0xff96e5ff);oval(c,60,12,76,37,0xffffa6de);
        oval(c,23,27,77,84,0xffc9f1f4);oval(c,30,31,70,77,0xfffce7f6);
        // Eyes and cheeks.
        int eye=0xff283455;
        if(blink){color(eye);p.setStrokeWidth(3);c.drawRoundRect(new RectF(34,48,44,51),3,3,p);c.drawRoundRect(new RectF(56,48,66,51),3,3,p);}
        else{oval(c,34,42,44,55,eye);oval(c,56,42,66,55,eye);circle(c,38,45,2,Color.WHITE);circle(c,60,45,2,Color.WHITE);}
        oval(c,24,57,36,63,0xffffa9c6);oval(c,64,57,76,63,0xffffa9c6);
        color(0xff5c557a);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2.6f);p.setStrokeCap(Paint.Cap.ROUND);
        Path mouth=new Path();
        if(sad){mouth.moveTo(43,71);mouth.quadTo(50,64,57,71);}
        else if(busy){mouth.moveTo(46,64);mouth.quadTo(50,77,54,64);}
        else if(happy){mouth.moveTo(41,65);mouth.quadTo(50,77,59,65);}
        else{mouth.moveTo(43,68);mouth.quadTo(50,72,57,68);}
        c.drawPath(mouth,p);p.setStyle(Paint.Style.FILL);
        if(sad){oval(c,32,53,36,65,0xff8fdff9);oval(c,64,53,68,65,0xff8fdff9);}
        if(frame<reactionUntil){
            color(0xffffb0e2);p.setTextSize(22);p.setTextAlign(Paint.Align.CENTER);
            c.drawText("feed".equals(reaction)?"♥":"praise".equals(reaction)?"✦":"discipline".equals(reaction)?"...":"Z",50,16,p);
        }
        original.restoreToCount(save);
        postInvalidateDelayed(580);
    }
}
