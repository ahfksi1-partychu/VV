package com.mycarhub.phone.floating

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.mycarhub.MyCarHubApplication
import com.mycarhub.R
import com.mycarhub.phone.PhoneVoice
import com.mycarhub.ui.WakeWordSetupActivity

/**
 * 상시 호출어 듣기 설정 (폰 테스트용 빌드 전용).
 *
 * [상시 듣기 시작]을 누르면 필요한 것을 순서대로 한 번씩 요청한다: 호출어 등록 → 마이크 권한 → (선택) 알림 표시 →
 * 다른 앱 위에 표시. 끝나면 바로 호출어 감지 서비스를 시작하고 설정 완료로 기록한다 (다음부터는 다시 묻지 않음).
 * 사용자가 거부한 단계는 다시 묻지 않고 넘어가거나(선택 항목), 시작할 수 없으면 멈춘다 (반복 요청하지 않음).
 * 마이크 포그라운드 서비스는 이 화면이 보이는 동안 시작한다 (Android 11+ while-in-use 조건).
 * 앱을 처음 열었을 때 설정이 끝나지 않았으면 메인 화면이 이 화면을 한 번 띄운다 ([EXTRA_FIRST_RUN]).
 */
class FloatingVoiceSetupActivity : Activity() {

    private lateinit var settings: FloatingVoiceSettings
    private lateinit var status: TextView
    private lateinit var toggle: Button

    // 순서대로 진행 중인 설정: 이번 진행에서 이미 요청한 단계, 돌아오기를 기다리는 단계
    private var flow = false
    private val asked = mutableSetOf<FlowStep>()
    private var waiting: FlowStep? = null

    private enum class FlowStep { MODEL, MIC, NOTIFY, OVERLAY }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_floating_setup)
        settings = FloatingVoiceSettings(this)
        status = findViewById(R.id.floatingStatus)
        toggle = findViewById(R.id.floatingToggle)

        findViewById<Button>(R.id.floatingModelButton).setOnClickListener {
            startActivity(Intent(this, WakeWordSetupActivity::class.java))
        }
        findViewById<Button>(R.id.floatingMicButton).setOnClickListener {
            request(Manifest.permission.RECORD_AUDIO, REQUEST_MIC)
        }
        findViewById<Button>(R.id.floatingNotifyButton).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) request(Manifest.permission.POST_NOTIFICATIONS, REQUEST_NOTIFY)
        }
        findViewById<Button>(R.id.floatingOverlayButton).setOnClickListener { openOverlaySettings() }
        toggle.setOnClickListener { toggle() }
        findViewById<Button>(R.id.floatingLater).apply {
            visibility = if (intent.getBooleanExtra(EXTRA_FIRST_RUN, false)) View.VISIBLE else View.GONE
            setOnClickListener {
                PhoneVoice.setSetupPostponed(this@FloatingVoiceSetupActivity)
                finish()
            }
        }
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_START, false)) startFlow()
    }

    override fun onResume() {
        super.onResume()
        render()
        // 호출어 등록·시스템 설정 화면에서 돌아옴: 다음 단계로
        if (flow && (waiting == FlowStep.MODEL || waiting == FlowStep.OVERLAY)) {
            waiting = null
            advance()
        }
    }

    private fun toggle() {
        if (FloatingVoiceService.isRunning) {
            settings.enabled = false
            FloatingVoiceService.stop(this)
            Toast.makeText(this, R.string.floating_stopped, Toast.LENGTH_SHORT).show()
            toggle.postDelayed({ if (!isFinishing) render() }, RENDER_DELAY_MS)
            render()
        } else {
            startFlow()
        }
    }

    // region 순서대로 설정

    private fun startFlow() {
        flow = true
        asked.clear()
        waiting = null
        advance()
    }

    /** 아직 안 된 단계를 하나 요청하거나, 모두 끝났으면 듣기를 시작한다. */
    private fun advance() {
        if (!flow || isFinishing) return
        when {
            !settings.hasWakeWord ->
                if (!ask(FlowStep.MODEL)) stopFlow(R.string.setup_flow_no_model)
                else startActivity(Intent(this, WakeWordSetupActivity::class.java))
            !settings.hasMicPermission ->
                if (!ask(FlowStep.MIC)) stopFlow(R.string.setup_flow_no_mic)
                else request(Manifest.permission.RECORD_AUDIO, REQUEST_MIC)
            // 선택: 거부해도 다음 단계로
            !settings.hasNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ask(FlowStep.NOTIFY) ->
                request(Manifest.permission.POST_NOTIFICATIONS, REQUEST_NOTIFY)
            !settings.canDrawOverlays && ask(FlowStep.OVERLAY) -> {
                Toast.makeText(this, R.string.setup_flow_overlay_toast, Toast.LENGTH_LONG).show()
                openOverlaySettings()
            }
            else -> finishFlow()
        }
    }

    /** 이번 진행에서 처음 요청하는 단계면 기다리는 단계로 기록하고 true. 이미 요청했으면 (거부·취소) false. */
    private fun ask(step: FlowStep): Boolean {
        if (!asked.add(step)) return false
        waiting = step
        return true
    }

    private fun finishFlow() {
        flow = false
        settings.enabled = true
        // ✕로 끈 뒤 다시 켜는 경우: 플로팅 버튼도 함께 다시 보인다
        settings.showBubble = true
        val started = FloatingVoiceService.isRunning || FloatingVoiceService.start(this)
        if (!started) {
            Toast.makeText(this, R.string.floating_start_failed, Toast.LENGTH_LONG).show()
            render()
            return
        }
        PhoneVoice.setSetupDone(this)
        Toast.makeText(
            this,
            if (settings.canDrawOverlays) R.string.setup_flow_done else R.string.setup_flow_done_app_only,
            Toast.LENGTH_LONG,
        ).show()
        // 메인 화면으로 돌아가 듣는 상태를 보여준다
        finish()
    }

    private fun stopFlow(message: Int) {
        flow = false
        waiting = null
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        render()
    }

    // endregion

    private fun render() {
        val model = (application as MyCarHubApplication).wakeWords.model
        row(R.id.floatingModelState, R.id.floatingModelButton, model != null,
            model?.let { getString(R.string.floating_req_model_ok, it.name) } ?: getString(R.string.floating_req_model_missing))
        row(R.id.floatingMicState, R.id.floatingMicButton, settings.hasMicPermission,
            getString(if (settings.hasMicPermission) R.string.floating_req_mic_ok else R.string.floating_req_mic_missing))
        row(R.id.floatingNotifyState, R.id.floatingNotifyButton, settings.hasNotificationPermission,
            getString(if (settings.hasNotificationPermission) R.string.floating_req_notify_ok else R.string.floating_req_notify_missing))
        row(R.id.floatingOverlayState, R.id.floatingOverlayButton, settings.canDrawOverlays,
            getString(if (settings.canDrawOverlays) R.string.floating_req_overlay_ok else R.string.floating_req_overlay_missing))

        val running = FloatingVoiceService.isRunning
        status.setText(if (running) R.string.floating_status_on else R.string.floating_status_off)
        toggle.setText(if (running) R.string.floating_turn_off else R.string.floating_turn_on)
    }

    private fun row(textId: Int, buttonId: Int, ok: Boolean, text: String) {
        findViewById<TextView>(textId).text = text
        findViewById<Button>(buttonId).visibility = if (ok) View.GONE else View.VISIBLE
    }

    private fun request(permission: String, code: Int) {
        requestPermissions(arrayOf(permission), code)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (flow) {
            // 순서대로 설정 중: 거부해도 설정 화면으로 보내지 않고 다음 단계로 (마이크가 없으면 멈춘다)
            waiting = null
            render()
            advance()
            return
        }
        val denied = grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED
        if (denied && permissions.isNotEmpty() && !shouldShowRequestPermissionRationale(permissions.first())) {
            // 직접 누른 버튼, '다시 묻지 않음' 상태: MyCarHub의 해당 설정 화면으로 안내 (알림은 앱 알림 화면, 그 밖은 앱 정보)
            val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri())
            if (requestCode == REQUEST_NOTIFY) {
                Toast.makeText(this, R.string.floating_notify_blocked, Toast.LENGTH_LONG).show()
                openSettings(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
                    appDetails,
                )
            } else {
                Toast.makeText(this, R.string.floating_permission_blocked, Toast.LENGTH_LONG).show()
                openSettings(appDetails)
            }
        }
        render()
    }

    /** 패키지를 지정하면 MyCarHub 항목으로 바로 간다 (갤럭시는 목록에서 MyCarHub 위치로 스크롤된다). */
    private fun openOverlaySettings() {
        openSettings(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri()),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
        )
    }

    private fun packageUri() = Uri.fromParts("package", packageName, null)

    /** 앞의 화면부터 열어 보고, 기기에 없으면 다음 화면으로 대체한다. */
    private fun openSettings(vararg intents: Intent) {
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (e: ActivityNotFoundException) {
                // 다음 후보로
            }
        }
        // 열 수 있는 화면이 없음: 순서대로 설정 중이면 다음 단계로
        if (flow) {
            waiting = null
            advance()
        }
    }

    companion object {
        private const val REQUEST_MIC = 51
        private const val REQUEST_NOTIFY = 52
        private const val RENDER_DELAY_MS = 600L
        private const val EXTRA_START = "com.mycarhub.phone.extra.START_SETUP"
        private const val EXTRA_FIRST_RUN = "com.mycarhub.phone.extra.FIRST_RUN"

        /** [start]: 열자마자 순서대로 설정을 시작한다. [firstRun]: 앱이 자동으로 띄운 안내 ([나중에] 버튼 표시). */
        fun intent(context: Context, start: Boolean, firstRun: Boolean = false): Intent =
            Intent(context, FloatingVoiceSetupActivity::class.java)
                .putExtra(EXTRA_START, start)
                .putExtra(EXTRA_FIRST_RUN, firstRun)
    }
}
