package app.localdrop
import android.app.Activity
import android.os.Bundle
import android.content.Intent
class PickerActivity:Activity() {
    override fun onCreate(state:Bundle?) {super.onCreate(state);if(state==null) startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true).addCategory(Intent.CATEGORY_OPENABLE),1)}
    @Deprecated("Native Activity callback")
    override fun onActivityResult(request:Int,result:Int,data:Intent?) {
        super.onActivityResult(request,result,data)
        if(result==RESULT_OK && data!=null) {
            val share=Intent(this,ShareActivity::class.java).setAction(Intent.ACTION_SEND_MULTIPLE)
            val uris=arrayListOf<android.net.Uri>();data.data?.let{uris.add(it)};data.clipData?.let { for(i in 0 until it.itemCount) uris.add(it.getItemAt(i).uri) }
            share.putParcelableArrayListExtra(Intent.EXTRA_STREAM,uris).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(share)
        };finish()
    }
}
