package app.localdrop
import android.Manifest
import android.app.Activity
import android.os.Bundle
import android.hardware.Camera
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.content.pm.PackageManager
import android.widget.Toast
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer

@Suppress("DEPRECATION")
class ScanActivity:Activity(),SurfaceHolder.Callback {
    private var camera:Camera?=null
    private var done=false
    private var last=0L
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.CAMERA),7)
    }
    override fun onRequestPermissionsResult(r:Int,p:Array<out String>,g:IntArray) {super.onRequestPermissionsResult(r,p,g);if(g.firstOrNull()==PackageManager.PERMISSION_GRANTED) preview() else finish()}
    private fun preview() {
        val frame=android.widget.FrameLayout(this).apply{setBackgroundColor(Desk.canvas);fitsSystemWindows=true}
        frame.addView(SurfaceView(this).apply{holder.addCallback(this@ScanActivity)},android.widget.FrameLayout.LayoutParams(-1,-1))
        val heading=Desk.column(this).apply{setPadding(Desk.dp(this@ScanActivity,22),Desk.dp(this@ScanActivity,22),Desk.dp(this@ScanActivity,22),Desk.dp(this@ScanActivity,18));setBackgroundColor(android.graphics.Color.argb(225,25,28,27))}
        heading.addView(Desk.micro(this,"LOCALDROP / PAIRING",Desk.orange))
        heading.addView(Desk.label(this,"Meet your laptop.",26f,Desk.paper,true),android.widget.LinearLayout.LayoutParams(-1,-2).apply{topMargin=Desk.dp(this@ScanActivity,12)})
        heading.addView(Desk.label(this,"Point at the QR code in Windows LocalDrop.",13f,Desk.muted),android.widget.LinearLayout.LayoutParams(-1,-2).apply{topMargin=Desk.dp(this@ScanActivity,6)})
        frame.addView(heading,android.widget.FrameLayout.LayoutParams(-1,-2,android.view.Gravity.TOP))
        val note=Desk.label(this,"Only scan your own laptop. This code creates a trusted connection.",12f,Desk.paper).apply{setPadding(Desk.dp(this@ScanActivity,22),Desk.dp(this@ScanActivity,18),Desk.dp(this@ScanActivity,22),Desk.dp(this@ScanActivity,18));setBackgroundColor(android.graphics.Color.argb(225,25,28,27))}
        frame.addView(note,android.widget.FrameLayout.LayoutParams(-1,-2,android.view.Gravity.BOTTOM))
        setContentView(frame)
    }
    override fun surfaceCreated(holder:SurfaceHolder) {
        runCatching {
            camera=Camera.open().also { c ->
                val params=c.parameters
                params.supportedPreviewSizes.filter { it.width<=1280 }.minByOrNull { kotlin.math.abs(it.width-640) }?.let { params.setPreviewSize(it.width,it.height) }
                if(params.supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) params.focusMode=Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
                c.parameters=params;c.setDisplayOrientation(90);c.setPreviewDisplay(holder)
                val size=c.parameters.previewSize
                c.setPreviewCallback { bytes,_ ->
                    val now=android.os.SystemClock.elapsedRealtime(); if(done || now-last<400) return@setPreviewCallback;last=now
                    try {
                        val source=PlanarYUVLuminanceSource(bytes,size.width,size.height,0,0,size.width,size.height,false)
                        val result=MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)),mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
                        MainActivity.accept(this,result.text);done=true;Toast.makeText(this,"Laptop paired",Toast.LENGTH_SHORT).show();finish()
                    } catch(_:NotFoundException) {} catch(e:Exception) { if(e !is com.google.zxing.ReaderException) {Toast.makeText(this,e.message,Toast.LENGTH_LONG).show();done=true;finish()} }
                };c.startPreview()
            }
        }.onFailure {Toast.makeText(this,"Camera unavailable: ${it.message}",Toast.LENGTH_LONG).show();finish()}
    }
    override fun surfaceChanged(h:SurfaceHolder,f:Int,w:Int,height:Int) {}
    override fun surfaceDestroyed(h:SurfaceHolder) {release()}
    private fun release(){camera?.setPreviewCallback(null);camera?.stopPreview();camera?.release();camera=null}
    override fun onResume(){super.onResume();if(!done && checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) preview()}
    override fun onPause(){release();super.onPause()}
}
