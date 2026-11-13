package app.localdrop
object Native {
    init { System.loadLibrary("localdrop_android") }
    @JvmStatic external fun send(pairing: String, folder: String): String
    @JvmStatic external fun pull(pairing: String, folder: String): String
    @JvmStatic external fun cancel()
    @JvmStatic external fun confirm(pairing:String,device:String):String
}
