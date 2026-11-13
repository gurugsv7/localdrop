package app.localdrop
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
class ShareTile: TileService() {
    override fun onClick() {
        super.onClick()
        val intent=Intent(this,PickerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if(Build.VERSION.SDK_INT>=34) startActivityAndCollapse(PendingIntent.getActivity(this,1,intent,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        else launchLegacy(intent)
    }
    // PendingIntent overload was added in API 34. This path only runs on API 26–33.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun launchLegacy(intent:Intent) { startActivityAndCollapse(intent) }
}
