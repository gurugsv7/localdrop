package app.localdrop
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

object Queue {
    private const val MAX_BYTES = 1024L * 1024 * 1024
    fun prefs(c: Context) = c.getSharedPreferences("localdrop", Context.MODE_PRIVATE)
    fun root(c: Context) = File(c.filesDir, "outbox").apply { mkdirs() }
    fun pending(c: Context) = root(c).listFiles()?.filter { File(it,"draft.json").exists() }?.sortedBy { it.lastModified() } ?: emptyList()
    fun enqueue(c: Context, intent: Intent): File {
        require(prefs(c).contains("pair")) { "Open LocalDrop and pair your laptop first" }
        val dir = File(root(c),UUID.randomUUID().toString().replace("-", "")).apply { mkdirs() }
        val files = JSONArray()
        val uris = mutableListOf<Uri>()
        @Suppress("DEPRECATION")
        when(intent.action) {
            Intent.ACTION_SEND -> intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.add(it) }
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
        }
        if (uris.isEmpty()) intent.clipData?.let { clip -> for(i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris.add(it) } }
        require(uris.size <= 128) { "Share up to 128 files at a time" }
        var total = root(c).walkTopDown().filter { it.isFile }.sumOf { it.length() }
        for ((index,uri) in uris.withIndex()) {
            require(uri.scheme == "content") { "Only Android content providers are accepted" }
            var name = "shared-$index"
            c.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst()) name=it.getString(0) ?: name }
            name = name.replace(Regex("[\\\\/:\\x00-\\x1f]"),"_").trimEnd('.',' ').ifBlank { "shared-$index" }
            while(name.toByteArray(Charsets.UTF_8).size>200) name=name.substring(0,name.offsetByCodePoints(name.length,-1))
            val path = "$index.bin"
            c.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Cannot read shared file" }
                File(dir,path).outputStream().use { output ->
                    val buffer=ByteArray(48*1024)
                    while(true) { val n=input.read(buffer); if(n<0) break; total+=n; require(total<=MAX_BYTES) { "Outbox limit is 1 GiB. Clear sent copies first" }; output.write(buffer,0,n) }
                }
            }
            files.put(JSONObject().put("path",path).put("name",name).put("mime",c.contentResolver.getType(uri) ?: "application/octet-stream"))
        }
        val text=intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        require(text == null || text.toByteArray().size<=32768) { "Text limit is 32 KiB" }
        require(text != null || files.length()>0) { "Nothing to share" }
        File(dir,"draft.tmp").writeText(JSONObject().put("text",text ?: JSONObject.NULL).put("files",files).toString())
        require(File(dir,"draft.tmp").renameTo(File(dir,"draft.json"))) { "Could not commit share queue" }
        schedule(c); return dir
    }
    fun schedule(c: Context) {
        val job=JobInfo.Builder(45871,ComponentName(c,SendJob::class.java)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setBackoffCriteria(30000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build()
        require(c.getSystemService(JobScheduler::class.java).schedule(job)==JobScheduler.RESULT_SUCCESS) { "Android could not schedule transfer; retry in LocalDrop" }
    }
    @Synchronized fun sendAll(c: Context): String {
        val pair=prefs(c).getString("pair",null) ?: return "Pair your laptop first"
        for(dir in pending(c)) {
            if(File(dir,"sent").exists()) continue
            val error=Native.send(pair,dir.absolutePath)
            if(error.isNotEmpty()) { prefs(c).edit().putString("status","Queued: $error").apply(); return error }
            File(dir,"sent").writeText("committed")
            Alerts.show(c,"Copied on Windows","Your share is ready on the laptop. Press Ctrl+V to paste.")
        }
        prefs(c).edit().putString("status","Copied to your Windows clipboard").apply(); return ""
    }
}
