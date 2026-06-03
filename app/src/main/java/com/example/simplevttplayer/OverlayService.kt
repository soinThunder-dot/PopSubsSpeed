/** * OverlayService.kt - 字幕浮動窗體服務 * * ================================================================
 * 【核心功能總覽】 * ================================================================ *
 * 1. 浮動字幕顯示系統 *    - 使用 WindowManager 創建系統級浮動窗口 *    - 支援字幕即時更新與顯示 *    - 提供拖曳移動、調整位置功能 *    - 日文語法高亮處理 (JpGrammarHighlighter) *
 * 2. [2.8 新增] 浮窗內嵌控制面板 *    - 播放速度調整: 0.5x ~ 2.0x (7段) *    - 時間軸拖曳: SeekBar 手動定位 *    - 字體大小控制: 動態調整所有字幕 *    - 僅在暫停時顯示，播放時自動隱藏 *
 * 3. [2.8 新增] Foreground Service 升級 *    - 符合 Android 8.0+ 前景服務規範 *    - 常駐通知欄，避免系統回收 *    - Notification Channel 管理 *    - 提供服務狀態指示 *
 * 4. 廣播通信機制 *    - LocalBroadcastManager 接收 MainActivity 指令
 *    - 支援動作: *      • UPDATE_SUBTITLE: 更新字幕文字 *      • PAUSE_PLAY: 暫停/播放切換 *      • RESET_OVERLAY_POSITION: 重置浮窗位置
 *                      • UPDATE_FONT_SIZE: 字體大小調整 *      • [2.8] OVERLAY_SPEED_CHANGE: 速度變更 *      • [2.8] OVERLAY_SEEK: 時間軸拖曳 *      • [2.8] UPDATE_TIME: 時間戳更新 *
 * 5. 生命週期管理 *    - onCreate: 初始化浮窗、註冊接收器 *    - onStartCommand: 處理啟動指令 *    - onDestroy: 清理資源、移除視圖 * * ================================================================
 * 【2.8 版本重點變更】 * ================================================================ *
 * A. 控制面板 UI (controlPanel) *    - speedSpinner: Spinner 選單 (0.5x ~ 2.0x) *    - timeSeekBar: SeekBar 時間軸 *    - fontSizeEditor: EditText 字體輸入 *    - 依據 isPaused 狀態動態顯示/隱藏 *
 * B. Foreground 通知系統 *    - createNotificationChannel(): 建立通知頻道 *    - createNotification(): 生成前景通知 *    - startForeground(NOTIFICATION_ID, notification) *
 * C. 雙向通信增強 *    - Overlay → MainActivity: 速度/拖曳事件回傳 *    - MainActivity → Overlay: 時間戳同步 * */
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
import android.widget.EditText
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.NotificationCompat

class OverlayService : Service() {
    companion object {                // ============================================================
        // 廣播 Action 常數區 (LocalBroadcast 指令定義)        // ============================================================
        // 用於 MainActivity 與 OverlayService 之間的通訊        // LocalBroadcastManager.sendBroadcast(intent) 發送指令        
        // ── 2.7 基本操作 Actions ──        // UPDATE_SUBTITLE: 更新字幕文字 (Extra: subtitle_text)        //   MainActivity 發送新字幕 → OverlayService 顯示在浮窗
        const val ACTION_UPDATE_SUBTITLE = "com.example.simplevttplayer.UPDATE_SUBTITLE"
        const val EXTRA_SUBTITLE_TEXT = "subtitle_text"
        // PAUSE_PLAY: 切換暫停/播放狀態        //   切換 isPaused flag → 控制 controlPanel 顯示/隱藏
        const val ACTION_PAUSE_PLAY = "com.example.simplevttplayer.PAUSE_PLAY"
        const val ACTION_RESET_OVERLAY_POSITION = "com.example.simplevttplayer.RESET_OVERLAY_POSITION"
        const val ACTION_UPDATE_FONT_SIZE = "com.example.simplevttplayer.UPDATE_FONT_SIZE"
        const val EXTRA_FONT_SIZE = "font_size"
        const val ACTION_OVERLAY_SEEK = "com.example.simplevttplayer.OVERLAY_SEEK"
        const val ACTION_UPDATE_TIME = "com.example.simplevttplayer.UPDATE_TIME"
        const val ACTION_NEXT_EP = "com.example.simplevttplayer.NEXT_EP"
        val TAG: String = OverlayService::class.java.simpleName
    }
    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var textViewOverlaySubtitle: TextView
    private lateinit var params: WindowManager.LayoutParams
    private var isPaused = false
    // 2.8: Control panel views
    private lateinit var controlPanel: View
    private lateinit var editMin: EditText
    private lateinit var editSec: EditText
    private lateinit var buttonOverlayNextEp: View
    
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
                else -> {  Log.w(TAG, "Unknown broadcast action: ${intent?.action}")  }
            }
        }
    }
    
    override fun onBind(intent: Intent?): IBinder? {  return null  }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "OverlayService onCreate")
        try {
            overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null)
            textViewOverlaySubtitle = overlayView.findViewById(R.id.textViewOverlaySubtitle)            
            controlPanel = overlayView.findViewById(R.id.controlPanel)     // 2.8: Get control panel views
            editMin = overlayView.findViewById(R.id.editMin)
            editSec = overlayView.findViewById(R.id.editSec)
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            editMin.setOnClickListener {
                enableOverlayInput()
                editMin.requestFocus()
                imm.showSoftInput(editMin, android.view.inputmethod.InputMethodManager.SHOW_FORCED)
            }
            editSec.setOnClickListener {
                enableOverlayInput()
                editSec.requestFocus()
                imm.showSoftInput(editSec, android.view.inputmethod.InputMethodManager.SHOW_FORCED)
            }
            setupTimeEditor( editMin )
            setupTimeEditor( editSec )
            
            buttonOverlayNextEp = overlayView.findViewById(R.id.buttonOverlayNextEp)
            buttonOverlayNextEp.setOnClickListener {
                val intent = Intent(ACTION_NEXT_EP)
                LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
            }
            
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
            params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,  // 或你之前用的 TYPE_PHONE
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN    or// 先移除 FLAG_NOT_FOCUSABLE
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,// 預設不可獲得焦點（不會彈鍵盤），點 EditText 前透過 enableOverlayInput() 去掉 NOT_FOCUSABLE
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
            }
            LocalBroadcastManager.getInstance(this).registerReceiver(subtitleUpdateReceiver, filter)
            Log.d(TAG, "BroadcastReceiver registered for all actions.")
        } catch (e: Exception) {
            Log.e(TAG, "Error during OverlayService onCreate", e)
            Toast.makeText(this, "Failed to create overlay.", Toast.LENGTH_SHORT).show()
            stopSelf()
        }
    }
    
    private fun sendTimeToMainFromOverlay(min: Int, sec: Int) {
        val totalMs = (min * 60 + sec) * 1000L
        val intent = Intent(OverlayService.ACTION_OVERLAY_SEEK)
        intent.putExtra("seek_to_ms", totalMs)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "OverlayService onStartCommand Received")
        return START_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()    
        Log.d(TAG, "OverlayService onDestroy")
        stopForeground(true)  // 2.8: 移除通知
        try { if (::overlayView.isInitialized && overlayView.isAttachedToWindow) {
                windowManager.removeView(overlayView)
                Log.d(TAG, "Overlay view removed.")
              }
        } catch (e: Exception) {  Log.e(TAG, "Error removing overlay views", e) }
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(subtitleUpdateReceiver)
            Log.d(TAG, "SubtitleUpdateReceiver unregistered.")
        } catch (e: IllegalArgumentException) { Log.w(TAG, "Receiver possibly already unregistered or not registered.", e)  }
        val dieIntent = Intent("OVERLAY_SERVICE_DIED")//監聽 Service 死掉，MainActivity 可以收到
        LocalBroadcastManager.getInstance(this).sendBroadcast(dieIntent)
    }
    
    private fun updateSubtitleText(text: String) {
        if (::textViewOverlaySubtitle.isInitialized && ::overlayView.isInitialized) {
            currentSubtitle = text
            if (text.isBlank()) {
                if (overlayView.visibility != View.GONE) { Log.d(TAG, "Hiding overlay view (blank text received).") }
            } else { if (overlayView.visibility != View.VISIBLE) {
                        Log.d(TAG, "Showing overlay view.")
                        overlayView.visibility = View.VISIBLE  }
                val styled: CharSequence = JpGrammarHighlighter.highlight(text)
                textViewOverlaySubtitle.text = styled
            }
        } else {  Log.w(TAG, "Overlay views not initialized when trying to update text ('$text').") }
    }
    
    private fun togglePauseFromOverlay() {
        isPaused = !isPaused
        val intent = Intent(ACTION_PAUSE_PLAY).apply { putExtra("is_paused", isPaused) }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)        
        updateSubtitlePauseState()        
        if (::controlPanel.isInitialized) {    // 2.8: Show/hide control panel on pause/resume
            val vis = if (isPaused) View.VISIBLE else View.GONE
            controlPanel.visibility = vis
            if (::buttonOverlayNextEp.isInitialized) { buttonOverlayNextEp.visibility = vis }
            Log.d(TAG, "Control panel visibility: ${if (isPaused) "VISIBLE" else "GONE"}")
        }
        //Toast.makeText(this, if (isPaused) "Paused" else "Resumed", Toast.LENGTH_SHORT).show()
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
            } catch (e: Exception) {  Log.e(TAG, "Error moving overlay up", e)  }
        } else {  Log.w(TAG, "Cannot move overlay: views not initialized")  }
    }
    
    private fun resetOverlayPosition() {
        if (::params.isInitialized && ::overlayView.isInitialized) {
            params.y = 0
            try {
                windowManager.updateViewLayout(overlayView, params)
                Log.d(TAG, "Overlay position reset to Y: 0")
            } catch (e: Exception) { Log.e(TAG, "Error resetting overlay position", e) }
        } else { Log.w(TAG, "Cannot move overlay: views not initialized")  }
    }
    
    private fun updateOverlayFontSize(fontSize: Int) {
        currentFontSize = fontSize
        if (::textViewOverlaySubtitle.isInitialized) {
            textViewOverlaySubtitle.textSize = fontSize.toFloat()
            Log.d(TAG, "Overlay font size updated to: $fontSize")
        }
    }

    private fun enableOverlayInput() {
        if (!::params.isInitialized || !::overlayView.isInitialized || !overlayView.isAttachedToWindow) return
        params.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        windowManager.updateViewLayout(overlayView, params)
    }
    private fun disableOverlayInput() {
        if (!::params.isInitialized || !::overlayView.isInitialized || !overlayView.isAttachedToWindow) return
        params.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN  or  WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        windowManager.updateViewLayout(overlayView, params)
    }
    private fun setupTimeEditor(edit: EditText) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        edit.setOnEditorActionListener { v, actionId, event ->
            val isImeDone = actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            val isEnterKey = event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.action == android.view.KeyEvent.ACTION_UP
            if (isImeDone || isEnterKey) {
                val minVal = editMin.text.toString().toIntOrNull() ?: 0
                val secVal = editSec.text.toString().toIntOrNull() ?: 0
                if (secVal in 0..59) {
                    Toast.makeText( this,"Overlay set to %02d:%02d".format(minVal, secVal), Toast.LENGTH_SHORT ).show()
                    sendTimeToMainFromOverlay(minVal, secVal)
                } else { editSec.text?.clear()  }
                imm.hideSoftInputFromWindow(edit.windowToken, 0)    // 收鍵盤 + 關輸入模式
                edit.clearFocus()
                disableOverlayInput()
                true
            } else {  false   }
        }
    }

    
}
