package app.localdrop
import android.app.Activity
import android.os.Bundle
import android.widget.TextView
class ShareActivity: Activity() {
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        val screen=Desk.column(this).apply{setBackgroundColor(Desk.canvas);setPadding(Desk.dp(this@ShareActivity,24),Desk.dp(this@ShareActivity,28),Desk.dp(this@ShareActivity,24),Desk.dp(this@ShareActivity,24));fitsSystemWindows=true}
        screen.addView(Desk.micro(this,"LOCALDROP / TRANSFER",Desk.orange))
        screen.addView(Desk.label(this,"On its way.",30f,Desk.paper,true),android.widget.LinearLayout.LayoutParams(-1,-2).apply{topMargin=Desk.dp(this@ShareActivity,24)})
        screen.addView(Desk.label(this,"Sending to your trusted laptop…",14f,Desk.muted),android.widget.LinearLayout.LayoutParams(-1,-2).apply{topMargin=Desk.dp(this@ShareActivity,8)})
        setContentView(screen)
        Thread {
            val result=runCatching { Queue.enqueue(this,intent); Queue.sendAll(this) }
            runOnUiThread {
                val message=result.fold({if(it.isEmpty()) "Copied to Windows clipboard" else "Saved to queue · laptop unavailable"},{it.message ?: "Share failed"})
                if(result.isSuccess && result.getOrDefault("").isNotEmpty()) Alerts.show(this,"Saved for later","Your laptop is unavailable. This share stays queued on your phone.")
                android.widget.Toast.makeText(this,message,android.widget.Toast.LENGTH_LONG).show(); finish()
            }
        }.start()
    }
}
