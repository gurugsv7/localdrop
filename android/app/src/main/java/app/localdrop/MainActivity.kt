package app.localdrop

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity(), SensorEventListener {
    private val ink=Desk.paper
    private val muted=Desk.muted
    private val accent=Desk.orange
    private val canvasColor=Desk.canvas
    private lateinit var status:TextView
    private lateinit var badge:TextView
    private lateinit var queueInfo:TextView
    private lateinit var alertInfo:TextView
    private lateinit var editor:EditText
    private lateinit var pairing:LinearLayout
    private lateinit var managePairing:Button
    private var shakeEnabled=false
    private var threshold=2.5f
    private var lastShake=0L
    private val sensors by lazy {getSystemService(SensorManager::class.java)}
    private val preferences by lazy {Queue.prefs(this)}
    private val listener=SharedPreferences.OnSharedPreferenceChangeListener {_,_->runOnUiThread {update()}}

    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun shape(color:Int,stroke:Boolean=false)=GradientDrawable().apply {
        setColor(color);cornerRadius=dp(8).toFloat()
        if(stroke)setStroke(dp(1),Desk.line)
    }
    private fun column(parent:LinearLayout,card:Boolean=false):LinearLayout {
        val view=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            if(card){background=shape(Desk.panel,true);setPadding(dp(18),dp(18),dp(18),dp(18))}
        }
        parent.addView(view,LinearLayout.LayoutParams(-1,-2).apply {if(card)topMargin=dp(16)})
        return view
    }
    private fun text(parent:LinearLayout,value:String,size:Float=14f,bold:Boolean=false):TextView {
        val view=TextView(this).apply {text=value;textSize=size;setTextColor(if(size<14)muted else ink);if(bold)setTypeface(typeface,Typeface.BOLD);setPadding(0,dp(3),0,dp(7))}
        parent.addView(view);return view
    }
    private fun button(parent:LinearLayout,label:String,primary:Boolean=false,action:()->Unit):Button {
        val view=Desk.button(this,label,action).apply {
            if(primary){Desk.clickable(this,Desk.orange);setTextColor(Desk.canvas)}
        }
        parent.addView(view,LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(9)})
        return view
    }
    private fun message(value:String)=Toast.makeText(this,value,Toast.LENGTH_LONG).show()

    override fun onCreate(saved:Bundle?) {
        super.onCreate(saved)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility=0
        @Suppress("DEPRECATION")
        window.statusBarColor=canvasColor
        @Suppress("DEPRECATION")
        window.navigationBarColor=canvasColor
        render(saved?.getString("draft").orEmpty())
        if(Build.VERSION.SDK_INT>=33)onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT){
            if(preferencesPage.visibility==View.VISIBLE)showPreferences(false) else finish()
        }
        if(Build.VERSION.SDK_INT>=33 && !preferences.getBoolean("notification_prompted_v2",false) && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
            preferences.edit().putBoolean("notification_prompted_v2",true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),8)
        }
    }
    override fun onSaveInstanceState(out:Bundle){if(::editor.isInitialized)out.putString("draft",editor.text.toString());super.onSaveInstanceState(out)}
    override fun onResume(){super.onResume();preferences.registerOnSharedPreferenceChangeListener(listener);update();registerShake();confirmPairing(this)}
    override fun onPause(){preferences.unregisterOnSharedPreferenceChangeListener(listener);sensors.unregisterListener(this);super.onPause()}
    private fun registerShake(){sensors.unregisterListener(this);if(shakeEnabled)sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let{sensors.registerListener(this,it,100000)}}
    override fun onAccuracyChanged(sensor:Sensor?,accuracy:Int){}
    override fun onSensorChanged(event:SensorEvent){
        val g=kotlin.math.sqrt(event.values.sumOf{(it*it).toDouble()}).toFloat()/SensorManager.GRAVITY_EARTH
        val now=android.os.SystemClock.elapsedRealtime()
        if(g>threshold&&now-lastShake>3000){lastShake=now;sendCopied()}
    }
    private fun update(){
        if(!::status.isInitialized)return
        val paired=preferences.contains("pair")
        badge.text=android.text.SpannableString(if(paired)"●  LAPTOP PAIRED" else "○  LAPTOP NOT PAIRED").apply{
            setSpan(android.text.style.ForegroundColorSpan(if(paired)Desk.green else Desk.orange),0,1,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        badge.setTextColor(Desk.paper)
        pairedDetail.text=if(paired)"Trusted over local Wi-Fi" else "Scan once to get started"
        managePairing.text=if(paired)"Manage pairing ↗" else "Pair your laptop ↗"
        status.text=preferences.getString("status",if(paired)"Trusted device. Ready for your next share." else "Scan once. Share whenever you need.")
        managePairing.visibility=View.VISIBLE
        if(!paired)pairing.visibility=View.VISIBLE
        val pending=Queue.pending(this).count{!File(it,"sent").exists()}
        val bytes=Queue.root(this).walkTopDown().filter{it.isFile}.sumOf{it.length()}
        queueInfo.text="$pending waiting · ${bytes/1024/1024} MiB stored on this phone"
        waitingCount.text="$pending WAITING"
        queueTitle.text=if(pending==0)"All caught up." else "$pending share(s) waiting for your laptop."
        alertInfo.text=when {
            !Alerts.permitted(this)->"Allow notifications to get receive and copy confirmations."
            !preferences.getBoolean("notifications",true)->"Transfer notifications are switched off."
            else->"Notifications on · only transfer updates, no shared content."
        }
    }
    private fun sendText(value:String){
        if(value.isBlank()){message("Type or copy some text first");return}
        if(!preferences.contains("pair")){message("Pair your laptop first");return}
        startActivity(Intent(this,ShareActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,value))
    }
    private fun sendCopied(){
        val clip=getSystemService(ClipboardManager::class.java).primaryClip
        val value=if(clip!=null&&clip.itemCount>0)clip.getItemAt(0).text?.toString() else null
        if(value.isNullOrBlank())message("Your clipboard has no text. Copy something first.") else sendText(value)
    }
    private fun notifications(){
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),8);return}
        if(!Alerts.permitted(this)) {
            startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,packageName));return
        }
        preferences.edit().putBoolean("notifications",true).apply()
        Alerts.show(this,"LocalDrop is ready","You’ll get a confirmation when a transfer is received or copied.")
        update()
    }
    override fun onRequestPermissionsResult(code:Int,p:Array<out String>,g:IntArray){
        super.onRequestPermissionsResult(code,p,g)
        if(code==8){if(g.firstOrNull()==PackageManager.PERMISSION_GRANTED){preferences.edit().putBoolean("notifications",true).apply();Alerts.show(this,"Notifications enabled","Transfer confirmations will appear here.")};update()}
    }

    private lateinit var homePage:View
    private lateinit var preferencesPage:View
    private lateinit var queueTitle:TextView
    private lateinit var waitingCount:TextView
    private lateinit var pairedDetail:TextView

    private fun space(parent:LinearLayout,height:Int){parent.addView(View(this),LinearLayout.LayoutParams(1,dp(height)))}
    private fun line(parent:LinearLayout){parent.addView(Desk.divider(this))}
    private fun inset(parent:LinearLayout,child:View,top:Int=0){parent.addView(child,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(top)})}
    private fun icon(kind:String,size:Int=24,color:Int=Desk.paper)=DeskIcon(this,kind,color).apply{layoutParams=LinearLayout.LayoutParams(dp(size),dp(size))}
    private fun showPreferences(show:Boolean){
        homePage.visibility=if(show)View.GONE else View.VISIBLE
        preferencesPage.visibility=if(show)View.VISIBLE else View.GONE
        registerShake()
    }
    @Deprecated("Native Activity back callback")
    override fun onBackPressed(){if(::preferencesPage.isInitialized&&preferencesPage.visibility==View.VISIBLE)showPreferences(false) else super.onBackPressed()}

    private fun receive(){
        if(!preferences.contains("pair")){message("Pair your laptop first");showPreferences(true);return}
        status.text="Checking your laptop…"
        Thread{
            val result=runCatching{Native.pull(preferences.getString("pair","")!!,File(filesDir,"inbox").absolutePath)}.getOrElse{"error:${it.message}"}
            runOnUiThread{
                if(isFinishing||isDestroyed)return@runOnUiThread
                if(result.startsWith("error:")){status.text=result.removePrefix("error:");message("Laptop unavailable. Try again on the same Wi-Fi.")}
                else if(result.isEmpty()){status.text="No pending Windows transfers";message("No pending Windows transfers")}
                else{
                    val m=JSONObject(result);val count=m.getJSONArray("items").length()
                    if(!m.isNull("text"))getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("LocalDrop",m.getString("text")))
                    val summary=if(count==0)"Text received and copied to your clipboard." else "$count file(s) received. Open them in Preferences."
                    preferences.edit().putString("status",summary).apply();Alerts.show(this,"Received from Windows",summary);update()
                }
            }
        }.start()
    }

    private fun render(draft:String){
        val frame=FrameLayout(this).apply{setBackgroundColor(canvasColor);fitsSystemWindows=true}
        val home=Desk.column(this).apply{setPadding(dp(18),dp(12),dp(18),dp(20))}
        val homeScroll=ScrollView(this).apply{isFillViewport=true;isVerticalScrollBarEnabled=false;addView(home)}
        homePage=homeScroll;frame.addView(homeScroll,FrameLayout.LayoutParams(-1,-1))
        val prefs=Desk.column(this).apply{setPadding(dp(18),dp(12),dp(18),dp(24))}
        val prefsScroll=ScrollView(this).apply{isFillViewport=true;isVerticalScrollBarEnabled=false;visibility=View.GONE;addView(prefs)}
        preferencesPage=prefsScroll;frame.addView(prefsScroll,FrameLayout.LayoutParams(-1,-1))

        val header=Desk.row(this)
        header.addView(icon("mark",32,Desk.canvas).apply{background=Desk.shape(this@MainActivity,Desk.orange,false,3);setPadding(dp(5),dp(5),dp(5),dp(5))})
        header.addView(Desk.label(this,"LocalDrop",21f,Desk.paper,true),LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=dp(12)})
        header.addView(FrameLayout(this).apply{
            contentDescription="Preferences";Desk.clickable(this,Desk.canvas);setOnClickListener{showPreferences(true)}
            addView(DeskIcon(this@MainActivity,"gear"),FrameLayout.LayoutParams(dp(24),dp(24),android.view.Gravity.CENTER))
        },LinearLayout.LayoutParams(dp(44),dp(44)))
        home.addView(header)
        space(home,20)
        home.addView(Desk.micro(this,"YOUR CONNECTION"))
        inset(home,Desk.label(this,"Within reach.",30f,Desk.paper,true),7)
        inset(home,Desk.label(this,"Your laptop is one tap away.",14f,Desk.muted),4)
        val connection=Desk.column(this).apply{background=Desk.shape(this@MainActivity,Desk.panel,true);setPadding(dp(14),dp(13),dp(14),dp(2))}
        inset(home,connection,18)
        badge=Desk.micro(this,"").apply{setTextColor(Desk.paper)}
        connection.addView(badge)
        val device=Desk.row(this)
        device.addView(icon("laptop",39))
        val names=Desk.column(this)
        names.addView(Desk.label(this,"Windows laptop",15f,Desk.paper,true))
        pairedDetail=Desk.label(this,"Trusted over local Wi-Fi",11f,Desk.muted)
        inset(names,pairedDetail,4)
        device.addView(names,LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=dp(12)})
        device.addView(DeskRoute(this),LinearLayout.LayoutParams(dp(88),dp(40)).apply{leftMargin=dp(6)})
        inset(connection,device,14);space(connection,13);line(connection)
        managePairing=Button(this).apply{
            text="Manage pairing ↗";isAllCaps=false;textSize=12f;letterSpacing=0f;gravity=android.view.Gravity.CENTER_VERTICAL
            setTextColor(Desk.paper);setPadding(0,dp(8),0,dp(8));minimumHeight=dp(40);minHeight=dp(40);stateListAnimator=null
            Desk.clickable(this,Desk.panel);setOnClickListener{showPreferences(true);pairing.visibility=View.VISIBLE}
        }
        connection.addView(managePairing,LinearLayout.LayoutParams(-1,-2))

        val quick=Desk.row(this).apply{
            setPadding(dp(17),dp(17),dp(14),dp(17));minimumHeight=dp(78)
            Desk.clickable(this,Desk.orange);contentDescription="Send copied text. From this phone to your laptop"
            setOnClickListener{sendCopied()}
        }
        quick.addView(icon("clipboard",30,Desk.canvas))
        val quickText=Desk.column(this)
        quickText.addView(Desk.label(this,"Send copied text",20f,Desk.canvas,true))
        inset(quickText,Desk.label(this,"From this phone to your laptop",11f,Desk.canvas),4)
        quick.addView(quickText,LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=dp(16)})
        quick.addView(icon("arrow",22,Desk.canvas))
        inset(home,quick,16)

        val compose=Desk.column(this).apply{background=Desk.shape(this@MainActivity,Desk.paper);setPadding(dp(15),dp(14),dp(15),dp(1))}
        compose.addView(Desk.micro(this,"OR WRITE SOMETHING",Desk.canvas))
        editor=EditText(this).apply{
            hint="A link, a line, a little something…";setText(draft);textSize=15f;minLines=1;maxLines=5
            setTextColor(Desk.canvas);setHintTextColor(Color.rgb(105,110,101));background=null
            setPadding(0,dp(13),0,dp(12));gravity=android.view.Gravity.TOP
            inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            if(Build.VERSION.SDK_INT>=29)textCursorDrawable?.setTint(Desk.canvas)
        }
        compose.addView(editor,LinearLayout.LayoutParams(-1,-2))
        compose.addView(View(this).apply{setBackgroundColor(Color.rgb(138,145,131))},LinearLayout.LayoutParams(-1,dp(1)))
        compose.addView(Button(this).apply{
            text="Send text ↗";isAllCaps=false;textSize=13f;setTypeface(typeface,Typeface.BOLD);letterSpacing=0f
            gravity=android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL;setTextColor(Desk.canvas)
            setPadding(0,dp(8),0,dp(8));minHeight=dp(42);stateListAnimator=null;Desk.clickable(this,Desk.paper)
            setOnClickListener{sendText(editor.text.toString())}
        },LinearLayout.LayoutParams(-1,-2))
        inset(home,compose,16)

        val tools=Desk.row(this)
        tools.addView(Desk.button(this,"Choose files ↗"){startActivity(Intent(this,PickerActivity::class.java))},LinearLayout.LayoutParams(0,-2,1f))
        tools.addView(Desk.button(this,"Receive ↓"){receive()},LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=dp(12)})
        inset(home,tools,16)
        space(home,27)
        val outbox=Desk.row(this)
        outbox.addView(Desk.micro(this,"OUTBOX",Desk.paper),LinearLayout.LayoutParams(0,-2,1f))
        waitingCount=Desk.micro(this,"0 WAITING");outbox.addView(waitingCount)
        home.addView(outbox);space(home,9);line(home);space(home,13)
        queueTitle=Desk.label(this,"All caught up.",14f);home.addView(queueTitle)
        inset(home,Desk.label(this,"Shares retry when your laptop is nearby.",12f,Desk.muted),5)
        status=Desk.label(this,"",11f,Desk.muted).apply{setLineSpacing(dp(2).toFloat(),1f)}
        inset(home,status,7)
        space(home,25);line(home);space(home,14)
        home.addView(Desk.label(this,"",11f,Desk.muted).apply{text=android.text.SpannableString("●   Local. Encrypted. Yours.").apply{setSpan(android.text.style.ForegroundColorSpan(Desk.green),0,1,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)}})

        val prefHeader=Desk.row(this)
        prefHeader.addView(FrameLayout(this).apply{
            contentDescription="Back to overview";Desk.clickable(this,Desk.canvas);setOnClickListener{showPreferences(false)}
            addView(DeskIcon(this@MainActivity,"back"),FrameLayout.LayoutParams(dp(23),dp(23),android.view.Gravity.CENTER))
        },LinearLayout.LayoutParams(dp(44),dp(44)))
        prefHeader.addView(Desk.label(this,"Preferences",21f,Desk.paper,true),LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=dp(8)})
        prefs.addView(prefHeader);space(prefs,20)
        prefs.addView(Desk.micro(this,"MAKE IT YOURS"))
        inset(prefs,Desk.label(this,"A little less friction.",26f,Desk.paper,true),8)
        inset(prefs,Desk.label(this,"Your devices. Your way.",13f,Desk.muted),6)

        val pairCard=column(prefs,true)
        text(pairCard,"PAIRING",10f,true).setTextColor(accent)
        text(pairCard,"Your trusted laptop",18f,true)
        text(pairCard,"Pair once. Future shares use your saved local connection.",12f)
        pairing=column(pairCard)
        button(pairing,"Scan laptop QR",true){startActivity(Intent(this,ScanActivity::class.java))}
        button(pairing,"Enter pairing code"){
            val input=EditText(this).apply{hint="Pairing JSON";inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE;setPadding(dp(16),dp(12),dp(16),dp(12));importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO}
            android.app.AlertDialog.Builder(this).setTitle("Pair laptop").setView(input).setPositiveButton("Pair"){_,_->runCatching{accept(this,input.text.toString())}.onFailure{message(it.message?:"Pairing failed")};update()}.setNegativeButton("Cancel",null).show()
        }

        val transferOptions=column(prefs,true)
        text(transferOptions,"TRANSFERS",10f,true).setTextColor(accent)
        queueInfo=text(transferOptions,"",12f)
        button(transferOptions,"Retry waiting shares"){
            Thread{val error=runCatching{Queue.sendAll(this)}.getOrElse{it.message?:"Transfer failed"};runOnUiThread{if(!isDestroyed){update();message(if(error.isEmpty())"All queued shares sent" else error)}}}.start()
        }
        button(transferOptions,"Open received files"){openReceived()}
        text(transferOptions,"Tip: select text in another app → ⋮ → LocalDrop. Or share images, links and files from the Android Share menu.",12f)

        val notify=column(prefs,true)
        text(notify,"NOTIFICATIONS",10f,true).setTextColor(accent)
        alertInfo=text(notify,"",12f)
        button(notify,"Enable / test notifications"){notifications()}
        notify.addView(Switch(this).apply{
            text="Transfer confirmations";textSize=14f;setTextColor(Desk.paper)
            thumbTintList=ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(Desk.orange,Desk.muted))
            isChecked=preferences.getBoolean("notifications",true);setPadding(0,dp(14),0,dp(5))
            setOnCheckedChangeListener{_,checked->preferences.edit().putBoolean("notifications",checked).apply();if(checked&&!Alerts.permitted(this@MainActivity))notifications();update()}
        })

        val gestures=column(prefs,true)
        text(gestures,"SHAKE SHORTCUT",10f,true).setTextColor(accent)
        shakeEnabled=preferences.getBoolean("shake",false);threshold=preferences.getFloat("sensitivity",2.5f)
        gestures.addView(Switch(this).apply{
            text="Shake to send copied text";textSize=14f;setTextColor(Desk.paper);isChecked=shakeEnabled
            thumbTintList=ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(Desk.orange,Desk.muted))
            setOnCheckedChangeListener{_,checked->shakeEnabled=checked;preferences.edit().putBoolean("shake",checked).apply();registerShake()}
        })
        text(gestures,"Only while LocalDrop is open. No background clipboard monitoring. Left = more sensitive.",12f)
        gestures.addView(SeekBar(this).apply{
            contentDescription="Shake sensitivity";progressTintList=ColorStateList.valueOf(Desk.orange);thumbTintList=ColorStateList.valueOf(Desk.orange)
            max=25;progress=((threshold-1.5f)*10).toInt()
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                override fun onProgressChanged(s:SeekBar?,p:Int,user:Boolean){threshold=1.5f+p/10f;preferences.edit().putFloat("sensitivity",threshold).apply()}
                override fun onStartTrackingTouch(s:SeekBar?){};override fun onStopTrackingTouch(s:SeekBar?){}
            })
        })

        val storage=column(prefs,true)
        text(storage,"LOCAL STORAGE",10f,true).setTextColor(accent)
        text(storage,"Originals in other apps are always kept. Sent copies stay on this phone until you choose to remove them.",12f)
        button(storage,"Clear sent outbox copies"){
            android.app.AlertDialog.Builder(this).setMessage("Remove local copies already acknowledged by Windows? Originals and pending shares stay.").setPositiveButton("Remove"){_,_->Queue.pending(this).filter{File(it,"sent").exists()}.forEach{it.deleteRecursively()};update()}.setNegativeButton("Keep",null).show()
        }
        button(storage,"Cancel pending / incomplete shares"){
            android.app.AlertDialog.Builder(this).setMessage("Cancel queued shares and remove local unsent copies? Originals in other apps stay. Wait for an active share to finish first.").setPositiveButton("Cancel shares"){_,_->
                Native.cancel();getSystemService(android.app.job.JobScheduler::class.java).cancel(45871)
                Thread{synchronized(Queue){Queue.root(this).listFiles()?.filter{!File(it,"sent").exists()}?.forEach{it.deleteRecursively()}};runOnUiThread{if(!isDestroyed)update()}}.start()
            }.setNegativeButton("Keep",null).show()
        }
        space(prefs,22)
        prefs.addView(Desk.micro(this,"LOCALDROP / 0.3"))
        inset(prefs,Desk.label(this,"No accounts. No cloud. No always-on phone service. Android may defer queued retries while idle.",12f,Desk.muted),8)
        setContentView(frame)
        if(!preferences.contains("pair"))showPreferences(true)
        update()
    }

    private fun openReceived(){
        val batches=File(filesDir,"inbox").listFiles()?.filter{File(it,"receipt.json").exists()}?.sortedByDescending{it.lastModified()}?:emptyList()
        val files=batches.flatMap{dir->dir.listFiles()?.filter{it.name!="receipt.json"}?:emptyList()}
        if(files.isEmpty()){message("No received files yet");return}
        android.app.AlertDialog.Builder(this).setTitle("Received files").setItems(files.map{it.name}.toTypedArray()){_,i->
            val file=files[i];val uri=android.net.Uri.Builder().scheme("content").authority("app.localdrop.received").appendPath(file.parentFile!!.name).appendPath(file.name).build()
            val mime=android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())?:"application/octet-stream"
            runCatching{startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))}.onFailure{message("No app can open this type")}
        }.setNegativeButton("Close",null).show()
    }
    companion object {
        private val confirming=AtomicBoolean(false)
        fun confirmPairing(c:Context){
            val prefs=Queue.prefs(c);val pair=prefs.getString("pair",null)?:return
            if(prefs.getBoolean("confirmed_v2",false)||!confirming.compareAndSet(false,true))return
            val app=c.applicationContext
            Thread{
                try {
                    val error=runCatching{Native.confirm(pair,Build.MODEL.take(20).ifBlank{"Android phone"})}.getOrDefault("unavailable")
                    if(error.isEmpty())prefs.edit().putBoolean("confirmed_v2",true).putString("status","Laptop paired. Ready to share.").apply()
                } finally {confirming.set(false)}
            }.start()
        }
        fun accept(c:Context,raw:String){
            val p=JSONObject(raw)
            require(p.getInt("version")==1&&p.getInt("port")==45871&&p.getString("secret").matches(Regex("[0-9a-f]{64}"))&&p.getString("id").matches(Regex("[0-9a-f]{32}"))){"Invalid LocalDrop QR"}
            val secret=p.getString("secret").chunked(2).map{it.toInt(16).toByte()}.toByteArray()
            val id=java.security.MessageDigest.getInstance("SHA-256").digest(secret).take(16).joinToString(""){"%02x".format(it)}
            require(p.getString("id")==id){"Pairing identity mismatch"}
            require(Queue.pending(c).none{!File(it,"sent").exists()}){"Finish pending shares before changing laptops"}
            Queue.prefs(c).edit().putString("pair",p.toString()).putBoolean("confirmed_v2",false).putString("status","Laptop paired. Ready to share.").apply()
            confirmPairing(c)
        }
    }
}
