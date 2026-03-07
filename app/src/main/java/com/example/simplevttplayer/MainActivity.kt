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
import android.content.BroadcastReceiver // 廣播接收器（接收覆蓋層暫停/播放訊號）
import android.content.Context // Context 上下文（用於串接系統資源）
import android.content.Intent // 匯入：Intent 意圖類別（用於啟動活動或服務）
import android.net.Uri // 匯入：Uri 統一資源識別符（用於檔案路徑）
import androidx.appcompat.app.AppCompatActivity // 匯入：AppCompatActivity 相容性活動基礎類別
import android.os.Bundle // 匯入：Bundle 資料包（用於傳遞數據）
import android.os.Handler // 匯入：Handler 處理器（用於主線程通訊）
import android.os.Looper // 匯入：Looper 循環器（用於消息循環）
import android.provider.OpenableColumns // 匯入：OpenableColumns 可開啟欄位（用於檔案名稱查詢）
import android.util.Log // 匯入：Log 日誌類別（用於記錄調試訊息）
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

    // --- UI Elements ---
    // --- UI 元件定義區 ---
    private lateinit var buttonSelectFile: MaterialButton // 按鈕：選擇字幕檔案
    private lateinit var textViewFilePath: TextView // 文字視圖：顯示檔案路徑
    private lateinit var textViewCurrentTime: TextView // 文字視圖：顯示當前播放時間
    private lateinit var textViewSubtitle: TextView // 文字視圖：顯示字幕文字
    private lateinit var buttonPlayPause: MaterialButton // 按鈕：播放/暫停
    private lateinit var buttonReset: MaterialButton // 按鈕：重設時間軸
    private lateinit var buttonLaunchOverlay: MaterialButton // 按鈕：啓動覆蓋層
    private lateinit var sliderPlayback: Slider // Slider：時間軸控制
    private lateinit var spinnerSpeed: Spinner // Spinner：播放速度選擇
    private lateinit var textViewYellowTime: TextView // 文字視圖：顯示速度調整後的時間 (Bug1 Fix)

    // --- Subtitle Data ---
    // --- 字幕資料定義區 ---
    data class SubtitleCue(
        val startTimeMs: Long,
        val endTimeMs: Long,
        val text: String
    ) // 字幕提示資料類別：起始時間/結束時間/文字內容

    private var subtitleCues: List<SubtitleCue> = emptyList() // 字幕列表：儲存所有已解析的字幕項目
    private var selectedFileUri: Uri? = null // 選擇的檔案 URI：當前字幕檔案的位置

    // --- Playback & UI State ---
    // --- 播放狀態與 UI 狀態區 ---
    private val handler = Handler(Looper.getMainLooper()) // Handler：用於更新 UI 的主線程處理器
    private var isPlaying = false // 播放中標誌
    private var startTimeNanos: Long = 0L // 開始時間戳（奈秒）
    private var pausedElapsedTimeMillis: Long = 0L // 暫停時的累積時間（毫秒）
    private var currentCueIndex: Int = -1 // 當前字幕索引
    private var wasPlayingBeforeSeek = false // Seek 操作前的播放狀態
    private var isOverlayUIShown = true // 覆蓋層 UI 顯示狀態
    private var playbackSpeed: Float = 1.0f // 播放速度（倍數）

    // --- File Selection Launcher ---
    private val selectSubtitleFileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        // --- 檔案選擇啟動器區 ---
        if (result.resultCode == Activity.RESULT_OK) {
            // 註冊 Activity 結果啟動器：用於檔案選擇器
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
            if (checkOverlayPermission()) {
                Log.d(TAG, "Overlay perm granted post-settings.");
                startOverlayService()
            } else {
                Log.w(TAG, "Overlay perm not granted post-settings.");
                Toast.makeText(this, "Overlay permission required.", Toast.LENGTH_SHORT).show()
            }
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
        textViewYellowTime = findViewById(R.id.textViewYellowTime) // Bug1 Fix: 黃色時間戳

        // Set Listeners
        buttonSelectFile.setOnClickListener { openFilePicker() }
        buttonPlayPause.setOnClickListener { togglePlayPause() }
        buttonReset.setOnClickListener { resetPlayback() }

        // Bug4 Fix: "Toggle Overlay Visibility" button logic
        buttonLaunchOverlay.setOnClickListener {
            if (isOverlayUIShown) {
                // Currently shown, hide it
                isOverlayUIShown = false
                Log.d(TAG, "Overlay UI set to HIDDEN (isOverlayUIShown=false)")
                Toast.makeText(this, "Overlay Hidden", Toast.LENGTH_SHORT).show()
                sendSubtitleUpdate("") // Send blank to hide
            } else {
                // Currently hidden, show it
                isOverlayUIShown = true
                Log.d(TAG, "Overlay UI set to SHOWN (isOverlayUIShown=true)")
                Toast.makeText(this, "Overlay Shown", Toast.LENGTH_SHORT).show()
                
                // If service not running, launch it. Otherwise just update text.
                if (checkOverlayPermission()) {
                    startOverlayService()
                } else {
                    requestOverlayPermission()
                }
                
                // Send current subtitle if playing
                val currentText = textViewSubtitle.text.toString()
                if (currentText != "[Subtitles will appear here]" && currentText != "[Ready to play]") {
                   sendSubtitleUpdate(currentText)
                }
            }
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

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause: App to background, overlay continues running")
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume: App returned to foreground")
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(updateRunnable)
        stopOverlayService()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Log.d(TAG, "onDestroy: Stopped service & cleared keep screen on flag.")
        
        try {
            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).unregisterReceiver(overlayPausePlayReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering receiver", e)
        }
    }

    // --- Slider Setup ---
    private fun setupSliderListener() {
        sliderPlayback.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                textViewCurrentTime.text = formatTime(value.toLong());
                textViewYellowTime.text = formatTime((value * playbackSpeed).toLong()) // 滑動時即時更新黃色時間戳
            }
        }

        sliderPlayback.addOnSliderTouchListener(object : OnSliderTouchListener {
            @SuppressLint("RestrictedApi")
            override fun onStartTrackingTouch(slider: Slider) {
                wasPlayingBeforeSeek = isPlaying
                if (isPlaying) {
                    // We don't call pausePlayback() here to avoid logic side effects, 
                    // just stop the runnable and update state flag.
                    isPlaying = false
                    handler.removeCallbacks(updateRunnable)
                }
                Log.d(TAG, "Slider touch started.")
            }

            @SuppressLint("RestrictedApi")
            override fun onStopTrackingTouch(slider: Slider) {
                val seekToMillis = slider.value.toLong()
                Log.d(TAG, "Seek finished via Slider at: $seekToMillis ms")
                
                // Bug5 Fix: Correctly calculate startTimeNanos to avoid skip
                pausedElapsedTimeMillis = seekToMillis
                textViewCurrentTime.text = formatTime(pausedElapsedTimeMillis)
                textViewYellowTime.text = formatTime((pausedElapsedTimeMillis * playbackSpeed).toLong())
                
                val currentCue = findCueForTime(pausedElapsedTimeMillis)
                val currentText = currentCue?.text ?: ""
                textViewSubtitle.text = currentText
                sendSubtitleUpdate(currentText)

                // Re-sync nano clock
                startTimeNanos = System.nanoTime() - (pausedElapsedTimeMillis * 1_000_000)

                if (wasPlayingBeforeSeek) {
                    startPlayback()
                } else {
                    setPlayButtonState(false)
                }
            }
        })
    }

    // --- Speed Spinner Setup ---
    private fun setupSpeedSpinner() {
        val speedOptions = arrayOf("0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, speedOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSpeed.adapter = adapter
        spinnerSpeed.setSelection(2) // Default to 1.0x

        spinnerSpeed.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val oldSpeed = playbackSpeed
                playbackSpeed = when (position) {
                    0 -> 0.5f
                    1 -> 0.75f
                    2 -> 1.0f
                    3 -> 1.25f
                    4 -> 1.5f
                    5 -> 2.0f
                    else -> 1.0f
                }
                Log.d(TAG, "Playback speed changed from ${oldSpeed}x to ${playbackSpeed}x")
                
                // Re-calculate startTimeNanos based on CURRENT elapsed time to prevent "jump" when speed changes mid-play
                if (isPlaying) {
                    // We need to keep the visual elapsed time the same, so adjust the nano clock
                    val visualElapsed = (System.nanoTime() - startTimeNanos) / 1_000_000 
                    // No, that's complex. Simpler: speed affects future progress. 
                    // In our current logic, speed is applied to (now - start). 
                    // So to keep "now" progress the same, we must shift startTimeNanos.
                    val currentProgress = (System.nanoTime() - startTimeNanos) * oldSpeed / 1_000_000
                    startTimeNanos = System.nanoTime() - (currentProgress * 1_000_000 / playbackSpeed).toLong()
                }
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
                    buttonPlayPause.isEnabled = true
                    buttonReset.isEnabled = true
                    buttonLaunchOverlay.isEnabled = true
                    
                    val duration = (subtitleCues.lastOrNull()?.endTimeMs ?: 0L) + 60000L // 1 min padding
                    sliderPlayback.valueFrom = 0.0f
                    sliderPlayback.valueTo = duration.toFloat()
                    sliderPlayback.value = 0.0f
                    sliderPlayback.isEnabled = true
                    
                    textViewSubtitle.text = "[Ready to play]"
                    textViewCurrentTime.text = formatTime(0)
                    textViewYellowTime.text = formatTime(0)
                    setPlayButtonState(false);
                    sendSubtitleUpdate("")
                } else {
                    Toast.makeText(this, "No cues parsed.", Toast.LENGTH_LONG).show();
                    resetPlaybackStateOnError()
                }
            } ?: run {
                Toast.makeText(this, "Failed file stream.", Toast.LENGTH_LONG).show();
                resetPlaybackStateOnError()
            }
        } catch (e: Exception) {
            Log.e(TAG, "load/parse $format error", e);
            resetPlaybackStateOnError()
        }
    }

    // ============================================
    // --- Subtitle Parsing Logic (Omitted for brevity, assuming existing ones work or fix if needed) ---
    // ============================================
    private fun parseVtt(inputStream: InputStream): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        val reader = inputStream.bufferedReader(Charsets.UTF_8)
        try {
            var line = reader.readLine()
            if (line?.startsWith("\uFEFF") == true) line = line.substring(1)
            if (line == null || !line.trim().startsWith("WEBVTT")) return emptyList()

            while (reader.readLine().also { line = it } != null) {
                val t = line?.trim() ?: ""
                if (t.isEmpty() || t.startsWith("NOTE")) continue
                if (t.contains("-->")) {
                    val times = t.split("-->")
                    if (times.size < 2) continue
                    val start = timeToMillis(times[0].trim())
                    val endPart = times[1].trim().split(Regex("\\s+"))[0]
                    val end = timeToMillis(endPart)
                    
                    val textBuilder = StringBuilder()
                    var contentLine: String? = reader.readLine()
                    while (contentLine != null && contentLine.isNotBlank()) {
                        if (textBuilder.isNotEmpty()) textBuilder.append("")
                        textBuilder.append(contentLine)
                        contentLine = reader.readLine()
                    }
                    if (start != null && end != null && textBuilder.isNotEmpty()) {
                        cues.add(SubtitleCue(start, end, textBuilder.toString()))
                    }
                }
            }
        } catch (e: Exception) { Log.e("VTT", "Error", e) }
        return cues.sortedBy { it.startTimeMs }
    }

    private fun parseSrt(inputStream: InputStream): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        val reader = inputStream.bufferedReader(Charsets.UTF_8)
        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val tL = line?.trim()
                if (tL.isNullOrEmpty()) continue
                if (tL.toIntOrNull() != null) {
                    val timeL = reader.readLine()?.trim()
                    if (timeL != null && timeL.contains("-->")) {
                        val ts = timeL.split("-->")
                        val start = timeToMillis(ts[0].trim().replace(',', '.'))
                        val end = timeToMillis(ts[1].trim().split(Regex("\\s+"))[0].replace(',', '.'))
                        
                        val b = StringBuilder()
                        var txtL: String? = reader.readLine()
                        while (txtL != null && txtL.isNotBlank()) {
                            if (b.isNotEmpty()) b.append("")
                            b.append(txtL)
                            txtL = reader.readLine()
                        }
                        if (start != null && end != null && b.isNotEmpty()) {
                            cues.add(SubtitleCue(start, end, b.toString()))
                        }
                    }
                }
            }
        } catch (e: Exception) { Log.e("SRT", "Error", e) }
        return cues.sortedBy { it.startTimeMs }
    }

    private fun timeToMillis(t: String): Long? {
        try {
            val p = t.split(":")
            val last = p.last()
            val dot = last.indexOf('.')
            val secStr: String
            val msStr: String
            if (dot != -1) {
                secStr = last.substring(0, dot)
                msStr = last.substring(dot + 1).padEnd(3, '0').take(3)
            } else {
                secStr = last
                msStr = "000"
            }
            val ms = msStr.toLong()
            val s = secStr.toLong()
            
            return when (p.size) {
                3 -> { // HH:MM:SS
                    val h = p[0].toLong()
                    val m = p[1].toLong()
                    (h * 3600 + m * 60 + s) * 1000 + ms
                }
                2 -> { // MM:SS
                    val m = p[0].toLong()
                    (m * 60 + s) * 1000 + ms
                }
                else -> null
            }
        } catch (e: Exception) { return null }
    }

    // ============================================
    // --- Overlay & Playback Logic ---
    // ============================================

    private fun checkOverlayPermission(): Boolean = 
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            overlayPermissionLauncher.launch(i)
        }
    }

    private fun startOverlayService() {
        if (!checkOverlayPermission()) return
        startService(Intent(this, OverlayService::class.java))
    }

    private fun stopOverlayService() {
        stopService(Intent(this, OverlayService::class.java))
    }

    private fun sendSubtitleUpdate(text: String) {
        // Bug4 Fix: Honor the visibility flag
        val textToSend = if (isOverlayUIShown) text else ""
        val i = Intent(ACTION_UPDATE_SUBTITLE_LOCAL).apply {
            putExtra(EXTRA_SUBTITLE_TEXT_LOCAL, textToSend)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(i)
    }

    private fun togglePlayPause() {
        if (isPlaying) pausePlayback() else startPlayback()
    }

    private fun startPlayback() {
        if (subtitleCues.isEmpty()) return
        isPlaying = true
        setPlayButtonState(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // Bug5 Fix: Use actual paused time to sync start clock
        startTimeNanos = System.nanoTime() - (pausedElapsedTimeMillis * 1_000_000)
        
        handler.post(updateRunnable)
        
        // Notify overlay
        val intent = Intent(OverlayService.ACTION_PAUSE_PLAY).apply { putExtra("is_paused", false) }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun pausePlayback() {
        if (!isPlaying) return
        isPlaying = false
        setPlayButtonState(false)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // Save exact progress
        pausedElapsedTimeMillis = (System.nanoTime() - startTimeNanos) / 1_000_000
        handler.removeCallbacks(updateRunnable)
        
        // Notify overlay
        val intent = Intent(OverlayService.ACTION_PAUSE_PLAY).apply { putExtra("is_paused", true) }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun resetPlayback() {
        pausePlayback()
        pausedElapsedTimeMillis = 0L
        startTimeNanos = 0L
        textViewSubtitle.text = "[Ready to play]"
        textViewCurrentTime.text = formatTime(0)
        textViewYellowTime.text = formatTime(0)
        sliderPlayback.value = 0.0f
        sendSubtitleUpdate("")
    }

    private fun resetPlaybackStateOnError() {
        subtitleCues = emptyList()
        buttonPlayPause.isEnabled = false
        sliderPlayback.isEnabled = false
        textViewSubtitle.text = "[Error loading file]"
        sendSubtitleUpdate("")
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

    // Bug3 Fix: Ensure correct core loop
    private val updateRunnable = object : Runnable {
        override fun run() {
            if (!isPlaying) return
            
            // 1. Calculate time
            val elapsedReal = (System.nanoTime() - startTimeNanos) / 1_000_000
            
            // 2. Update UI Timers (Bug1 Fix)
            textViewCurrentTime.text = formatTime(elapsedReal)
            textViewYellowTime.text = formatTime((elapsedReal * playbackSpeed).toLong())
            
            // 3. Update Slider
            if (!sliderPlayback.isPressed) {
                if (elapsedReal.toFloat() <= sliderPlayback.valueTo) {
                    sliderPlayback.value = elapsedReal.toFloat()
                }
            }
            
            // 4. Update Subtitles (Core Bug3 fix)
            val cue = findCueForTime(elapsedReal)
            val newText = cue?.text ?: ""
            if (textViewSubtitle.text != newText) {
                textViewSubtitle.text = newText
                sendSubtitleUpdate(newText)
            }
            
            // 5. Check end
            if (subtitleCues.isNotEmpty() && elapsedReal >= subtitleCues.last().endTimeMs) {
                pausePlayback()
                textViewSubtitle.text = "[Playback Finished]"
                sendSubtitleUpdate("[Playback Finished]")
                return
            }
            
            handler.postDelayed(this, 30) // 30ms for smoother update
        }
    }

    private fun findCueForTime(time: Long): SubtitleCue? = subtitleCues.find { time >= it.startTimeMs && time < it.endTimeMs }

    private fun formatTime(millis: Long): String {
        val sT = millis / 1000
        val m = sT / 60
        val s = sT % 60
        val ms = millis % 1000
        return String.format("%02d:%02d.%03d", m, s, ms)
    }
}
