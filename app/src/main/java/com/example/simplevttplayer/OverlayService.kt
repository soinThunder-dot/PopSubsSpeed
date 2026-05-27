package com.example.simplevttplayer

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
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.simplevttplayer.JpGrammarHighlighter
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.SeekBar
import android.widget.EditText
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.NotificationCompat

class OverlayService : Service() {
    companion object {
        const val ACTION_UPDATE_SUBTITLE = "com.example.simplevttplayer.UPDATE_SUBTITLE"
        const val EXTRA_SUBTITLE_TEXT = "subtitle_text"
        const val ACTION_PAUSE_PLAY = "com.example.simplevttplayer.PAUSE_PLAY"
        const val ACTION_RESET_OVERLAY_POSITION = "com.example.simplevttplayer.RESET_OVERLAY_POSITION"
        const val ACTION_UPDATE_FONT_SIZE = "com.example.simplevttplayer.UPDATE_FONT_SIZE"
        const val EXTRA_FONT_SIZE = "font_size"
        // 2.8: Foreground service notification constants
        const val NOTIFICATION_CHANNEL_ID = "overlay_service_channel"
        const val NOTIFICATION_ID = 1001
        // 新增 3 個 actions
        const val ACTION_OVERLAY_SPEED_CHANGE = "com.example.simplevttplayer.OVERLAY_SPEED_CHANGE"
        const val ACTION_OVERLAY_SEEK = "com.example.simplevttplayer.OVERLAY_SEEK"
        const val ACTION_UPDATE_TIME = "com.example.simplevttplayer.UPDATE_TIME" // MainActivity → Overlay
        val TAG: String = OverlayService::class.java.simpleName
    }
    
    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var textViewOverlaySubtitle: TextView
    private lateinit var params: WindowManager.LayoutParams
    private var isPaused = false
    
    // 2.8: Control panel views
    private lateinit var controlPanel: View
    private lateinit var overlaySpinnerSpeed: Spinner
    private lateinit var overlaySeekBar: SeekBar
    private lateinit var overlayTextTime: TextView
    private lateinit var overlayEditFontSize: EditText
    
    // 2.8: Three copy overlays (top-left, center, top-right)
    private lateinit var copyOverlayLeftView: View
    private lateinit var copyOverlayCenterView: View
    private lateinit var copyOverlayRightView: View
    private lateinit var copyTextLeft: TextView
    private lateinit var copyTextCenter: TextView
    private lateinit var copyTextRight: TextView
    private lateinit var paramsLeft: WindowManager.LayoutParams
    private lateinit var paramsCenter: WindowManager.LayoutParams
    private lateinit var paramsRight: WindowManager.LayoutParams
    
    private var currentSubtitle = ""
    private var currentFontSize = 20

    private val autoSaveHandler = Handler(Looper.getMainLooper())
    private val autoSaveRunnable = object : Runnable {
        override fun run() {            // TODO: 這裡你要呼叫 Service 版本的儲存邏輯
            autoSaveHandler.postDelayed(this, AUTO_SAVE_INTERVAL_MS)
        }            // 例如：saveOverlayPositionOrTimestamp()
    }
    private val AUTO_SAVE_INTERVAL_MS = 3 * 60 * 1000L  // 3 分鐘
    
    private val subtitleUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Broadcast received! Action: ${intent?.action}")
            when (intent?.action) {
                ACTION_UPDATE_TIME -> {
                    val currentTime = intent.getLongExtra("current_time_ms", 0L)
                    overlaySeekBar.progress = currentTime.toInt()
                    overlayTextTime.text = formatTime(currentTime)
                }
                ACTION_UPDATE_SUBTITLE -> {
                    val subtitleText = intent.getStringExtra(EXTRA_SUBTITLE_TEXT) ?: ""
                    Log.d(TAG, "Received subtitle broadcast: '$subtitleText'")
                    updateSubtitleText(subtitleText)
                }
                ACTION_RESET_OVERLAY_POSITION -> {
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
        return null
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "OverlayService onCreate")
        createNotificationChannel()       // 2.8: 升級為前景服務，防止被系統終止
        startForeground(NOTIFICATION_ID, createNotification())
        try {
            overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null)
            textViewOverlaySubtitle = overlayView.findViewById(R.id.textViewOverlaySubtitle)
            
            // 2.8: Get control panel views
            controlPanel = overlayView.findViewById(R.id.controlPanel)
            overlaySpinnerSpeed = overlayView.findViewById(R.id.overlaySpinnerSpeed)
            overlaySeekBar = overlayView.findViewById(R.id.overlaySeekBar)
            overlayTextTime = overlayView.findViewById(R.id.overlayTextTime)
            overlayEditFontSize = overlayView.findViewById(R.id.overlayEditFontSize)
            
            // 2.8: Setup speed spinner
            setupOverlaySpeedSpinner()
            
            // SeekBar 改變時 → 通知 MainActivity
            overlaySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        overlayTextTime.text = formatTime(progress.toLong())
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    // ❌ 目前缺少這個 - 應該暫停播放或通知 MainActivity
                }
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val intent = Intent(ACTION_OVERLAY_SEEK)
                    intent.putExtra("seek_to_ms", seekBar?.progress?.toLong() ?: 0L)
                    LocalBroadcastManager.getInstance(this@OverlayService).sendBroadcast(intent)
                }
            })
            
            // 2.8: Setup font size EditText
            overlayEditFontSize.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val fontSize = s?.toString()?.toIntOrNull() ?: 20
                    currentFontSize = fontSize
                    updateOverlayFontSize(fontSize)
                    // Notify MainActivity
                    val intent = Intent(ACTION_UPDATE_FONT_SIZE)
                    intent.putExtra(EXTRA_FONT_SIZE, fontSize)
                    LocalBroadcastManager.getInstance(this@OverlayService).sendBroadcast(intent)
                }
            })
            
            textViewOverlaySubtitle.setOnClickListener {
                Log.d(TAG, "Subtitle clicked - toggle pause!")
                togglePauseFromOverlay()
            }
            
            val buttonMoveUp: View? = overlayView.findViewById(R.id.buttonMoveUp)
            buttonMoveUp?.setOnClickListener {
                Log.d(TAG, "Move up button clicked!")
                moveOverlayUpByButtonClick()
            }
            
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            
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
            windowManager.addView(overlayView, params)
            Log.d(TAG, "Overlay view added successfully.")
            
            val filter = IntentFilter().apply {
                addAction(ACTION_UPDATE_SUBTITLE)
                addAction(ACTION_PAUSE_PLAY)
                addAction(ACTION_RESET_OVERLAY_POSITION)
                addAction(ACTION_UPDATE_FONT_SIZE)
                addAction(ACTION_UPDATE_TIME) // ✅ 加這個
            }
            LocalBroadcastManager.getInstance(this).registerReceiver(subtitleUpdateReceiver, filter)
            Log.d(TAG, "BroadcastReceiver registered for all actions.")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during OverlayService onCreate", e)
            Toast.makeText(this, "Failed to create overlay.", Toast.LENGTH_SHORT).show()
            stopSelf()
        }
        autoSaveHandler.postDelayed(autoSaveRunnable, AUTO_SAVE_INTERVAL_MS)
    }
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "字幕懸浮視窗服務",
                NotificationManager.IMPORTANCE_LOW  // LOW 不會發出聲音
            ).apply {
                description = "保持字幕懸浮視窗在背景運行"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): android.app.Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("字幕懸浮視窗運行中")
            .setContentText("點擊返回應用")
            .setSmallIcon(android.R.drawable.ic_dialog_info)  // 替換為你的 app icon
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)  // 無法被滑掉
            .build()
    }
    
    private fun setupOverlaySpeedSpinner() {
        val speedOptions = arrayOf("0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, speedOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        overlaySpinnerSpeed.adapter = adapter
        overlaySpinnerSpeed.setSelection(2) // Default 1.0x
        
        overlaySpinnerSpeed.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                Log.d(TAG, "Speed changed to position: $position")// Notify MainActivity of speed change would go here
                val intent = Intent(ACTION_OVERLAY_SPEED_CHANGE)// ✅ 需要加：發送 broadcast 給 MainActivity
                intent.putExtra("speed_position", position)
                LocalBroadcastManager.getInstance(this@OverlayService).sendBroadcast(intent)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "OverlayService onStartCommand Received")
        return START_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()    
        autoSaveHandler.removeCallbacks(autoSaveRunnable)  // 2.8: 停止定期儲存
        Log.d(TAG, "OverlayService onDestroy")
        stopForeground(true)  // 2.8: 移除通知
        try {
            if (::overlayView.isInitialized && overlayView.isAttachedToWindow) {
                windowManager.removeView(overlayView)
                Log.d(TAG, "Overlay view removed.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing overlay views", e)
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
            currentSubtitle = text
            if (text.isBlank()) {
                if (overlayView.visibility != View.GONE) {
                    Log.d(TAG, "Hiding overlay view (blank text received).")
                }
            } else {
                if (overlayView.visibility != View.VISIBLE) {
                    Log.d(TAG, "Showing overlay view.")
                    overlayView.visibility = View.VISIBLE
                }
                val styled: CharSequence = JpGrammarHighlighter.highlight(text)
                textViewOverlaySubtitle.text = styled
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
        
        // 2.8: Show/hide control panel on pause/resume
        if (::controlPanel.isInitialized) {
            controlPanel.visibility = if (isPaused) View.VISIBLE else View.GONE
            Log.d(TAG, "Control panel visibility: ${if (isPaused) "VISIBLE" else "GONE"}")
        }
        
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
        currentFontSize = fontSize
        if (::textViewOverlaySubtitle.isInitialized) {
            textViewOverlaySubtitle.textSize = fontSize.toFloat()
            Log.d(TAG, "Overlay font size updated to: $fontSize")
        }
    }
    
    private fun formatTime(ms: Long): String {
        val s = ms / 1000
        return String.format("%02d:%02d.%03d", s / 60, s % 60, ms % 1000)
    }
}
