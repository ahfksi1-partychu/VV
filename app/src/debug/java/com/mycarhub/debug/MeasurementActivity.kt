package com.mycarhub.debug

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import com.mycarhub.MyCarHubApplication
import com.mycarhub.R
import com.mycarhub.wakeword.audio.MicrophonePcmSource
import com.mycarhub.wakeword.core.AudioSpec
import com.mycarhub.wakeword.core.Levels
import com.mycarhub.wakeword.core.StreamingWakeWordDetector
import com.mycarhub.wakeword.core.WakeWordModel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.util.Date

/**
 * 개발 빌드 전용 측정 화면.
 *
 * 1. 저장 내용·용도를 안내하고 동의를 받는다 (동의 전에는 녹음하지 않음).
 * 2. 조건별 세션 동안 마이크 소리 전체를 WAV로 저장하면서 현재 모델로 호출어를 감지·기록한다.
 * 3. 세션 종료 시 시도 횟수를 입력받아 감지율·오인식을 meta.json 에 남긴다.
 */
class MeasurementActivity : Activity() {

    private lateinit var store: MeasurementStore
    private var model: WakeWordModel? = null

    private lateinit var consentPanel: View
    private lateinit var sessionPanel: View
    private lateinit var modelInfo: TextView
    private lateinit var conditionGroup: RadioGroup
    private lateinit var liveStatus: TextView
    private lateinit var levelText: TextView
    private lateinit var levelMeter: ProgressBar
    private lateinit var detectionText: TextView
    private lateinit var startButton: Button
    private lateinit var summaryText: TextView

    private val main = Handler(Looper.getMainLooper())
    private var loop: SessionLoop? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_measurement)
        store = MeasurementStore(this)
        model = (application as MyCarHubApplication).wakeWords.model

        consentPanel = findViewById(R.id.consentPanel)
        sessionPanel = findViewById(R.id.sessionPanel)
        modelInfo = findViewById(R.id.modelInfo)
        conditionGroup = findViewById(R.id.conditionGroup)
        liveStatus = findViewById(R.id.liveStatus)
        levelText = findViewById(R.id.levelText)
        levelMeter = findViewById(R.id.measureLevel)
        detectionText = findViewById(R.id.detectionText)
        startButton = findViewById(R.id.startButton)
        summaryText = findViewById(R.id.summaryText)

        MeasurementStore.Condition.entries.forEachIndexed { i, condition ->
            conditionGroup.addView(RadioButton(this).apply {
                id = View.generateViewId()
                tag = condition
                textSize = 20f
                text = getString(
                    if (condition.expectWakeWord) R.string.measure_expect_detect else R.string.measure_expect_reject,
                    getString(condition.labelRes),
                )
                isChecked = i == 0
            })
        }

        findViewById<Button>(R.id.consentAgree).setOnClickListener {
            store.giveConsent()
            render()
        }
        findViewById<Button>(R.id.consentDecline).setOnClickListener { finish() }
        startButton.setOnClickListener { if (loop == null) startSession() else stopSession() }
        findViewById<Button>(R.id.deleteButton).setOnClickListener { confirmDelete(withdraw = false) }
        findViewById<Button>(R.id.withdrawButton).setOnClickListener { confirmDelete(withdraw = true) }
        render()
    }

    override fun onPause() {
        // 화면을 벗어나면 세션을 끝낸다 (시도 횟수는 입력받지 못하므로 0으로 기록)
        if (loop != null) finishSession(attempts = null)
        super.onPause()
    }

    private fun render() {
        val consent = store.hasConsent
        consentPanel.visibility = if (consent) View.GONE else View.VISIBLE
        sessionPanel.visibility = if (consent) View.VISIBLE else View.GONE
        if (!consent) return
        val m = model
        modelInfo.text = if (m == null) {
            getString(R.string.measure_no_model)
        } else {
            getString(
                R.string.measure_model_info, m.name, m.threshold,
                DateFormat.getDateTimeInstance().format(Date(store.consentAtMillis)),
            )
        }
        startButton.isEnabled = m != null
        startButton.setText(if (loop == null) R.string.measure_start else R.string.measure_stop)
        for (i in 0 until conditionGroup.childCount) conditionGroup.getChildAt(i).isEnabled = loop == null
        renderSummary()
    }

    // region 세션

    private fun selectedCondition(): MeasurementStore.Condition =
        conditionGroup.findViewById<RadioButton>(conditionGroup.checkedRadioButtonId).tag as MeasurementStore.Condition

    private fun startSession() {
        val m = model ?: return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
            return
        }
        val condition = selectedCondition()
        val session = SessionLoop(m, condition, store.newSessionDir(condition))
        loop = session
        session.start()
        detectionText.text = getString(R.string.measure_detections, 0)
        liveStatus.text = getString(R.string.measure_recording, getString(condition.labelRes))
        render()
    }

    private fun stopSession() {
        val session = loop ?: return
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.measure_attempts_hint)
            textSize = 24f
        }
        session.halt()
        AlertDialog.Builder(this)
            .setTitle(R.string.measure_attempts_title)
            .setMessage(
                if (session.condition == MeasurementStore.Condition.CONVERSATION) {
                    R.string.measure_attempts_conversation
                } else {
                    R.string.measure_attempts_message
                },
            )
            .setView(input)
            .setCancelable(false)
            .setPositiveButton(R.string.save) { _, _ -> finishSession(input.text.toString().toIntOrNull() ?: 0) }
            .setNegativeButton(R.string.measure_discard) { _, _ -> discardSession() }
            .show()
    }

    private fun finishSession(attempts: Int?) {
        val session = loop ?: return
        loop = null
        session.halt()
        session.join(2_000)
        try {
            session.writeMeta(attempts ?: 0)
            liveStatus.text = getString(R.string.measure_saved, session.dir.name)
        } catch (e: IOException) {
            liveStatus.text = getString(R.string.measure_save_failed, e.message)
        }
        render()
    }

    private fun discardSession() {
        val session = loop ?: return
        loop = null
        session.halt()
        session.join(2_000)
        session.dir.deleteRecursively()
        liveStatus.setText(R.string.measure_discarded)
        render()
    }

    private inner class SessionLoop(
        private val model: WakeWordModel,
        val condition: MeasurementStore.Condition,
        val dir: File,
    ) : Thread("MeasurementSession") {

        @Volatile
        private var running = true
        private val startedAt = System.currentTimeMillis()
        private val detections = JSONArray()
        private val segments = JSONArray()
        private var durationMillis = 0
        private var silencedMillis = 0
        private var micError = false

        fun halt() {
            running = false
        }

        /** 다른 앱(시스템 음성비서 등)이 마이크 우선권을 가져가 무음을 받는 중 */
        @Volatile
        private var silenced = false

        override fun run() {
            val source = MicrophonePcmSource(this@MeasurementActivity) { silenced = it }
            if (!source.start()) {
                micError = true
                main.post { liveStatus.setText(R.string.setup_mic_unavailable) }
                return
            }
            val writer = StreamingWavWriter(File(dir, MeasurementStore.AUDIO_FILE))
            val detector = StreamingWakeWordDetector(model)
            var processed = 0L
            detector.segmentListener = StreamingWakeWordDetector.SegmentListener { score, _, millis ->
                segments.put(JSONObject().put("atMs", processed * 1000 / AudioSpec.SAMPLE_RATE).put("durationMs", millis).put("score", score.toDouble().finite()))
            }
            val buffer = ShortArray(AudioSpec.samplesForMillis(100))
            var lastUi = 0L
            var count = 0
            try {
                while (running) {
                    val n = source.read(buffer)
                    if (n < 0) break
                    writer.write(buffer, n)
                    processed += n
                    if (silenced) silencedMillis += AudioSpec.millisForSamples(n)
                    val detection = detector.process(buffer, n)
                    if (detection != null) {
                        count++
                        detections.put(JSONObject().put("atMs", processed * 1000 / AudioSpec.SAMPLE_RATE).put("score", detection.score.toDouble()))
                        val shown = count
                        main.post { detectionText.text = getString(R.string.measure_detections, shown) }
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUi >= 100) {
                        lastUi = now
                        val level = Levels.rmsDb(buffer, 0, n)
                        val seconds = processed / AudioSpec.SAMPLE_RATE
                        val muted = silenced
                        main.post {
                            levelMeter.progress = (level + 70f).toInt().coerceIn(0, 70)
                            levelText.text = getString(
                                if (muted) R.string.measure_level_silenced else R.string.measure_level, level, seconds,
                            )
                        }
                    }
                }
            } finally {
                source.stop()
                writer.close()
                durationMillis = writer.durationMillis
            }
        }

        fun writeMeta(attempts: Int) {
            val meta = JSONObject()
                .put("purpose", "MFCC+DTW vs embedding comparison only (development build)")
                .put("condition", condition.id)
                .put("conditionLabel", getString(condition.labelRes))
                .put("expectWakeWord", condition.expectWakeWord)
                .put("startedAt", startedAt)
                .put("durationMs", durationMillis)
                .put("attempts", attempts)
                .put("detectionCount", detections.length())
                .put("detections", detections)
                .put("segments", segments)
                .put("silencedMs", silencedMillis)
                .put("micError", micError)
                .put("audio", MeasurementStore.AUDIO_FILE)
                .put("model", JSONObject()
                    .put("name", model.name)
                    .put("threshold", model.threshold.toDouble())
                    .put("createdAt", model.createdAtMillis)
                    .put("featureVersion", model.featureVersion))
            File(dir, MeasurementStore.META_FILE).writeText(meta.toString(2))
        }
    }

    // endregion

    private fun renderSummary() {
        val sessions = store.sessions()
        if (sessions.isEmpty()) {
            summaryText.setText(R.string.measure_summary_empty)
            return
        }
        val lines = MeasurementStore.Condition.entries.mapNotNull { condition ->
            val rows = sessions.filter { it.optString("condition") == condition.id }
            if (rows.isEmpty()) return@mapNotNull null
            val attempts = rows.sumOf { it.optInt("attempts") }
            val detected = rows.sumOf { it.optInt("detectionCount") }
            val minutes = rows.sumOf { it.optInt("durationMs") } / 60_000f
            val label = getString(condition.labelRes)
            if (condition.expectWakeWord) {
                val rate = if (attempts > 0) 100f * detected / attempts else 0f
                getString(R.string.measure_summary_positive, label, rows.size, attempts, detected, rate)
            } else {
                getString(R.string.measure_summary_negative, label, rows.size, attempts, detected, minutes)
            }
        }
        val mb = store.totalBytes() / 1_048_576f
        summaryText.text = lines.joinToString("\n") + "\n" + getString(R.string.measure_storage, sessions.size, mb)
    }

    private fun confirmDelete(withdraw: Boolean) {
        AlertDialog.Builder(this)
            .setTitle(if (withdraw) R.string.measure_withdraw else R.string.measure_delete)
            .setMessage(if (withdraw) R.string.measure_withdraw_message else R.string.measure_delete_message)
            .setPositiveButton(R.string.remove) { _, _ ->
                if (loop != null) discardSession()
                if (withdraw) store.withdrawConsent() else store.deleteAll()
                liveStatus.setText(R.string.measure_deleted)
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startSession()
    }

    private fun Double.finite(): Any = if (isFinite()) this else JSONObject.NULL

    private companion object {
        const val REQUEST_MIC = 30
    }
}
