package app.localdrop
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
class ReceivedProvider:ContentProvider() {
    override fun onCreate()=true
    private fun file(uri:Uri):File {
        val root=File(context!!.filesDir,"inbox").canonicalFile
        require(uri.pathSegments.size==2)
        val file=File(File(root,uri.pathSegments[0]),uri.pathSegments[1]).canonicalFile
        require(file.toPath().startsWith(root.toPath()) && file.name!="receipt.json" && file.isFile)
        return file
    }
    override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {require(mode=="r");return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY)}
    override fun getType(uri:Uri)=android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(file(uri).extension.lowercase()) ?: "application/octet-stream"
    override fun query(uri:Uri,projection:Array<out String>?,selection:String?,args:Array<out String>?,sort:String?):Cursor {
        val f=file(uri);val columns=projection ?: arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {addRow(columns.map {when(it){OpenableColumns.DISPLAY_NAME->f.name;OpenableColumns.SIZE->f.length();else->null}})}
    }
    override fun insert(u:Uri,v:ContentValues?):Uri?=throw UnsupportedOperationException()
    override fun delete(u:Uri,s:String?,a:Array<out String>?)=throw UnsupportedOperationException()
    override fun update(u:Uri,v:ContentValues?,s:String?,a:Array<out String>?)=throw UnsupportedOperationException()
}
