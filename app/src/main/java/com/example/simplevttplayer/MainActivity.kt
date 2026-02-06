/**
 * MainActivity.kt - 主活動檔案
 * 
 * 功能說明：
 * - 字幕檔案選擇與解析（VTT/SRT 格式）
 * - 字幕播放控制（播放/暫停/重設/速度調整）
 * - 懸浮視窗覆蓋層服務管理
 * - 生命週期處理（修正：切換App時不暫停播放）
 */

package com.example.simplevttplayer // **<<< CHECK THIS LINE CAREFULLY!** // 套件聲明：定義程式所屬的套件路徑

import android.annotation.SuppressLint // 匯入：抑制 Lint 警告的註解
import android.app.Activity // 匯入：Activity 基礎類別
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent // 匯入：Intent 意圖類別（用於啟動活動或服務）
import android.net.Uri // 匯入：Uri 統一資源識別符（用於檔案路徑）
import androidx.appcompat.app.AppCompatActivity // 匯入：AppCompatActivity 相容性活動基礎類別
import android.os.Bundle // 匯入：Bundle 資料包（用於傳遞數據）
import android.os.Handler // 匯入：Handler 處理器（用於主線程通訊）
import android.os.Looper // 匯入：Looper 循環器（用於消息循環）
import android.provider.OpenableColumns // 匯入：OpenableColumns 可開啟欄位（用於檔案名稱查詢）
import android.util.Log // 匯入：Log 日誌類別（用於記錄調試訊息）
// No longer using standard Button/SeekBar directly in code // 不再直接使用標準 Button/SeekBar
import android.widget.TextView // 匯入：TextView 文字視圖元件
import android.widget.Toast // 匯入：Toast 提示訊息元件
import androidx.activity.result.contract.ActivityResultContracts // 匯入：ActivityResultContracts 活動結果契約
import java.io.BufferedReader // 匯入：BufferedReader 緩衝讀取器（用於檔案讀取）
import java.io.InputStream // 匯入：InputStream 輸入串流（用於檔案讀取）
import android.os.Build // 匯入：Build 系統建置資訊
import android.provider.Settings // 匯入：Settings 設定類別（用於系統設定）
import androidx.activity.result.ActivityResultLauncher // 匯入：ActivityResultLauncher 活動結果啟動器
import androidx.localbroadcastmanager.content.LocalBroadcastManager // 匯入：LocalBroadcastManager 本地廣播管理器
import com.google.android.material.slider.Slider // Import Slider // 匯入 Slider 滑塊元件
import com.google.android.material.slider.Slider.OnChangeListener // 匯入：Slider.OnChangeListener 滑塊變更監聽器
import com.google.android.material.slider.Slider.OnSliderTouchListener // 匯入：Slider.OnSliderTouchListener 滑塊觸摸監聽器
import android.view.View // 匯入：View 視圖基礎類別
import android.view.WindowManager // *** Import for Keep Screen On *** // 匯入 WindowManager 視窗管理器（用於螢幕常亮）
import androidx.core.content.ContextCompat // 匯入：ContextCompat 上下文相容工具
import com.google.android.material.button.MaterialButton // 匯入：MaterialButton Material Design 按鈕元件
import android.widget.Spinner // 匯入：Spinner 下拉式選單元件
import android.widget.AdapterView // 匯入：AdapterView 適配器視圖（用於列表/下拉式選單）
import android.widget.ArrayAdapter // 匯入：ArrayAdapter 陣列適配器（用於填充列表數據）

class MainActivity : AppCompatActivity() {

    // --- Constants ---
        // --- 常數定義區 ---
    companion object {
        private const val ACTION_UPDATE_SUBTITLE_LOCAL = OverlayService.ACTION_UPDATE_SUBTITLE // 廣播動作：更新字幕文字
        private const val EXTRA_SUBTITLE_TEXT_LOCAL = OverlayService.EXTRA_SUBTITLE_TEXT // 額外資料鍵：字幕文字內容
        private val TAG: String = MainActivity::class.java.simpleName // 活動標籤：用於日誌
    }

    private lateinit var overlayPermissionLauncher: ActivityResultLauncher<Intent> // 權限請求啟動器：用於請求螢幕覆蓋權限

    // Receiver to listen for pause/play from overlay
    private val overlayPausePlayReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == OverlayService.ACTION_PAUSE_PLAY) {
                val isPaused = intent.getBooleanExtra("is_paused", false)
                if (isPaused) {
                    if (isPlaying) pausePlayback()
                } else {
                    if (!isPlaying) startPlayback()
                }
                Log.d(TAG, "Received pause/play from overlay: isPaused=$isPaused")
            }
        }
    }

    
    // --- UI Elements --- // --- UI 元件定義區 ---
    private lateinit var buttonSelectFile: MaterialButton // 按鈕：選擇字幕檔案
    private lateinit var textViewFilePath: TextView // 文字視圖：顯示檔案路徑
    private lateinit var textViewCurrentTime: TextView // 文字視圖：顯示當前播放時間
    private lateinit var textViewSubtitle: TextView // 文字視圖：顯示字幕文字
    private lateinit var buttonPlayPause: MaterialButton // 按鈕：播放/暫停
    private lateinit var buttonReset: MaterialButton // 按鈕：重設時間軸
    private lateinit var buttonLaunchOverlay: MaterialButton // 按鈕：啓動覆蓋層
    private lateinit var sliderPlayback: Slider // Slider：時間軸控制
    private lateinit var spinnerSpeed: Spinner // Spinner：播放速度選擇
     private lateinit var editTextOverlayFontSize: android.widget.EditText // 浮窗字體大小編輯框

    // --- Subtitle Data --- // --- 字幕資料定義區 ---
    data class SubtitleCue( val startTimeMs: Long, val endTimeMs: Long, val text: String ) // 字幕提示資料類別：起始時間/結束時間/文字內容

    private var subtitleCues: List<SubtitleCue> = emptyList() // 字幕列表：儲存所有已解析的字幕項目
    private var selectedFileUri: Uri? = null // 選擇的檔案 URI：當前字幕檔案的位置

    // --- Playback & UI State --- // --- 播放狀態與 UI 狀態區 ---
    private val handler = Handler(Looper.getMainLooper()) // Handler：用於更新 UI 的主線程處理器
    private var isPlaying = false // 播放中標誌
    private var startTimeNanos: Long = 0L // 開始時間戳（奈秒）
    private var pausedElapsedTimeMillis: Long = 0L // 暫停時的累積時間（毫秒）
    private var currentCueIndex: Int = -1 // 當前字幕索引
    private var wasPlayingBeforeSeek = false // Seek 操作前的播放狀態
    private var isOverlayUIShown = true // 覆蓋層 UI 顯示狀態
    private var playbackSpeed: Float = 1.0f // 播放速度（倍數）
 // 播放結束時間（用於自動停止）
    // --- File Selection Launcher ---
    private val selectSubtitleFileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result -> // --- 檔案選擇啟動器區 ---
        if (result.resultCode == Activity.RESULT_OK) { // 註冊 Activity 結果啟動器：用於檔案選擇器
            result.data?.data?.also { uri ->
                selectedFileUri = uri
                val fileName = getFileName(uri)
                resetPlayback() // Reset first
                if (fileName != null) {
                    textViewFilePath.text = "File: $fileName"
                    when {
                        fileName.lowercase().endsWith(".vtt") -> loadAndParseSubtitleFile(uri, "vtt")
                        fileName.lowercase().endsWith(".srt") -> loadAndParseSubtitleFile(uri, "srt")
                        else -> {
                            Toast.makeText(this, "Not VTT/SRT ($fileName)", Toast.LENGTH_LONG).show()
                            resetPlaybackStateOnError()
                            textViewFilePath.text = "File: $fileName (Not VTT/SRT?)"
                        }
                    }
                } else {
                    Toast.makeText(this, "No filename.", Toast.LENGTH_SHORT).show()
                    resetPlaybackStateOnError()
                    textViewFilePath.text = "File: (Unknown)"
                }
            }
        } else {
            Toast.makeText(this, "File selection cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    // --- Activity Lifecycle ---
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main) // Uses layout with Material components

        overlayPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (checkOverlayPermission()) { Log.d(TAG, "Overlay perm granted post-settings."); startOverlayService()
            } else { Log.w(TAG, "Overlay perm not granted post-settings."); Toast.makeText(this, "Overlay permission required.", Toast.LENGTH_SHORT).show() }
        }

        // Init UI Elements
        buttonSelectFile = findViewById(R.id.buttonSelectFile)
        textViewFilePath = findViewById(R.id.textViewFilePath)
        textViewCurrentTime = findViewById(R.id.textViewCurrentTime)
        textViewSubtitle = findViewById(R.id.textViewSubtitle)
        buttonPlayPause = findViewById(R.id.buttonPlayPause)
        buttonReset = findViewById(R.id.buttonReset)
        buttonLaunchOverlay = findViewById(R.id.buttonLaunchOverlay)
        sliderPlayback = findViewById(R.id.sliderPlayback) // Use Slider ID
        spinnerSpeed = findViewById(R.id.spinnerSpeed)
        editTextOverlayFontSize = findViewById(R.id.editTextOverlayFontSize) // 初始化浮窗字體大小漄選框

        // Set Listeners
        buttonSelectFile.setOnClickListener { openFilePicker() }
        buttonPlayPause.setOnClickListener { togglePlayPause() }
        buttonReset.setOnClickListener { resetPlayback() }
        buttonLaunchOverlay.setOnClickListener {
            isOverlayUIShown = !isOverlayUIShown
            if (isOverlayUIShown) { Log.d(TAG,"Overlay ON"); Toast.makeText(this,"Overlay Shown",Toast.LENGTH_SHORT).show(); handleLaunchOverlayClick(); sendSubtitleUpdate(textViewSubtitle.text.toString())
            } else { Log.d(TAG,"Overlay OFF"); Toast.makeText(this,"Overlay Hidden",Toast.LENGTH_SHORT).show(); sendSubtitleUpdate("") }
        }

        setupSliderListener() // Setup listener for the Slider
        setupSpeedSpinner() // Setup speed control

        // Register receiver for overlay pause/play
        val pausePlayFilter = android.content.IntentFilter(OverlayService.ACTION_PAUSE_PLAY)
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).registerReceiver(overlayPausePlayReceiver, pausePlayFilter)
        Log.d(TAG, "Overlay pause/play receiver registered")

        
        // Initial state
        buttonLaunchOverlay.isEnabled = false
        sliderPlayback.isEnabled = false
        setPlayButtonState(false) // Ensure correct initial icon
    }

        // *** FIXED: Do NOT pause playback when switching apps - let overlay continue ***
    override fun onPause() {
        super.onPause()
        // Do NOT call pausePlayback() here - let the overlay service continue independently
        // The OverlayService will keep running even when MainActivity is paused
        Log.d(TAG, "onPause: App to background, overlay continues running")
    }

    override fun onResume() {
        super.onResume()
        // App returned to foreground. Playback continues unchanged.
        Log.d(TAG, "onResume: App returned to foreground")
    }
    // *** ADDED Keep Screen On flag clearing ***
    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(updateRunnable)
        stopOverlayService()
        // Ensure screen on flag is cleared if activity is destroyed
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Log.d(TAG, "onDestroy: Stopped service & cleared keep screen on flag.")

        // Unregister receiver
        try {
            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).unregisterReceiver(overlayPausePlayReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering receiver", e)
        }
    }

    // --- Slider Setup ---
    private fun setupSliderListener() {
        sliderPlayback.addOnChangeListener { _, value, fromUser -> // Underscore for unused 'slider' param
            if (fromUser) { textViewCurrentTime.text = formatTime(value.toLong()) }
        }

        sliderPlayback.addOnSliderTouchListener(object : OnSliderTouchListener {
            @SuppressLint("RestrictedApi")
            override fun onStartTrackingTouch(slider: Slider) {
                wasPlayingBeforeSeek = isPlaying
                if (isPlaying) { pausePlayback() }
                Log.d(TAG, "Slider touch started.")
            }

            @SuppressLint("RestrictedApi")
            override fun onStopTrackingTouch(slider: Slider) {
                val seekToMillis = slider.value.toLong()
                Log.d(TAG, "Seek finished via Slider at: $seekToMillis ms")
                pausedElapsedTimeMillis = seekToMillis
                textViewCurrentTime.text = formatTime(pausedElapsedTimeMillis)
                val currentCue = findCueForTime(pausedElapsedTimeMillis)
                val currentText = currentCue?.text ?: ""
                textViewSubtitle.text = currentText
                sendSubtitleUpdate(currentText)
                startTimeNanos = System.nanoTime() - (pausedElapsedTimeMillis * 1_000_000)
                if (wasPlayingBeforeSeek) { startPlayback() }
                else { setPlayButtonState(false) } // Ensure icon is Play if not resuming
            }
        })
    }

    // --- Speed Spinner Setup ---
    private fun setupSpeedSpinner() {
    val speedOptions = arrayOf("0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x")
    val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, speedOptions)
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    spinnerSpeed.adapter = adapter
    spinnerSpeed.setSelection(2)  // Default to 1.0x
    spinnerSpeed.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            playbackSpeed = when (position) {
                0 -> 0.5f
                1 -> 0.75f
                2 -> 1.0f
                3 -> 1.25f
                4 -> 1.5f
                5 -> 2.0f
                else -> 1.0f
                }
            Log.d(TAG, "Playback speed changed to ${playbackSpeed}x")
            }
        override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // ============================================
    // --- File Handling & Parsing ---
    // ============================================
    
    @SuppressLint("Range")
    private fun getFileName(uri: Uri): String? {
        var f: String? = null
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i != -1) f = c.getString(i)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getFileName error: $uri", e)
        }
        
        if (f == null) {
            f = uri.path
            val cut = f?.lastIndexOf('/')
            if (cut != -1 && cut != null) {
                f = f?.substring(cut + 1)
            }
        }
        return f
    }
    
    private fun openFilePicker() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        selectSubtitleFileLauncher.launch(i)
    }

    private fun loadAndParseSubtitleFile(uri: Uri, format: String) {
        Log.d(TAG, "Attempting to load $format file: $uri")
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                subtitleCues = if (format == "vtt") parseVtt(inputStream) else parseSrt(inputStream)
                if (subtitleCues.isNotEmpty()) {
                    Toast.makeText(this, "${format.uppercase()} loaded: ${subtitleCues.size} cues", Toast.LENGTH_SHORT).show()
                    buttonPlayPause.isEnabled = true; buttonReset.isEnabled = true; buttonLaunchOverlay.isEnabled = true
                val duration = (subtitleCues.lastOrNull()?.endTimeMs ?: 0L) + 5400000L // Add 90 minutes (blank time) to duration                    
                    sliderPlayback.valueFrom = 0.0f; sliderPlayback.valueTo = duration.toFloat(); sliderPlayback.value = 0.0f; sliderPlayback.isEnabled = true
                    textViewSubtitle.text = "[Ready to play]"; textViewCurrentTime.text = formatTime(0)
                    isOverlayUIShown = true; setPlayButtonState(false); sendSubtitleUpdate("")
                } else { Toast.makeText(this, "No cues parsed.", Toast.LENGTH_LONG).show(); resetPlaybackStateOnError() }
            } ?: run { Toast.makeText(this, "Failed file stream.", Toast.LENGTH_LONG).show(); Log.w(TAG, "Null InputStream: $uri"); resetPlaybackStateOnError() }
        } catch (e: Exception) { Log.e(TAG, "load/parse $format error", e); Toast.makeText(this, "Load ${format.uppercase()} error: ${e.message}", Toast.LENGTH_LONG).show(); resetPlaybackStateOnError() }
    }

    // ============================================
    // --- VTT Parsing Logic ---
    // ============================================
    
    private fun parseVtt(inputStream: InputStream): List<SubtitleCue> {
        val c = mutableListOf<SubtitleCue>()
        val r = inputStream.bufferedReader(Charsets.UTF_8)
        
        try {
            var h = r.readLine()
            if (h?.startsWith("\uFEFF") == true) {
                h = h.substring(1)
            }
            if (h == null || !h.trim().startsWith("WEBVTT")) {
                Log.e("VTTParser", "Bad Header: '$h'")
                runOnUiThread {
                    Toast.makeText(this, "Bad VTT Header", Toast.LENGTH_LONG).show()
                }
                return emptyList()
            }
            
            var l: String?
            while (r.readLine().also { l = it } != null) {
                val t = l?.trim() ?: ""
                if (t.isEmpty() || t.startsWith("NOTE")) {
                    continue
                }
                if (t.contains("-->")) {
                    parseTimeAndTextVtt(t, r, c)
                } else {
                    Log.w("VTTParser", "Skip line: $t")
                }
            }
        } catch (e: Exception) {
            Log.e("VTTParser", "Parse VTT Error", e)
            runOnUiThread {
                Toast.makeText(this, "VTT Process Error", Toast.LENGTH_SHORT).show()
            }
        } finally {
            try {
                r.close()
            } catch (e: Exception) {}
        }
        
        return c.sortedBy { it.startTimeMs }
    }
    
    private fun parseTimeAndTextVtt(
        tL: String,
        r: BufferedReader,
        c: MutableList<SubtitleCue>
    ) {
        try {
            val t = tL.split("-->")
            if (t.size < 2) {
                Log.w("VTTParser", "Bad time: $tL")
                return
            }
            
            val s = timeToMillis(t[0].trim())
            val eS = t[1].trim().split(Regex("\\s+"))[0]
            val e = timeToMillis(eS)
            
            val b = StringBuilder()
            var x: String? = r.readLine()
            while (x != null && x.isNotBlank()) {
                if (b.isNotEmpty()) b.append("\n")
                b.append(x)
                x = r.readLine()
            }
            
            if (s != null && e != null && b.isNotEmpty()) {
                if (e > s) {
                    c.add(SubtitleCue(s, e, b.toString()))
                } else {
                    Log.w("VTTParser", "End<=Start: $tL")
                }
            } else {
                Log.w("VTTParser", "Bad cue: $tL")
            }
        } catch (e: Exception) {
            Log.e("VTTParser", "Parse cue error: $tL", e)
        }
    }
    
    // ============================================
    // --- SRT Parser Helper ---
    // 解析 SRT 字幕檔案為字幕 cues 列表
    // ============================================
    
    private fun parseSrt(inputStream: InputStream): List<SubtitleCue> {
        val c = mutableListOf<SubtitleCue>()
        val r = inputStream.bufferedReader(Charsets.UTF_8)
        
        try {
            var l: String?
            while (r.readLine().also { l = it } != null) {
                val tL = l?.trim()
                if (tL.isNullOrEmpty()) continue
                
                if (tL.toIntOrNull() != null) {
                    val timeL = r.readLine()?.trim()
                    if (timeL != null && timeL.contains("-->")) {
                        val ts = timeL.split("-->")
                        if (ts.size >= 2) {
                            val s = timeToMillis(ts[0].trim().replace(',', '.'))
                            val eS = ts[1].trim().split(Regex("\\s+"))[0]
                            val e = timeToMillis(eS.replace(',', '.'))
                            
                            val b = StringBuilder()
                            var txtL: String? = r.readLine()
                            while (txtL != null && !txtL.isBlank()) {
                                if (b.isNotEmpty()) b.append("\n")
                                b.append(txtL)
                                txtL = r.readLine()
                            }
                            
                            if (txtL == null && b.isEmpty() && ts[0].trim().isNotEmpty()) {
                                Log.w("SRTParser", "Malformed SRT end: $timeL")
                            }
                            
                            if (s != null && e != null && b.isNotEmpty() && e > s) {
                                c.add(SubtitleCue(s, e, b.toString()))
                            } else {
                                Log.w("SRTParser", "Skip invalid SRT cue: $tL / $timeL")
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("SRTParser", "Parse SRT error", e)
            runOnUiThread {
                Toast.makeText(this, "SRT Parse Error", Toast.LENGTH_SHORT).show()
            }
        } finally {
            try {
                r.close()
            } catch (ioe: Exception) {}
        }
        
        return c.sortedBy { it.startTimeMs }
    }

    
    // ============================================
    // --- Time Parsing Helper ---
    // 將時間字符串（HH:MM:SS.ms 或 MM:SS.ms 或 SS.ms）轉換為毫秒
    // ============================================
    
    private fun timeToMillis(t: String): Long? {
        try {
            // 分割字符串為時:分:秒部分和毫秒部分
            val p = t.split(":")  // 按冒號分割
            val lP = p.last()     // 最後一部分（秒.毫秒）
            val dI = lP.indexOf('.') // 找小數點位置
            
            // 分離秒數和毫秒數
            val sS: String
            val msS: String
            if (dI != -1) {
                sS = lP.substring(0, dI)      // 秒數部分
                msS = lP.substring(dI + 1)    // 毫秒部分
            } else {
                sS = lP
                msS = "0"
            }
            
            // 驗證和提取毫秒（只取前 3 位）
            val msDigits = msS.filter { it.isDigit() }
            if (msDigits.isEmpty()) {
                Log.w("TimeParser", "Bad ms: $t")
                return null
            }
            val ms = msDigits.padEnd(3, '0').take(3).toLong()
            
            // 驗證秒數
            if (sS.isEmpty() || sS.any { !it.isDigit() }) {
                Log.w("TimeParser", "Bad secs: $t")
                return null
            }
            val s = sS.toLong()
            if (s < 0 || s > 59) {
                Log.w("TimeParser", "Bad secs val: $t")
                return null
            }
            
            // 根據分割後的部分數量計算不同格式
            return when (p.size) {
                // 格式：HH:MM:SS.ms（3 個冒號）
                3 -> {
                    if (p[1].isEmpty() || p[1].any { !it.isDigit() } ||
                        p[0].isEmpty() || p[0].any { !it.isDigit() }
                    ) {
                        Log.w("TimeParser", "Bad H/M: $t")
                        return null
                    }
                    val m = p[1].toLong()  // 分鐘
                    val h = p[0].toLong()  // 小時
                    if (m < 0 || m > 59) {
                        Log.w("TimeParser", "Bad min val: $t")
                        return null
                    }
                    (h * 3600000 + m * 60000 + s * 1000 + ms)
                }
                // 格式：MM:SS.ms（2 個冒號）
                2 -> {
                    if (p[0].isEmpty() || p[0].any { !it.isDigit() }) {
                        Log.w("TimeParser", "Bad M: $t")
                        return null
                    }
                    val m = p[0].toLong()  // 分鐘
                    if (m < 0 || m > 59) {
                        Log.w("TimeParser", "Bad min val: $t")
                        return null
                    }
                    (m * 60000 + s * 1000 + ms)
                }
                // 格式不正確
                else -> {
                    Log.w("TimeParser", "Bad colon#: $t")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e("TimeParser", "Time parse err: $t", e)
            return null
        }
    }

    
    // ============================================
    // --- Overlay Permission and Service Handling ---
    // 管理浮窗權限、服務啟動/停止、字幕廣播
    // ============================================
    
    private fun handleLaunchOverlayClick() {
        Log.d(TAG, "Ensuring overlay service started.")
        
        // ✅ 重置綠色按鈕位置（hide 後重新 show 時）
        if (!isOverlayUIShown) {
            Log.d(TAG, "Overlay was hidden, resetting overlay state...")
            val resetIntent = Intent(OverlayService.ACTION_RESET_OVERLAY_POSITION)
            LocalBroadcastManager.getInstance(this).sendBroadcast(resetIntent)
        }
        
        if (checkOverlayPermission()) {
            startOverlayService()
        } else {
            requestOverlayPermission()
        }
    }

    
    // 檢查是否有浮窗繪製權限
    private fun checkOverlayPermission(): Boolean {
        val has = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true  // Android M 以下預設擁有權限
        }
        Log.d(TAG, "Overlay perm status: $has")
        return has
    }

    
    
    // 請求浮窗繪製權限
    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Log.d(TAG, "Requesting overlay perm.")
            Toast.makeText(this, "Need 'Draw over apps' permission.", Toast.LENGTH_LONG).show()
            
            val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            try {
                overlayPermissionLauncher.launch(i)
            } catch (e: Exception) {
                Log.e(TAG, "Can't launch overlay settings", e)
                Toast.makeText(this, "Can't open perm settings.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    
    // 啟動 OverlayService
    private fun startOverlayService() {
        if (!checkOverlayPermission()) {
            Log.w(TAG, "Start service denied (no perm)")
            requestOverlayPermission()
            return
        }
        
        Log.d(TAG, "Starting OverlayService...")
        val i = Intent(this, OverlayService::class.java)
        try {
            startService(i)
        } catch (e: Exception) {
            Log.e(TAG, "Can't start OverlayService", e)
            Toast.makeText(this, "Failed to start overlay.", Toast.LENGTH_SHORT).show()
        }
    }

    // 停止 OverlayService
    private fun stopOverlayService() {
        Log.d(TAG, "Stopping OverlayService...")
        val i = Intent(this, OverlayService::class.java)
        stopService(i)
    }
    
    // 廣播字幕更新到 OverlayService
    private fun sendSubtitleUpdate(text: String) {
        // ✅ 判斷是否顯示字幕（若 overlay 隱藏則發送空字符串）
        val textToSend = if (isOverlayUIShown) text else ""
        
        if (textToSend.isNotBlank()) {
            Log.d(TAG, "Broadcasting update: '$textToSend'")
        } else {
            Log.d(TAG, "Broadcasting empty subtitle update.")
        }
        
        // ✅ 使用 companion 中定義的常數確保 action 一致
        val i = Intent(ACTION_UPDATE_SUBTITLE_LOCAL).apply {
            putExtra(EXTRA_SUBTITLE_TEXT_LOCAL, textToSend)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(i)
        Log.d(TAG, "Subtitle broadcast sent successfully")
    }


    // --- Playback Control ---
    private fun togglePlayPause() { if (isPlaying) pausePlayback() else startPlayback() }

    // *** ADDED Keep Screen On logic & Icon change ***
    private fun startPlayback() {
        if (subtitleCues.isEmpty()) return
        isPlaying = true
        setPlayButtonState(true) // Set icon to Pause
        // *** Keep screen on ***
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Log.d(TAG, "Screen kept on.")

        startTimeNanos = System.nanoTime() - (pausedElapsedTimeMillis * 1_000_000)
        if (isOverlayUIShown) { val c = findCueForTime(pausedElapsedTimeMillis); sendSubtitleUpdate(c?.text ?: "") } else { sendSubtitleUpdate("") }
        handler.post(updateRunnable)

        // Notify overlay of play state
        val intent = Intent(OverlayService.ACTION_PAUSE_PLAY).apply {
            putExtra("is_paused", false)
        }
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    // *** ADDED Keep Screen On logic & Icon change ***
    private fun pausePlayback() {
        if (!isPlaying) return
        isPlaying = false
        setPlayButtonState(false) // Set icon to Play
        // *** Allow screen to turn off ***
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Log.d(TAG, "Screen allowed to turn off.")

        // Update paused time only when actually pausing
        pausedElapsedTimeMillis = (System.nanoTime() - startTimeNanos) / 1_000_000
        handler.removeCallbacks(updateRunnable)

        // Notify overlay of pause state
        val intent = Intent(OverlayService.ACTION_PAUSE_PLAY).apply {
            putExtra("is_paused", true)
        }
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    // *** UPDATED for Slider & Keep Screen On flag ***
    private fun resetPlayback() {
        if (isPlaying) { handler.removeCallbacks(updateRunnable); isPlaying = false }
        pausedElapsedTimeMillis = 0L
        startTimeNanos = 0L
        currentCueIndex = -1
        isOverlayUIShown = true // Reset overlay visibility state
        textViewSubtitle.text = "[Select VTT / SRT File]" // Updated default text
        textViewCurrentTime.text = formatTime(0)
        setPlayButtonState(false) // Set icon to Play
        val cuesLoaded = subtitleCues.isNotEmpty()
        buttonPlayPause.isEnabled = cuesLoaded
        buttonReset.isEnabled = cuesLoaded
        buttonLaunchOverlay.isEnabled = cuesLoaded
        sliderPlayback.value = 0.0f // Reset Slider value
        sliderPlayback.isEnabled = cuesLoaded
        sendSubtitleUpdate("") // Clear the overlay text

        // ---> ADDED Clear flag on reset <---
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Log.d(TAG, "Playback reset, screen allowed to turn off.")
    }

    // *** UPDATED for Slider & Keep Screen On flag ***
    private fun resetPlaybackStateOnError() {
        subtitleCues = emptyList(); selectedFileUri = null
        buttonPlayPause.isEnabled = false; buttonReset.isEnabled = false; buttonLaunchOverlay.isEnabled = false
        sliderPlayback.value = 0.0f // Reset Slider value
        sliderPlayback.isEnabled = false
        isOverlayUIShown = true; textViewSubtitle.text = "[Error loading file]"; textViewCurrentTime.text = formatTime(0)
        if (!textViewFilePath.text.startsWith("File:")) { textViewFilePath.text = "No file or error" }
        sendSubtitleUpdate("")

        // ---> ADDED Clear flag on error <---
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Log.d(TAG, "Error state reset, screen allowed to turn off.")
    }

    // Helper function to change Play/Pause button icon and text
    private fun setPlayButtonState(playing: Boolean) {
        if (playing) {
            buttonPlayPause.text = "Pause" // Keep text for accessibility readers
            // Ensure you have ic_pause in res/drawable
            buttonPlayPause.icon = ContextCompat.getDrawable(this, R.drawable.ic_pause)
        } else {
            buttonPlayPause.text = "Play"
            // Ensure you have ic_play_arrow in res/drawable
            buttonPlayPause.icon = ContextCompat.getDrawable(this, R.drawable.ic_play_arrow)
        }
    }

    // --- Subtitle Display Update Logic ---
    private val updateRunnable = object : Runnable {
        override fun run() {
            if (!isPlaying) return
            val rawElapsedMillis = (System.nanoTime() - startTimeNanos) / 1_000_000
            val elapsedMillis = (rawElapsedMillis * playbackSpeed).toLong()  // 應用速度
            textViewCurrentTime.text = formatTime(elapsedMillis)

            // Update Slider only if user isn't dragging it
            // Also check bounds to prevent crash if time slightly exceeds max due to timing
            if (!sliderPlayback.isPressed) {
                if (elapsedMillis.toFloat() >= sliderPlayback.valueFrom && elapsedMillis.toFloat() <= sliderPlayback.valueTo) {
                    sliderPlayback.value = elapsedMillis.toFloat()
                } else if (elapsedMillis.toFloat() > sliderPlayback.valueTo) {
                    // If time exceeded max, clamp slider value to max
                    sliderPlayback.value = sliderPlayback.valueTo
                }
            }

            val activeCue = findCueForTime(elapsedMillis)
            val newText = activeCue?.text ?: ""
            var textChanged = false
            if (textViewSubtitle.text != newText) { textViewSubtitle.text = newText; textChanged = true }
            if (textChanged || (activeCue == null && newText == "")) { sendSubtitleUpdate(newText) }

            if (subtitleCues.isNotEmpty()) {
                val lastCueEndTime = subtitleCues.last().endTimeMs
                if (elapsedMillis >= lastCueEndTime) {
                    // Call pausePlayback first to handle flags/state/button icon
                    pausePlayback()
                    // Set final UI state after pausing
                    textViewCurrentTime.text = formatTime(lastCueEndTime)
                    if (!sliderPlayback.isPressed) { sliderPlayback.value = sliderPlayback.valueTo }
                    textViewSubtitle.text = "[Playback Finished]"
                    sendSubtitleUpdate("[Playback Finished]")
                    return // Stop runnable
                }
            } else { pausePlayback(); sendSubtitleUpdate(""); return } // Safety check
            handler.postDelayed(this, 50) // Update ~20 times per second
        }
    }

    // --- Find Cue Logic ---
    private fun findCueForTime(elapsedMillis: Long): SubtitleCue? { return subtitleCues.find { c -> elapsedMillis >= c.startTimeMs && elapsedMillis < c.endTimeMs } }

    // --- Format Time Logic ---
    private fun formatTime(millis: Long): String { if (millis < 0) return "00:00.000"; val sT = millis / 1000; val m = sT / 60; val s = sT % 60; val ms = millis % 1000; return String.format("%02d:%02d.%03d", m, s, ms) }

} // End of MainActivity class
