package com.mycarhub.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.mycarhub.MyCarHubApplication
import com.mycarhub.R
import com.mycarhub.apps.system.CarRestrictionMonitor
import com.mycarhub.wakeword.EnrollmentSession
import com.mycarhub.wakeword.EnrollmentSession.TakeState
import com.mycarhub.wakeword.TakeRecorder
import com.mycarhub.wakeword.WakeWordManager
import com.mycarhub.wakeword.WakeWordName
import com.mycarhub.wakeword.core.QualityIssue
import com.mycarhub.wakeword.core.TrainingResult
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 호출어 등록 화면: 자동차 이름 입력 → 5회 녹음(회차별 품질 검사·재녹음) → 개인 모델 학습·저장.
 *
 * 글자 입력과 녹음이 필요하므로 주행 중에는 쓰지 않는다 (distractionOptimized 미지정 + 앱 내부 차단).
 */
class WakeWordSetupActivity : Activity() {

    private lateinit var manager: WakeWordManager
    private lateinit var restrictions: CarRestrictionMonitor

    private lateinit var namePanel: View
    private lateinit var recordPanel: View
    private lateinit var donePanel: View
    private lateinit var nameInput: EditText
    private lateinit var nameMessage: TextView
    private lateinit var recordInstruction: TextView
    private lateinit var recordProgress: TextView
    private lateinit var recordPrompt: TextView
    private lateinit var levelMeter: ProgressBar
    private lateinit var takesContainer: LinearLayout
    private lateinit var recordButton: Button
    private lateinit var cancelButton: Button
    private lateinit var drivingBanner: TextView

    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private var session: EnrollmentSession? = null
    private var recording = false
    private var busy = false

    @Volatile
    private var cancelRecording = false

    /** 권한 요청 직전에 누른 녹음 회차 (허용되면 이어서 녹음). */
    private var pendingTake: Int? = null
    private var permissionAsked = false

    private val restrictionListener = CarRestrictionMonitor.Listener { applyRestrictions(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wakeword_setup)
        val app = application as MyCarHubApplication
        manager = app.wakeWords
        restrictions = app.restrictions

        namePanel = findViewById(R.id.namePanel)
        recordPanel = findViewById(R.id.recordPanel)
        donePanel = findViewById(R.id.donePanel)
        nameInput = findViewById(R.id.nameInput)
        nameMessage = findViewById(R.id.nameMessage)
        recordInstruction = findViewById(R.id.recordInstruction)
        recordProgress = findViewById(R.id.recordProgress)
        recordPrompt = findViewById(R.id.recordPrompt)
        levelMeter = findViewById(R.id.levelMeter)
        takesContainer = findViewById(R.id.takesContainer)
        recordButton = findViewById(R.id.recordButton)
        cancelButton = findViewById(R.id.cancelButton)
        drivingBanner = findViewById(R.id.setupDrivingBanner)

        // 호출어는 '쁘이' 하나: 고칠 수 없게 보여주기만 한다. 이전 호출어가 있었으면 바뀌었다고 알린다.
        nameInput.setText(WakeWordName.CURRENT)
        nameInput.keyListener = null
        nameInput.isFocusable = false
        nameMessage.text = manager.retiredName?.let { getString(R.string.setup_name_changed, it, WakeWordName.CURRENT) }
            ?: getString(R.string.setup_name_fixed, WakeWordName.CURRENT)
        nameInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                nameMessage.setText(R.string.setup_name_rule)
            }
        })
        nameInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                onNext()
                true
            } else {
                false
            }
        }
        findViewById<Button>(R.id.laterButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.nextButton).setOnClickListener { onNext() }
        recordButton.setOnClickListener { session?.nextTake?.let { recordTake(it) } }
        cancelButton.setOnClickListener { confirmCancel() }
        findViewById<Button>(R.id.finishButton).setOnClickListener { finish() }

        restrictions.addListener(restrictionListener)
    }

    override fun onResume() {
        super.onResume()
        applyRestrictions(restrictions.isDrivingRestricted)
    }

    override fun onPause() {
        // 화면을 벗어나면 녹음을 멈춘다 (진행 중 회차는 버림)
        cancelRecording = true
        super.onPause()
    }

    override fun onDestroy() {
        restrictions.removeListener(restrictionListener)
        cancelRecording = true
        worker.shutdownNow()
        super.onDestroy()
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // minSdk 28: OnBackInvokedCallback(API 33+) 대신 공통 경로 사용
    override fun onBackPressed() {
        if (recordPanel.visibility == View.VISIBLE) confirmCancel() else super.onBackPressed()
    }

    // region 1단계: 이름

    private fun onNext() {
        // 호출어는 '쁘이'로 정해져 있다 (입력란은 보여주기만 함)
        val name = WakeWordName.CURRENT
        session = manager.newEnrollment()
        namePanel.visibility = View.GONE
        recordPanel.visibility = View.VISIBLE
        recordInstruction.text = getString(R.string.setup_record_instruction, name)
        recordPrompt.text = if (name.length < 3) getString(R.string.setup_name_short_warning) else ""
        buildTakeRows()
        render()
        if (!manager.hasMicrophonePermission() && !manager.usesTestAudio) requestMicrophone(null)
    }

    // endregion

    // region 2단계: 녹음

    private fun buildTakeRows() {
        val s = session ?: return
        takesContainer.removeAllViews()
        repeat(s.takeCount) { index ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = resources.getDimensionPixelSize(R.dimen.touch_target)
            }
            val label = TextView(this).apply {
                textSize = 20f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val again = Button(this).apply {
                setText(R.string.setup_rerecord)
                textSize = 20f
                minimumHeight = resources.getDimensionPixelSize(R.dimen.touch_target)
                setOnClickListener { recordTake(index) }
            }
            row.addView(label)
            row.addView(again)
            takesContainer.addView(row)
        }
    }

    private fun render() {
        val s = session ?: return
        recordProgress.text = getString(R.string.setup_progress, s.acceptedCount, s.takeCount)
        for (i in 0 until s.takeCount) {
            val row = takesContainer.getChildAt(i) as LinearLayout
            val label = row.getChildAt(0) as TextView
            val again = row.getChildAt(1) as Button
            val state = s.state(i)
            label.text = when (state) {
                TakeState.Empty -> getString(R.string.setup_take_empty, i + 1)
                is TakeState.Accepted -> getString(
                    R.string.setup_take_ok, i + 1, state.report.speechMillis / 1000f, state.report.snrDb.toInt(),
                )
                is TakeState.Rejected -> getString(R.string.setup_take_bad, i + 1, state.issues.joinToString(" · ") { issueText(it) })
                TakeState.Inconsistent -> getString(R.string.setup_take_bad, i + 1, getString(R.string.issue_inconsistent))
            }
            label.alpha = if (state == TakeState.Empty) 0.6f else 1f
            label.setTextColor(if (state is TakeState.Rejected || state == TakeState.Inconsistent) BAD_COLOR else recordProgress.currentTextColor)
            again.visibility = if (state == TakeState.Empty) View.INVISIBLE else View.VISIBLE
            again.isEnabled = !busy && !restrictions.isDrivingRestricted
        }
        val next = s.nextTake
        recordButton.text = when {
            next == null -> getString(R.string.setup_record, s.takeCount)
            s.state(next) == TakeState.Empty -> getString(R.string.setup_record, next + 1)
            else -> getString(R.string.setup_rerecord_take, next + 1)
        }
        recordButton.isEnabled = !busy && next != null && !restrictions.isDrivingRestricted
        cancelButton.isEnabled = true
    }

    private fun recordTake(index: Int) {
        val s = session ?: return
        if (busy || restrictions.isDrivingRestricted) return
        if (!manager.usesTestAudio && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestMicrophone(index)
            return
        }
        busy = true
        recording = true
        cancelRecording = false
        recordPrompt.text = getString(R.string.setup_recording, s.name)
        render()
        worker.execute {
            val result = manager.recordTake(index, { levelDb, _ ->
                main.post { levelMeter.progress = (levelDb + 60f).toInt().coerceIn(0, 60) }
            }, { cancelRecording })
            main.post { onRecorded(index, result) }
        }
    }

    private fun onRecorded(index: Int, result: TakeRecorder.Result) {
        if (isDestroyed) return
        recording = false
        levelMeter.progress = 0
        val s = session ?: return
        when (result) {
            TakeRecorder.Result.Cancelled -> {
                busy = false
                recordPrompt.text = ""
                render()
            }
            TakeRecorder.Result.MicrophoneUnavailable -> {
                busy = false
                recordPrompt.setText(R.string.setup_mic_unavailable)
                render()
            }
            is TakeRecorder.Result.Recorded -> {
                recordPrompt.setText(R.string.setup_analyzing)
                worker.execute {
                    val state = s.submit(index, result.samples)
                    main.post {
                        busy = false
                        recordPrompt.text = if (state is TakeState.Rejected) {
                            getString(R.string.setup_take_bad, index + 1, state.issues.joinToString(" · ") { issueText(it) })
                        } else {
                            ""
                        }
                        render()
                        if (s.nextTake == null) train()
                    }
                }
            }
        }
    }

    private fun train() {
        val s = session ?: return
        busy = true
        recordPrompt.setText(R.string.setup_training)
        render()
        worker.execute {
            val result = s.train()
            val saveError = if (result is TrainingResult.Success) {
                try {
                    manager.save(s, result)
                    null
                } catch (e: IOException) {
                    e.message ?: e.javaClass.simpleName
                }
            } else {
                null
            }
            main.post { onTrained(result, saveError) }
        }
    }

    private fun onTrained(result: TrainingResult, saveError: String?) {
        if (isDestroyed) return
        busy = false
        when {
            saveError != null -> recordPrompt.text = getString(R.string.setup_save_failed, saveError)
            result is TrainingResult.Success -> {
                recordPanel.visibility = View.GONE
                donePanel.visibility = View.VISIBLE
                findViewById<TextView>(R.id.doneMessage).text = getString(R.string.setup_done_message, result.model.name)
                findViewById<TextView>(R.id.doneDetail).text = getString(R.string.setup_done_detail, result.model.threshold)
                setResult(RESULT_OK)
                return
            }
            result is TrainingResult.Inconsistent -> recordPrompt.setText(R.string.setup_fix_marked)
            result is TrainingResult.PoorQuality -> recordPrompt.setText(R.string.setup_fix_marked)
            else -> recordPrompt.text = result.toString()
        }
        render()
    }

    private fun confirmCancel() {
        if (session == null || donePanel.visibility == View.VISIBLE) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.setup_cancel_title)
            .setMessage(R.string.setup_cancel_message)
            .setPositiveButton(R.string.setup_cancel_confirm) { _, _ ->
                cancelRecording = true
                session = null
                finish()
            }
            .setNegativeButton(R.string.setup_continue, null)
            .show()
    }

    // endregion

    // region 마이크 권한

    private fun requestMicrophone(take: Int?) {
        pendingTake = take
        AlertDialog.Builder(this)
            .setTitle(R.string.mic_permission_title)
            .setMessage(R.string.mic_permission_rationale)
            .setPositiveButton(R.string.mic_permission_allow) { _, _ ->
                permissionAsked = true
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
            }
            .setNegativeButton(R.string.cancel) { _, _ -> recordPrompt.setText(R.string.mic_permission_denied) }
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_MIC) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            recordPrompt.text = ""
            pendingTake?.let { recordTake(it) }
        } else if (permissionAsked && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            // "다시 묻지 않음" 상태: 시스템 설정은 앱이 바꾸지 않고, 사용자가 직접 허용하도록 안내만 한다
            AlertDialog.Builder(this)
                .setTitle(R.string.mic_permission_title)
                .setMessage(R.string.mic_permission_blocked)
                .setPositiveButton(R.string.open_app_settings) { _, _ ->
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            recordPrompt.setText(R.string.mic_permission_blocked)
        } else {
            recordPrompt.setText(R.string.mic_permission_denied)
        }
        pendingTake = null
    }

    // endregion

    private fun applyRestrictions(drivingRestricted: Boolean) {
        drivingBanner.visibility = if (drivingRestricted) View.VISIBLE else View.GONE
        nameInput.isEnabled = !drivingRestricted
        findViewById<Button>(R.id.nextButton).isEnabled = !drivingRestricted
        if (drivingRestricted && recording) cancelRecording = true
        if (session != null) render()
    }

    private fun issueText(issue: QualityIssue): String = getString(
        when (issue) {
            QualityIssue.SILENT -> R.string.issue_silent
            QualityIssue.TOO_QUIET -> R.string.issue_too_quiet
            QualityIssue.TOO_SHORT -> R.string.issue_too_short
            QualityIssue.TOO_LONG -> R.string.issue_too_long
            QualityIssue.CLIPPED -> R.string.issue_clipped
            QualityIssue.NOISY -> R.string.issue_noisy
            QualityIssue.CUT_OFF -> R.string.issue_cut_off
        },
    )

    private companion object {
        const val REQUEST_MIC = 10
        const val BAD_COLOR = 0xFFFF8A65.toInt()
    }
}
