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
package com.example.simplevttplayer   // App package，讓 Service 能被 Android 找到

import android.app.Service            // 繼承 Service 用
import android.widget.Toast           // 顯示短提示訊息
import android.content.BroadcastReceiver  // 用來接 LocalBroadcast 的基類
import android.content.Context        // Android 上下文物件
import android.content.Intent         // 廣播與啟動元件用的 Intent
import android.content.IntentFilter   // 註冊時指定要監聽哪些 Action
import android.graphics.PixelFormat   // 控制 Window 像素格式（TRANSLUCENT 等）
import android.os.Build             
import android.os.IBinder             // Service 綁定介面型別
import android.util.Log               // 寫 Logcat 訊息
import android.view.Gravity           // 控制浮窗位置（上/下/中）
import android.view.LayoutInflater    // 把 XML layout 轉成 View
import android.view.View              // 所有 UI 元件的基類
import android.view.WindowManager     // 管理系統級 window（overlay）
import android.widget.TextView        // 顯示文字用的 View
import androidx.localbroadcastmanager.content.LocalBroadcastManager  // app 內部用的 Broadcast 管理
import com.example.simplevttplayer.JpGrammarHighlighter              // 你自己的日文語法高亮工具
import android.widget.EditText        // 可輸入文字的欄位
import android.app.NotificationChannel // （目前未用）前景通知頻道
import android.app.NotificationManager // （目前未用）管理通知
import android.app.PendingIntent       // （目前未用）通知點擊行為
import androidx.core.app.NotificationCompat // （目前未用）建 notification 用 helper

class OverlayService : Service() {    // 主角：負責顯示系統浮窗字幕 + 控制面板的 Service
    companion object {                // ============================================================
        // 廣播 Action 常數區 (LocalBroadcast 指令定義)        // ============================================================
        // 用於 MainActivity 與 OverlayService 之間的通訊        // LocalBroadcastManager.sendBroadcast(intent) 發送指令        
        // ── 2.7 基本操作 Actions ──        // UPDATE_SUBTITLE: 更新字幕文字 (Extra: subtitle_text)        //   MainActivity 發送新字幕 → OverlayService 顯示在浮窗
        const val ACTION_UPDATE_SUBTITLE = "com.example.simplevttplayer.UPDATE_SUBTITLE" // Main→Overlay：更新字幕
        const val EXTRA_SUBTITLE_TEXT = "subtitle_text"                                 // 廣播裡字幕內容的 key
        const val ACTION_PAUSE_PLAY = "com.example.simplevttplayer.PAUSE_PLAY"          // Overlay→Main：暫停/播放切換
        const val ACTION_RESET_OVERLAY_POSITION = "com.example.simplevttplayer.RESET_OVERLAY_POSITION" // 重設浮窗位置
        const val ACTION_UPDATE_FONT_SIZE = "com.example.simplevttplayer.UPDATE_FONT_SIZE"              // 更新字體大小
        const val EXTRA_FONT_SIZE = "font_size"                                         // 廣播裡字體大小的 key
        const val ACTION_OVERLAY_SEEK = "com.example.simplevttplayer.OVERLAY_SEEK"      // Overlay→Main：跳時間
        const val ACTION_UPDATE_TIME = "com.example.simplevttplayer.UPDATE_TIME"        // 預留：時間戳更新，目前沒用
        const val ACTION_NEXT_EP = "com.example.simplevttplayer.NEXT_EP"                // Overlay→Main：下一集
        const val ACTION_OVERLAY_CLOSE = "com.example.simplevttplayer.ACTION_OVERLAY_CLOSE"//加 close 按鈕 
        val TAG: String = OverlayService::class.java.simpleName                         // Logcat tag
    }
    private lateinit var windowManager: WindowManager           // 系統 Window 管理器，用來控制浮窗
    private lateinit var overlayView: View                      // 整個 overlay_layout 的根 View
    private lateinit var textViewOverlaySubtitle: TextView      // 顯示字幕的 TextView
    private lateinit var params: WindowManager.LayoutParams     // 控制浮窗大小、位置、flags 的物件
    private var isPaused = false                                // 是否處於暫停狀態
    // 2.8: Control panel views
    private lateinit var controlPanel: View                     // 控制面板部份（包含時間輸入等）
    private lateinit var editMin: EditText                      // 控制面板上的分鐘輸入框
    private lateinit var editSec: EditText                      // 控制面板上的秒數輸入框
    private lateinit var buttonOverlayNextEp: View              // Overlay 上的「Next Ep」按鈕
    private var currentSubtitle = ""
    private var currentFontSize = 20
    
    private val subtitleUpdateReceiver = object : BroadcastReceiver() { // 接收 MainActivity 廣播
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Broadcast received! Action: ${intent?.action}")
            when (intent?.action) {
                ACTION_UPDATE_SUBTITLE -> {
                    val subtitleText = intent.getStringExtra(EXTRA_SUBTITLE_TEXT) ?: "" // 取字幕文字，沒有就空字串
                    Log.d(TAG, "Received subtitle broadcast: '$subtitleText'")
                    updateSubtitleText(subtitleText)           // 更新浮窗字幕
                }
                ACTION_RESET_OVERLAY_POSITION -> {
                    Log.d(TAG, "Received reset overlay position request")
                    resetOverlayPosition()                     // 重設浮窗位置
                }
                ACTION_UPDATE_FONT_SIZE -> {
                    val fontSize = intent.getIntExtra(EXTRA_FONT_SIZE, 20) // 預設字體大小 20
                    Log.d(TAG, "Received font size update: $fontSize")
                    updateOverlayFontSize(fontSize)            // 更新 overlay 字體大小
                }
                else -> {  Log.w(TAG, "Unknown broadcast action: ${intent?.action}")  } // 未知 Action 只記錄
            }
        }
    }
    
    override fun onBind(intent: Intent?): IBinder? {  return null  } // 不支援 bind，僅 startService 使用

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "OverlayService onCreate")
        try {
            overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null) // inflate 浮窗 layout
            textViewOverlaySubtitle = overlayView.findViewById(R.id.textViewOverlaySubtitle) // 綁定字幕 TextView
            controlPanel = overlayView.findViewById(R.id.controlPanel)     // 2.8: Get control panel views
            editMin = overlayView.findViewById(R.id.editMin)               // 綁定分鐘輸入欄位
            editSec = overlayView.findViewById(R.id.editSec)               // 綁定秒數輸入欄位
            buttonClose = overlayView.findViewById(R.id.buttonClose) //overlay關制
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager // 控制鍵盤
            editMin.setOnClickListener {
                enableOverlayInput()                                       // 移除 NOT_FOCUSABLE，允許輸入
                editMin.requestFocus()                                     // 焦點給分鐘欄位
                imm.showSoftInput(editMin, android.view.inputmethod.InputMethodManager.SHOW_FORCED) // 打開鍵盤
            }
            editSec.setOnClickListener {
                enableOverlayInput()                                       // 同上，允許輸入
                editSec.requestFocus()                                     // 焦點給秒數欄位
                imm.showSoftInput(editSec, android.view.inputmethod.InputMethodManager.SHOW_FORCED) // 打開鍵盤
            }
            setupTimeEditor(editMin)                                       // 對分鐘欄位掛 Enter/DONE 行為
            setupTimeEditor(editSec)                                       // 對秒數欄位掛 Enter/DONE 行為

            buttonOverlayNextEp = overlayView.findViewById(R.id.buttonOverlayNextEp) // 綁定「Next Ep」按鈕
            buttonOverlayNextEp.visibility = View.GONE   // 初始時就隱藏 NEXT 按鈕
            buttonOverlayNextEp.setOnClickListener {
                val intent = Intent(ACTION_NEXT_EP)                        // 建立 NEXT_EP 廣播
                LocalBroadcastManager.getInstance(this).sendBroadcast(intent) // 丟給 MainActivity 處理下一集
            }
            textViewOverlaySubtitle.setOnClickListener {
                Log.d(TAG, "Subtitle clicked - toggle pause!")
                togglePauseFromOverlay()                                   // 點字幕切換暫停/播放
            }
            val buttonMoveUp: View? = overlayView.findViewById(R.id.buttonMoveUp) // 上移浮窗的按鈕（可選）
            buttonMoveUp?.setOnClickListener {
                Log.d(TAG, "Move up button clicked!")
                moveOverlayUpByButtonClick()                               // 浮窗往上移一段距離
            }
            buttonClose.setOnClickListener {
                val intent = Intent(ACTION_OVERLAY_CLOSE)
                LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
            }
            
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager // 取得 WindowManager
            params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,                   // 寬度跟內容
                WindowManager.LayoutParams.WRAP_CONTENT,                   // 高度跟內容
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,       // 系統 overlay 類型
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN    or     // 使用整個螢幕座標
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,             // 預設不能拿焦點（不會彈鍵盤）
                PixelFormat.TRANSLUCENT                                    // 透明背景
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL      // 貼底中間
                y = 0                                                      // 初始 Y 偏移為 0
            }
            windowManager.addView(overlayView, params)                     // 把浮窗掛到螢幕上
            Log.d(TAG, "Overlay view added successfully.")

            val filter = IntentFilter().apply {
                addAction(ACTION_UPDATE_SUBTITLE)                          // 要聽字幕更新
                addAction(ACTION_PAUSE_PLAY)                               // 要聽暫停/播放
                addAction(ACTION_RESET_OVERLAY_POSITION)                   // 要聽位置重設
                addAction(ACTION_UPDATE_FONT_SIZE)                         // 要聽字體大小更新
            }
            LocalBroadcastManager.getInstance(this).registerReceiver(subtitleUpdateReceiver, filter) // 註冊接收器
            Log.d(TAG, "BroadcastReceiver registered for all actions.")
        } catch (e: Exception) {
            Log.e(TAG, "Error during OverlayService onCreate", e)
            Toast.makeText(this, "Failed to create overlay.", Toast.LENGTH_SHORT).show() // 提示使用者
            stopSelf()                                                     // 初始化失敗就停掉 Service
        }
    }

    private fun sendTimeToMainFromOverlay(min: Int, sec: Int) {
        val totalMs = (min * 60 + sec) * 1000L                             // 分→秒→毫秒
        val intent = Intent(OverlayService.ACTION_OVERLAY_SEEK)            // 建立 OVERLAY_SEEK intent
        intent.putExtra("seek_to_ms", totalMs)                             // 把 ms 放到 extra 裡
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)      // 發給 MainActivity 的接收器
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "OverlayService onStartCommand Received")               // 每次 startService 都會 log
        return START_STICKY                                                // 被系統殺掉會嘗試重啟
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "OverlayService onDestroy")
        stopForeground(true)                                               // 若有前景通知就移除
        try{if (::overlayView.isInitialized && overlayView.isAttachedToWindow) {
                windowManager.removeView(overlayView)                      // 從螢幕移除浮窗
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
        if (::textViewOverlaySubtitle.isInitialized && ::overlayView.isInitialized) { // 確保 view 已就緒
            currentSubtitle = text                                         // 記下最新字幕（目前只存，不另用）
            if (text.isBlank()) {
                if (overlayView.visibility != View.GONE) { Log.d(TAG, "Hiding overlay view (blank text received).") }
            } else { if (overlayView.visibility != View.VISIBLE) {
                        Log.d(TAG, "Showing overlay view.")
                        overlayView.visibility = View.VISIBLE  }
                val styled: CharSequence = JpGrammarHighlighter.highlight(text) // 做日文高亮
                textViewOverlaySubtitle.text = styled                      // 套用到 TextView
            }
        } else {  Log.w(TAG, "Overlay views not initialized when trying to update text ('$text').") }
    }
    
    private fun togglePauseFromOverlay() {
        isPaused = !isPaused                                               // flip 暫停狀態
        val intent = Intent(ACTION_PAUSE_PLAY).apply { putExtra("is_paused", isPaused) } // 連同 is_paused 一起廣播
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        updateSubtitlePauseState()                                         // 更新字幕顏色（紅/白）       
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
        val textColor = if (isPaused) android.graphics.Color.RED else android.graphics.Color.WHITE // 暫停 = 紅字
        textViewOverlaySubtitle.setTextColor(textColor)                    // 更新字幕顏色
        Log.d(TAG, "Updated subtitle color: isPaused=$isPaused, color=${if (isPaused) "RED" else "WHITE"}")
    }
    
    private fun moveOverlayUpByButtonClick() {
        if (::params.isInitialized && ::overlayView.isInitialized) {
            val moveDistance = 36                                          // 每次往上移動距離（px）
            Log.d(TAG, "Moving overlay up by $moveDistance pixels")
            params.y += moveDistance                                       // 修改 Y 座標
            try {
                windowManager.updateViewLayout(overlayView, params)        // 套用到畫面
                Log.d(TAG, "Overlay moved up. New Y: ${params.y}")
            } catch (e: Exception) {  Log.e(TAG, "Error moving overlay up", e)  }
        } else {  Log.w(TAG, "Cannot move overlay: views not initialized")  } // 還沒初始化就被點
    }
    
    private fun resetOverlayPosition() {
        if (::params.isInitialized && ::overlayView.isInitialized) {
            params.y = 0                                                   // 重設 Y 回到底
            try {
                windowManager.updateViewLayout(overlayView, params)        // 套用新位置
                Log.d(TAG, "Overlay position reset to Y: 0")
            } catch (e: Exception) { Log.e(TAG, "Error resetting overlay position", e) }
        } else { Log.w(TAG, "Cannot move overlay: views not initialized")  }
    }

    private fun updateOverlayFontSize(fontSize: Int) {
        currentFontSize = fontSize                                         // 記錄現在字體大小
        if (::textViewOverlaySubtitle.isInitialized) {
            textViewOverlaySubtitle.textSize = fontSize.toFloat()          // 改變 overlay 字體大小
            Log.d(TAG, "Overlay font size updated to: $fontSize")
        }
    }

    private fun enableOverlayInput() {
        if (!::params.isInitialized || !::overlayView.isInitialized || !overlayView.isAttachedToWindow) return // 防呆
        params.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN    // 拿掉 NOT_FOCUSABLE，允許輸入
        windowManager.updateViewLayout(overlayView, params)                // 套用新的 flags
    }
    private fun disableOverlayInput() {
        if (!::params.isInitialized || !::overlayView.isInitialized || !overlayView.isAttachedToWindow) return // 防呆
        params.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN  or  WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        // 加回 NOT_FOCUSABLE，回到純顯示模式
        windowManager.updateViewLayout(overlayView, params)
    }
    private fun setupTimeEditor(edit: EditText) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        edit.setOnEditorActionListener { v, actionId, event ->
            val isImeDone = actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            val isEnterKey = event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.action == android.view.KeyEvent.ACTION_UP
            if (isImeDone || isEnterKey) {
                val minVal = editMin.text.toString().toIntOrNull() ?: 0    // 解析分鐘，空或錯誤就 0
                val secVal = editSec.text.toString().toIntOrNull() ?: 0    // 解析秒數，空或錯誤就 0
                if (secVal in 0..59) {
                    Toast.makeText(this,"Overlay set to %02d:%02d".format(minVal, secVal), Toast.LENGTH_SHORT ).show()
                    sendTimeToMainFromOverlay(minVal, secVal)              // 合法秒數：通知 MainActivity 跳時間
                } else { editSec.text?.clear()  }                          // 非 0–59：清掉秒數欄位
                imm.hideSoftInputFromWindow(edit.windowToken, 0)           // 收鍵盤
                edit.clearFocus()                                          // 移除焦點
                disableOverlayInput()                                      // 把浮窗 flags 改回不可輸入
                true                                                       // 告訴系統事件已處理
            } else {  false   }                                            // 不是 Done / Enter 就不處理
        }
    }

    
}
