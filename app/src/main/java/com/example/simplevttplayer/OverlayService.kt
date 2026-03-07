package com.example.simplevttplayer // **IMPORTANT: Adjust package name if needed!**
import android.app.Service
import android.widget.Toast
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.localbroadcastmanager.content.LocalBroadcastManager // Use LocalBroadcastManager
class OverlayService : Service() {
    companion object {
        // These constants MUST match the ones used in MainActivity
        const val ACTION_UPDATE_SUBTITLE = "com.example.simplevttplayer.UPDATE_SUBTITLE"
        const val EXTRA_SUBTITLE_TEXT = "subtitle_text"
        const val ACTION_PAUSE_PLAY = "com.example.simplevttplayer.PAUSE_PLAY" 
        const val ACTION_RESET_OVERLAY_POSITION = "com.example.simplevttplayer.RESET_OVERLAY_POSITION" // ✅ 新增重置 action
        const val ACTION_UPDATE_FONT_SIZE = "com.example.simplevttplayer.UPDATE_FONT_SIZE"
        const val EXTRA_FONT_SIZE = "font_size"
        
        val TAG: String = OverlayService::class.java.simpleName
    }
    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var textViewOverlaySubtitle: TextView
    private lateinit var params: WindowManager.LayoutParams 
    private var isPaused = false // Track pause state
    // Listens for broadcasts from MainActivity containing subtitle text
    private val subtitleUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Broadcast received! Action: ${intent?.action}") // ✅ 加 Log 確認收到
            when (intent?.action) {
                ACTION_UPDATE_SUBTITLE -> {
                    val subtitleText = intent.getStringExtra(EXTRA_SUBTITLE_TEXT) ?: ""
                    Log.d(TAG, "Received subtitle broadcast: '$subtitleText'")
                    updateSubtitleText(subtitleText)
                }
                ACTION_RESET_OVERLAY_POSITION -> { // ✅ 新增重置邏輯
                    Log.d(TAG, "Received reset overlay position request")
                    resetOverlayPosition()
                }
                ACTION_UPDATE_FONT_SIZE -> {
                    val fontSize = intent.getIntExtra(EXTRA_FONT_SIZE, 20)
                    Log.d(TAG, "Received font size update: $fontSize")
                    updateOverlayFontSize(fontSize)
                }
                else -> {
                    Log.w(TAG, "Unknown broadcast action: ${intent?.action}")
                }
            }
        }
    }
    override fun onBind(intent: Intent?): IBinder? {
        // Not using binding, so return null
        return null
    }
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "OverlayService onCreate")
        try {
            // Inflate the overlay layout
            overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null)
            textViewOverlaySubtitle = overlayView.findViewById(R.id.textViewOverlaySubtitle)
            
            // Set click listener on subtitle to toggle pause
            textViewOverlaySubtitle.setOnClickListener {
                Log.d(TAG, "Subtitle clicked - toggle pause!")
                togglePauseFromOverlay()
            }
            
            val buttonMoveUp: View? = overlayView.findViewById(R.id.buttonMoveUp)
            if (buttonMoveUp != null) {
                buttonMoveUp.setOnClickListener {
                    Log.d(TAG, "Move up button clicked!")
                    moveOverlayUpByButtonClick()
                }
                Log.d(TAG, "Move up button listener set successfully")
            } else {
                Log.w(TAG, "buttonMoveUp is null! Check overlay_layout.xml IDs")
            }
            
            // ✅ 把下面這些都移進來！
            
            // Get WindowManager service
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            
            // Define layout parameters for the overlay window
            val layoutFlag: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }
            
            params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = 0
            }
            
            // Add the view to the window manager
            windowManager.addView(overlayView, params)
            Log.d(TAG, "Overlay view added successfully.")
            
            // Register the broadcast receiver using LocalBroadcastManager
            val filter = IntentFilter().apply {
                addAction(ACTION_UPDATE_SUBTITLE) // ✅ 用 companion 中定義的常數
                addAction(ACTION_PAUSE_PLAY) // ✅ 用 companion 中定義的常數
                addAction(ACTION_RESET_OVERLAY_POSITION) // ✅ 新增重置
                addAction(ACTION_UPDATE_FONT_SIZE) // 浮窗字體大小更新
            }
            LocalBroadcastManager.getInstance(this).registerReceiver(subtitleUpdateReceiver, filter)
            Log.d(TAG, "BroadcastReceiver registered for all actions.")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during OverlayService onCreate", e)
            Toast.makeText(this, "Failed to create overlay.", Toast.LENGTH_SHORT).show()
            stopSelf()
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "OverlayService onStartCommand Received")
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "OverlayService onDestroy")
        try {
            if (::overlayView.isInitialized && overlayView.isAttachedToWindow) {
                windowManager.removeView(overlayView)
                Log.d(TAG, "Overlay view removed.")
            } else {
                Log.d(TAG, "Overlay view not attached or not initialized, no removal needed.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing overlay view", e)
        }
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(subtitleUpdateReceiver)
            Log.d(TAG, "SubtitleUpdateReceiver unregistered.")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Receiver possibly already unregistered or not registered.", e)
        }
    }
    private fun updateSubtitleText(text: String) {
        if (::textViewOverlaySubtitle.isInitialized && ::overlayView.isInitialized) {
            if (text.isBlank()) {
                if (overlayView.visibility != View.GONE) {
                    Log.d(TAG, "Hiding overlay view (blank text received).")
                    overlayView.visibility = View.GONE
                }
            } else {
                if (overlayView.visibility != View.VISIBLE) {
                    Log.d(TAG, "Showing overlay view.")
                    overlayView.visibility = View.VISIBLE
                }
                textViewOverlaySubtitle.text = text
            }
        } else {
            Log.w(TAG, "Overlay views not initialized when trying to update text ('$text').")
        }
    }
    private fun togglePauseFromOverlay() {
        isPaused = !isPaused
        
        val intent = Intent(ACTION_PAUSE_PLAY).apply {
            putExtra("is_paused", isPaused)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        
        updateSubtitlePauseState()
        
        Toast.makeText(this, if (isPaused) "Paused" else "Resumed", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "Pause toggled from overlay: isPaused=$isPaused")
    }
    
    private fun updateSubtitlePauseState() {
        val textColor = if (isPaused) android.graphics.Color.RED else android.graphics.Color.WHITE
        textViewOverlaySubtitle.setTextColor(textColor)
        Log.d(TAG, "Updated subtitle color: isPaused=$isPaused, color=${if (isPaused) "RED" else "WHITE"}")
    }
    private fun moveOverlayUpByButtonClick() {
        if (::params.isInitialized && ::overlayView.isInitialized) {
            val moveDistance = 36
            Log.d(TAG, "Moving overlay up by $moveDistance pixels")
            params.y += moveDistance
            try {
                windowManager.updateViewLayout(overlayView, params)
                Log.d(TAG, "Overlay moved up. New Y: ${params.y}")
            } catch (e: Exception) {
                Log.e(TAG, "Error moving overlay up", e)
            }
        } else {
            Log.w(TAG, "Cannot move overlay: views not initialized")
        }
    }
    
    private fun resetOverlayPosition() {
        if (::params.isInitialized && ::overlayView.isInitialized) {
            params.y = 0
            try {
                windowManager.updateViewLayout(overlayView, params)
                Log.d(TAG, "Overlay position reset to Y: 0")
            } catch (e: Exception) {
                Log.e(TAG, "Error resetting overlay position", e)
            }
        } else {
            Log.w(TAG, "Cannot move overlay: views not initialized")
        }
    }
    private fun updateOverlayFontSize(fontSize: Int) {
        if (::textViewOverlaySubtitle.isInitialized) {
            textViewOverlaySubtitle.textSize = fontSize.toFloat()
            Log.d(TAG, "Overlay font size updated to: $fontSize")
        } else {
            Log.w(TAG, "Cannot update font size: textViewOverlaySubtitle not initialized")
        }
    }
}
