/** * MainActivity.kt - 字幕播放器主活動檔案【核心功能總覽】
 * 1. 字幕檔案選擇與解析 *    - 支援格式：VTT (WebVTT) / SRT (SubRip) *    - 使用 SAF (Storage Access Framework) 選擇檔案 *    - 持久化 URI 權限，重啟後仍可訪問
 * 2. 精確字幕播放控制 *    - 播放/暫停/重設 - 速度調整：0.5x, 0.75x, 1.0x, 1.25x, 1.5x, 2.0x *    - 高精度時間計算（奈秒級 System.nanoTime()）- 30ms 更新間隔，流暢顯示
 * 3. 懸浮視窗服務管理 *    - 啟動/停止 OverlayService（浮動字幕窗口）- 雙向通訊：Activity ↔ Service *    - LocalBroadcastManager 廣播機制
 * 4. 自動進度儲存 (2.8 新增) *    - 每 3 分鐘自動儲存播放位置 - 暫停時立即儲存 *    - Reload Last File 功能恢復上次進度 - 儲存原始時間（不受播放速度影響）     * 5. 日文語法高亮 (Kuromoji) *    - 詞性分析與顏色標記 - 可開關功能 * ===================================================================================
 * 【技術架構】 * - 架構模式：傳統 Activity 架構（未使用 ViewModel） * - 通訊機制：LocalBroadcastManager（Activity ↔ Service）     * - 資料持久化：SharedPreferences * - 定時任務：Handler + Runnable（主線程） * - 播放計時：System.nanoTime()（高精度奈秒級） * ===================================================================================
 * 【關鍵元件】 * - Material Slider：拖曳式進度條，支援即時預覽 * - Spinner：速度選擇下拉選單      * - ActivityResultLauncher：檔案選擇器 & 權限請求 * - BroadcastReceiver：監聽 Service 事件 * - Handler：30ms 更新循環 + 3分鐘自動儲存
 * @author so-dot @version 2.8_super_overlay @since 2014-05-27 */
package com.example.simplevttplayer      // 整個 app 的 package 名稱，Activity / Service 都在這裡

import android.annotation.SuppressLint   // 用來壓掉某些 Lint 警告（例如 Range）
import android.app.Activity              // 只有在需要明寫 Activity.RESULT_OK 等常數時用
import android.content.BroadcastReceiver // 收 LocalBroadcast / 系統廣播的基底類
import android.content.Context           // Android 上下文物件（拿資源、preferences 等）
import android.content.Intent            // 啟動 Activity / Service 或發送廣播用
import android.content.IntentFilter      // 註冊 BroadcastReceiver 時過濾 Action 用
import android.graphics.Color            // 直接用整數顏色值（例如 Color.RED）
import android.net.Uri                   // SAF / Intent 中代表檔案或網址的 URI 型別
import android.os.Build                  // 判斷系統版本（Build.VERSION.SDK_INT）
import android.os.Bundle                 // Activity onCreate 的 savedInstanceState
import android.os.Handler                // 在主執行緒排程 Runnable（更新 UI/計時）
import android.os.Looper                 // 拿到主執行緒的 Looper（Handler 會用到）
import android.provider.OpenableColumns  // 用 contentResolver 查 SAF 檔名時用到的欄位常數
import android.provider.Settings         // 開系統設定頁（例如 overlay 權限）用
import android.util.Log                  // 寫 Logcat 訊息（Log.d / Log.e 等）
import android.view.View                 // 所有 View 的基底類，Button/TextView 都繼承它
import android.view.WindowManager        // 控制 Window flag（KEEP_SCREEN_ON 等）
import android.view.inputmethod.EditorInfo // 判斷 IME_ACTION_DONE / SEARCH 等鍵盤 action
import android.widget.AdapterView        // Spinner / ListView 的 onItemSelected 會用到
import android.widget.ArrayAdapter       // Spinner 用的簡單文字 adapter
import android.widget.EditText           // 單行/多行文字輸入欄位
import android.widget.Spinner            // 下拉選單（播放速度選擇）
import android.widget.TextView           // 顯示文字用的基本 View
import android.widget.Toast              // 在畫面下方彈出的短訊息
import androidx.activity.result.ActivityResultLauncher // 新式 Activity 結果回傳 API 的 launcher 型別
import androidx.activity.result.contract.ActivityResultContracts // 內建 ActivityResult 合約（如 StartActivityForResult）
import androidx.appcompat.app.AppCompatActivity        // 支援庫版 Activity（Material/Toolbar 等）
import androidx.core.content.ContextCompat             // 向後相容工具（取 drawable / color 等）
import androidx.documentfile.provider.DocumentFile     // 操作 SAF 資料夾 / 檔案的包裝類
import androidx.localbroadcastmanager.content.LocalBroadcastManager // app 內部用的 broadcast manager
import com.google.android.material.button.MaterialButton // Material Design 風格按鈕
import com.google.android.material.slider.Slider         // Material Slider（時間軸）
import com.google.android.material.slider.Slider.OnChangeListener    // Slider 變動監聽介面
import com.google.android.material.slider.Slider.OnSliderTouchListener// Slider 觸控開始/結束監聽
import java.io.BufferedReader            // 包裝 InputStream 成逐行讀的 reader
import java.io.InputStream               // 檔案的原始位元流（SAF 打開字幕檔時用）
import java.net.URLEncoder               // 將搜尋字串轉成 URL safe 格式（Google 搜尋）

class MainActivity : AppCompatActivity() {
    companion object {    // 【Companion Object】靜態常數與類別級別變數                  /** 【廣播 Action 常數】與 OverlayService 通訊* 從 OverlayService 引用確保兩邊完全一致，避免打字錯誤*/
        private const val ACTION_UPDATE_SUBTITLE_LOCAL = OverlayService.ACTION_UPDATE_SUBTITLE
        private const val EXTRA_SUBTITLE_TEXT_LOCAL = OverlayService.EXTRA_SUBTITLE_TEXT
        private val TAG: String = MainActivity::class.java.simpleName           /*** 【日誌標籤】用於 Logcat 過濾 * 使用類別簡稱作為 TAG，方便追蹤         */
        private const val PREFS_NAME = "popsubs_prefs"        /**         * 【SharedPreferences 檔名】         * 儲存 app 所有持久化資料的 SP 檔案名稱         */
        private const val KEY_LAST_SUBTITLE_URI = "last_subtitle_uri"  /**         * 【儲存鍵】上次使用的字幕檔案 URI         * 值類型：String (Uri.toString())       * 用途：Reload Last File 功能         */
        /**         * 【2.8 新增】儲存鍵 - 上次播放位置的原始時間戳記         * 值類型：Long (毫秒)       * 重要：儲存的是「原始時間」，未受播放速度影響    * 範例說明：   * - 以 2.0x 速度播放到實際 10 秒時    * - 字幕實際進度是 20 秒（10秒 × 2.0倍速）
         * - 此處儲存 20000ms（原始進度），而非 10000ms        * 為什麼儲存原始時間？         * - 與字幕檔案的時間軸一致         * - Reload 後無論什麼播放速度都能回到正確位置         * - pausedElapsedTimeMillis 本身就是原始時間         */
        private const val KEY_LAST_TIMESTAMP = "last_timestamp_ms"  /**         * 【2.8 新增】自動儲存間隔         * 值：180,000 毫秒 = 3 分鐘
        *          * 觸發時機：         * - 播放期間，每 3 分鐘自動儲存一次當前進度         * - startPlayback() 時啟動定時器         * - pausePlayback() 時停止定時器（並立即儲存一次）         */
        private const val AUTO_SAVE_INTERVAL_MS = 180_000L
        private const val REQUEST_OPEN_FOLDER = 2001  //*!*使用者選字幕時，同步觸發選資料夾
        private const val GOOGLE_BASE = "https://www.google.com/search?udm=14&q="// Google 搜尋 base URL（固定帶 udm=14）+站台 group
        private const val SITE_GROUP_ALL = "(site:kitsunekko.net OR site:jimaku.cc OR site:sub-scene.com OR site:subdl.com OR site:opensubtitles.org OR site:addic7ed.com)"
    }
    private lateinit var editTextQuery: EditText
    // 【ActivityResultLauncher】檔案選擇器 & 權限請求啟動器
    /**     * 【Overlay 權限請求啟動器】     * 用途：請求 SYSTEM_ALERT_WINDOW 權限（顯示在其他應用上層）
     *      * 流程：     * 1. 使用者點擊「啟動 Overlay」按鈕     * 2. checkOverlayPermission() 檢查權限     * 3. 若無權限 → requestOverlayPermission() → 啟動此 launcher     * 4. 跳轉到系統設定頁面："允許顯示在其他應用程式上層"
     * 5. 使用者授權後返回 → callback 中檢查權限 → startOverlayService()     *      * Android 版本差異：     * - Android M (6.0) 以上需要此權限     * - Android M 以下自動授予     */
    private lateinit var overlayPermissionLauncher: ActivityResultLauncher<Intent>
    // 【BroadcastReceiver】接收來自 OverlayService 的廣播    
    /**     * 【Overlay 暫停/播放接收器】     *      * 監聽事件：OverlayService.ACTION_PAUSE_PLAY
     *      * 使用場景：     * - 使用者點擊浮動字幕（Overlay）切換暫停/播放     * - OverlayService 發送廣播通知 MainActivity     * - MainActivity 同步更新播放狀態     * 
     * Intent Extra：     * - "is_paused" (Boolean)     *   - true  → MainActivity 呼叫 pausePlayback()     *   - false → MainActivity 呼叫 startPlayback()
     * 為什麼需要這個？     * - OverlayService 無法直接呼叫 MainActivity 的方法     * - 透過廣播實現鬆耦合的雙向通訊     * - 確保兩邊播放狀態同步     */
    private val overlayPausePlayReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == OverlayService.ACTION_PAUSE_PLAY) {            // 檢查 Action 是否為暫停/播放事件
                val isPaused = intent.getBooleanExtra("is_paused", false) // 取得暫停狀態 // 同步 MainActivity 的播放狀態
                if (isPaused) {   if (isPlaying) pausePlayback()   } else {   if (!isPlaying) startPlayback()  }       // Overlay 已暫停 → MainActivity 也暫停 // Overlay 已播放 → MainActivity 也播放
            }
        }
    }
    private val overlayNextEpReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {    if (intent?.action == OverlayService.ACTION_NEXT_EP) { tryLoadNextEpisode() }    }
    }
    /**     * 【2.8 新增】Overlay 控制面板接收器     *      * 監聽事件：     * ===== ACTION_OVERLAY_SEEK =====     * Intent Extra：     * - "seek_to_ms" (Long) - 目標時間（毫秒）     * 
     * 處理邏輯：     * 1. 更新 pausedElapsedTimeMillis = seekToMs     * 2. 重新計算 startTimeNanos（當前系統時間 - 目標時間）     * 3. 同步 Slider 位置     * 4. 播放會從新位置繼續（updateRunnable 會讀取 startTimeNanos）     */
    private val overlayControlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                OverlayService.ACTION_OVERLAY_SEEK -> { // Overlay 進度 → MainActivity 跳轉
                    val seekToMs = intent.getLongExtra("seek_to_ms", 0L)
                    Toast.makeText( this@MainActivity, "Seek to ${formatTime(seekToMs)} from overlay", Toast.LENGTH_SHORT ).show()
                    Log.d(TAG, "Overlay seek to: ${formatTime(seekToMs)}")
                    pausedElapsedTimeMillis = seekToMs// 更新暫停時的累積時間（原始時間）// 重新計算播放開始時間點    // 公式：startTimeNanos = 當前系統時間 - (目標時間 × 1,000,000)
                    // 為什麼乘以 1,000,000？因為 nanoTime 是奈秒，seekToMs 是毫秒
                    startTimeNanos = System.nanoTime() - (seekToMs * 1_000_000)
                    sliderPlayback.value = seekToMs.toFloat()          //1. 同步 Slider 位置
                }
                OverlayService.ACTION_NEXT_EP -> {   tryLoadNextEpisode()   }
            }
        }
    }    // 【UI 元件】View 引用
    private lateinit var buttonSelectFile: MaterialButton    /** 【按鈕】選擇字幕檔案 */
    private lateinit var buttonReloadLast: MaterialButton  /** 【按鈕】重新載入上次檔案 (2.8: 會恢復上次播放位置) */
    private lateinit var buttonTryNextEp: MaterialButton //Main 的 NEXT 按鈕 → tryLoadNextEpisode(
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
    // 【播放狀態變數】  /**     * 【Kuromoji 啟用狀態】     * true = 啟用日文詞性分析與顏色標記    //* false = 純文字顯示     * 可透過 textViewJpToggle 點擊切換     * 變更時會同步更新 JpGrammarHighlighter.enabled
    private var kuromojiEnabled: Boolean = false
    /**     * 【字幕 Cue 列表】     * 儲存所有解析完成的字幕條目     *      * 結構：List<SubtitleCue>     * - SubtitleCue(startTimeMs, endTimeMs, text)     *      * 排序：按 startTimeMs 由小到大排序     * 
     * 用途：     * - 播放時根據當前時間查找對應字幕     * - findCueForTime() 遍歷此列表尋找匹配的 cue     *      * 更新時機：     * - loadAndParseSubtitleFile() 解析完成後更新     * - handleSubtitleFileSelected() 開頭清空 (emptyList())*/
    private var subtitleCues: List<SubtitleCue> = emptyList()
    /**     * 【當前選擇的字幕檔案 URI】     *      * 類型：Uri? (nullable)   * 用途：     * - 記錄使用者選擇的檔案位置     * - 重新載入功能的依據
     *      * 更新時機：     * - selectSubtitleFileLauncher callback     * - buttonReloadLast 點擊（從 SharedPreferences 讀取）*/
    private var selectedFileUri: Uri? = null
    /**     * 【主線程 Handler】     *  用途：     * 1. updateRunnable - 每 30ms 更新播放進度     * 2. 其他需要延遲執行的任務
     *      * 為什麼需要？     * - Android UI 更新必須在主線程     * - Handler.post() 將 Runnable 加入主線程訊息佇列     * - Handler.postDelayed() 實現定時循環     */
    private val handler = Handler(Looper.getMainLooper())
    /**     * 【2.8 新增】自動儲存 Handler     *  用途：專門處理 3 分鐘定期儲存任務     *      * 為什麼分離？   * - 與 handler (30ms 更新) 分開，避免相互干擾     * - autoSaveRunnable 只在播放時運行     * - 邏輯清晰，易於維護     */
    private val autoSaveHandler = Handler(Looper.getMainLooper())
    /**     * 【播放狀態標記】       * true  = 正在播放      - updateRunnable 每 30ms 執行 - autoSaveRunnable 每 3 分鐘執行   - 螢幕保持喚醒 (FLAG_KEEP_SCREEN_ON)
                                    * false = 已暫停     *       - updateRunnable 停止     *       - autoSaveRunnable 停止     *       - 螢幕可自動關閉     */
    private var isPlaying = false
    /**     * 【播放開始時的系統奈秒時間】     *      * 用途：高精度播放計時     * 計時公式：     * ```     * 經過時間(ms) = (System.nanoTime() - startTimeNanos) / 1,000,000     * ```     * 
     * 為什麼用 nanoTime？     * - System.currentTimeMillis() 會受系統時間調整影響     * - System.nanoTime() 是單調遞增，不受時區/夏令時影響     * - 精度更高（奈秒級），避免累積誤差
     *      * 更新時機：     * 1. startPlayback() - 計算新的起始點     * 2. 速度變更 - 重新計算以補償速度差異     * 3. Slider 拖曳結束 - 跳轉到新位置*/
    private var startTimeNanos: Long = 0L
    /**     * 【暫停時的累積播放時間】單位：毫秒     *      * 重要概念：這是「原始時間」，不受播放速度影響！     * 
     * 範例說明：     * - 以 2.0x 播放 10 秒     * - 實際經過時間 = 10 秒     * - 字幕進度 = 10 × 2.0 = 20 秒     * - pausedElapsedTimeMillis = 20,000ms
     *      * 為什麼儲存原始時間？     * 1. 與字幕檔案時間軸一致     * 2. 變速後仍能正確查找字幕     * 3. 儲存進度時統一標準     * 
     * 更新時機：  * 1. pausePlayback() - 計算當前位置     * 2. Slider 拖曳 - 使用者手動設定     * 3. Overlay seek - 遠端控制跳轉     * 4. resetPlayback() - 歸零
     * 用途：     * 1. 恢復播放的起點     * 2. 自動儲存進度 (KEY_LAST_TIMESTAMP)     * 3. Slider 當前值     */
    private var pausedElapsedTimeMillis: Long = 0L
    /**     * 【Overlay UI 顯示狀態】     * true  = OverlayService 運行中，顯示浮動字幕     * false = OverlayService 已停止，隱藏浮動字幕  * 控制邏輯：     * - buttonLaunchOverlay 點擊時切換     * - sendSubtitleUpdate() 根據此值決定是否傳送字幕文字     */
    private var isOverlayUIShown = true
    /**     * 【播放速度倍率】     *      * 預設：1.0x (正常速度)     * 範圍：0.5x ~ 2.0x   Spinner 對應：     * - Position 0 → 0.5x     * - Position 1 → 0.75x     * - Position 2 → 1.0x (預設)     * - Position 3 → 1.25x    * - Position 4 → 1.5x     * - Position 5 → 2.0x
     * 影響範圍：     * 1. 字幕查詢時間 = (經過時間 × playbackSpeed)     * 2. textViewCurrentTime 顯示（加速時間）     * 3. startTimeNanos 重新計算（變速時補償）
     * 不影響：     * - pausedElapsedTimeMillis（永遠是原始時間）     * - textViewYellowTime（黃色時間，固定顯示原始時間）     */
    private var playbackSpeed: Float = 1.0f
    // 【Data Class】字幕 Cue 資料結構   
    /**     * 【字幕條目資料類別】     *      * 代表一個字幕 cue（subtitle cue），包含時間與文字資訊
     *      * @property startTimeMs 開始時間（毫秒）     *       - 相對於字幕檔案開頭 (00:00.000)  - 字幕出現的時間點
     * @property endTimeMs   結束時間（毫秒）  - 字幕消失的時間點    - 必須 > startTimeMs     * @property text        字幕文字內容  - 原始文字（未經 Kuromoji 處理）  - 可能包含換行符 \n   - VTT 可能包含 HTML 標籤（目前未處理）
     * 使用範例：     * SubtitleCue(     *     startTimeMs = 5000,  // 00:05.000     *     endTimeMs = 8000,    // 00:08.000     *     text = "こんにちは、世界！"     * )
     * 查詢邏輯：     * // 找出當前時間對應的字幕     * val cue = subtitleCues.find {      *     currentTime >= it.startTimeMs && currentTime < it.endTimeMs      * }
     * 注意事項：     * 1. 時間單位統一為毫秒（ms）     * 2. 區間為 [startTimeMs, endTimeMs)，左閉右開     * 3. text 可能為空字串（空白字幕）     * 4. 多個 cue 可能時間重疊（罕見，但可能發生）     */
    data class SubtitleCue(        val startTimeMs: Long,        val endTimeMs: Long,        val text: String    )
    /**     * 【2.8 新增】自動儲存 Runnable    *      * 功能：每 3 分鐘自動儲存播放進度到 SharedPreferences
     * 執行邏輯：     * 1. 檢查是否需要儲存（播放過且有進度）     * 2. 呼叫 saveCurrentTimestamp()     * 3. 再次排程 3 分鐘後執行（遞迴排程）
     * 啟動時機：     * - startPlayback() 中呼叫 autoSaveHandler.postDelayed()     * 停止時機：     * - pausePlayback() 中呼叫 autoSaveHandler.removeCallbacks()     * - onDestroy() 確保清理
     * 為什麼需要自動儲存？     * - 防止 app 異常關閉導致進度丟失     * - 長時間播放時定期備份     * - 無需使用者手動操作     */
    private val autoSaveRunnable = object : Runnable {        override fun run() {
            saveCurrentTimestamp()            // 儲存當前播放進度
            autoSaveHandler.postDelayed(this, AUTO_SAVE_INTERVAL_MS)            // 再次排程 3 分鐘後執行（遞迴自排程）
    }         }
    //「Try next ep」按鈕：用 SAF 在同目錄找新檔,原本用 ACTION_OPEN_DOCUMENT 選了一個檔案並且 takePersistableUriPermission，現在可以把這個 uri 存起來，例如在 MainActivity 裡有個變數：
    private var lastSubtitleUri: Uri? = null
    private var lastFolderUri: Uri? = null  //*!*使用者選字幕時，同步觸發選資料夾
    // 【核心函數 1】字幕檔案處理
    /**     * 【處理字幕檔案選擇】     *      * 當使用者從檔案選擇器選擇字幕檔後，此函數統一處理所有相關邏輯
     * 【執行流程】     *      * 步驟 1：記錄 URI     *   - 儲存到 selectedFileUri     *   - 持久化到 SharedPreferences (KEY_LAST_SUBTITLE_URI)     *   → 用途：下次啟動可透過 Reload Last File 快速載入
     * 步驟 2：清空舊資料     *   - subtitleCues = emptyList()     *   → 防止新舊字幕混用     *      * 步驟 3：重設播放狀態     *   - 呼叫 resetPlayback() × 2（為何兩次？可能是確保完全重置）     *   - 暫停播放     *   - 時間歸零     *   - UI 重置     * 
     * 步驟 4：取得檔案名稱     *   - 呼叫 getFileName(uri)     *   - 從 ContentResolver 查詢 DISPLAY_NAME     *      * 步驟 5：判斷格式並解析     *   - .vtt → loadAndParseSubtitleFile(uri, "vtt")     *   - .srt → loadAndParseSubtitleFile(uri, "srt")     *   - 其他  → Toast 錯誤訊息 + resetPlaybackStateOnError()
     * 【參數】     * @param uri 字幕檔案的 URI     *            - 來源：Storage Access Framework (SAF)     *            - 協議：通常是 content://     *            - 需持久化權限才能重啟後訪問
     * 【副作用】（會修改的狀態）     * 1. selectedFileUri = uri     * 2. SharedPreferences 寫入 KEY_LAST_SUBTITLE_URI     * 3. subtitleCues = emptyList()     * 4. isPlaying = false
     * 5. pausedElapsedTimeMillis = 0L     * 6. startTimeNanos = 0L     * 7. UI 更新（textViewFilePath, textViewSubtitle, slider, 等）
     * 【錯誤處理】     * 情況 1：getFileName() 返回 null     *   → Toast: "File name error"     *   → 呼叫 resetPlaybackStateOnError()
     * 情況 2：副檔名不是 .vtt 或 .srt     *   → Toast: "Not VTT/SRT"     *   → 呼叫 resetPlaybackStateOnError()
     * 【呼叫來源】     * 1. selectSubtitleFileLauncher 的 callback（檔案選擇器返回）     * 2. buttonReloadLast 點擊事件（Reload Last File）
     * 【注意事.       * - 不檢查 URI 有效性（假設 SAF 返回的 URI 必定有效）     * - 不驗證檔案大小（超大檔案可能導致 OOM）     */
    private fun handleSubtitleFileSelected(uri: Uri) {
        selectedFileUri = uri        // 步驟 1：記錄並持久化 URI
        lastSubtitleUri = uri        //2. 「Try next ep」按鈕：用 SAF 在同目錄找新檔
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit().putString(KEY_LAST_SUBTITLE_URI, uri.toString()).apply()
        Log.d(TAG, "Subtitle file selected: $uri")
        subtitleCues = emptyList()  // 清空舊字幕列表
        val fileName = getFileName(uri)        // 步驟 4：取得檔案名稱
        resetPlayback()  // 第二次重設（可能是確保完全清除狀態）
        if (fileName != null) {
            textViewFilePath.text = "File: $fileName"            // 步驟 5：更新 UI 顯示檔案名稱
            Log.d(TAG, "File name: $fileName")
            when {            // 步驟 6：根據副檔名選擇解析器
                fileName.lowercase().endsWith(".vtt") -> {  Log.d(TAG, "Parsing as VTT format")  ;  loadAndParseSubtitleFile(uri, "vtt")   }
                fileName.lowercase().endsWith(".srt") -> {  Log.d(TAG, "Parsing as SRT format")  ;  loadAndParseSubtitleFile(uri, "srt")   }
                else -> { Log.w(TAG, "Unsupported file format: $fileName") ; Toast.makeText(this, "Not VTT/SRT", Toast.LENGTH_SHORT).show() ; resetPlaybackStateOnError()  }  // 不支援的格式
            }
        } else {  Log.e(TAG, "Failed to get file name from URI: $uri")  ;  Toast.makeText(this, "File name error", Toast.LENGTH_SHORT).show()  ;  resetPlaybackStateOnError() }         // 檔名取得失敗（可能是權限或 URI 無效）
    }
    /**     * 【檔案選擇器啟動器】     *      * 使用 ActivityResultLauncher 註冊檔案選擇器回調
     * 【執行流程】     * 1. 使用者點擊 "Select File" 按鈕     * 2. openFilePicker() 啟動系統檔案選擇器     * 3. 使用者選擇檔案並確認     * 4. 此 launcher 的 callback 被呼叫
     * 5. 檢查 resultCode == RESULT_OK     * 6. 取得 URI：result.data?.data     * 7. 嘗試持久化 URI 權限（takePersistableUriPermission）     * 8. 呼叫 handleSubtitleFileSelected(uri)
     * 【持久化權限處理】     * 為什麼需要？     * - SAF 返回的 URI 預設只在當前 session 有效     * - app 重啟後無法訪問相同 URI     * - takePersistableUriPermission() 可延長權限到永久
     * try-catch 處理：     * - 某些情況下會拋出 SecurityException     * - 例如：使用者選擇的是暫存檔案     * - Catch 後忽略錯誤，繼續載入檔案     * - 影響：Reload Last File 可能無效，但當次載入正常     * 
     * 【錯誤處理】     *      * 情況 1：resultCode != RESULT_OK     *   → 使用者取消選擇     *   → 不執行任何動作     *      * 情況 2：result.data?.data == null     *   → 系統未返回 URI（罕見）     *   → 不執行任何動作     * 
     * 情況 3：takePersistableUriPermission 拋出 SecurityException     *   → Catch 忽略     *   → 繼續執行 handleSubtitleFileSelected     *   → 當次載入正常，但重啟後無法 Reload
     * 【ActivityResultContracts.StartActivityForResult】    *      * 這是新版 Activity Result API（取代舊的 startActivityForResult）     *      * 優點：  - 類型安全     * - 在 onCreate 前註冊（避免狀態丟失）     * - Lambda 風格，更簡潔 */
    private val selectSubtitleFileLauncher = registerForActivityResult(        ActivityResultContracts.StartActivityForResult()    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {        // 檢查使用者是否確認選擇（未取消）
            result.data?.data?.also { uri ->            // 取得選擇的檔案 URI                // 嘗試持久化 URI 權限                // 持久化後，app 重啟後仍可訪問此 URI
                try {
                    val flags = result.data?.flags ?: 0
                    contentResolver.takePersistableUriPermission(   uri,    flags and Intent.FLAG_GRANT_READ_URI_PERMISSION    )
                    Log.d(TAG, "Persistable URI permission granted: $uri")
                } catch (e: SecurityException) {   Log.w(TAG, "Failed to take persistable URI permission", e)  }         // 權限無法持久化（例如暫存檔案）     // 忽略錯誤，繼續載入
                handleSubtitleFileSelected(uri)                // 處理選擇的檔案
                if (lastFolderUri == null) openFolderPicker()  //*!*
            }
        } else {   Log.d(TAG, "File selection cancelled")    }          // 使用者取消選擇
    }
    // 【核心函數 2】Activity 生命週期
    /**     * 【Activity 創建】     *      * Activity 生命週期第一個被呼叫的方法
     * 【執行內容】     *      * 階段 1：基礎初始化     *   - super.onCreate()     *   - setContentView(R.layout.activity_main)     * 
     * 階段 2：註冊 ActivityResultLauncher     *   - overlayPermissionLauncher（Overlay 權限請求）     *      * 階段 3：View 綁定     *   - findViewById() 取得所有 UI 元件引用     *   - 按鈕、文字、滑桿、下拉選單等     * 
     * 階段 4：設定按鈕監聽器     *   - buttonSelectFile → openFilePicker()     *   - buttonReloadLast → 重載上次檔案 + 恢復進度     *   - buttonPlayPause → togglePlayPause()
     *   - buttonReset → resetPlayback()     *   - buttonLaunchOverlay → 切換 Overlay 服務     *   - textViewJpToggle → 切換 Kuromoji 高亮     * 
     * 階段 5：設定其他 UI 監聽器     *   - editTextOverlayFontSize → 字體大小變更監聽     *   - Slider → setupSliderListener()     *   - Spinner → setupSpeedSpinner()     * 
     * 階段 6：註冊 BroadcastReceiver     *   - overlayPausePlayReceiver（監聽 Overlay 暫停/播放）     *   - overlayControlReceiver（監聽 Overlay 控制面板）     *      * ===================================================================================
     * 【重要細節】     * ===== Reload Last File 邏輯 =====     * ```kotlin
     * buttonReloadLast.setOnClickListener {     *     // 1. 從 SharedPreferences 讀取上次的 URI
     *     val uriString = prefs.getString(KEY_LAST_SUBTITLE_URI, null)     *          *     // 2. 檢查是否有上次的檔案
     *     if (uriString.isNullOrEmpty()) {     *         Toast.makeText(this, "No last file to reload.", Toast.LENGTH_SHORT).show()     *         return@setOnClickListener     *     }     *     
     *     // 3. 解析 URI 並載入檔案
     *     val uri = Uri.parse(uriString)
     *     handleSubtitleFileSelected(uri)    *     
     *     // 4. 【2.8 新增】恢復上次儲存的播放位置
     *     val savedTimestamp = prefs.getLong(KEY_LAST_TIMESTAMP, 0L)
     *     if (savedTimestamp > 0L && subtitleCues.isNotEmpty()) {     *         // 設定播放位置到上次進度
     *         pausedElapsedTimeMillis = savedTimestamp
     *         sliderPlayback.value = savedTimestamp.toFloat()     *   
     *         textViewYellowTime.text = formatTime(savedTimestamp)           *         // 更新時間顯示
     *         textViewCurrentTime.text = formatTime((savedTimestamp * playbackSpeed).toLong())     *       
     *         Toast.makeText(this, "已恢復至 ${formatTime(savedTimestamp)}", Toast.LENGTH_SHORT).show()       *         // 顯示 Toast 提示
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
        // 【階段 1】註冊 ActivityResultLauncher    * Overlay 權限請求 Launcher    *  流程：    1. requestOverlayPermission() 呼叫 launcher.launch()    * 2. 跳轉系統設定頁面     * 3. 使用者授權後返回    * 4. Callback 中檢查權限並啟動服務         */
        overlayPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult() // 權限請求返回後，檢查是否已授予
        ) {  if (checkOverlayPermission()) {  Log.d(TAG, "Overlay permission granted, starting service")  ;  startOverlayService()
            } else {   Log.w(TAG, "Overlay permission denied")  ;  Toast.makeText(this, "需要懸浮視窗權限", Toast.LENGTH_SHORT).show()    }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {        // ── 新增：請求豁免電池優化 ──────────────────────
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent( android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:$packageName") )
                startActivity(intent)
            }
        }// ===============================================================================
        // 【階段 2】View 綁定        // Kuromoji 切換指示器
        textViewJpToggle = findViewById(R.id.textViewJpToggle)        
        // 按鈕群組
        buttonSelectFile = findViewById(R.id.buttonSelectFile)
        buttonReloadLast = findViewById(R.id.buttonReloadLastFile)
        buttonPlayPause = findViewById(R.id.buttonPlayPause)
        buttonReset = findViewById(R.id.buttonReset)
        buttonLaunchOverlay = findViewById(R.id.buttonLaunchOverlay)
        buttonTryNextEp = findViewById(R.id.buttonTryNextEp)   // 這行是你要新增的
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
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {    doSearch() ;  true } else { false }  } /** 告訴系統「已處理」*/// 交給系統預設處理     
        // 【階段 3】按鈕監聽器         * 【選擇檔案按鈕】         * 開啟系統檔案選擇器（SAF）         */
        buttonSelectFile.setOnClickListener {   Log.d(TAG, "Select file button clicked")  ;  openFilePicker()   }
        buttonTryNextEp.setOnClickListener { tryLoadNextEpisode() } //按 Main 那顆「Find next ep」
        buttonReloadLast.setOnClickListener {        /**  Reload Last File 按鈕】         * 重新載入上次使用的字幕檔案         * 2.8 新增：同時恢復上次播放位置         */
            Log.d(TAG, "Reload last file button clicked")            
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val uriString = prefs.getString(KEY_LAST_SUBTITLE_URI, null)            
            if (uriString.isNullOrEmpty()) {  Toast.makeText(this, "No last file to reload.", Toast.LENGTH_SHORT).show()  ;  return@setOnClickListener   } // 檢查是否有上次的檔案記錄 // 解析 URI 並載入檔案
            val uri = Uri.parse(uriString)
            handleSubtitleFileSelected(uri)            
            val savedTimestamp = prefs.getLong(KEY_LAST_TIMESTAMP, 0L)  // 【2.8 新增】恢復上次儲存的播放位置
            if (savedTimestamp > 0L && subtitleCues.isNotEmpty()) {
                Log.d(TAG, "Restoring timestamp: ${formatTime(savedTimestamp)}")
                pausedElapsedTimeMillis = savedTimestamp                // 設定播放位置
                sliderPlayback.value = savedTimestamp.toFloat()
                Toast.makeText(   this,   "已恢復至 ${formatTime(savedTimestamp)}",   Toast.LENGTH_SHORT   ).show() // 提示使用者
            }
        }        /**         * 【播放/暫停按鈕】         * 切換播放狀態         */
        buttonPlayPause.setOnClickListener {   Log.d(TAG, "Play/Pause button clicked")  ;  togglePlayPause() }        /**         * 【重設按鈕】         * 回到 00:00.000，清空字幕顯示         */
        buttonReset.setOnClickListener {       Log.d(TAG, "Reset button clicked")       ;  resetPlayback()   }        // ===============================================================================
        // 【階段 4】Kuromoji 切換            /**         * 更新 Kuromoji 切換指示器的顏色         * - 啟用：綠色 (#4CAF50)         * - 停用：灰色 (#9E9E9E)         */
        fun updateJpToggleUi() { textViewJpToggle.setTextColor(if (kuromojiEnabled) { Color.parseColor("#4CAF50") } else { Color.parseColor("#9E9E9E") }) } // 綠色 ON// 灰色 OFF
        updateJpToggleUi()/**  // 初始化顯示狀態       * 點擊切換 Kuromoji 功能         */
        textViewJpToggle.setOnClickListener {          
            kuromojiEnabled = !kuromojiEnabled         // 切換狀態  
            JpGrammarHighlighter.enabled = kuromojiEnabled            // 同步到 highlighter            
            updateJpToggleUi()            // 更新 UI 顏色
            sendSubtitleUpdate(textViewSubtitle.text.toString())            // 重新傳送當前字幕以刷新顯示            
            Log.d(TAG, "Kuromoji toggled: $kuromojiEnabled")
        }        // ===============================================================================
        // 【階段 5】Overlay 字體大小監聽      /**         * Overlay 字體大小輸入框監聽器         *          * 當使用者在 MainActivity 改變字體大小時：
        // * 1. 解析輸入的數字（預設 20）         * 2. 建立 ACTION_UPDATE_FONT_SIZE 廣播         * 3. 傳送給 OverlayService         * 4. OverlayService 更新所有字幕視窗字體         */
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
                if (checkOverlayPermission()) {startOverlayService()  } else { requestOverlayPermission()} // 檢查權限// 有權限：直接啟動服務// 無權限：請求權限
                sendSubtitleUpdate(textViewSubtitle.text.toString())  // 傳送當前字幕到 Overlay
            } else {                // 關閉 Overlay
                Log.d(TAG, "Stopping overlay service")  ;  sendSubtitleUpdate("")  ;  stopOverlayService()         // 清空 Overlay 字幕顯示           // 停止服務
            }
        }  // 【階段 6】播放控制設定        // ===============================================================================        
        setupSliderListener()        // 設定 Slider 監聽器（拖曳進度條）        
        setupSpeedSpinner()        // 設定 Spinner 監聽器（速度選擇）        // ===============================================================================
        // 【階段 7】註冊 BroadcastReceiver    註冊 Overlay 暫停/播放接收器         *          * 監聽：OverlayService.ACTION_PAUSE_PLAY         * 用途：當使用者在 Overlay 點擊暫停時，同步 MainActivity 狀態         */
        val pausePlayFilter = android.content.IntentFilter(OverlayService.ACTION_PAUSE_PLAY)
        LocalBroadcastManager.getInstance(this).registerReceiver(overlayPausePlayReceiver, pausePlayFilter)
        Log.d(TAG, "Registered overlayPausePlayReceiver")
        /**         * 【2.8 新增】註冊 Overlay 控制面板接收器     * 監聽：         * 1. OverlayService.ACTION_OVERLAY_SPEED_CHANGE    * 2. OverlayService.ACTION_OVERLAY_SEEK   * 用途：當使用者在 Overlay 控制面板操作時，同步 MainActivity */
        val overlayControlFilter = IntentFilter().apply {     addAction(OverlayService.ACTION_OVERLAY_SEEK)    }
        LocalBroadcastManager.getInstance(this).registerReceiver(overlayControlReceiver, overlayControlFilter)
        val deathFilter = IntentFilter("OVERLAY_SERVICE_DIED")         ;    LocalBroadcastManager.getInstance(this).registerReceiver(overlayDeathReceiver, deathFilter)
        val nextEpFilter = IntentFilter(OverlayService.ACTION_NEXT_EP) ;    LocalBroadcastManager.getInstance(this).registerReceiver(overlayNextEpReceiver, nextEpFilter)
    }
    
    private fun buildGoogleSearchUrl(query: String): String { // 把「使用者輸入 + site group」組成 Google 搜尋網址
        val raw = "$query $SITE_GROUP_ALL"                    // 在原始關鍵字後面加上站台群組條件
        val encoded = URLEncoder.encode(raw, "UTF-8")         // 用 UTF-8 URL encode 防止空白/特殊字元炸掉
        return GOOGLE_BASE + encoded   }                       // 接到 Google 搜尋 base URL，變成完整網址
    private fun doSearch() {
        val query = editTextQuery.text.toString().trim()      // 讀輸入框文字並去掉前後空白
        if (query.isEmpty()) return                           // 空字串就直接不搜尋
        val url = buildGoogleSearchUrl(query)                 // 依照站台群組組成 Google 搜尋網址
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)) // 建立「用瀏覽器開網址」的 Intent
        startActivity(intent)   }                              // 系統會用使用者的預設瀏覽器打開

    private fun setupSliderListener() {
        sliderPlayback.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {  textViewCurrentTime.text = formatTime((value * playbackSpeed).toLong()) ;  textViewYellowTime.text = formatTime(value.toLong())  }
        }// 只在使用者手動拖動時更新顯示// 照速度換算後的時間// 原始 slider 位置（未乘速度）
        sliderPlayback.addOnSliderTouchListener(object : OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                if (isPlaying) {  isPlaying = false   ;   handler.removeCallbacks(updateRunnable)  } // 正在播放時，一開始拖動就暫停更新 // 停掉自動更新 Runnable
            }
            override fun onStopTrackingTouch(slider: Slider) {
                pausedElapsedTimeMillis = slider.value.toLong() // 停止拖動時，把 slider 的值存成暫停時間
                startTimeNanos = System.nanoTime() - (pausedElapsedTimeMillis * 1_000_000)// 調整 startTimeNanos，讓 resume 播放時從這個時間點繼續
            }
        })
    }

    private fun saveCurrentTimestamp() {
        if (!isPlaying && pausedElapsedTimeMillis == 0L) return  // 完全沒播過就不用存
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE) // 取得 app SharedPreferences
        prefs.edit().putLong(KEY_LAST_TIMESTAMP, pausedElapsedTimeMillis).apply()  // 把目前暫停時間寫入 KEY_LAST_TIMESTAMP
        Log.d(TAG, "Auto-savedTimestamp: ${formatTime(pausedElapsedTimeMillis)}")
    }
    
    private fun setupSpeedSpinner() {
        val speedOptions = arrayOf("0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x") // 顯示在 Spinner 的文字選項
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, speedOptions) // 用簡單內建 layout
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)       // 下拉選單 layout
        spinnerSpeed.adapter = adapter                    // 綁定 adapter 到 Spinner
        spinnerSpeed.setSelection(2)                      // 預設選中 1.0x（index 2）
        spinnerSpeed.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val oldSpeed = playbackSpeed              // 記住舊的速度，方便後面換算時間
                playbackSpeed = when (position){0 -> 0.5f; 1 -> 0.75f; 2 -> 1.0f; 3 -> 1.25f; 4 -> 1.5f; 5 -> 2.0f; else -> 1.0f}
                if (isPlaying) {                          // 如果正在播放，切速度時要調整基準時間
                    val currentProgress = (System.nanoTime() - startTimeNanos) * oldSpeed / 1_000_000// 先算出目前已經經過的「實際播放毫秒」（含舊速度）
                    startTimeNanos = System.nanoTime() - (currentProgress * 1_000_000 / playbackSpeed).toLong()// 反推新的 startTimeNanos，讓畫面不要突然跳
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {} // 沒選到任何項目時不做事
        }
    }

    private fun loadAndParseSubtitleFile(uri: Uri, format: String) {
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                subtitleCues = if (format == "vtt") parseVtt(inputStream) else parseSrt(inputStream)
                if (subtitleCues.isNotEmpty()) {
                    buttonPlayPause.isEnabled = true       // 啟用播放按鈕
                    buttonReset.isEnabled = true           // 啟用重置按鈕
                    buttonLaunchOverlay.isEnabled = true   // 啟用 overlay 按鈕
                    val duration = (subtitleCues.last().endTimeMs) + 7200000L // 影片長度 + 預留兩小時 buffer
                    sliderPlayback.valueTo = duration.toFloat()               // slider 最大值設為此 duration
                    sliderPlayback.isEnabled = true        // 允許使用者拖動 time slider
                    textViewSubtitle.text = "[Ready to play]" // UI 顯示已準備好
                }
            }
        } catch (e: Exception) {}
    }

    private fun parseVtt(inputStream: InputStream): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()            // 暫存所有 cue 的 list
        val reader = inputStream.bufferedReader(Charsets.UTF_8)   // ← 加 charset // 用 BufferedReader 逐行讀入
        try {
            var line = reader.readLine()
            if (line?.startsWith("\uFEFF") == true) line = line.substring(1)   // 去掉 UTF-8 BOM
            if (line == null || !line.contains("WEBVTT")) return emptyList()   // 頭行不含 WEBVTT 就不是 VTT
            while (reader.readLine().also { line = it } != null) {             // 一直往下讀到 EOF
                if (line?.contains("-->") == true) {                           // 找到時間軸那行
                    val t = line!!.split("-->")
                    val s = timeToMillis(t[0].trim())                          // 開始時間
                    val e = timeToMillis(t[1].trim().split(" ")[0])            // 結束時間（有時結尾有註記，先切掉）
                    val b = StringBuilder()                                    // 用來累積字幕文字
                    var cL = reader.readLine()
                    while (cL != null && cL.isNotBlank()) {                    // 直到遇到空行為止
                        if (b.isNotEmpty()) b.append(" ")                      // 行與行之間加空白
                        b.append(cL); cL = reader.readLine()
                    }
                    if (s != null && e != null) cues.add(SubtitleCue(s, e, b.toString())) // 有合法時間才加入
                }
            }
        } catch (e: Exception) {} // Parser 失敗目前被吞掉，之後可補 log
        return cues.sortedBy { it.startTimeMs }            // 依 startTime 排序，方便之後用 findCueForTime
    }  
    private fun parseSrt(inputStream: InputStream): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        val reader = inputStream.bufferedReader(Charsets.UTF_8)   // ← 加 charset
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
            val p = t.split(":")                           // 依冒號劃分（可能是 mm:ss 或 hh:mm:ss）
            val last = p.last()                            // 最後一段含秒與毫秒
            val dot = last.indexOf('.')                    // 找小數點位置
            val s = if (dot != -1) last.substring(0, dot).toLong() else last.toLong() // 秒的整數部份
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

    private fun startOverlayService() {        if (checkOverlayPermission()) startService(Intent(this, OverlayService::class.java))    }

    private fun stopOverlayService() { stopService(Intent(this, OverlayService::class.java)) }

    private fun sendSubtitleUpdate(text: String) {
        val i = Intent(ACTION_UPDATE_SUBTITLE_LOCAL).apply {            putExtra(EXTRA_SUBTITLE_TEXT_LOCAL, if (isOverlayUIShown) text else "")        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(i)
    }

    private fun togglePlayPause() { if (isPlaying) pausePlayback() else startPlayback() }

    private fun startPlayback() {
        if (subtitleCues.isEmpty()) return                // 沒有任何字幕就不用播放
        isPlaying = true; setPlayButtonState(true)        // 切狀態 + 改 UI 成「Pause」
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // 播放時保持螢幕常亮
        startTimeNanos = System.nanoTime() - (pausedElapsedTimeMillis * 1_000_000)// 將 startTimeNanos 往前推，讓「經過時間 = 現在 - startTimeNanos」對應到暫停時間之後
        handler.post(updateRunnable)                      // 啟動 30ms 更新循環
        autoSaveHandler.removeCallbacks(autoSaveRunnable) // 2.8: 啟動自動儲存前先清一次
        autoSaveHandler.postDelayed(autoSaveRunnable, AUTO_SAVE_INTERVAL_MS) // 安排下一次自動儲存
        val intent = Intent(OverlayService.ACTION_PAUSE_PLAY).apply { putExtra("is_paused", false) }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent) // 通知 overlay：現在是播放中
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
        if (playing) {      buttonPlayPause.text = "Pause"  ;  buttonPlayPause.icon = ContextCompat.getDrawable(this, R.drawable.ic_pause)
        } else {            buttonPlayPause.text = "Play"   ;  buttonPlayPause.icon = ContextCompat.getDrawable(this, R.drawable.ic_play_arrow)    }
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
            if (isOverlayUIShown && checkOverlayPermission()) {     startOverlayService()    ;    sendSubtitleUpdate(textViewSubtitle.text.toString())    }
        }// 如果 overlay UI flag 是開的，就重啟 Service// 重新推一次當前字幕給 overlay
    }
    private val overlayCloseReceiver = object : BroadcastReceiver() {//overlay close 加 receiver 接廣播，呼叫 resetPlayback()
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == OverlayService.ACTION_OVERLAY_CLOSE) {  resetPlayback()  }
        }
    }
    
    private fun findCueForTime(time: Long): SubtitleCue? = subtitleCues.find { time >= it.startTimeMs && time < it.endTimeMs }

    private fun formatTime(ms: Long): String {    val s = ms / 1000; return String.format("%02d:%02d.%03d", s / 60, s % 60, ms % 1000)    }

    @SuppressLint("Range")
    private fun getFileName(uri: Uri): String? {
        return contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(c.getColumnIndex(OpenableColumns.DISPLAY_NAME)) else null// 從 SAF 提供的 cursor 中讀 DISPLAY_NAME 欄，拿到檔名
        }
    }
    private fun openFilePicker() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)         // 只允許可開啟的文件
            type = "*/*"                                  // 主類型先設為全部
            putExtra(     Intent.EXTRA_MIME_TYPES,    arrayOf("text/vtt", "application/x-subrip", "text/plain")    )
        }                                           // 再用多 MIME 限縮到字幕類型
        selectSubtitleFileLauncher.launch(i)              // 使用 ActivityResultLauncher 開 SAF picker
    }//v使用者選字幕時，同步觸發選資料夾---->
    private fun openFolderPicker() { val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE) ; startActivityForResult(intent, REQUEST_OPEN_FOLDER)}
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) { super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OPEN_FOLDER && resultCode == Activity.RESULT_OK) {
            val folderUri = data?.data ?: return
            contentResolver.takePersistableUriPermission( folderUri, Intent.FLAG_GRANT_READ_URI_PERMISSION )
            lastFolderUri = folderUri  //*!*
            Toast.makeText(this, "資料夾已設定", Toast.LENGTH_SHORT).show()
        }
    }
    private fun buildNextEpisodeBase(nameWithExt: String): String? { // 回傳「到 E 下一集為止的部分」，丟掉尾巴
        val dotIndex = nameWithExt.lastIndexOf('.')    // Show.S01E03 v2.srt → baseNext = "Show.S01E04"
        val namePart = if (dotIndex != -1) nameWithExt.substring(0, dotIndex) else nameWithExt
        val regex  = Regex("[Ee](\\d+)")        // 找 E## / e##，例如 E3, E03, e12, e009
        val regexR = Regex("(\\d+)[Xx](\\d+)") // 例如 09x05, 9X5
        val eMatch = regex.find(namePart)    // 先處理 E## / e##
        if (eMatch != null) {
            val numberStr = eMatch.groupValues[1]
            val number = numberStr.toLongOrNull() ?: return null
            val incremented = (number + 1).toString().padStart(numberStr.length, '0')
            val eChar = namePart[eMatch.range.first]
            val prefixBeforeE = namePart.substring(0, eMatch.range.first)
            return prefixBeforeE + eChar + incremented           // 保持原本 E03 → E04 的邏輯
        }        // 再試 09x05 這種
        val xMatch = regexR.find(namePart) ?: return null
        val seasonStr = xMatch.groupValues[1]                     // "09"
        val epStr = xMatch.groupValues[2]                         // "05"
        val epNum = epStr.toLongOrNull() ?: return null
        val nextEp = (epNum + 1).toString().padStart(epStr.length, '0')
        val prefixBefore = namePart.substring(0, xMatch.range.first) // "Show.09x05" 前面的東西
        val xChar = namePart[xMatch.range.first + seasonStr.length]  // 原始的 'x' 或 'X'
        return prefixBefore + seasonStr + xChar + nextEp             // "Show.09x06"（保留原有位數）[web:75][web:76]
    }
    private fun tryLoadNextEpisode() {    // 綁在 buttonTryNextEp 的 onClick
        Log.d(TAG, "tryLoadNextEpisode() called")
        val currentUri = lastSubtitleUri ?: return Toast
            .makeText(this, "No close pattern file", Toast.LENGTH_SHORT).show()// 沒有記錄最後字幕 URI，就直接提示後返回
        val cursor = contentResolver.query(currentUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null ) // 從 SAF 查出目前檔名（含副檔名）
        val currentName = cursor?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?: run { return Toast.makeText(this, "No close pattern file", Toast.LENGTH_SHORT).show() }// 沒查到檔名就直接結束
        Toast.makeText(this, """Next from "$currentName".""", Toast.LENGTH_SHORT).show()
        val baseNext = buildNextEpisodeBase(currentName)  // 算出下一集的「基底」字串（到 E## 為止）
        if (baseNext == null)return Toast.makeText(this, "No close pattern file", Toast.LENGTH_SHORT).show()  ;  Log.d(TAG, """Next-ep base = "$baseNext" """)     //例如 "Show.S01E04"
        Toast.makeText(this, "try grab: $baseNext", Toast.LENGTH_LONG).show()//try grab: ...（buildNextEpisodeBase() 產生的字串）
        val folderUri = lastFolderUri ?: run { return Toast.makeText(this, "No close pattern file", Toast.LENGTH_SHORT).show()  }
        val folderDoc = DocumentFile.fromTreeUri(this, folderUri) ?: run { return Toast.makeText(this, "No close pattern file", Toast.LENGTH_SHORT).show()  }
        val children = folderDoc.listFiles()  //*!*
        Log.d(TAG, "Children in folder: ${children.map { it.name }}")
        children.forEach { child -> Toast.makeText(this, "child: ${child.name}", Toast.LENGTH_SHORT).show() }//看畫面上跑出來的 child: ... 幾個檔名
        val targetDoc = children.firstOrNull { child ->   // 尋找「檔名以 baseNext 開頭」的檔案
            val name = child.name ?: return@firstOrNull false
            name.startsWith(baseNext)                     // 尾巴、版本號、解析度全部忽略
        }
        if (targetDoc != null && targetDoc.isFile && targetDoc.canRead()) {
            lastSubtitleUri = targetDoc.uri               // 更新 lastSubtitleUri
            handleSubtitleFileSelected(targetDoc.uri)     // 當作選擇新字幕檔重新載入
        } else { Toast.makeText(this, "No close pattern file", Toast.LENGTH_SHORT).show()}
    }

    
}
