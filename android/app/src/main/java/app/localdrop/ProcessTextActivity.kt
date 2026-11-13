package app.localdrop

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/** Read-only selected-text action. Never replaces text in the originating editor. */
class ProcessTextActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val selected = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        if (intent.action == Intent.ACTION_PROCESS_TEXT && !selected.isNullOrBlank()) {
            startActivity(Intent(this, ShareActivity::class.java)
                .setAction(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, selected))
        } else Toast.makeText(this, "Select some text first", Toast.LENGTH_SHORT).show()
        setResult(RESULT_CANCELED)
        finish()
    }
}
