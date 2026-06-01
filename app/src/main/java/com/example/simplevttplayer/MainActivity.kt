/** * MainActivity.kt - 字幕播放器主活動檔案【核心功能總覽】
 * 1. 字幕檔案選擇與解析 *    - 支援格式：VTT (WebVTT) / SRT (SubRip) *    - 使用 SAF (Storage Access Framework) 選擇檔案 *    - 持久化 URI 權限，重啟後仍可訪問
 * 2. 精確字幕播放控制 *    - 播放/暫停/重設 - 速度調整：0.5x, 0.75x, 1.0x, 1.25x, 1.5x, 2.0x *    - 高精度時間計算（奈秒級 System.nanoTime()）- 30ms 更新間隔，流暢顯示
 * 3. 懸浮視窗服務管理 *    - 啟動/停止 OverlayService（浮動字幕窗口）- 雙向通訊：Activity ↔ Service *    - LocalBroadcastManager 廣播機制
 * 4. 自動進度儲存 (2.8 新增) *    - 每 3 分鐘自動儲存播放位置 - 暫停時立即儲存 *    - Reload Last File 功能恢復上次進度 - 儲存原始時間（不受播放速度影響）
 * 5. 日文語法高亮 (Kuromoji) *    - 詞性分析與顏色標記 - 可開關功能 * ===================================================================================
 * 【技術架構】 * - 架構模式：傳統 Activity 架構（未使用 ViewModel） * - 通訊機制：LocalBroadcastManager（Activity ↔ Service）
 * - 資料持久化：SharedPreferences * - 定時任務：Handler + Runnable（主線程） * - 播放計時：System.nanoTime()（高精度奈秒級） * ===================================================================================
 * 【關鍵元件】 * - Material Slider：拖曳式進度條，支援即時預覽 * - Spinner：速度選擇下拉選單
 * - ActivityResultLauncher：檔案選擇器 & 權限請求 * - BroadcastReceiver：監聽 Service 事件 * - Handler：30ms 更新循環 + 3分鐘自動儲存
 * @author so-dot @version 2.8_super_overlay @since 2026-05-27 */
package com.example.simplevttplayer

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.slider.Slider.OnChangeListener
import com.google.android.material.slider.Slider.OnSliderTouchListener
import java.io.BufferedReader
import java.io.InputStream
import java.net.URLEncoder

class MainActivity : AppCompatActivity() {
    companion object {    // 【Companion Object】靜態常數與類別級別變數
        /** 【廣播 Action 常數】與 OverlayService 通訊* 從 OverlayService 引用確保兩邊完全一致，避免打字錯誤*/
        private const val ACTION_UPDATE_SUBTITLE_LOCAL = OverlayService.ACTION_UPDATE_SUBTITLE
        private const val EXTRA_SUBTITLE_TEXT_LOCAL = OverlayService.EXTRA_SUBTITLE_TEXT
        /*** 【日誌標籤】用於 Logcat 過濾 * 使用類別簡稱作為 TAG，方便追蹤         */
        private val TAG: String = MainActivity::class.java.simpleName
        /**         * 【SharedPreferences 檔名】         * 儲存 app 所有持久化資料的 SP 檔案名稱         */
        private const val PREFS_NAME = "popsubs_prefs"
        /**         * 【儲存鍵】上次使用的字幕檔案 URI         * 值類型：String (Uri.toString())
         * 用途：Reload Last File 功能         */
        private const val KEY_LAST_SUBTITLE_URI = "last_subtitle_uri"
        /**         * 【2.8 新增】儲存鍵 - 上次播放位置的原始時間戳記         * 值類型：Long (毫秒)
         * 重要：儲存的是「原始時間」，未受播放速度影響
         * 範例說明：         * - 以 2.0x 速度播放到實際 10 秒時         * - 字幕實際進度是 20 秒（10秒 × 2.0倍速）
         * - 此處儲存 20000ms（原始進度），而非 10000ms 
         * 為什麼儲存原始時間？         * - 與字幕檔案的時間軸一致
         * - Reload 後無論什麼播放速度都能回到正確位置         * - pausedElapsedTimeMillis 本身就是原始時間         */
        private const val KEY_LAST_TIMESTAMP = "last_timestamp_ms"
        /**         * 【2.8 新增】自動儲存間隔         * 值：180,000 毫秒 = 3 分鐘
         *          * 觸發時機：         * - 播放期間，每 3 分鐘自動儲存一次當前進度
         * - startPlayback() 時啟動定時器         * - pausePlayback() 時停止定時器（並立即儲存一次）         */
        private const val AUTO_SAVE_INTERVAL_MS = 180_000L
        private const val GOOGLE_BASE = "https://www.google.com/search?udm=14&q="// Google 搜尋 base URL（固定帶 udm=14）+站台 group
        private const val SITE_GROUP_ALL = "(site:kitsunekko.net OR site:jimaku.cc OR site:sub-scene.com OR site:subdl.com OR site:opensubtitles.org)"
    }
    private lateinit var editTextQuery: EditText
    // 【ActivityResultLauncher】檔案選擇器 & 權限請求啟動器
    /**     * 【Overlay 權限請求啟動器】     * 用途：請求 SYSTEM_ALERT_WINDOW 權限（顯示在其他應用上層）
     *      * 流程：     * 1. 使用者點擊「啟動 Overlay」按鈕     * 2. checkOverlayPermission() 檢查權限
     * 3. 若無權限 → requestOverlayPermission() → 啟動此 launcher     * 4. 跳轉到系統設定頁面："允許顯示在其他應用程式上層"
     * 5. 使用者授權後返回 → callback 中檢查權限 → startOverlayService()
     *      * Android 版本差異：     * - Android M (6.0) 以上需要此權限     * - Android M 以下自動授予     */
    private lateinit var overlayPermissionLauncher: ActivityResultLauncher<Intent>
    // 【BroadcastReceiver】接收來自 OverlayService 的廣播    
    /**     * 【Overlay 暫停/播放接收器】     *      * 監聽事件：OverlayService.ACTION_PAUSE_PLAY
     *      * 使用場景：     * - 使用者點擊浮動字幕（Overlay）切換暫停/播放     * - OverlayService 發送廣播通知 MainActivity
     * - MainActivity 同步更新播放狀態     * 
     * Intent Extra：     * - "is_paused" (Boolean)
     *   - true  → MainActivity 呼叫 pausePlayback()     *   - false → MainActivity 呼叫 startPlayback()
     * 為什麼需要這個？     * - OverlayService 無法直接呼叫 MainActivity 的方法
     * - 透過廣播實現鬆耦合的雙向通訊     * - 確保兩邊播放狀態同步     */
    private val overlayPausePlayReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == OverlayService.ACTION_PAUSE_PLAY) {            // 檢查 Action 是否為暫停/播放事件
                // 取得暫停狀態
                val isPaused = intent.getBooleanExtra("is_paused", false)
                // 同步 MainActivity 的播放狀態
                if (isPaused) {
                    if (isPlaying) pausePlayback()                    // Overlay 已暫停 → MainActivity 也暫停
                } else {
                    if (!isPlaying) startPlayback()                    // Overlay 已播放 → MainActivity 也播放
                }
            }
        }
    }
    private val overlayNextEpReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == OverlayService.ACTION_NEXT_EP) { tryLoadNextEpisode() }
        }
    }
    /**     * 【2.8 新增】Overlay 控制面板接收器     * 
     * 監聽事件：
     * ===== ACTION_OVERLAY_SEEK =====
     * Intent Extra：     * - "seek_to_ms" (Long) - 目標時間（毫秒）     * 
     * 處理邏輯：     * 1. 更新 pausedElapsedTimeMillis = seekToMs     * 2. 重新計算 startTimeNanos（當前系統時間 - 目標時間）
     * 3. 同步 Slider 位置     * 4. 播放會從新位置繼續（updateRunnable 會讀取 startTimeNanos）     */
    private val overlayControlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                OverlayService.ACTION_OVERLAY_SEEK -> { // Overlay 進度 → MainActivity 跳轉
                    val seekToMs = intent.getLongExtra("seek_to_ms", 0L)
                    Log.d(TAG, "Overlay seek to: ${formatTime(seekToMs)}")
                    pausedElapsedTimeMillis = seekToMs// 更新暫停時的累積時間（原始時間）
                    // 重新計算播放開始時間點    // 公式：startTimeNanos = 當前系統時間 - (目標時間 × 1,000,000)
                    // 為什麼乘以 1,000,000？因為 nanoTime 是奈秒，seekToMs 是毫秒
                    startTimeNanos = System.nanoTime() - (seekToMs * 1_000_000)
                    sliderPlayback.value = seekToMs.toFloat()          //1. 同步 Slider 位置
                    textViewYellowTime.text = formatTime(seekToMs)    // 2. 更新 slider 與畫面上的時間文字
                    textViewCurrentTime.text = formatTime((seekToMs * playbackSpeed).toLong())
                    val cue = findCueForTime((seekToMs * playbackSpeed).toLong())
                    val newText = cue?.text ?: ""// 3. 找出此時間點對應的字幕，更新主畫面 + 通知 Overlay
                    textViewSubtitle.text = newText
                    sendSubtitleUpdate(newText)
                }
                OverlayService.ACTION_NEXT_EP -> {
                    tryLoadNextEpisode()
                }
            }
        }
    }
    // 【UI 元件】View 引用
    private lateinit var buttonSelectFile: MaterialButton    /** 【按鈕】選擇字幕檔案 */
    private lateinit var buttonReloadLast: MaterialButton  /** 【按鈕】重新載入上次檔案 (2.8: 會恢復上次播放位置) */
    private lateinit var buttonPlayPause: MaterialButton  /** 【按鈕】播放/暫停切換 */
    private lateinit var buttonReset: MaterialButton   /** 【按鈕】重設（回到 00:00.000，清空字幕） */
    private lateinit var buttonLaunchOverlay: MaterialButton    /** 【按鈕】啟動/關閉 Overlay 服務 */
    private lateinit var textViewFilePath: TextView    /** 【文字】顯示當前檔案路徑/名稱 */
    private lateinit var textViewCurrentTime: TextView    /** 【文字】顯示當前播放時間（受速度影響） */
    private lateinit var textViewSubtitle: TextView    /** 【文字】顯示當前字幕內容（主要顯示區） */
    private lateinit var sliderPlayback: Slider    /** 【滑桿】Material Slider，拖曳控制播放進度 */
    private lateinit var spinnerSpeed: Spinner    /** 【下拉選單】播放速度選擇器 (6檔變速) */
    private lateinit var textViewYellowTime: TextView    /** 【文字】黃色時間顯示（原始時間，不受速度影響） */
    private lateinit var editTextOverlayFontSize: android.widget.EditText    /** 【輸入框】Overlay 字體大小調整 */
    private lateinit var textViewJpToggle: TextView    /** 【文字】Kuromoji 日文語法高亮開關指示器 */
    // 【播放狀態變數】  /**     * 【Kuromoji 啟用狀態】     * true = 啟用日文詞性分析與顏色標記
    //* false = 純文字顯示     * 可透過 textViewJpToggle 點擊切換     * 變更時會同步更新 JpGrammarHighlighter.enabled
    private var kuromojiEnabled: Boolean = true
    /**     * 【字幕 Cue 列表】     * 儲存所有解析完成的字幕條目
     *      * 結構：List<SubtitleCue>     * - SubtitleCue(startTimeMs, endTimeMs, text)
     *      * 排序：按 startTimeMs 由小到大排序     * 
     * 用途：     * - 播放時根據當前時間查找對應字幕     * - findCueForTime() 遍歷此列表尋找匹配的 cue
     *      * 更新時機：     * - loadAndParseSubtitleFile() 解析完成後更新     * - handleSubtitleFileSelected() 開頭清空 (emptyList())*/
    private var subtitleCues: List<SubtitleCue> = emptyList()
    /**     * 【當前選擇的字幕檔案 URI】     *      * 類型：Uri? (nullable)
     *      * 用途：     * - 記錄使用者選擇的檔案位置     * - 重新載入功能的依據
     *      * 更新時機：     * - selectSubtitleFileLauncher callback     * - buttonReloadLast 點擊（從 SharedPreferences 讀取）*/
    private var selectedFileUri: Uri? = null
    /**     * 【主線程 Handler】     * 
     * 用途：     * 1. updateRunnable - 每 30ms 更新播放進度     * 2. 其他需要延遲執行的任務
     *      * 為什麼需要？     * - Android UI 更新必須在主線程     * - Handler.post() 將 Runnable 加入主線程訊息佇列
     * - Handler.postDelayed() 實現定時循環     */
    private val handler = Handler(Looper.getMainLooper())
    /**     * 【2.8 新增】自動儲存 Handler     * 
     * 用途：專門處理 3 分鐘定期儲存任務     *      * 為什麼分離？ 
     * - 與 handler (30ms 更新) 分開，避免相互干擾     * - autoSaveRunnable 只在播放時運行     * - 邏輯清晰，易於維護     */
    private val autoSaveHandler = Handler(Looper.getMainLooper())
    /**     * 【播放狀態標記】     * 
     * true  = 正在播放      - updateRunnable 每 30ms 執行 - autoSaveRunnable 每 3 分鐘執行   - 螢幕保持喚醒 (FLAG_KEEP_SCREEN_ON)
     * false = 已暫停     *       - updateRunnable 停止     *       - autoSaveRunnable 停止     *       - 螢幕可自動關閉     */
    private var isPlaying = false
    /**     * 【播放開始時的系統奈秒時間】     *      * 用途：高精度播放計時     * 
     * 計時公式：     * ```     * 經過時間(ms) = (System.nanoTime() - startTimeNanos) / 1,000,000     * ```     * 
     * 為什麼用 nanoTime？     * - System.currentTimeMillis() 會受系統時間調整影響
     * - System.nanoTime() 是單調遞增，不受時區/夏令時影響     * - 精度更高（奈秒級），避免累積誤差
     *      * 更新時機：
     * 1. startPlayback() - 計算新的起始點     * 2. 速度變更 - 重新計算以補償速度差異     * 3. Slider 拖曳結束 - 跳轉到新位置*/
    private var startTimeNanos: Long = 0L
    /**     * 【暫停時的累積播放時間】單位：毫秒     *      * 重要概念：這是「原始時間」，不受播放速度影響！     * 
     * 範例說明：     * - 以 2.0x 播放 10 秒     * - 實際經過時間 = 10 秒     * - 字幕進度 = 10 × 2.0 = 20 秒     * - pausedElapsedTimeMillis = 20,000ms
     *      * 為什麼儲存原始時間？     * 1. 與字幕檔案時間軸一致     * 2. 變速後仍能正確查找字幕     * 3. 儲存進度時統一標準     * 
     * 更新時機：     
     * 1. pausePlayback() - 計算當前位置     * 2. Slider 拖曳 - 使用者手動設定     * 3. Overlay seek - 遠端控制跳轉     * 4. resetPlayback() - 歸零
     * 用途：     * 1. 恢復播放的起點     * 2. 自動儲存進度 (KEY_LAST_TIMESTAMP)     * 3. Slider 當前值     */
    private var pausedElapsedTimeMillis: Long = 0L
    /**     * 【Overlay UI 顯示狀態】     * 
     * true  = OverlayService 運行中，顯示浮動字幕     * false = OverlayService 已停止，隱藏浮動字幕
     *      * 控制邏輯：     * - buttonLaunchOverlay 點擊時切換     * - sendSubtitleUpdate() 根據此值決定是否傳送字幕文字     */
    private var isOverlayUIShown = true
    /**     * 【播放速度倍率】     *      * 預設：1.0x (正常速度)     * 範圍：0.5x ~ 2.0x     * 
     * Spinner 對應：     * - Position 0 → 0.5x     * - Position 1 → 0.75x     * - Position 2 → 1.0x (預設)     * - Position 3 → 1.25x    * - Position 4 → 1.5x     * - Position 5 → 2.0x
     * 影響範圍：     * 1. 字幕查詢時間 = (經過時間 × playbackSpeed)     * 2. textViewCurrentTime 顯示（加速時間）     * 3. startTimeNanos 重新計算（變速時補償）
     * 不影響：     * - pausedElapsedTimeMillis（永遠是原始時間）     * - textViewYellowTime（黃色時間，固定顯示原始時間）     */
    private var playbackSpeed: Float = 1.0f
    // 【Data Class】字幕 Cue 資料結構   
    /**     * 【字幕條目資料類別】     *      * 代表一個字幕 cue（subtitle cue），包含時間與文字資訊
     *      * @property startTimeMs 開始時間（毫秒）     *       - 相對於字幕檔案開頭 (00:00.000)  - 字幕出現的時間點
     * @property endTimeMs   結束時間（毫秒）  - 字幕消失的時間點    - 必須 > startTimeMs
     * @property text        字幕文字內容  - 原始文字（未經 Kuromoji 處理）  - 可能包含換行符 \n   - VTT 可能包含 HTML 標籤（目前未處理）
     * 使用範例：
     * SubtitleCue(
     *     startTimeMs = 5000,  // 00:05.000
     *     endTimeMs = 8000,    // 00:08.000
     *     text = "こんにちは、世界！"
     * )
     * 查詢邏輯：     * // 找出當前時間對應的字幕
     * val cue = subtitleCues.find { 
     *     currentTime >= it.startTimeMs && currentTime < it.endTimeMs 
     * }
     * 注意事項：
     * 1. 時間單位統一為毫秒（ms）     * 2. 區間為 [startTimeMs, endTimeMs)，左閉右開
     * 3. text 可能為空字串（空白字幕）     * 4. 多個 cue 可能時間重疊（罕見，但可能發生）     */
    data class SubtitleCue(
        val startTimeMs: Long,
        val endTimeMs: Long,
        val text: String
    )
    /**
     * 【2.8 新增】自動儲存 Runnable    *      * 功能：每 3 分鐘自動儲存播放進度到 SharedPreferences
     * 執行邏輯：     * 1. 檢查是否需要儲存（播放過且有進度）     * 2. 呼叫 saveCurrentTimestamp()     * 3. 再次排程 3 分鐘後執行（遞迴排程）
     * 啟動時機：     * - startPlayback() 中呼叫 autoSaveHandler.postDelayed()
     * 停止時機：     * - pausePlayback() 中呼叫 autoSaveHandler.removeCallbacks()     * - onDestroy() 確保清理
     * 為什麼需要自動儲存？     * - 防止 app 異常關閉導致進度丟失     * - 長時間播放時定期備份     * - 無需使用者手動操作     */
    private val autoSaveRunnable = object : Runnable {
        override fun run() {
            saveCurrentTimestamp()            // 儲存當前播放進度
            autoSaveHandler.postDelayed(this, AUTO_SAVE_INTERVAL_MS)            // 再次排程 3 分鐘後執行（遞迴自排程）
        }
    }
    //「Try next ep」按鈕：用 SAF 在同目錄找新檔,原本用 ACTION_OPEN_DOCUMENT 選了一個檔案並且 takePersistableUriPermission，現在可以把這個 uri 存起來，例如在 MainActivity 裡有個變數：
    private var lastSubtitleUri: Uri? = null
    // 【核心函數 1】字幕檔案處理
    /**     * 【處理字幕檔案選擇】     *      * 當使用者從檔案選擇器選擇字幕檔後，此函數統一處理所有相關邏輯
     * 【執行流程】     * 
     * 步驟 1：記錄 URI     *   - 儲存到 selectedFileUri     *   - 持久化到 SharedPreferences (KEY_LAST_SUBTITLE_URI)     *   → 用途：下次啟動可透過 Reload Last File 快速載入
     * 步驟 2：清空舊資料     *   - subtitleCues = emptyList()     *   → 防止新舊字幕混用     * 
     * 步驟 3：重設播放狀態     *   - 呼叫 resetPlayback() × 2（為何兩次？可能是確保完全重置）     *   - 暫停播放     *   - 時間歸零     *   - UI 重置     * 
     * 步驟 4：取得檔案名稱     *   - 呼叫 getFileName(uri)     *   - 從 ContentResolver 查詢 DISPLAY_NAME     * 
     * 步驟 5：判斷格式並解析     *   - .vtt → loadAndParseSubtitleFile(uri, "vtt")     *   - .srt → loadAndParseSubtitleFile(uri, "srt")     *   - 其他  → Toast 錯誤訊息 + resetPlaybackStateOnError()
     * 【參數】
     * @param uri 字幕檔案的 URI     *            - 來源：Storage Access Framework (SAF)     *            - 協議：通常是 content://     *            - 需持久化權限才能重啟後訪問
     * 【副作用】（會修改的狀態）
     * 1. selectedFileUri = uri     * 2. SharedPreferences 寫入 KEY_LAST_SUBTITLE_URI     * 3. subtitleCues = emptyList()     * 4. isPlaying = false
     * 5. pausedElapsedTimeMillis = 0L     * 6. startTimeNanos = 0L     * 7. UI 更新（textViewFilePath, textViewSubtitle, slider, 等）
     * 【錯誤處理】
     * 情況 1：getFileName() 返回 null     *   → Toast: "File name error"     *   → 呼叫 resetPlaybackStateOnError()
     * 情況 2：副檔名不是 .vtt 或 .srt     *   → Toast: "Not VTT/SRT"     *   → 呼叫 resetPlaybackStateOnError()
     * 【呼叫來源】     * 1. selectSubtitleFileLauncher 的 callback（檔案選擇器返回）     * 2. buttonReloadLast 點擊事件（Reload Last File）
     * 【注意事項】     * - 函數內呼叫了兩次 resetPlayback()，第二次可能是冗餘     * - 不檢查 URI 有效性（假設 SAF 返回的 URI 必定有效）     * - 不驗證檔案大小（超大檔案可能導致 OOM）     */
    private fun handleSubtitleFileSelected(uri: Uri) {
        selectedFileUri = uri        // 步驟 1：記錄並持久化 URI
        lastSubtitleUri = uri        //2. 「Try next ep」按鈕：用 SAF 在同目錄找新檔
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit().putString(KEY_LAST_SUBTITLE_URI, uri.toString()).apply()
        Log.d(TAG, "Subtitle file selected: $uri")
        // 步驟 2 & 3：清空舊資料並重設
        subtitleCues = emptyList()  // 清空舊字幕列表
        resetPlayback()             // 第一次重設
        val fileName = getFileName(uri)        // 步驟 4：取得檔案名稱
        resetPlayback()  // 第二次重設（可能是確保完全清除狀態）
        if (fileName != null) {
            textViewFilePath.text = "File: $fileName"            // 步驟 5：更新 UI 顯示檔案名稱
            Log.d(TAG, "File name: $fileName")
            when {            // 步驟 6：根據副檔名選擇解析器
                fileName.lowercase().endsWith(".vtt") -> {                // VTT 格式 (WebVTT)
                    Log.d(TAG, "Parsing as VTT format")
                    loadAndParseSubtitleFile(uri, "vtt")
                }
                fileName.lowercase().endsWith(".srt") -> {                // SRT 格式 (SubRip)
                    Log.d(TAG, "Parsing as SRT format")
                    loadAndParseSubtitleFile(uri, "srt")
                }
                else -> {                // 不支援的格式
                    Log.w(TAG, "Unsupported file format: $fileName")
                    Toast.makeText(this, "Not VTT/SRT", Toast.LENGTH_SHORT).show()
                    resetPlaybackStateOnError()
                }
            }
        } else {            // 檔名取得失敗（可能是權限或 URI 無效）
            Log.e(TAG, "Failed to get file name from URI: $uri")
            Toast.makeText(this, "File name error", Toast.LENGTH_SHORT).show()
            resetPlaybackStateOnError()
        }
    }
    /**     * 【檔案選擇器啟動器】     *      * 使用 ActivityResultLauncher 註冊檔案選擇器回調
     * 【執行流程】
     * 1. 使用者點擊 "Select File" 按鈕     * 2. openFilePicker() 啟動系統檔案選擇器     * 3. 使用者選擇檔案並確認     * 4. 此 launcher 的 callback 被呼叫
     * 5. 檢查 resultCode == RESULT_OK     * 6. 取得 URI：result.data?.data     * 7. 嘗試持久化 URI 權限（takePersistableUriPermission）     * 8. 呼叫 handleSubtitleFileSelected(uri)
     * 【持久化權限處理】
     * 為什麼需要？     * - SAF 返回的 URI 預設只在當前 session 有效     * - app 重啟後無法訪問相同 URI     * - takePersistableUriPermission() 可延長權限到永久
     * try-catch 處理：     * - 某些情況下會拋出 SecurityException     * - 例如：使用者選擇的是暫存檔案     * - Catch 後忽略錯誤，繼續載入檔案     * - 影響：Reload Last File 可能無效，但當次載入正常     * 
     * 【錯誤處理】     * 
     * 情況 1：resultCode != RESULT_OK     *   → 使用者取消選擇     *   → 不執行任何動作     * 
     * 情況 2：result.data?.data == null     *   → 系統未返回 URI（罕見）     *   → 不執行任何動作     * 
     * 情況 3：takePersistableUriPermission 拋出 SecurityException     *   → Catch 忽略     *   → 繼續執行 handleSubtitleFileSelected     *   → 當次載入正常，但重啟後無法 Reload
     * 【ActivityResultContracts.StartActivityForResult】    *      * 這是新版 Activity Result API（取代舊的 startActivityForResult）     * 
     * 優點：     * - 類型安全     * - 在 onCreate 前註冊（避免狀態丟失）     * - Lambda 風格，更簡潔     */
    private val selectSubtitleFileLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {        // 檢查使用者是否確認選擇（未取消）
            result.data?.data?.also { uri ->            // 取得選擇的檔案 URI                // 嘗試持久化 URI 權限                // 持久化後，app 重啟後仍可訪問此 URI
                try {
                    val flags = result.data?.flags ?: 0
                    contentResolver.takePersistableUriPermission(
                        uri,
                        flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    Log.d(TAG, "Persistable URI permission granted: $uri")
                } catch (e: SecurityException) {                    // 權限無法持久化（例如暫存檔案）
                    Log.w(TAG, "Failed to take persistable URI permission", e)                    // 忽略錯誤，繼續載入
                }
                handleSubtitleFileSelected(uri)                // 處理選擇的檔案
            }
        } else {            // 使用者取消選擇
            Log.d(TAG, "File selection cancelled")
        }
    }
    // 【核心函數 2】Activity 生命週期
    /**     * 【Activity 創建】     *      * Activity 生命週期第一個被呼叫的方法
     * 【執行內容】     * 
     * 階段 1：基礎初始化     *   - super.onCreate()     *   - setContentView(R.layout.activity_main)     * 
     * 階段 2：註冊 ActivityResultLauncher     *   - overlayPermissionLauncher（Overlay 權限請求）     * 
     * 階段 3：View 綁定     *   - findViewById() 取得所有 UI 元件引用     *   - 按鈕、文字、滑桿、下拉選單等     * 
     * 階段 4：設定按鈕監聽器     *   - buttonSelectFile → openFilePicker()     *   - buttonReloadLast → 重載上次檔案 + 恢復進度     *   - buttonPlayPause → togglePlayPause()
     *   - buttonReset → resetPlayback()     *   - buttonLaunchOverlay → 切換 Overlay 服務     *   - textViewJpToggle → 切換 Kuromoji 高亮     * 
     * 階段 5：設定其他 UI 監聽器     *   - editTextOverlayFontSize → 字體大小變更監聽     *   - Slider → setupSliderListener()     *   - Spinner → setupSpeedSpinner()     * 
     * 階段 6：註冊 BroadcastReceiver     *   - overlayPausePlayReceiver（監聽 Overlay 暫停/播放）     *   - overlayControlReceiver（監聽 Overlay 控制面板）     *      * ===================================================================================
     * 【重要細節】     * ===== Reload Last File 邏輯 =====
     * ```kotlin
     * buttonReloadLast.setOnClickListener {     *     // 1. 從 SharedPreferences 讀取上次的 URI
     *     val uriString = prefs.getString(KEY_LAST_SUBTITLE_URI, null)     *          *     // 2. 檢查是否有上次的檔案
     *     if (uriString.isNullOrEmpty()) {
     *         Toast.makeText(this, "No last file to reload.", Toast.LENGTH_SHORT).show()
     *         return@setOnClickListener
     *     }     *     
     *     // 3. 解析 URI 並載入檔案
     *     val uri = Uri.parse(uriString)
     *     handleSubtitleFileSelected(uri)    *     
     *     // 4. 【2.8 新增】恢復上次儲存的播放位置
     *     val savedTimestamp = prefs.getLong(KEY_LAST_TIMESTAMP, 0L)
     *     if (savedTimestamp > 0L && subtitleCues.isNotEmpty()) {
     *         // 設定播放位置到上次進度
     *         pausedElapsedTimeMillis = savedTimestamp
     *         sliderPlayback.value = savedTimestamp.toFloat()     *         
     *         // 更新時間顯示
     *         textViewYellowTime.text = formatTime(savedTimestamp)
     *         textViewCurrentTime.text = formatTime((savedTimestamp * playbackSpeed).toLong())     *         
     *         // 顯示 Toast 提示
     *         Toast.makeText(this, "已恢復至 ${formatTime(savedTimestamp)}", Toast.LENGTH_SHORT).show()
     *     }
     * }     * ```     * 
     * ===== Overlay 字體大小同步 =====     * - MainActivity 輸入框改變時，廣播通知 OverlayService     * - OverlayService 接收後更新所有字幕視窗字體     * 
     * ===== Kuromoji 切換 UI =====     * - textViewJpToggle 顯示狀態（綠色 ON / 灰色 OFF）     * - 點擊切換 kuromojiEnabled     * - 同步更新 JpGrammarHighlighter.enabled     * - 重新傳送當前字幕以刷新顯示     *      * ===================================================================================
     * 【BroadcastReceiver 註冊】     * ==================================================================================     * 
     * overlayPausePlayReceiver：     *   - Action: OverlayService.ACTION_PAUSE_PLAY     *   - 用途：Overlay 點擊暫停時同步 MainActivity     * 
     * overlayControlReceiver：     *   - Action 1: OverlayService.ACTION_OVERLAY_SPEED_CHANGE     *   - Action 2: OverlayService.ACTION_OVERLAY_SEEK     *   - 用途：Overlay 控制面板操作時同步 MainActivity     * 
     * 使用 LocalBroadcastManager：     *   - 僅限 app 內部通訊     *   - 比系統級 BroadcastReceiver 更安全、更快     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Log.d(TAG, "MainActivity onCreate")        // ===============================================================================
        // 【階段 1】註冊 ActivityResultLauncher        // ===============================================================================        
        /**         * Overlay 權限請求 Launcher         * 
         * 流程：         * 1. requestOverlayPermission() 呼叫 launcher.launch()         * 2. 跳轉系統設定頁面         * 3. 使用者授權後返回         * 4. Callback 中檢查權限並啟動服務         */
        overlayPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {            // 權限請求返回後，檢查是否已授予
            if (checkOverlayPermission()) {
                Log.d(TAG, "Overlay permission granted, starting service")
                startOverlayService()
            } else {
                Log.w(TAG, "Overlay permission denied")
                Toast.makeText(this, "需要懸浮視窗權限", Toast.LENGTH_SHORT).show()
            }
        }        // ===============================================================================
        // 【階段 2】View 綁定        // Kuromoji 切換指示器
        textViewJpToggle = findViewById(R.id.textViewJpToggle)        
        // 按鈕群組
        buttonSelectFile = findViewById(R.id.buttonSelectFile)
        buttonReloadLast = findViewById(R.id.buttonReloadLastFile)
        buttonPlayPause = findViewById(R.id.buttonPlayPause)
        buttonReset = findViewById(R.id.buttonReset)
        buttonLaunchOverlay = findViewById(R.id.buttonLaunchOverlay)        
        // 文字顯示
        textViewFilePath = findViewById(R.id.textViewFilePath)
        textViewCurrentTime = findViewById(R.id.textViewCurrentTime)
        textViewSubtitle = findViewById(R.id.textViewSubtitle)
        textViewYellowTime = findViewById(R.id.textViewYellowTime)        
        // 播放控制
        sliderPlayback = findViewById(R.id.sliderPlayback)
        spinnerSpeed = findViewById(R.id.spinnerSpeed)        
        // Overlay 控制
        editTextOverlayFontSize = findViewById(R.id.editTextOverlayFontSize)       
        editTextQuery = findViewById(R.id.editTextQuery)
        editTextQuery.setOnEditorActionListener { _, actionId, _ ->       // 監聽鍵盤上的 Search / Enter
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                doSearch()  /** 告訴系統「已處理」*/
                true } else { false }  // 交給系統預設處理     
        }   
        // 【階段 3】按鈕監聽器        // ===============================================================================        
        /**         * 【選擇檔案按鈕】         * 開啟系統檔案選擇器（SAF）         */
        buttonSelectFile.setOnClickListener {
            Log.d(TAG, "Select file button clicked")
            openFilePicker()
        }
        /**         * 【Reload Last File 按鈕】         * 重新載入上次使用的字幕檔案         * 2.8 新增：同時恢復上次播放位置         */
        buttonReloadLast.setOnClickListener {
            Log.d(TAG, "Reload last file button clicked")            
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val uriString = prefs.getString(KEY_LAST_SUBTITLE_URI, null)            
            if (uriString.isNullOrEmpty()) {      // 檢查是否有上次的檔案記錄
                Toast.makeText(this, "No last file to reload.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            } // 解析 URI 並載入檔案
            val uri = Uri.parse(uriString)
            handleSubtitleFileSelected(uri)            
            val savedTimestamp = prefs.getLong(KEY_LAST_TIMESTAMP, 0L)  // 【2.8 新增】恢復上次儲存的播放位置
            if (savedTimestamp > 0L && subtitleCues.isNotEmpty()) {
                Log.d(TAG, "Restoring timestamp: ${formatTime(savedTimestamp)}")
                pausedElapsedTimeMillis = savedTimestamp                // 設定播放位置
                sliderPlayback.value = savedTimestamp.toFloat()
                textViewYellowTime.text = formatTime(savedTimestamp)    // 更新時間顯示
                textViewCurrentTime.text = formatTime((savedTimestamp * playbackSpeed).toLong())
                Toast.makeText(                // 提示使用者
                    this, 
                    "已恢復至 ${formatTime(savedTimestamp)}", 
                    Toast.LENGTH_SHORT
                ).show()
            }
        }        /**         * 【播放/暫停按鈕】         * 切換播放狀態         */
        buttonPlayPause.setOnClickListener {
            Log.d(TAG, "Play/Pause button clicked")
            togglePlayPause()
        }        /**         * 【重設按鈕】         * 回到 00:00.000，清空字幕顯示         */
        buttonReset.setOnClickListener {
            Log.d(TAG, "Reset button clicked")
            resetPlayback()
        }        // ===============================================================================
        // 【階段 4】Kuromoji 切換        // ===============================================================================
        /**         * 更新 Kuromoji 切換指示器的顏色         * - 啟用：綠色 (#4CAF50)         * - 停用：灰色 (#9E9E9E)         */
        fun updateJpToggleUi() {
            textViewJpToggle.setTextColor(
                if (kuromojiEnabled) { Color.parseColor("#4CAF50")    // 綠色 ON
                } else {               Color.parseColor("#9E9E9E") }) // 灰色 OFF
        }// 初始化顯示狀態
        updateJpToggleUi()/**         * 點擊切換 Kuromoji 功能         */
        textViewJpToggle.setOnClickListener {          
            kuromojiEnabled = !kuromojiEnabled         // 切換狀態  
            JpGrammarHighlighter.enabled = kuromojiEnabled            // 同步到 highlighter            
            updateJpToggleUi()            // 更新 UI 顏色
            sendSubtitleUpdate(textViewSubtitle.text.toString())            // 重新傳送當前字幕以刷新顯示            
            Log.d(TAG, "Kuromoji toggled: $kuromojiEnabled")
        }        // ===============================================================================
        // 【階段 5】Overlay 字體大小監聽        // ===============================================================================        
        /**         * Overlay 字體大小輸入框監聽器         *          * 當使用者在 MainActivity 改變字體大小時：
         * 1. 解析輸入的數字（預設 20）         * 2. 建立 ACTION_UPDATE_FONT_SIZE 廣播         * 3. 傳送給 OverlayService         * 4. OverlayService 更新所有字幕視窗字體         */
        editTextOverlayFontSize.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {    }            // 文字改變前（不需處理）
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {     }           // 文字改變中（不需處理）
            override fun afterTextChanged(s: android.text.Editable?) {                // 文字改變後（處理新值）                
                val fontSize = s?.toString()?.toIntOrNull() ?: 20                // 解析字體大小（無效輸入預設為 20）                
                val intent = Intent(OverlayService.ACTION_UPDATE_FONT_SIZE)                // 建立廣播 Intent
                intent.putExtra(OverlayService.EXTRA_FONT_SIZE, fontSize)
                LocalBroadcastManager.getInstance(this@MainActivity).sendBroadcast(intent)                // 傳送給 OverlayService
                Log.d(TAG, "Font size updated: $fontSize")
            }
        })        /**         * 【啟動 Overlay 按鈕】         * 切換 OverlayService 的運行狀態         */
        buttonLaunchOverlay.setOnClickListener {            // 切換顯示狀態
            isOverlayUIShown = !isOverlayUIShown            
            if (isOverlayUIShown) {                // 啟動 Overlay
                Log.d(TAG, "Launching overlay service")                
                if (checkOverlayPermission()) {               // 檢查權限
                    startOverlayService()                    // 有權限：直接啟動服務
                } else { requestOverlayPermission()}        // 無權限：請求權限
                sendSubtitleUpdate(textViewSubtitle.text.toString())  // 傳送當前字幕到 Overlay
            } else {                // 關閉 Overlay
                Log.d(TAG, "Stopping overlay service")
                sendSubtitleUpdate("")      // 清空 Overlay 字幕顯示
                stopOverlayService()                // 停止服務
            }
        }        // ===============================================================================
        // 【階段 6】播放控制設定        // ===============================================================================        
        setupSliderListener()        // 設定 Slider 監聽器（拖曳進度條）        
        setupSpeedSpinner()        // 設定 Spinner 監聽器（速度選擇）        // ===============================================================================
        // 【階段 7】註冊 BroadcastReceiver        // ===============================================================================        
        /**         * 註冊 Overlay 暫停/播放接收器         *          * 監聽：OverlayService.ACTION_PAUSE_PLAY         * 用途：當使用者在 Overlay 點擊暫停時，同步 MainActivity 狀態         */
        val pausePlayFilter = android.content.IntentFilter(OverlayService.ACTION_PAUSE_PLAY)
        LocalBroadcastManager.getInstance(this).registerReceiver(overlayPausePlayReceiver, pausePlayFilter)
        Log.d(TAG, "Registered overlayPausePlayReceiver")
        /**         * 【2.8 新增】註冊 Overlay 控制面板接收器         * 
         * 監聽：         * 1. OverlayService.ACTION_OVERLAY_SPEED_CHANGE         * 2. OverlayService.ACTION_OVERLAY_SEEK         * 
         * 用途：當使用者在 Overlay 控制面板操作時，同步 MainActivity         */
        val overlayControlFilter = IntentFilter().apply {
            addAction(OverlayService.ACTION_OVERLAY_SEEK)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(overlayControlReceiver, overlayControlFilter)
        
        val deathFilter = IntentFilter("OVERLAY_SERVICE_DIED")
        LocalBroadcastManager.getInstance(this).registerReceiver(overlayDeathReceiver, deathFilter)
        val nextEpFilter = IntentFilter(OverlayService.ACTION_NEXT_EP)
        LocalBroadcastManager.getInstance(this).registerReceiver(overlayNextEpReceiver, nextEpFilter)
    }
    
    private fun buildGoogleSearchUrl(query: String): String {//把「使用者輸入 + site group」組成 Google 搜尋網址。
        val raw = "$query $SITE_GROUP_ALL"
        val encoded = URLEncoder.encode(raw, "UTF-8")
        return GOOGLE_BASE + encoded }
    private fun doSearch() {
        val query = editTextQuery.text.toString().trim()
        if (query.isEmpty()) return
        val url = buildGoogleSearchUrl(query)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        startActivity(intent) }  // 系統會用使用者的預設瀏覽器開啟[web:17][web:21][web:28]

    private fun setupSliderListener() {
        sliderPlayback.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                textViewCurrentTime.text = formatTime((value * playbackSpeed).toLong())
                textViewYellowTime.text = formatTime(value.toLong())
            }
        }
        sliderPlayback.addOnSliderTouchListener(object : OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                if (isPlaying) {
                    isPlaying = false
                    handler.removeCallbacks(updateRunnable)
                }
            }
            override fun onStopTrackingTouch(slider: Slider) {
                pausedElapsedTimeMillis = slider.value.toLong()
                startTimeNanos = System.nanoTime() - (pausedElapsedTimeMillis * 1_000_000)
            }
        })
    }

    private fun saveCurrentTimestamp() {
        if (!isPlaying && pausedElapsedTimeMillis == 0L) return  // 未播放過則不儲存
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit().putLong(KEY_LAST_TIMESTAMP, pausedElapsedTimeMillis).apply()
        Log.d(TAG, "Auto-savedTimestamp: ${formatTime(pausedElapsedTimeMillis)}")
    }
    
    private fun setupSpeedSpinner() {
        val speedOptions = arrayOf("0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, speedOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSpeed.adapter = adapter
        spinnerSpeed.setSelection(2)
        spinnerSpeed.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val oldSpeed = playbackSpeed
                playbackSpeed = when (position) {
                    0 -> 0.5f; 1 -> 0.75f; 2 -> 1.0f; 3 -> 1.25f; 4 -> 1.5f; 5 -> 2.0f; else -> 1.0f
                }
                if (isPlaying) {
                    val currentProgress = (System.nanoTime() - startTimeNanos) * oldSpeed / 1_000_000
                    startTimeNanos = System.nanoTime() - (currentProgress * 1_000_000 / playbackSpeed).toLong()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun loadAndParseSubtitleFile(uri: Uri, format: String) {
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                subtitleCues = if (format == "vtt") parseVtt(inputStream) else parseSrt(inputStream)
                if (subtitleCues.isNotEmpty()) {
                    buttonPlayPause.isEnabled = true
                    buttonReset.isEnabled = true
                    buttonLaunchOverlay.isEnabled = true
                    val duration = (subtitleCues.last().endTimeMs) + 7200000L
                    sliderPlayback.valueTo = duration.toFloat()
                    sliderPlayback.isEnabled = true
                    textViewSubtitle.text = "[Ready to play]"
                }
            }
        } catch (e: Exception) {}
    }

    private fun parseVtt(inputStream: InputStream): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        val reader = inputStream.bufferedReader()
        try {
            var line = reader.readLine()
            if (line?.startsWith("\uFEFF") == true) line = line.substring(1)
            if (line == null || !line.contains("WEBVTT")) return emptyList()
            while (reader.readLine().also { line = it } != null) {
                if (line?.contains("-->") == true) {
                    val t = line!!.split("-->")
                    val s = timeToMillis(t[0].trim())
                    val e = timeToMillis(t[1].trim().split(" ")[0])
                    val b = StringBuilder()
                    var cL = reader.readLine()
                    while (cL != null && cL.isNotBlank()) {
                        if (b.isNotEmpty()) b.append(" ")
                        b.append(cL); cL = reader.readLine()
                    }
                    if (s != null && e != null) cues.add(SubtitleCue(s, e, b.toString()))
                }
            }
        } catch (e: Exception) {}
        return cues.sortedBy { it.startTimeMs }
    }

    private fun parseSrt(inputStream: InputStream): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        val reader = inputStream.bufferedReader()
        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line?.trim()?.toIntOrNull() != null) {
                    val timeL = reader.readLine()
                    if (timeL?.contains("-->") == true) {
                        val ts = timeL.split("-->")
                        val s = timeToMillis(ts[0].trim().replace(',', '.'))
                        val e = timeToMillis(ts[1].trim().split(" ")[0].replace(',', '.'))
                        val b = StringBuilder()
                        var tL = reader.readLine()
                        while (tL != null && tL.isNotBlank()) {
                            if (b.isNotEmpty()) b.append(" ")
                            b.append(tL); tL = reader.readLine()
                        }
                        if (s != null && e != null) cues.add(SubtitleCue(s, e, b.toString()))
                    }
                }
            }
        } catch (e: Exception) {}
        return cues.sortedBy { it.startTimeMs }
    }

    private fun timeToMillis(t: String): Long? {
        return try {
            val p = t.split(":")
            val last = p.last()
            val dot = last.indexOf('.')
            val s = if (dot != -1) last.substring(0, dot).toLong() else last.toLong()
            val ms = if (dot != -1) last.substring(dot + 1).padEnd(3, '0').take(3).toLong() else 0L
            if (p.size == 3) (p[0].toLong() * 3600 + p[1].toLong() * 60 + s) * 1000 + ms
            else (p[0].toLong() * 60 + s) * 1000 + ms
        } catch (e: Exception) { null }
    }

    private fun checkOverlayPermission(): Boolean = 
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            overlayPermissionLauncher.launch(i)
        }
    }

    private fun startOverlayService() {
        if (checkOverlayPermission()) startService(Intent(this, OverlayService::class.java))
    }

    private fun stopOverlayService() { stopService(Intent(this, OverlayService::class.java)) }

    private fun sendSubtitleUpdate(text: String) {
        val i = Intent(ACTION_UPDATE_SUBTITLE_LOCAL).apply {
            putExtra(EXTRA_SUBTITLE_TEXT_LOCAL, if (isOverlayUIShown) text else "")
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(i)
    }

    private fun togglePlayPause() { if (isPlaying) pausePlayback() else startPlayback() }

    private fun startPlayback() {
        if (subtitleCues.isEmpty()) return
        isPlaying = true; setPlayButtonState(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        startTimeNanos = System.nanoTime() - (pausedElapsedTimeMillis * 1_000_000)
        handler.post(updateRunnable)
        autoSaveHandler.removeCallbacks(autoSaveRunnable)// 2.8: 啟動自動儲存
        autoSaveHandler.postDelayed(autoSaveRunnable, AUTO_SAVE_INTERVAL_MS)
        val intent = Intent(OverlayService.ACTION_PAUSE_PLAY).apply { putExtra("is_paused", false) }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun pausePlayback() {
        if (!isPlaying) return
        isPlaying = false; setPlayButtonState(false)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        pausedElapsedTimeMillis = (System.nanoTime() - startTimeNanos) / 1_000_000
        handler.removeCallbacks(updateRunnable)
        autoSaveHandler.removeCallbacks(autoSaveRunnable)// 2.8: 停止自動儲存並立即儲存一次
        saveCurrentTimestamp()
        val intent = Intent(OverlayService.ACTION_PAUSE_PLAY).apply { putExtra("is_paused", true) }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun resetPlayback() {    
        handler.removeCallbacks(updateRunnable)   // ★ 停掉舊的 runnable

        pausePlayback(); pausedElapsedTimeMillis = 0L; startTimeNanos = 0L
        textViewSubtitle.text = "[Ready to play]"; textViewCurrentTime.text = formatTime(0)
        textViewYellowTime.text = formatTime(0); sliderPlayback.value = 0.0f; sendSubtitleUpdate("")
    }

    private fun resetPlaybackStateOnError() {
        subtitleCues = emptyList(); buttonPlayPause.isEnabled = false
        sliderPlayback.isEnabled = false; textViewSubtitle.text = "[Error loading file]"; sendSubtitleUpdate("")
    }

    private fun setPlayButtonState(playing: Boolean) {
        if (playing) {
            buttonPlayPause.text = "Pause"
            buttonPlayPause.icon = ContextCompat.getDrawable(this, R.drawable.ic_pause)
        } else {
            buttonPlayPause.text = "Play"
            buttonPlayPause.icon = ContextCompat.getDrawable(this, R.drawable.ic_play_arrow)
        }
    }

    private val updateRunnable = object : Runnable {
        override fun run() {
            if (!isPlaying) return
            val eR = (System.nanoTime() - startTimeNanos) / 1_000_000
            textViewCurrentTime.text = formatTime((eR * playbackSpeed).toLong())
            textViewYellowTime.text = formatTime(eR)
            if (!sliderPlayback.isPressed && eR.toFloat() <= sliderPlayback.valueTo) sliderPlayback.value = eR.toFloat()
            val cue = findCueForTime((eR * playbackSpeed).toLong())  // ✅ 這樣字幕查詢才會根據速度調整後的時間去比對
            val nT = cue?.text ?: ""
            val intent = Intent(OverlayService.ACTION_UPDATE_TIME)// ✅ 加這個：通知 Overlay 更新時間
            intent.putExtra("current_time_ms", eR)
            LocalBroadcastManager.getInstance(this@MainActivity).sendBroadcast(intent)//  >
            if (textViewSubtitle.text != nT) { textViewSubtitle.text = nT; sendSubtitleUpdate(nT) }
            //if (subtitleCues.isNotEmpty() && eR >= subtitleCues.last().endTimeMs) {
                //pausePlayback(); textViewSubtitle.text = "[Playback Finished]"; sendSubtitleUpdate("[Playback Finished]")
                //return
            //}                            //把這整個 if 區塊刪掉，改成讓 runnable 在超出範圍後自然停止：
            handler.postDelayed(this, 30)
        }
    }

    private val overlayDeathReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (isOverlayUIShown && checkOverlayPermission()) {// 如果 overlay UI flag 是開的，就重啟 Service
                startOverlayService()                // 重新推一次當前字幕給 overlay
                sendSubtitleUpdate(textViewSubtitle.text.toString())
            }
        }
    }
    
    private fun findCueForTime(time: Long): SubtitleCue? = subtitleCues.find { time >= it.startTimeMs && time < it.endTimeMs }

    private fun formatTime(ms: Long): String {
        val s = ms / 1000; return String.format("%02d:%02d.%03d", s / 60, s % 60, ms % 1000)
    }

    @SuppressLint("Range")
    private fun getFileName(uri: Uri): String? {
        return contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(c.getColumnIndex(OpenableColumns.DISPLAY_NAME)) else null
        }
    }

    private fun openFilePicker() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("text/vtt", "application/x-subrip", "text/plain")
            )
        }
        selectSubtitleFileLauncher.launch(i)
    }

    private fun incrementLastDigitInName(fileName: String): String {
        val dotIndex = fileName.lastIndexOf('.')    // 先拆掉副檔名
        val namePart = if (dotIndex != -1) fileName.substring(0, dotIndex) else fileName
        val extPart = if (dotIndex != -1) fileName.substring(dotIndex) else ""
        val regex = Regex("(\\d+)(?!.*\\d)")    // 從右邊找連續的數字
        val match = regex.find(namePart)
        if (match != null) {
            val numberStr = match.value
            val start = match.range.first
            val end = match.range.last
            val number = numberStr.toLongOrNull() ?: return fileName
            val incremented = (number + 1).toString().padStart(numberStr.length, '0')
            val newNamePart =  namePart.substring(0, start) + incremented + namePart.substring(end + 1)
            return newNamePart + extPart } 
        else { return fileName }        // 沒有數字就不動
        }
        private fun tryLoadNextEpisode() {    // 假設你已經在 onCreate 裡用 findViewById<Button>(R.id.buttonTryNextEp)
            val currentUri = lastSubtitleUri ?: return Toast.makeText(this, "No close pattern file", Toast.LENGTH_SHORT).show()
            // 先用 ContentResolver 查出目前檔名
            val cursor = contentResolver.query( currentUri, arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null )
            val currentName = cursor?.use { if (it.moveToFirst()) it.getString(0) else null
            } ?: run {
                Toast.makeText(this, "No close pattern file", Toast.LENGTH_SHORT).show()
                return }
            val targetName = incrementLastDigitInName(currentName)
            val currentDoc = DocumentFile.fromSingleUri(this, currentUri)        // 用 DocumentFile 取得父目錄，再在裡面找同名檔案
            val parentDoc = currentDoc?.parentFile
            if (parentDoc == null || !parentDoc.isDirectory) {
                Toast.makeText(this, "No close pattern file", Toast.LENGTH_SHORT).show()
                return
            }
            val children = parentDoc.listFiles()
            val targetDoc = children.firstOrNull { it.name == targetName }
            if (targetDoc != null && targetDoc.isFile && targetDoc.canRead()) {
                lastSubtitleUri = targetDoc.uri            // 找到下一集，直接當成新的字幕檔載入
                handleSubtitleFileSelected(targetDoc.uri)
            } else { Toast.makeText(this, "No close pattern file", Toast.LENGTH_SHORT).show() }
    }

    
}
