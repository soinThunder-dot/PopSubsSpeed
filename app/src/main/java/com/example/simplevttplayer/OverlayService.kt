// ============================================================
// 檔案：OverlayService.kt
// 用途：字幕懸浮窗服務（Overlay Service）
//   - 在其他 App 畫面之上顯示一個浮動的字幕視窗（需「顯示在其他應用程式上層」權限）
//   - 透過 LocalBroadcastManager 接收 MainActivity 傳來的字幕文字、字體大小、重置位置等指令
//   - 點擊字幕可切換 暫停/播放，並將狀態廣播回 MainActivity
//   - 提供「上移」按鈕，可逐步把浮窗往上移動
// ============================================================

// 套件名稱：必須與專案的 applicationId / 資料夾結構一致
package com.example.simplevttplayer // **IMPORTANT: Adjust package name if needed!**

// ---------- 匯入 Android 相關類別 ----------
// Service：背景服務基底類別；Toast：短暫提示訊息
import android.app.Service
import android.widget.Toast

// 廣播接收器、Context、Intent 與 IntentFilter：用於接收/傳送廣播指令
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

// PixelFormat：設定浮窗像素格式（TRANSLUCENT = 半透明背景）
import android.graphics.PixelFormat

// Build：判斷 Android 版本；IBinder：onBind 回傳型別
import android.os.Build
import android.os.IBinder

// Log：輸出除錯訊息到 Logcat
import android.util.Log

// 視圖相關：Gravity（對齊方式）、LayoutInflater（載入 XML 版面）、View、WindowManager（管理浮窗）、TextView
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView

// LocalBroadcastManager：App 內部廣播（只在本 App 內傳遞，較安全且效率高）
import androidx.localbroadcastmanager.content.LocalBroadcastManager // Use LocalBroadcastManager

// ============================================================
// OverlayService：繼承 Service，負責建立並管理字幕懸浮窗
// ============================================================
class OverlayService : Service() {

    // ---------- companion object：類別層級的常數（類似 Java 的 static） ----------
    companion object {
        // These constants MUST match the ones used in MainActivity

        // 更新字幕文字的廣播 Action 與對應的 Extra key（字幕內容）
        const val ACTION_UPDATE_SUBTITLE = "com.example.simplevttplayer.UPDATE_SUBTITLE"
        const val EXTRA_SUBTITLE_TEXT = "subtitle_text"

        // 暫停/播放切換的廣播 Action（浮窗點擊後由本服務發出給 MainActivity）
        const val ACTION_PAUSE_PLAY = "com.example.simplevttplayer.PAUSE_PLAY" 

        // 重置浮窗位置（回到最底部 y = 0）的廣播 Action
        const val ACTION_RESET_OVERLAY_POSITION = "com.example.simplevttplayer.RESET_OVERLAY_POSITION" // ✅ 新增重置 action

        // 更新浮窗字體大小的廣播 Action 與對應的 Extra key（字體大小，單位 sp）
        const val ACTION_UPDATE_FONT_SIZE = "com.example.simplevttplayer.UPDATE_FONT_SIZE"
        const val EXTRA_FONT_SIZE = "font_size"
        

        // Log 使用的標籤（TAG），自動取類別名稱 "OverlayService"
        val TAG: String = OverlayService::class.java.simpleName
    }

    // ---------- 成員變數 ----------
    // windowManager：系統視窗管理器，用來新增/更新/移除浮窗
    // overlayView：浮窗的根視圖（由 overlay_layout.xml 載入）
    // textViewOverlaySubtitle：顯示字幕文字的 TextView
    // params：浮窗的版面參數（位置、大小、旗標等），移動浮窗時會修改它
    // lateinit 表示稍後（onCreate 中）才初始化
    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var textViewOverlaySubtitle: TextView
    private lateinit var params: WindowManager.LayoutParams 

    // isPaused：記錄目前是否為暫停狀態（預設為播放中 false）
    private var isPaused = false // Track pause state

    // ============================================================
    // 廣播接收器：處理來自 MainActivity 的各種指令
    // Listens for broadcasts from MainActivity containing subtitle text
    private val subtitleUpdateReceiver = object : BroadcastReceiver() {

        // 收到廣播時被呼叫，依 Action 分派到不同處理邏輯
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Broadcast received! Action: ${intent?.action}") // ✅ 加 Log 確認收到

            // 依據 intent 的 action 做分支判斷
            when (intent?.action) {

                // 【更新字幕】取出字幕文字（若為 null 則用空字串），並更新到浮窗
                ACTION_UPDATE_SUBTITLE -> {
                    val subtitleText = intent.getStringExtra(EXTRA_SUBTITLE_TEXT) ?: ""
                    Log.d(TAG, "Received subtitle broadcast: '$subtitleText'")
                    updateSubtitleText(subtitleText)
                }

                // 【重置位置】把浮窗移回最底部
                ACTION_RESET_OVERLAY_POSITION -> { // ✅ 新增重置邏輯
                    Log.d(TAG, "Received reset overlay position request")
                    resetOverlayPosition()
                }

                // 【更新字體大小】取出字體大小（預設 20），並套用到字幕 TextView
                ACTION_UPDATE_FONT_SIZE -> {
                    val fontSize = intent.getIntExtra(EXTRA_FONT_SIZE, 20)
                    Log.d(TAG, "Received font size update: $fontSize")
                    updateOverlayFontSize(fontSize)
                }

                // 【其他/未知 Action】僅記錄警告
                // 注意：ACTION_PAUSE_PLAY 雖然有註冊（見 onCreate 的 IntentFilter），
                //       但這裡沒有對應分支，因此收到時（含本服務自己發出的）會落到此處只印出警告
                else -> {
                    Log.w(TAG, "Unknown broadcast action: ${intent?.action}")
                }
            }
        }
    }

    // ============================================================
    // onBind：本服務不支援綁定（只用 startService 啟動），因此回傳 null
    override fun onBind(intent: Intent?): IBinder? {
        // Not using binding, so return null
        return null
    }

    // ============================================================
    // onCreate：服務建立時呼叫一次，負責建立浮窗、設定監聽器、註冊廣播
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "OverlayService onCreate")

        // 使用 try-catch 包住整個初始化流程，任何錯誤都會提示使用者並停止服務
        try {
            // Inflate the overlay layout

            // 從 overlay_layout.xml 載入浮窗版面，並取得字幕 TextView
            overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null)
            textViewOverlaySubtitle = overlayView.findViewById(R.id.textViewOverlaySubtitle)
            
            // Set click listener on subtitle to toggle pause

            // 點擊字幕 → 切換暫停/播放
            textViewOverlaySubtitle.setOnClickListener {
                Log.d(TAG, "Subtitle clicked - toggle pause!")
                togglePauseFromOverlay()
            }
            

            // 取得「上移」按鈕（可為 null，以防 XML 中沒有此 ID）
            // 若存在則設定點擊事件：每按一次浮窗往上移動一段距離
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

            // 取得系統 WindowManager 服務
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            
            // Define layout parameters for the overlay window

            // 依 Android 版本選擇浮窗類型：
            //   Android 8.0 (O) 以上必須使用 TYPE_APPLICATION_OVERLAY
            //   舊版本則使用 TYPE_PHONE（已棄用）
            val layoutFlag: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }
            

            // 建立浮窗版面參數：
            //   寬高：WRAP_CONTENT（依內容自動調整）
            //   FLAG_NOT_FOCUSABLE：浮窗不搶焦點，底下的 App 仍可接收按鍵輸入
            //   FLAG_LAYOUT_IN_SCREEN：允許版面延伸到整個螢幕範圍
            //   TRANSLUCENT：支援半透明
            //   gravity：置於畫面底部、水平置中；y = 0 表示貼齊底部（y 越大越往上）
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

            // 把浮窗加入 WindowManager，正式顯示在畫面上
            windowManager.addView(overlayView, params)
            Log.d(TAG, "Overlay view added successfully.")
            
            // Register the broadcast receiver using LocalBroadcastManager

            // 建立 IntentFilter，註冊要監聽的所有 Action，
            // 再透過 LocalBroadcastManager 註冊廣播接收器
            val filter = IntentFilter().apply {
                addAction(ACTION_UPDATE_SUBTITLE) // ✅ 用 companion 中定義的常數
                addAction(ACTION_PAUSE_PLAY) // ✅ 用 companion 中定義的常數
                addAction(ACTION_RESET_OVERLAY_POSITION) // ✅ 新增重置
                addAction(ACTION_UPDATE_FONT_SIZE) // 浮窗字體大小更新
            }
            LocalBroadcastManager.getInstance(this).registerReceiver(subtitleUpdateReceiver, filter)
            Log.d(TAG, "BroadcastReceiver registered for all actions.")
            

        // 發生例外：記錄錯誤、顯示 Toast 提示，並停止服務
        } catch (e: Exception) {
            Log.e(TAG, "Error during OverlayService onCreate", e)
            Toast.makeText(this, "Failed to create overlay.", Toast.LENGTH_SHORT).show()
            stopSelf()
        }
    }

    // ============================================================
    // onStartCommand：每次 startService 時呼叫
    // START_NOT_STICKY：服務被系統終止後不會自動重新啟動
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "OverlayService onStartCommand Received")
        return START_NOT_STICKY
    }

    // ============================================================
    // onDestroy：服務銷毀時呼叫，負責清理資源（移除浮窗、取消註冊廣播）
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "OverlayService onDestroy")

        // 若浮窗已初始化且仍附著在視窗上，則將其移除，避免 WindowLeaked 錯誤
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

        // 取消註冊廣播接收器；若尚未註冊會拋出 IllegalArgumentException，這裡捕捉並記錄警告
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(subtitleUpdateReceiver)
            Log.d(TAG, "SubtitleUpdateReceiver unregistered.")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Receiver possibly already unregistered or not registered.", e)
        }
    }

    // ============================================================
    // updateSubtitleText：更新浮窗字幕文字
    //   - 先確認視圖已初始化
    //   - 文字為空白時：原本會隱藏浮窗，但隱藏那行目前被註解掉（浮窗保持顯示）
    //   - 文字不為空時：若浮窗不是可見狀態則設為可見，並更新文字
    private fun updateSubtitleText(text: String) {
        if (::textViewOverlaySubtitle.isInitialized && ::overlayView.isInitialized) {
            if (text.isBlank()) {
                if (overlayView.visibility != View.GONE) {
                    Log.d(TAG, "Hiding overlay view (blank text received).")
                    //***********overlayView.visibility = View.GONE
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

    // ============================================================
    // togglePauseFromOverlay：點擊字幕時切換暫停/播放
    //   1. 反轉 isPaused 狀態
    //   2. 發送 ACTION_PAUSE_PLAY 廣播（附帶 is_paused）通知 MainActivity
    //   3. 更新字幕顏色（暫停 = 紅色，播放 = 白色）
    //   4. 顯示 Toast 提示目前狀態
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
    

    // ============================================================
    // updateSubtitlePauseState：依暫停狀態改變字幕顏色（暫停紅色 / 播放白色）
    private fun updateSubtitlePauseState() {
        val textColor = if (isPaused) android.graphics.Color.RED else android.graphics.Color.WHITE
        textViewOverlaySubtitle.setTextColor(textColor)
        Log.d(TAG, "Updated subtitle color: isPaused=$isPaused, color=${if (isPaused) "RED" else "WHITE"}")
    }

    // ============================================================
    // moveOverlayUpByButtonClick：按「上移」按鈕時，把浮窗往上移動 36 像素
    //   因 gravity 為 BOTTOM，y 值增加代表離底部更遠（往上）
    //   修改 params.y 後需呼叫 updateViewLayout 才會生效
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
    

    // ============================================================
    // resetOverlayPosition：把浮窗位置重置回底部（y = 0）
    //   （else 分支的訊息沿用了 "Cannot move overlay"，功能上不影響）
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

    // ============================================================
    // updateOverlayFontSize：更新字幕字體大小（textSize 單位為 sp）
    private fun updateOverlayFontSize(fontSize: Int) {
        if (::textViewOverlaySubtitle.isInitialized) {
            textViewOverlaySubtitle.textSize = fontSize.toFloat()
            Log.d(TAG, "Overlay font size updated to: $fontSize")
        } else {
            Log.w(TAG, "Cannot update font size: textViewOverlaySubtitle not initialized")
        }
    }

// ---------- OverlayService 類別結束 ----------
}
