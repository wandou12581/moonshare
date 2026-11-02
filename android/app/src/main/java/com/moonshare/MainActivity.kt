package com.moonshare

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.moonshare.util.NetworkUtils

class MainActivity : Activity() {

    private lateinit var statusChip: TextView
    private lateinit var pairingCode: TextView
    private lateinit var addressText: TextView
    private lateinit var portText: TextView
    private lateinit var viewerText: TextView
    private lateinit var liveDot: View
    private lateinit var toggleButton: Button

    private var stateReceiverRegistered = false
    private var lastError: String? = null
    private var mediaProjectionManager: MediaProjectionManager? = null

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ScreenShareService.ACTION_STATE) return
            render(
                running = intent.getBooleanExtra(ScreenShareService.EXTRA_RUNNING, false),
                code = intent.getStringExtra(ScreenShareService.EXTRA_CODE) ?: "",
                viewers = intent.getIntExtra(ScreenShareService.EXTRA_VIEWERS, 0),
                addresses = intent.getStringExtra(ScreenShareService.EXTRA_ADDRESSES) ?: "",
                viewerName = intent.getStringExtra(ScreenShareService.EXTRA_VIEWER_NAME),
                viewerAddress = intent.getStringExtra(ScreenShareService.EXTRA_VIEWER_ADDRESS),
                error = intent.getStringExtra(ScreenShareService.EXTRA_ERROR)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.parseColor("#080B10")
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)

        mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        statusChip = findViewById(R.id.statusChip)
        pairingCode = findViewById(R.id.pairingCode)
        addressText = findViewById(R.id.addressText)
        portText = findViewById(R.id.portText)
        viewerText = findViewById(R.id.viewerText)
        liveDot = findViewById(R.id.liveDot)
        toggleButton = findViewById(R.id.toggleButton)

        toggleButton.setOnClickListener {
            if (ScreenShareService.isSharing) {
                stopService(Intent(this, ScreenShareService::class.java).setAction(ScreenShareService.ACTION_STOP))
            } else {
                requestScreenCapture()
            }
        }

        addressText.setOnClickListener {
            val text = addressText.text?.toString().orEmpty()
            if (text.isNotBlank()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("MoonShare address", text))
                Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
            }
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MICROPHONE)
        }

        render(false, "", 0, NetworkUtils.displayLines(), null, null, null)
        registerStateReceiver()
    }

    override fun onResume() {
        super.onResume()
        if (ScreenShareService.isSharing) {
            startService(Intent(this, ScreenShareService::class.java).setAction(ScreenShareService.ACTION_QUERY))
        } else {
            render(false, "", 0, NetworkUtils.displayLines(), null, null, null)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (ScreenShareService.isSharing) {
            startService(Intent(this, ScreenShareService::class.java).setAction(ScreenShareService.ACTION_QUERY))
        }
    }

    override fun onDestroy() {
        if (stateReceiverRegistered) {
            try {
                unregisterReceiver(stateReceiver)
            } catch (_: Exception) {
            }
            stateReceiverRegistered = false
        }
        super.onDestroy()
    }

    private fun registerStateReceiver() {
        val filter = IntentFilter(ScreenShareService.ACTION_STATE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(stateReceiver, filter)
        }
        stateReceiverRegistered = true
    }

    private fun requestScreenCapture() {
        val manager = mediaProjectionManager
        if (manager == null) {
            Toast.makeText(this, "此设备不支持屏幕共享", Toast.LENGTH_SHORT).show()
            return
        }
        toggleButton.isEnabled = false
        toggleButton.text = getString(R.string.starting_share)
        try {
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
        } catch (t: Throwable) {
            toggleButton.isEnabled = true
            toggleButton.text = getString(R.string.start_share)
            Toast.makeText(this, t.message ?: "无法请求录屏权限", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            toggleButton.isEnabled = true
            toggleButton.text = getString(R.string.start_share)
            Toast.makeText(this, R.string.projection_denied, Toast.LENGTH_LONG).show()
            return
        }
        val serviceIntent = Intent(this, ScreenShareService::class.java).apply {
            action = ScreenShareService.ACTION_START
            putExtra(ScreenShareService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenShareService.EXTRA_RESULT_DATA, data)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (t: Throwable) {
            toggleButton.isEnabled = true
            toggleButton.text = getString(R.string.start_share)
            Toast.makeText(this, t.message ?: "无法启动共享服务", Toast.LENGTH_LONG).show()
        }
    }

    private fun render(
        running: Boolean,
        code: String,
        viewers: Int,
        addresses: String,
        viewerName: String?,
        viewerAddress: String?,
        error: String?
    ) {
        val live = running && viewers > 0
        if (live) {
            statusChip.setBackgroundResource(R.drawable.bg_chip_live)
            statusChip.setTextColor(Color.parseColor("#8FF5E5"))
            statusChip.text = if (viewers > 1) getString(R.string.status_live, viewers)
            else getString(R.string.status_live, 1)
        } else if (running) {
            statusChip.setBackgroundResource(R.drawable.bg_chip_live)
            statusChip.setTextColor(Color.parseColor("#8FF5E5"))
            statusChip.text = getString(R.string.status_waiting)
        } else {
            statusChip.setBackgroundResource(R.drawable.bg_chip_idle)
            statusChip.setTextColor(Color.parseColor("#A8B8C8"))
            statusChip.text = getString(R.string.status_idle)
        }

        pairingCode.text = if (code.length == 6) "${code.substring(0, 3)} ${code.substring(3)}"
        else getString(R.string.pairing_placeholder)
        addressText.text = addresses.ifBlank { getString(R.string.address_placeholder) }
        portText.text = "TCP 47800"

        liveDot.visibility = if (live) View.VISIBLE else View.GONE
        viewerText.text = when {
            live && !viewerName.isNullOrBlank() ->
                getString(R.string.viewer_prefix, "$viewerName${viewerAddress?.let { " · $it" } ?: ""}")
            live -> getString(R.string.viewer_connected)
            else -> getString(R.string.viewers_none)
        }

        toggleButton.isEnabled = true
        if (running) {
            toggleButton.text = getString(R.string.stop_share)
            toggleButton.setBackgroundResource(R.drawable.bg_button_stop)
            toggleButton.setTextColor(Color.parseColor("#FCA5A5"))
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            toggleButton.text = getString(R.string.start_share)
            toggleButton.setBackgroundResource(R.drawable.bg_button_primary)
            toggleButton.setTextColor(Color.parseColor("#061014"))
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        if (!error.isNullOrBlank() && error != lastError) {
            lastError = error
            Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            toggleButton.isEnabled = true
            toggleButton.text = getString(R.string.start_share)
            toggleButton.setBackgroundResource(R.drawable.bg_button_primary)
            toggleButton.setTextColor(Color.parseColor("#061014"))
        }
    }

    companion object {
        private const val REQUEST_CAPTURE = 1001
        private const val REQUEST_NOTIFICATIONS = 1002
        private const val REQUEST_MICROPHONE = 1003
    }
}
