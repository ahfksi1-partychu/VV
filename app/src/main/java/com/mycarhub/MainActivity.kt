package com.mycarhub

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.mycarhub.apps.FavoriteAppsManager
import com.mycarhub.apps.model.AliasResult
import com.mycarhub.apps.model.FavoriteApp
import com.mycarhub.apps.model.LaunchResult
import com.mycarhub.apps.system.CarRestrictionMonitor
import com.mycarhub.assistant.messages.MessageAccess
import com.mycarhub.assistant.stt.SttSettings
import com.mycarhub.ui.AppChoiceAdapter
import com.mycarhub.ui.AssistantActivity
import com.mycarhub.ui.NotificationAccessDialog
import com.mycarhub.ui.FavoriteAppsAdapter
import com.mycarhub.ui.WakeWordSetupActivity
import com.mycarhub.wakeword.WakeWordManager
import com.mycarhub.wakeword.WakeWordManager.ListenState
import com.mycarhub.wakeword.core.Detection
import com.mycarhub.wakeword.core.WakeWordModel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : Activity(), FavoriteAppsAdapter.Actions {

    private lateinit var manager: FavoriteAppsManager
    private lateinit var restrictions: CarRestrictionMonitor
    private lateinit var adapter: FavoriteAppsAdapter
    private lateinit var addButton: Button
    private lateinit var drivingBanner: TextView
    private lateinit var statusText: TextView

    private val mainHandler = Handler(Looper.getMainLooper())
    private val background: ExecutorService = Executors.newSingleThreadExecutor()
    private var openDialog: AlertDialog? = null

    private lateinit var wakeWords: WakeWordManager
    private lateinit var voicePrefs: SharedPreferences
    private lateinit var voiceStatus: TextView
    private lateinit var voiceListenButton: Button
    private lateinit var voiceSetupButton: Button
    private lateinit var voiceDeleteButton: Button
    private lateinit var voiceSystemStt: TextView
    private lateinit var sttSettings: SttSettings
    private lateinit var messages: MessageAccess
    private lateinit var voiceMessages: TextView
    private var resumed = false

    private val favoritesListener = FavoriteAppsManager.Listener { adapter.submit(it) }
    private val restrictionListener = CarRestrictionMonitor.Listener { applyRestrictions(it) }
    private val wakeWordListener = object : WakeWordManager.Listener {
        override fun onWakeWordDetected(model: WakeWordModel, detection: Detection) = openAssistant(model.name)
        override fun onListenStateChanged(state: ListenState) = renderVoice()
        override fun onModelChanged(model: WakeWordModel?) = renderVoice()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val app = application as MyCarHubApplication
        manager = app.favoriteApps
        restrictions = app.restrictions

        adapter = FavoriteAppsAdapter(app.catalog, this)
        addButton = findViewById(R.id.addAppButton)
        drivingBanner = findViewById(R.id.drivingBanner)
        statusText = findViewById(R.id.statusText)

        findViewById<ListView>(R.id.favoritesList).apply {
            adapter = this@MainActivity.adapter
            emptyView = this@MainActivity.findViewById(R.id.emptyView)
            setOnItemClickListener { _, _, position, _ -> launch(this@MainActivity.adapter.getItem(position)) }
        }
        addButton.setOnClickListener { showAppPicker() }
        findViewById<Button>(R.id.emptyAddButton).setOnClickListener { showAppPicker() }

        wakeWords = app.wakeWords
        voicePrefs = getSharedPreferences(VOICE_PREFS, MODE_PRIVATE)
        voiceStatus = findViewById(R.id.voiceStatus)
        voiceListenButton = findViewById(R.id.voiceListenButton)
        voiceSetupButton = findViewById(R.id.voiceSetupButton)
        voiceDeleteButton = findViewById(R.id.voiceDeleteButton)
        voiceSystemStt = findViewById(R.id.voiceSystemStt)
        sttSettings = app.sttSettings
        findViewById<Button>(R.id.voiceCommandButton).setOnClickListener { openAssistant(null) }
        voiceSystemStt.setOnClickListener { confirmRevokeSystemStt() }
        messages = app.messages
        voiceMessages = findViewById(R.id.voiceMessages)
        voiceMessages.setOnClickListener { openNotificationAccess() }
        voiceListenButton.setOnClickListener { toggleListening() }
        voiceSetupButton.setOnClickListener { openWakeWordSetup() }
        voiceDeleteButton.setOnClickListener { confirmDeleteWakeWord() }
        if (BuildConfig.MEASUREMENT_ACTIVITY.isNotEmpty()) {
            // 실제 목소리 측정 화면: 개발 빌드에만 있는 클래스라 이름으로 연다 (배포 빌드는 빈 값)
            findViewById<Button>(R.id.voiceMeasureButton).apply {
                visibility = View.VISIBLE
                setOnClickListener {
                    if (blockIfDriving()) return@setOnClickListener
                    startActivity(Intent().setClassName(this@MainActivity, BuildConfig.MEASUREMENT_ACTIVITY))
                }
            }
        }

        manager.addListener(favoritesListener)
        restrictions.addListener(restrictionListener)
        wakeWords.addListener(wakeWordListener)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        // 다른 곳에서 앱이 삭제·변경되었을 수 있으므로 화면에 돌아올 때마다 상태를 다시 그린다.
        adapter.submit(manager.favorites)
        applyRestrictions(restrictions.isDrivingRestricted)
        if (!voicePrefs.getBoolean(KEY_SETUP_OFFERED, false) && wakeWords.model == null && !restrictions.isDrivingRestricted) {
            // 최초 실행: 호출어 등록 화면을 한 번 띄운다 ('나중에'로 건너뛸 수 있음)
            voicePrefs.edit().putBoolean(KEY_SETUP_OFFERED, true).apply()
            startActivity(Intent(this, WakeWordSetupActivity::class.java))
            return
        }
        startListeningIfEnabled()
        renderVoice()
    }

    override fun onPause() {
        // 1단계 범위: 화면이 열려 있을 때만 듣는다 (백그라운드 감지는 별도 승인 후 검토)
        resumed = false
        wakeWords.stopListening()
        super.onPause()
    }

    override fun onDestroy() {
        manager.removeListener(favoritesListener)
        restrictions.removeListener(restrictionListener)
        wakeWords.removeListener(wakeWordListener)
        openDialog?.dismiss()
        background.shutdownNow()
        super.onDestroy()
    }

    // region 앱 추가

    private fun showAppPicker() {
        if (blockIfDriving()) return
        addButton.isEnabled = false
        statusText.setText(R.string.loading_apps)
        background.execute {
            val apps = manager.queryAvailableApps()
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                addButton.isEnabled = !restrictions.isDrivingRestricted
                statusText.text = ""
                showPickerDialog(apps)
            }
        }
    }

    private fun showPickerDialog(apps: List<com.mycarhub.apps.model.LaunchableApp>) {
        val builder = AlertDialog.Builder(this).setTitle(R.string.picker_title)
        if (apps.isEmpty()) {
            builder.setMessage(R.string.picker_empty).setPositiveButton(R.string.ok, null)
        } else {
            val choices = AppChoiceAdapter((application as MyCarHubApplication).catalog, apps, manager::isRegistered)
            builder.setAdapter(choices) { _, which ->
                val selected = apps[which]
                if (manager.add(selected)) {
                    showStatus(getString(R.string.added_app, selected.label))
                } else {
                    showStatus(getString(R.string.already_registered, selected.label))
                }
            }.setNegativeButton(R.string.cancel, null)
        }
        openDialog = builder.show()
    }

    // endregion

    // region 실행

    private fun launch(app: FavoriteApp) {
        when (val result = manager.launch(app.id)) {
            is LaunchResult.Launched -> showStatus(getString(R.string.launching_app, app.displayName))
            else -> showLaunchFailure(result)
        }
    }

    private fun showLaunchFailure(result: LaunchResult) {
        val message = when (result) {
            is LaunchResult.NotInstalled -> getString(R.string.launch_not_installed, result.app.displayName)
            is LaunchResult.Disabled -> getString(R.string.launch_disabled, result.app.displayName)
            is LaunchResult.NoLaunchableActivity -> getString(R.string.launch_no_activity, result.app.displayName)
            is LaunchResult.BlockedWhileDriving -> getString(R.string.launch_blocked_driving, result.app.displayName)
            is LaunchResult.Failed -> getString(R.string.launch_failed, result.app.displayName, result.reason)
            is LaunchResult.NoMatch -> getString(R.string.launch_no_match, result.query)
            is LaunchResult.Ambiguous -> getString(R.string.launch_ambiguous, result.query)
            is LaunchResult.Launched -> return
        }
        showStatus(message)
        openDialog = AlertDialog.Builder(this)
            .setTitle(R.string.launch_failed_title)
            .setMessage(message)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    // endregion

    // region 편집 (FavoriteAppsAdapter.Actions)

    override fun onMove(app: FavoriteApp, offset: Int) {
        if (blockIfDriving()) return
        manager.move(app.id, offset)
    }

    override fun onEditAlias(app: FavoriteApp) {
        if (blockIfDriving()) return
        if (restrictions.isKeyboardRestricted) {
            showStatus(getString(R.string.keyboard_blocked_driving))
            return
        }
        val input = EditText(this).apply {
            setText(app.alias.orEmpty())
            hint = app.label
            setSingleLine()
            filters = arrayOf(InputFilter.LengthFilter(FavoriteAppsManager.MAX_ALIAS_LENGTH))
            setSelection(text.length)
        }
        openDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.alias_title, app.label))
            .setMessage(R.string.alias_message)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ -> saveAlias(app, input.text.toString()) }
            .setNeutralButton(R.string.alias_clear) { _, _ -> saveAlias(app, null) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun saveAlias(app: FavoriteApp, alias: String?) {
        val message = when (val result = manager.setAlias(app.id, alias)) {
            AliasResult.Saved -> getString(R.string.alias_saved, app.label, alias?.trim())
            AliasResult.Cleared -> getString(R.string.alias_cleared, app.label)
            AliasResult.TooLong -> getString(R.string.alias_too_long, FavoriteAppsManager.MAX_ALIAS_LENGTH)
            is AliasResult.Duplicate -> getString(R.string.alias_duplicate, result.other.displayName)
            AliasResult.NotFound -> getString(R.string.app_not_found)
        }
        showStatus(message)
    }

    override fun onRemove(app: FavoriteApp) {
        if (blockIfDriving()) return
        openDialog = AlertDialog.Builder(this)
            .setTitle(R.string.remove_title)
            .setMessage(getString(R.string.remove_message, app.displayName))
            .setPositiveButton(R.string.remove) { _, _ ->
                if (manager.remove(app.id)) showStatus(getString(R.string.removed_app, app.displayName))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // endregion

    // region 음성 호출

    private fun startListeningIfEnabled() {
        if (!resumed || wakeWords.model == null) return
        if (!voicePrefs.getBoolean(KEY_LISTEN_ENABLED, true)) return
        if (!wakeWords.usesTestAudio && !wakeWords.hasMicrophonePermission()) return
        wakeWords.startListening()
    }

    private fun toggleListening() {
        if (wakeWords.listenState != ListenState.OFF) {
            voicePrefs.edit().putBoolean(KEY_LISTEN_ENABLED, false).apply()
            wakeWords.stopListening()
            return
        }
        if (!wakeWords.usesTestAudio && !wakeWords.hasMicrophonePermission()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.mic_permission_title)
                .setMessage(R.string.mic_permission_rationale)
                .setPositiveButton(R.string.mic_permission_allow) { _, _ ->
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        voicePrefs.edit().putBoolean(KEY_LISTEN_ENABLED, true).apply()
        startListeningIfEnabled()
        wakeWords.model?.let { showStatus(getString(R.string.voice_listening_started, it.name)) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_MIC) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            voicePrefs.edit().putBoolean(KEY_LISTEN_ENABLED, true).apply()
            startListeningIfEnabled()
        } else if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            showStatus(getString(R.string.mic_permission_blocked))
        } else {
            showStatus(getString(R.string.mic_permission_denied))
        }
        renderVoice()
    }

    private fun openWakeWordSetup() {
        if (restrictions.isDrivingRestricted) {
            showStatus(getString(R.string.setup_blocked_driving))
            return
        }
        startActivity(Intent(this, WakeWordSetupActivity::class.java))
    }

    private fun confirmDeleteWakeWord() {
        if (blockIfDriving()) return
        val model = wakeWords.model ?: return
        openDialog = AlertDialog.Builder(this)
            .setTitle(R.string.voice_delete_title)
            .setMessage(getString(R.string.voice_delete_message, model.name))
            .setPositiveButton(R.string.voice_delete) { _, _ ->
                wakeWords.delete()
                showStatus(getString(R.string.voice_deleted))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 호출어 감지 또는 '음성 명령' 버튼 → 음성비서 화면. 호출어 듣기는 이 화면의 onPause에서 멈추고,
     * 음성비서는 감지 스레드가 마이크를 놓은 뒤 음성 인식을 시작한다. 돌아오면 onResume에서 다시 듣는다.
     */
    private fun openAssistant(wakeWordName: String?) {
        wakeWords.stopListening()
        openDialog?.takeIf { it.isShowing }?.dismiss()
        startActivity(AssistantActivity.intent(this, wakeWordName))
    }

    /** 알림 접근 허용·해제: 무엇을 읽는지 안내한 뒤 시스템 설정 화면으로 (정차 중에만). */
    private fun openNotificationAccess() {
        if (blockIfDriving()) return
        val settings = messages.accessSettingsIntent() ?: return
        openDialog = NotificationAccessDialog.show(this, settings) {}
    }

    private fun confirmRevokeSystemStt() {
        if (blockIfDriving()) return
        openDialog = AlertDialog.Builder(this)
            .setTitle(R.string.voice_system_stt_revoke_title)
            .setMessage(R.string.voice_system_stt_revoke_message)
            .setPositiveButton(R.string.voice_system_stt_revoke) { _, _ ->
                sttSettings.allowSystemRecognizer = false
                showStatus(getString(R.string.voice_system_stt_revoked))
                renderVoice()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun renderVoice() {
        val model = wakeWords.model
        val state = wakeWords.listenState
        val stateText = when {
            model == null -> null
            state == ListenState.LISTENING -> getString(R.string.voice_state_listening)
            state == ListenState.SILENCED -> getString(R.string.voice_state_silenced)
            state == ListenState.MIC_UNAVAILABLE -> getString(R.string.voice_state_mic_unavailable)
            !wakeWords.usesTestAudio && !wakeWords.hasMicrophonePermission() -> getString(R.string.voice_state_no_permission)
            else -> getString(R.string.voice_state_off)
        }
        val base = if (model == null) {
            wakeWords.retiredName?.let { getString(R.string.voice_retired, it) } ?: getString(R.string.voice_not_registered)
        } else {
            getString(R.string.voice_registered, model.name) + " · " + stateText
        }
        voiceStatus.text = if (wakeWords.usesTestAudio) "$base ${getString(R.string.voice_test_audio)}" else base
        voiceSetupButton.setText(if (model == null) R.string.voice_register else R.string.voice_reregister)
        voiceListenButton.visibility = if (model == null) View.GONE else View.VISIBLE
        voiceDeleteButton.visibility = if (model == null) View.GONE else View.VISIBLE
        voiceListenButton.setText(if (state == ListenState.OFF) R.string.voice_listen_on else R.string.voice_listen_off)
        voiceSystemStt.visibility = if (sttSettings.allowSystemRecognizer) View.VISIBLE else View.GONE
        when (messages.status) {
            MessageAccess.Status.UNSUPPORTED -> voiceMessages.visibility = View.GONE
            MessageAccess.Status.NEEDS_ACCESS -> {
                voiceMessages.visibility = View.VISIBLE
                voiceMessages.setText(R.string.voice_messages_needs_access)
            }
            MessageAccess.Status.READY, MessageAccess.Status.NOT_CONNECTED -> {
                voiceMessages.visibility = View.VISIBLE
                voiceMessages.setText(R.string.voice_messages_ready)
            }
        }
        val driving = restrictions.isDrivingRestricted
        voiceSetupButton.isEnabled = !driving
        voiceDeleteButton.isEnabled = !driving
    }

    // endregion

    // region 주행 제한

    private fun applyRestrictions(drivingRestricted: Boolean) {
        drivingBanner.visibility = if (drivingRestricted) View.VISIBLE else View.GONE
        addButton.isEnabled = !drivingRestricted
        findViewById<Button>(R.id.emptyAddButton).isEnabled = !drivingRestricted
        adapter.setEditingEnabled(!drivingRestricted)
        if (drivingRestricted) openDialog?.takeIf { it.isShowing }?.dismiss()
        // 호출어 듣기와 음성비서는 주행 중에도 허용하고, 등록·삭제만 막는다
        renderVoice()
    }

    /** 주행 중에는 앱 실행만 허용하고 등록·편집은 막는다. */
    private fun blockIfDriving(): Boolean {
        if (!restrictions.isDrivingRestricted) return false
        showStatus(getString(R.string.edit_blocked_driving))
        return true
    }

    // endregion

    private fun showStatus(message: String) {
        statusText.text = message
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val VOICE_PREFS = "wake_word"
        const val KEY_SETUP_OFFERED = "setup_offered"
        const val KEY_LISTEN_ENABLED = "listen_enabled"
        const val REQUEST_MIC = 20
    }
}
