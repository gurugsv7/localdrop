package app.localdrop

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.File

object ImagePreview {
    /** Decode a screen-sized sample rather than allocating the full original. */
    fun decode(file: File): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0)
        var sample = 1
        while(bounds.outWidth.toLong() / sample * (bounds.outHeight / sample) > 2_000_000) sample *= 2
        return checkNotNull(BitmapFactory.decodeFile(file.path,
            BitmapFactory.Options().apply { inSampleSize = sample }))
    }

    fun show(activity: Activity, file: File) {
        val panel = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val status = TextView(activity).apply { text = "Loading image…"; setPadding(24,16,24,16) }
        val image = ImageView(activity).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "Received image preview" }
        panel.addView(status)
        panel.addView(image, LinearLayout.LayoutParams(-1,(activity.resources.displayMetrics.heightPixels*.55f).toInt()))
        val dialog = AlertDialog.Builder(activity).setTitle("Received image").setView(panel)
            .setPositiveButton("Close",null).setNeutralButton("Save to Gallery",null).create()
        var bitmap: Bitmap? = null
        dialog.setOnDismissListener { image.setImageDrawable(null); bitmap?.recycle(); bitmap=null }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { button ->
            button.isEnabled=false
            Thread {
                val result=runCatching { Gallery.save(activity.applicationContext,JSONObject(File(file.parentFile,"receipt.json").readText())) }
                activity.runOnUiThread {
                    if(dialog.isShowing){status.text=result.fold({"Saved to Gallery → LocalDrop"},{"Couldn’t save. Original remains in received files."});button.isEnabled=true}
                }
            }.start()
        }
        Thread {
            val result=runCatching {decode(file)}
            activity.runOnUiThread {
                if(!dialog.isShowing || activity.isDestroyed){result.getOrNull()?.recycle();return@runOnUiThread}
                result.fold({bitmap=it;image.setImageBitmap(it);status.text="Original received image"},{status.text="This image format cannot be previewed. The original is retained."})
            }
        }.start()
    }
}
