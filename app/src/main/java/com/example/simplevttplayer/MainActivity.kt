/**
 * MainActivity.kt - 主活動檔案
 * 
 * 功能說明：
 * - 字幕檔案選擇與解析（VTT/SRT 格式）
 * - 字幕播放控制（播放/暫停/重設/速度調整）
 * - 懸浮視窗覆蓋層服務管理
 * - 生命週期處理（修正：切換App時不暫停播放）
 */
package com.example.simplevttplayer // **<<< CHECK THIS LINE CAREFULLY!**
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import java.io.BufferedReader
import java.io.InputStream
import android.os.Build
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.slider.Slider
import com.google.android.material.slider.Slider.OnChangeListener
import com.google.android.material.slider.Slider.OnSliderTouchListener
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import android.widget.Spinner
import android.widget.AdapterView
import android.widget.ArrayAdapter

class MainActivity : AppCompatActivity() {
    companion object {
        private const val ACTION_UPDATE_SUBTITLE_LOCAL = OverlayService.ACTION_UPDATE_SUBTITLE
        private const val EXTRA_SUBTITLE_TEXT_LOCAL = OverlayService.EXTRA_SUBTITLE_TEXT
        private val TAG: String = MainActivity::class.java.simpleName
    }

    private lateinit var overlayPermissionLauncher: ActivityResultLauncher<Intent>
    private val overlayPausePlayReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == OverlayService.ACTION_PAUSE_PLAY) {
                val isPaused = intent.getBooleanExtra("is_paused", false)
                if (isPaused) {
                    if (isPlaying) pausePlayback()
                } else {
                    if (!isPlaying) startPlayback()
                }
            }
        }
    }

    private lateinit var buttonSelectFile: MaterialButton
    private lateinit var textViewFilePath: TextView
    private lateinit var textViewCurrentTime: TextView
    private lateinit var textViewSubtitle: TextView
    private lateinit var buttonPlayPause: MaterialButton
    private lateinit var buttonReset: MaterialButton
    private lateinit var buttonLaunchOverlay: MaterialButton
    private lateinit var sliderPlayback: Slider
    private lateinit var spinnerSpeed: Spinner
    private lateinit var textViewYellowTime: TextView
    private lateinit var editTextOverlayFontSize: android.widget.EditText

    private var subtitleCues: List<SubtitleCue> = emptyList()
    private var selectedFileUri: Uri? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isPlaying = false
    private var startTimeNanos: Long = 0L
    private var pausedElapsedTimeMillis: Long = 0L
    private var isOverlayUIShown = true
    private var playbackSpeed: Float = 1.0f

    data class SubtitleCue(val startTimeMs: Long, val endTimeMs: Long, val text: String)

    private val selectSubtitleFileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.also { uri ->
                selectedFileUri = uri
                val fileName = getFileName(uri)
                resetPlayback()
                if (fileName != null) {
                    textViewFilePath.text = "File: $fileName"
                    when {
                        fileName.lowercase().endsWith(".vtt") -> loadAndParseSubtitleFile(uri, "vtt")
                        fileName.lowercase().endsWith(".srt") -> loadAndParseSubtitleFile(uri, "srt")
                        else -> {
                            Toast.makeText(this, "Not VTT/SRT", Toast.LENGTH_SHORT).show()
                            resetPlaybackStateOnError()
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        overlayPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (checkOverlayPermission()) startOverlayService()
        }

        buttonSelectFile = findViewById(R.id.buttonSelectFile)
        textViewFilePath = findViewById(R.id.textViewFilePath)
        textViewCurrentTime = findViewById(R.id.textViewCurrentTime)
        textViewSubtitle = findViewById(R.id.textViewSubtitle)
        buttonPlayPause = findViewById(R.id.buttonPlayPause)
        buttonReset = findViewById(R.id.buttonReset)
        buttonLaunchOverlay = findViewById(R.id.buttonLaunchOverlay)
        sliderPlayback = findViewById(R.id.sliderPlayback)
        spinnerSpeed = findViewById(R.id.spinnerSpeed)
        textViewYellowTime = findViewById(R.id.textViewYellowTime)
        editTextOverlayFontSize = findViewById(R.id.editTextOverlayFontSize)

        buttonSelectFile.setOnClickListener { openFilePicker() }
        buttonPlayPause.setOnClickListener { togglePlayPause() }
        buttonReset.setOnClickListener { resetPlayback() }

        editTextOverlayFontSize.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val fontSize = s?.toString()?.toIntOrNull() ?: 20
                val intent = Intent(OverlayService.ACTION_UPDATE_FONT_SIZE)
                intent.putExtra(OverlayService.EXTRA_FONT_SIZE, fontSize)
                LocalBroadcastManager.getInstance(this@MainActivity).sendBroadcast(intent)
            }
        })

        buttonLaunchOverlay.setOnClickListener {
            isOverlayUIShown = !isOverlayUIShown
            if (isOverlayUIShown) {
                if (checkOverlayPermission()) startOverlayService() else requestOverlayPermission()
                sendSubtitleUpdate(textViewSubtitle.text.toString())
            } else {
                sendSubtitleUpdate("")
                stopOverlayService()
            }
        }

        setupSliderListener()
        setupSpeedSpinner()

        val pausePlayFilter = android.content.IntentFilter(OverlayService.ACTION_PAUSE_PLAY)
        LocalBroadcastManager.getInstance(this).registerReceiver(overlayPausePlayReceiver, pausePlayFilter)
    }

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
        val intent = Intent(OverlayService.ACTION_PAUSE_PLAY).apply { putExtra("is_paused", false) }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun pausePlayback() {
        if (!isPlaying) return
        isPlaying = false; setPlayButtonState(false)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        pausedElapsedTimeMillis = (System.nanoTime() - startTimeNanos) / 1_000_000
        handler.removeCallbacks(updateRunnable)
        val intent = Intent(OverlayService.ACTION_PAUSE_PLAY).apply { putExtra("is_paused", true) }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun resetPlayback() {
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
            if (textViewSubtitle.text != nT) { textViewSubtitle.text = nT; sendSubtitleUpdate(nT) }
            if (subtitleCues.isNotEmpty() && eR >= subtitleCues.last().endTimeMs) {
                pausePlayback(); textViewSubtitle.text = "[Playback Finished]"; sendSubtitleUpdate("[Playback Finished]")
                return
            }
            handler.postDelayed(this, 30)
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
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
        selectSubtitleFileLauncher.launch(i)
    }
}
