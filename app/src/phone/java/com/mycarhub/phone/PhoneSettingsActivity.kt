package com.mycarhub.phone

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.mycarhub.BuildConfig
import com.mycarhub.MyCarHubApplication
import com.mycarhub.R
import com.mycarhub.assistant.messages.MessageAccess
import com.mycarhub.phone.conversation.VoiceOutputSettings
import com.mycarhub.phone.conversation.VoiceSettingsActivity
import com.mycarhub.phone.floating.FloatingVoiceService
import com.mycarhub.phone.floating.FloatingVoiceSettings
import com.mycarhub.phone.floating.FloatingVoiceSetupActivity
import com.mycarhub.ui.WakeWordSetupActivity

/**
 * 폰 빌드 설정 화면: 메인 화면에서 옮긴 호출어 듣기 켜기·끄기, 재등록·삭제, 권한 관리, 즐겨찾기 표시 여부.
 * 각 기능은 기존 화면(호출어 등록, 알림 접근 안내, 백그라운드 호출 설정)을 그대로 연다.
 */
class PhoneSettingsActivity : Activity() {

    private val app get() = application as MyCarHubApplication
    private var openDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_phone_settings)

        findViewById<Button>(R.id.settingsRegister).setOnClickListener {
            if (blockIfDriving()) return@setOnClickListener
            startActivity(Intent(this, WakeWordSetupActivity::class.java))
        }
        findViewById<Button>(R.id.settingsDelete).setOnClickListener { confirmDelete() }
        findViewById<Button>(R.id.settingsMicButton).setOnClickListener { requestMicrophone() }
        findViewById<Button>(R.id.settingsContactsButton).setOnClickListener { requestContacts() }
        findViewById<Button>(R.id.settingsMessagesButton).setOnClickListener {
            app.messages.accessSettingsIntent()?.let { startActivity(it) }
        }
        findViewById<Button>(R.id.settingsFloatingButton).setOnClickListener {
            startActivity(FloatingVoiceSetupActivity.intent(this, start = false))
        }
        findViewById<Button>(R.id.settingsNamesButton).setOnClickListener {
            startActivity(Intent(this, KnownNamesActivity::class.java))
        }
        findViewById<Button>(R.id.settingsAppInfo).setOnClickListener { startActivity(appDetailsIntent()) }
        findViewById<Button>(R.id.settingsVoiceButton).setOnClickListener {
            startActivity(Intent(this, VoiceSettingsActivity::class.java))
        }
        findViewById<Switch>(R.id.settingsListen).apply {
            isChecked = PhoneVoice.listenEnabled(this@PhoneSettingsActivity)
            setOnCheckedChangeListener { _, checked -> setListening(checked) }
        }
        // 다른 앱 위 플로팅 버튼: 이 화면을 떠나 다른 앱으로 가면 서비스가 이 값대로 보이거나 숨긴다
        findViewById<Switch>(R.id.settingsShowBubble).apply {
            isChecked = PhoneVoice.showBubble(this@PhoneSettingsActivity)
            setOnCheckedChangeListener { _, checked -> PhoneVoice.setShowBubble(this@PhoneSettingsActivity, checked) }
        }
        findViewById<Switch>(R.id.settingsShowFavorites).apply {
            isChecked = PhoneMainScreen.showFavorites(this@PhoneSettingsActivity)
            setOnCheckedChangeListener { _, checked -> PhoneMainScreen.setShowFavorites(this@PhoneSettingsActivity, checked) }
        }
        if (BuildConfig.MEASUREMENT_ACTIVITY.isNotEmpty()) {
            // 실제 목소리 측정 화면: 개발 빌드에만 있는 클래스라 이름으로 연다
            findViewById<View>(R.id.settingsDevSection).visibility = View.VISIBLE
            findViewById<Button>(R.id.settingsMeasure).setOnClickListener {
                if (blockIfDriving()) return@setOnClickListener
                startActivity(Intent().setClassName(this, BuildConfig.MEASUREMENT_ACTIVITY))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroy() {
        openDialog?.dismiss()
        super.onDestroy()
    }

    private fun render() {
        val model = app.wakeWords.model
        findViewById<TextView>(R.id.settingsWakeName).text =
            if (model == null) getString(R.string.phone_settings_wake_none) else getString(R.string.phone_settings_wake_registered, model.name)
        findViewById<Button>(R.id.settingsRegister).setText(if (model == null) R.string.voice_register else R.string.voice_reregister)
        findViewById<View>(R.id.settingsDelete).visibility = if (model == null) View.GONE else View.VISIBLE
        findViewById<Switch>(R.id.settingsListen).isEnabled = model != null

        val mic = app.wakeWords.hasMicrophonePermission()
        findViewById<TextView>(R.id.settingsMicText).setText(if (mic) R.string.phone_perm_mic_on else R.string.phone_perm_mic_off)
        findViewById<View>(R.id.settingsMicButton).visibility = if (mic) View.GONE else View.VISIBLE

        val contacts = ContactNames.hasPermission(this)
        findViewById<TextView>(R.id.settingsContactsText).setText(if (contacts) R.string.phone_perm_contacts_on else R.string.phone_perm_contacts_off)
        findViewById<View>(R.id.settingsContactsButton).visibility = if (contacts) View.GONE else View.VISIBLE

        val messages = app.messages.status
        findViewById<View>(R.id.settingsMessagesRow).visibility =
            if (messages == MessageAccess.Status.UNSUPPORTED) View.GONE else View.VISIBLE
        val messagesOn = messages == MessageAccess.Status.READY || messages == MessageAccess.Status.NOT_CONNECTED
        findViewById<TextView>(R.id.settingsMessagesText)
            .setText(if (messagesOn) R.string.phone_perm_messages_on else R.string.phone_perm_messages_off)
        findViewById<Button>(R.id.settingsMessagesButton).setText(if (messagesOn) R.string.phone_change else R.string.phone_allow)

        // 다른 앱 사용 중 호출에 필요한 '다른 앱 위에 표시'와 서비스 상태
        val floating = FloatingVoiceSettings(this)
        findViewById<Switch>(R.id.settingsListen).isChecked = floating.enabled
        // 플로팅 버튼 메뉴에서 숨긴 경우를 반영
        findViewById<Switch>(R.id.settingsShowBubble).isChecked = floating.showBubble
        findViewById<TextView>(R.id.settingsFloatingText).setText(
            when {
                !floating.canDrawOverlays -> R.string.phone_perm_floating_needs_overlay
                floating.enabled && FloatingVoiceService.isRunning -> R.string.phone_perm_floating_on
                else -> R.string.phone_perm_floating_off
            },
        )

        findViewById<TextView>(R.id.settingsVoiceText).text =
            getString(R.string.voice_settings_entry_summary, String.format(java.util.Locale.ROOT, "%.1f", VoiceOutputSettings(this).rate))

        findViewById<TextView>(R.id.settingsNamesText).text = getString(R.string.phone_names_count, KnownNames.all(this).size)

        findViewById<TextView>(R.id.settingsFavoritesCount).text =
            getString(R.string.phone_favorites_count, app.favoriteApps.favorites.size)
    }

    /** 상시 호출어 듣기 켜기·끄기: 이 화면이 보이는 지금 서비스를 시작·중지한다. */
    private fun setListening(enabled: Boolean) {
        val floating = FloatingVoiceSettings(this)
        if (enabled && (!floating.hasMicPermission || !floating.canDrawOverlays)) {
            // 필요한 권한을 순서대로 요청한 뒤 켠다 (허용 화면으로 이어짐)
            startActivity(FloatingVoiceSetupActivity.intent(this, start = true))
            return
        }
        PhoneVoice.setListenEnabled(this, enabled)
        if (enabled) FloatingVoiceService.start(this) else FloatingVoiceService.stop(this)
        // 서비스 시작은 비동기라 잠시 뒤 상태를 다시 그린다
        window.decorView.postDelayed({ if (!isFinishing) render() }, RENDER_DELAY_MS)
    }

    private fun confirmDelete() {
        if (blockIfDriving()) return
        val model = app.wakeWords.model ?: return
        openDialog = AlertDialog.Builder(this)
            .setTitle(R.string.voice_delete_title)
            .setMessage(getString(R.string.phone_delete_message, model.name))
            .setPositiveButton(R.string.voice_delete) { _, _ ->
                app.wakeWords.delete()
                Toast.makeText(this, R.string.voice_deleted, Toast.LENGTH_SHORT).show()
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun requestMicrophone() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
    }

    private fun requestContacts() {
        if (ContactNames.hasPermission(this)) return
        requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQUEST_CONTACTS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CONTACTS) {
            if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED &&
                !shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS)
            ) {
                startActivity(appDetailsIntent())
            }
            return render()
        }
        if (requestCode != REQUEST_MIC) return
        if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED &&
            !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        ) {
            // '다시 묻지 않음': 시스템 창이 더 뜨지 않으므로 앱 정보의 권한 화면으로 안내
            Toast.makeText(this, R.string.phone_mic_blocked, Toast.LENGTH_LONG).show()
            startActivity(appDetailsIntent())
        }
        render()
    }

    private fun appDetailsIntent() =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))

    /** 폰에는 주행 상태가 없어 보통 통과한다. 차량에서 실행된 경우를 위해 메인과 같은 규칙을 지킨다. */
    private fun blockIfDriving(): Boolean {
        if (!app.restrictions.isDrivingRestricted) return false
        Toast.makeText(this, R.string.setup_blocked_driving, Toast.LENGTH_SHORT).show()
        return true
    }

    private companion object {
        const val REQUEST_MIC = 30
        const val REQUEST_CONTACTS = 31
        const val RENDER_DELAY_MS = 600L
    }
}
