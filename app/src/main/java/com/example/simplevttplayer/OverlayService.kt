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

class OverlayService : Service() {
    companion object {
        const val ACTION_UPDATE_SUBTITLE = "com.example.simplevttplayer.UPDATE_SUBTITLE"
        const val EXTRA_SUBTITLE_TEXT = "subtitle_text"
        const val ACTION_PAUSE_PLAY = "com.example.simplevttplayer.PAUSE_PLAY"
        const val ACTION_RESET_OVERLAY_POSITION = "com.example.simplevttplayer.RESET_OVERLAY_POSITION"
        const val ACTION_UPDATE_FONT_SIZE = "com.example.simplevttplayer.UPDATE_FONT_SIZE"
        const val EXTRA_FONT_SIZE = "font_size"
        
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
    
    private val subtitleUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Broadcast received! Action: ${intent?.action}")
            when (intent?.action) {
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
            
            // 2.8: Setup seekbar listener
            overlaySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        // Update time display - this would need MainActivity coordination
                        overlayTextTime.text = formatTime(progress.toLong())
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    // Notify MainActivity of seek position change
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
            
            // 2.8: Create 3 copy overlays at top positions
            create3CopyOverlays(layoutFlag)
            
            val filter = IntentFilter().apply {
                addAction(ACTION_UPDATE_SUBTITLE)
                addAction(ACTION_PAUSE_PLAY)
                addAction(ACTION_RESET_OVERLAY_POSITION)
                addAction(ACTION_UPDATE_FONT_SIZE)
            }
            LocalBroadcastManager.getInstance(this).registerReceiver(subtitleUpdateReceiver, filter)
            Log.d(TAG, "BroadcastReceiver registered for all actions.")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during OverlayService onCreate", e)
            Toast.makeText(this, "Failed to create overlay.", Toast.LENGTH_SHORT).show()
            stopSelf()
        }
    }
    
    private fun setupOverlaySpeedSpinner() {
        val speedOptions = arrayOf("0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, speedOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        overlaySpinnerSpeed.adapter = adapter
        overlaySpinnerSpeed.setSelection(2) // Default 1.0x
        
        overlaySpinnerSpeed.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                // Notify MainActivity of speed change would go here
                Log.d(TAG, "Speed changed to position: $position")
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
    
    private fun create3CopyOverlays(layoutFlag: Int) {
        try {
            // Top-Left copy
            copyOverlayLeftView = LayoutInflater.from(this).inflate(
                android.R.layout.simple_list_item_1, null
            )
            copyTextLeft = copyOverlayLeftView.findViewById(android.R.id.text1)
            copyTextLeft.textSize = currentFontSize.toFloat()
            copyTextLeft.setTextColor(android.graphics.Color.WHITE)
            
            paramsLeft = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 20
                y = 100
            }
            windowManager.addView(copyOverlayLeftView, paramsLeft)
            
            // Top-Center copy
            copyOverlayCenterView = LayoutInflater.from(this).inflate(
                android.R.layout.simple_list_item_1, null
            )
            copyTextCenter = copyOverlayCenterView.findViewById(android.R.id.text1)
            copyTextCenter.textSize = currentFontSize.toFloat()
            copyTextCenter.setTextColor(android.graphics.Color.WHITE)
            
            paramsCenter = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = 100
            }
            windowManager.addView(copyOverlayCenterView, paramsCenter)
            
            // Top-Right copy
            copyOverlayRightView = LayoutInflater.from(this).inflate(
                android.R.layout.simple_list_item_1, null
            )
            copyTextRight = copyOverlayRightView.findViewById(android.R.id.text1)
            copyTextRight.textSize = currentFontSize.toFloat()
            copyTextRight.setTextColor(android.graphics.Color.WHITE)
            
            paramsRight = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = 20
                y = 100
            }
            windowManager.addView(copyOverlayRightView, paramsRight)
            
            Log.d(TAG, "3 copy overlays created successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error creating 3 copy overlays", e)
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
            }
            
            // Remove 3 copy overlays
            if (::copyOverlayLeftView.isInitialized && copyOverlayLeftView.isAttachedToWindow) {
                windowManager.removeView(copyOverlayLeftView)
            }
            if (::copyOverlayCenterView.isInitialized && copyOverlayCenterView.isAttachedToWindow) {
                windowManager.removeView(copyOverlayCenterView)
            }
            if (::copyOverlayRightView.isInitialized && copyOverlayRightView.isAttachedToWindow) {
                windowManager.removeView(copyOverlayRightView)
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
                
                // 2.8: Update 3 copy overlays
                if (::copyTextLeft.isInitialized) {
                    copyTextLeft.text = styled
                }
                if (::copyTextCenter.isInitialized) {
                    copyTextCenter.text = styled
                }
                if (::copyTextRight.isInitialized) {
                    copyTextRight.text = styled
                }
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
        
        // 2.8: Update 3 copy overlays font size
        if (::copyTextLeft.isInitialized) {
            copyTextLeft.textSize = fontSize.toFloat()
        }
        if (::copyTextCenter.isInitialized) {
            copyTextCenter.textSize = fontSize.toFloat()
        }
        if (::copyTextRight.isInitialized) {
            copyTextRight.textSize = fontSize.toFloat()
        }
    }
    
    private fun formatTime(ms: Long): String {
        val s = ms / 1000
        return String.format("%02d:%02d.%03d", s / 60, s % 60, ms % 1000)
    }
}
