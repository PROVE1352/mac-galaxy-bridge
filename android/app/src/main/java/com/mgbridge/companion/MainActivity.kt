package com.mgbridge.companion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var macField: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        requestNeededPermissions()
    }

    override fun onResume() {
        super.onResume()
        if (!BridgeService.running) {
            ContextCompat.startForegroundService(this, Intent(this, BridgeService::class.java))
        }
        refreshStatus()
    }

    private fun buildUi(): ViewGroup {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        fun heading(t: String) = TextView(this).apply {
            text = t
            textSize = 20f
            setPadding(0, pad, 0, pad / 2)
        }

        root.addView(TextView(this).apply {
            text = "Mac-Galaxy Bridge"
            textSize = 26f
            gravity = Gravity.CENTER
            setPadding(0, pad, 0, pad)
        })

        status = TextView(this).apply { textSize = 15f }
        root.addView(status)

        root.addView(heading("Mac Bluetooth address"))
        macField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setText(Prefs.macAddr(this@MainActivity))
        }
        root.addView(macField)
        root.addView(Button(this).apply {
            text = "Save Mac address"
            setOnClickListener {
                Prefs.setMacAddr(this@MainActivity, macField.text.toString())
                Toast.makeText(this@MainActivity, "Saved", Toast.LENGTH_SHORT).show()
                refreshStatus()
            }
        })

        root.addView(heading("1. Background service"))
        root.addView(Button(this).apply {
            text = "Start bridge service"
            setOnClickListener {
                ContextCompat.startForegroundService(
                    this@MainActivity, Intent(this@MainActivity, BridgeService::class.java)
                )
                refreshStatus()
            }
        })
        root.addView(Button(this).apply {
            text = "Stop bridge service"
            setOnClickListener {
                stopService(Intent(this@MainActivity, BridgeService::class.java))
                refreshStatus()
            }
        })

        root.addView(heading("2. Hotspot toggle permission"))
        root.addView(Button(this).apply {
            text = "Open Accessibility settings"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })

        return root
    }

    private fun refreshStatus() {
        val svc = if (BridgeService.running) "running" else "stopped"
        val acc = if (HotspotAccessibilityService.instance != null) "enabled" else "OFF (tap below)"
        status.text = "Service: $svc\nAccessibility: $acc\nWatching Mac: ${Prefs.macAddr(this)}"
    }

    private fun requestNeededPermissions() {
        val need = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) need += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 42)
    }
}
