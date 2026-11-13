package app.localdrop

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Small native design primitives. No bitmap screens, layout library or animation loop. */
internal object Desk {
    val canvas=Color.rgb(25,28,27)
    val panel=Color.rgb(32,37,34)
    val paper=Color.rgb(238,234,224)
    val muted=Color.rgb(165,173,165)
    val orange=Color.rgb(247,132,92)
    val line=Color.rgb(80,88,79)
    val action=Color.rgb(48,58,50)
    val actionBorder=Color.rgb(137,150,131)
    val green=Color.rgb(141,222,112)
    fun dp(c:Context,n:Int)=(n*c.resources.displayMetrics.density+.5f).toInt()
    fun shape(c:Context,color:Int,border:Boolean=false,radius:Int=8)=GradientDrawable().apply {
        setColor(color);cornerRadius=dp(c,radius).toFloat()
        if(border)setStroke(dp(c,1),line)
    }
    fun clickable(v:View,color:Int,border:Boolean=false){
        val surface=shape(v.context,color,border)
        if(border)surface.setStroke(dp(v.context,1),actionBorder)
        v.background=RippleDrawable(ColorStateList.valueOf(Color.argb(65,238,234,224)),surface,shape(v.context,Color.WHITE))
        v.isFocusable=true
    }
    fun column(c:Context)=LinearLayout(c).apply{orientation=LinearLayout.VERTICAL}
    fun row(c:Context)=LinearLayout(c).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
    fun label(c:Context,value:String,size:Float=14f,color:Int=paper,bold:Boolean=false)=TextView(c).apply {
        text=value;textSize=size;setTextColor(color);includeFontPadding=false
        typeface=Typeface.create("sans-serif",if(bold)Typeface.BOLD else Typeface.NORMAL)
    }
    fun micro(c:Context,value:String,color:Int=muted)=label(c,value,10f,color,true).apply{letterSpacing=.14f}
    fun button(c:Context,value:String,action:()->Unit)=Button(c).apply {
        text=value;isAllCaps=false;textSize=13f;letterSpacing=0f
        typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
        setTextColor(paper);minWidth=0;minimumWidth=0;minHeight=dp(c,46)
        setPadding(dp(c,12),dp(c,8),dp(c,12),dp(c,8))
        stateListAnimator=null;elevation=0f;clickable(this,Desk.action,true);setOnClickListener{action()}
    }
    fun divider(c:Context)=View(c).apply{setBackgroundColor(line);layoutParams=LinearLayout.LayoutParams(-1,dp(c,1))}
}

internal class DeskIcon @JvmOverloads constructor(c:Context,private val kind:String="mark",private val tint:Int=Desk.paper):View(c){
    private val pen=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeWidth=1.7f;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND;color=tint}
    private val path=Path()
    init{importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
    override fun onDraw(canvas:Canvas){
        super.onDraw(canvas)
        val scale=minOf(width,height)/24f
        canvas.save();canvas.translate((width-24*scale)/2,(height-24*scale)/2);canvas.scale(scale,scale)
        fun line(a:Float,b:Float,c:Float,d:Float)=canvas.drawLine(a,b,c,d,pen)
        fun arrows(){line(14f,3f,21f,3f);line(21f,3f,21f,10f);line(21f,3f,13f,11f);line(10f,21f,3f,21f);line(3f,21f,3f,14f);line(3f,21f,11f,13f)}
        when(kind){
            "mark"->arrows()
            "clipboard"->{canvas.drawRoundRect(4f,5f,20f,22f,1f,1f,pen);canvas.drawRect(9f,2f,15f,7f,pen)}
            "laptop"->{canvas.drawRoundRect(4f,4f,20f,17f,1f,1f,pen);line(1f,20f,23f,20f)}
            "phone"->{canvas.drawRoundRect(6f,1f,18f,23f,2f,2f,pen);line(10f,19f,14f,19f)}
            "arrow"->{line(6f,18f,18f,6f);line(8f,6f,18f,6f);line(18f,6f,18f,16f)}
            "down"->{line(12f,3f,12f,20f);line(5f,13f,12f,20f);line(12f,20f,19f,13f)}
            "back"->{line(20f,12f,4f,12f);line(4f,12f,11f,5f);line(4f,12f,11f,19f)}
            "gear"->{
                path.reset()
                for(i in 0..31){val a=(i*2*Math.PI/32);val r=if(i%4==0||i%4==3)10.5 else 8.0;val x=(12+kotlin.math.cos(a)*r).toFloat();val y=(12+kotlin.math.sin(a)*r).toFloat();if(i==0)path.moveTo(x,y)else path.lineTo(x,y)}
                path.close();canvas.drawPath(path,pen);canvas.drawCircle(12f,12f,3.4f,pen)
            }
        }
        canvas.restore()
    }
}

internal class DeskRoute(c:Context):View(c){
    private val pen=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Desk.muted;style=Paint.Style.STROKE;strokeWidth=Desk.dp(c,1).toFloat();strokeCap=Paint.Cap.ROUND}
    override fun onDraw(c:Canvas){
        val cy=height/2f;val center=width*.42f;val unit=resources.displayMetrics.density
        var x=0f;while(x<width-21*unit){if(kotlin.math.abs(x-center)>13*unit)c.drawLine(x,cy,x+4*unit,cy,pen);x+=9*unit}
        pen.color=Desk.orange;pen.strokeWidth=1.8f*unit
        val a=center-6*unit;val b=cy+6*unit;val d=center+6*unit;val e=cy-6*unit
        c.drawLine(a,b,d,e,pen);c.drawLine(d-6*unit,e,d,e,pen);c.drawLine(d,e,d,e+6*unit,pen)
        c.drawLine(a,b,a+6*unit,b,pen);c.drawLine(a,b,a,b-6*unit,pen)
        pen.color=Desk.paper;pen.strokeWidth=1.5f*unit
        c.drawRoundRect(width-15*unit,cy-13*unit,width-2*unit,cy+13*unit,2*unit,2*unit,pen)
        c.drawLine(width-10*unit,cy+9*unit,width-7*unit,cy+9*unit,pen)
        pen.color=Desk.muted;pen.strokeWidth=unit
    }
}
