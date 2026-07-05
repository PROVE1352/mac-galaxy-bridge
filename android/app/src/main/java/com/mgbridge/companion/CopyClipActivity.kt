package com.mgbridge.companion

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Trampoline for the clipboard notification's Copy action: an activity briefly takes
 * focus, which is what One UI requires for a clipboard write to actually stick.
 */
class CopyClipActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TEXT = "text"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getStringExtra(EXTRA_TEXT)
        if (text != null) {
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("MGBridge", text))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
