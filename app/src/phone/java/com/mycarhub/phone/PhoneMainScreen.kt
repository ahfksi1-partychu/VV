package com.mycarhub.phone

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.mycarhub.MainActivity
import com.mycarhub.drive.DriveAssistant
import com.mycarhub.drive.DriveSyncManager
import com.mycarhub.drive.DriveSyncState
import com.mycarhub.drive.ParkingPhotos
import com.mycarhub.drive.ParkingStore
import com.mycarhub.phone.drive.ParkingPrompt
import com.mycarhub.phone.drive.ParkingRecordActivity
import com.mycarhub.phone.drive.ParkingRecordText
import java.util.concurrent.Executors
import com.mycarhub.MyCarHubApplication
import com.mycarhub.R
import com.mycarhub.assistant.messages.MessageAccess
import com.mycarhub.phone.floating.FloatingVoiceService
import com.mycarhub.phone.floating.FloatingVoiceService.Status
import com.mycarhub.phone.floating.FloatingVoiceSettings
import com.mycarhub.phone.floating.FloatingVoiceSetupActivity
import com.mycarhub.phone.conversation.PhoneAssistantActivity
import com.mycarhub.phone.drive.DriveTodoActivity
import com.mycarhub.ui.WakeWordSetupActivity
import com.mycarhub.wakeword.WakeWordManager
import com.mycarhub.wakeword.core.WakeWordModel

/**
 * 폰 빌드의 음성비서 메인 화면. MainActivity(main 코드)는 바꾸지 않고 폰 레이아웃에만 있는 뷰를 채운다.
 *
 * 호출어 감지는 상시 듣기 서비스([FloatingVoiceService])가 맡는다. 이 화면은 호출어 듣기가 켜져 있으면 서비스를 시작하고
 * (마이크 포그라운드 서비스는 앱 화면이 보일 때만 시작할 수 있다) 서비스의 실제 듣기 상태를 보여준다.
 * '듣는 중'은 감지 스레드가 실제로 마이크를 연 뒤에만 표시된다. 화면을 떠나도 서비스는 계속 듣는다.
 */
class PhoneMainScreen : Application.ActivityLifecycleCallbacks {

    private var shown: MainActivity? = null
    private var startFailed = false

    // 이번 앱 실행에서 설정 안내를 이미 띄웠는지
    private var guided = false

    private val statusListener: (Status) -> Unit = { render() }

    private val modelListener = object : WakeWordManager.Listener {
        override fun onModelChanged(model: WakeWordModel?) {
            shown?.let(::startIfEnabled)
            render()
        }
    }

    /** setContentView 이후(onStart)에 버튼을 한 번만 연결한다. */
    override fun onActivityStarted(activity: Activity) {
        if (activity !is MainActivity) return
        val settings = activity.findViewById<View>(R.id.phoneSettingsButton) ?: return
        if (settings.hasOnClickListeners()) return
        settings.setOnClickListener { activity.startActivity(Intent(activity, PhoneSettingsActivity::class.java)) }
        activity.findViewById<View>(R.id.phoneRegisterButton).setOnClickListener {
            // 듣기를 켜 둔 상태면 등록에 이어 권한 확인·듣기 시작까지 순서대로 진행한다
            if (FloatingVoiceSettings(activity).enabled) {
                activity.startActivity(FloatingVoiceSetupActivity.intent(activity, start = true))
            } else {
                activity.startActivity(Intent(activity, WakeWordSetupActivity::class.java))
            }
        }
        activity.findViewById<View>(R.id.phoneEnableButton).setOnClickListener { enable(activity) }
        activity.findViewById<View>(R.id.phoneDriveTodoButton).setOnClickListener {
            activity.startActivity(DriveTodoActivity.intent(activity))
        }
        // [직접 음성비서 실행]: 호출어 없이도 같은 음성 대화를 시험할 수 있다 (MainActivity의 기존 연결을 덮어씀)
        activity.findViewById<View>(R.id.voiceCommandButton).setOnClickListener {
            activity.startActivity(PhoneAssistantActivity.intent(activity, wakeWords(activity).model?.name))
        }
        bindParkingCard(activity)
        activity.findViewById<View>(R.id.phoneBackgroundHint).setOnClickListener {
            // '다른 앱 위에 표시'가 없으면 바로 순서대로 설정을 시작한다 (허용 화면으로 이어짐)
            val start = !FloatingVoiceSettings(activity).canDrawOverlays
            activity.startActivity(FloatingVoiceSetupActivity.intent(activity, start = start))
        }
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is MainActivity) return
        shown = activity
        wakeWords(activity).addListener(modelListener)
        FloatingVoiceService.addStatusListener(statusListener)
        ParkingStore.addListener(parkingListener)
        DriveSyncManager.addListener(parkingListener)
        // 차량에서 감지한 주차가 있으면 바로 받아 온다 (페어링한 경우만)
        if (DriveSyncState(activity).paired) DriveSyncManager.requestSync(activity, "main", 0L)
        activity.findViewById<View>(R.id.phoneFavoritesSection)?.visibility =
            if (showFavorites(activity)) View.VISIBLE else View.GONE
        // 켜 두었는데 앱 종료·재부팅·시스템 종료로 꺼진 경우, 화면이 보이는 지금 다시 시작한다 (백그라운드 시작은 정책상 불가)
        startIfEnabled(activity)
        render()
        guideSetupOnce(activity)
    }

    /**
     * 상시 듣기는 기본으로 켜져 있다. 처음 설치해 필요한 것이 없으면 앱을 연 뒤 딱 한 번 순서대로 안내한다:
     * 호출어 등록(없으면 등록 화면) → 마이크 권한 → (선택) 알림 → 다른 앱 위에 표시 → 허용되면 바로 듣기 시작.
     * 그 뒤로는 자동으로 다시 묻지 않고, 메인 화면의 [상시 듣기 시작]·[호출어 등록하기] 버튼으로 진행한다.
     * 사용자가 듣기를 직접 껐으면 띄우지 않는다. 이미 모두 갖춰져 있으면 (기존 사용자) 띄우지 않고 완료로 기록한다.
     */
    private fun guideSetupOnce(activity: MainActivity) {
        if (PhoneVoice.setupDone(activity)) return
        val settings = FloatingVoiceSettings(activity)
        if (settings.enabled && settings.isReady && settings.canDrawOverlays) return PhoneVoice.setSetupDone(activity)
        if (!settings.enabled || guided || PhoneVoice.setupPostponed(activity) || PhoneVoice.autoGuided(activity)) return
        guided = true
        PhoneVoice.setAutoGuided(activity)
        activity.startActivity(FloatingVoiceSetupActivity.intent(activity, start = true, firstRun = true))
    }

    override fun onActivityPaused(activity: Activity) {
        if (activity !== shown) return
        wakeWords(activity).removeListener(modelListener)
        FloatingVoiceService.removeStatusListener(statusListener)
        ParkingStore.removeListener(parkingListener)
        DriveSyncManager.removeListener(parkingListener)
        shown = null
    }

    // region 가장 최근 주차 기록 카드

    private val parkingListener: () -> Unit = { shown?.let { a -> a.runOnUiThread { renderParking(a) } } }
    private val photoLoader = Executors.newSingleThreadExecutor()
    private var cardPhoto: String? = null

    private fun bindParkingCard(activity: MainActivity) {
        fun open(mode: ParkingRecordActivity.Mode) {
            val spot = ParkingStore(activity).load() ?: return
            activity.startActivity(ParkingRecordActivity.intent(activity, spot.id, mode))
        }
        activity.findViewById<View>(R.id.phoneParkingCard).setOnClickListener { open(ParkingRecordActivity.Mode.VIEW) }
        activity.findViewById<View>(R.id.phoneParkingEdit).setOnClickListener { open(ParkingRecordActivity.Mode.TEXT) }
        activity.findViewById<View>(R.id.phoneParkingPhotoButton).setOnClickListener { open(ParkingRecordActivity.Mode.PHOTO) }
        activity.findViewById<View>(R.id.phoneParkingPromptText).setOnClickListener { open(ParkingRecordActivity.Mode.TEXT) }
        activity.findViewById<View>(R.id.phoneParkingPromptPhoto).setOnClickListener { open(ParkingRecordActivity.Mode.PHOTO) }
        activity.findViewById<View>(R.id.phoneParkingPromptSkip).setOnClickListener {
            ParkingPrompt.setState(activity, ParkingPrompt.State.SKIPPED)
            renderParking(activity)
        }
        activity.findViewById<View>(R.id.phoneParkingPhoto).setOnClickListener { open(ParkingRecordActivity.Mode.VIEW) }
        activity.findViewById<View>(R.id.phoneParkingMap).setOnClickListener {
            val spot = ParkingStore(activity).load() ?: return@setOnClickListener
            val map = DriveAssistant.mapIntent(spot, activity.getString(R.string.drive_parking_label))
            val target = if (map != null && map.resolveActivity(activity.packageManager) != null) map else DriveAssistant.webMapIntent(spot)
            try {
                target?.let(activity::startActivity)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(activity, R.string.drive_no_map_app, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 주차 기록이 있으면 카드: 구역 메모(없으면 주소), 주차 일시, 사진, 동기화 상태, 지도(위치 있을 때만), 수정·사진 변경 */
    private fun renderParking(activity: MainActivity) {
        val card = activity.findViewById<View>(R.id.phoneParkingCard) ?: return
        val spot = ParkingStore(activity).load()
        if (spot == null) {
            card.visibility = View.GONE
            return
        }
        card.visibility = View.VISIBLE
        activity.findViewById<TextView>(R.id.phoneParkingWhere).text = ParkingRecordText.where(activity, spot)
        activity.findViewById<TextView>(R.id.phoneParkingPlace).apply {
            visibility = visibleIf(spot.note != null || !spot.hasLocation)
            text = if (spot.hasLocation) ParkingRecordText.place(activity, spot) else activity.getString(R.string.parking_record_no_location_hint)
        }
        activity.findViewById<TextView>(R.id.phoneParkingTime).text = ParkingPrompt.whenAndHow(activity, spot)
        activity.findViewById<TextView>(R.id.phoneParkingSync).text = ParkingRecordText.syncStatus(activity, spot)
        activity.findViewById<View>(R.id.phoneParkingMap).visibility = visibleIf(spot.hasLocation)
        activity.findViewById<TextView>(R.id.phoneParkingPhotoButton)
            .setText(if (spot.photo != null) R.string.parking_card_photo_change else R.string.parking_card_photo_add)
        val asking = ParkingPrompt.isOpenFor(activity, spot)
        activity.findViewById<View>(R.id.phoneParkingPrompt).visibility = visibleIf(asking)
        activity.findViewById<View>(R.id.phoneParkingActions).visibility = visibleIf(!asking)

        val photoView = activity.findViewById<ImageView>(R.id.phoneParkingPhoto)
        val name = spot.photo
        if (name == null) {
            cardPhoto = null
            photoView.setImageDrawable(null)
            photoView.visibility = View.GONE
            return
        }
        if (name == cardPhoto && photoView.drawable != null) return
        cardPhoto = name
        val side = (THUMB_DP * activity.resources.displayMetrics.density).toInt()
        photoLoader.execute {
            val bitmap = ParkingPhotos.load(activity, name, side * 2)
            activity.runOnUiThread {
                if (cardPhoto != name) return@runOnUiThread
                photoView.setImageBitmap(bitmap)
                photoView.visibility = visibleIf(bitmap != null)
            }
        }
    }

    // endregion

    private fun startIfEnabled(activity: MainActivity) {
        val settings = FloatingVoiceSettings(activity)
        startFailed = false
        if (!settings.enabled || !settings.isReady || FloatingVoiceService.isRunning) return
        startFailed = !FloatingVoiceService.start(activity)
    }

    /** 메인 화면의 [호출어 듣기 켜기]: 필요한 권한이 다 있으면 바로 켜고, 없으면 순서대로 권한을 요청한 뒤 켠다. */
    private fun enable(activity: MainActivity) {
        val settings = FloatingVoiceSettings(activity)
        if (!settings.hasMicPermission || !settings.canDrawOverlays) {
            activity.startActivity(FloatingVoiceSetupActivity.intent(activity, start = true))
            return
        }
        PhoneVoice.setListenEnabled(activity, true)
        // ✕로 끈 뒤 다시 켜는 경우: 플로팅 버튼도 함께 다시 보인다
        PhoneVoice.setShowBubble(activity, true)
        // 선택: 알림창의 [듣기 중지] 버튼을 보이려면 알림 권한이 필요하다 (거부해도 듣기는 동작). 사용자가 켤 때 한 번 묻는다.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFY)
        }
        startIfEnabled(activity)
        render()
    }

    private fun render() {
        val activity = shown ?: return
        renderParking(activity)
        val model = wakeWords(activity).model
        val name = activity.findViewById<TextView>(R.id.phoneWakeName) ?: return
        name.text = model?.name ?: activity.getString(R.string.phone_wake_none)
        name.alpha = if (model == null) DIM else 1f

        val settings = FloatingVoiceSettings(activity)
        val status = FloatingVoiceService.status
        val needsEnable = model != null && (!settings.enabled || !settings.hasMicPermission)
        val listening = settings.enabled && status == Status.LISTENING
        val text = when {
            model == null -> wakeWords(activity).retiredName?.let { activity.getString(R.string.phone_state_retired, it) }
                ?: activity.getString(R.string.phone_state_none)
            !settings.enabled -> activity.getString(R.string.phone_state_off)
            !settings.hasMicPermission -> activity.getString(R.string.phone_state_no_permission)
            startFailed -> activity.getString(R.string.phone_state_start_failed)
            else -> when (status) {
                Status.LISTENING -> activity.getString(R.string.phone_state_listening, model.name)
                Status.SILENCED -> activity.getString(R.string.phone_state_silenced)
                Status.MIC_RETRY -> activity.getString(R.string.phone_state_mic_unavailable)
                Status.ASSISTANT -> activity.getString(R.string.phone_state_detected, model.name)
                Status.IN_CALL -> activity.getString(R.string.floating_status_in_call)
                else -> activity.getString(R.string.phone_state_starting)
            }
        }
        activity.findViewById<TextView>(R.id.phoneWakeState).apply {
            this.text = text
            setTextColor(activity.getColor(if (listening) R.color.phone_listening else R.color.phone_text_secondary))
        }
        activity.findViewById<View>(R.id.phoneMicCircle)
            .setBackgroundResource(if (listening) R.drawable.phone_mic_circle_listening else R.drawable.phone_mic_circle)
        activity.findViewById<View>(R.id.phoneRegisterButton).visibility = visibleIf(model == null)
        activity.findViewById<View>(R.id.phoneEnableButton).visibility = visibleIf(needsEnable)

        // 다른 앱을 쓰는 중에도 부르려면 '다른 앱 위에 표시'가 필요하다: 없으면 무엇이 필요한지 알려준다
        val running = settings.enabled && FloatingVoiceService.isRunning
        activity.findViewById<TextView>(R.id.phoneBackgroundHint).apply {
            visibility = visibleIf(model != null && settings.enabled)
            setText(
                when {
                    !settings.canDrawOverlays -> R.string.phone_background_needs_overlay
                    running -> R.string.phone_background_on
                    else -> R.string.phone_background_hint
                },
            )
        }
        val messages = (activity.application as MyCarHubApplication).messages.status
        activity.findViewById<TextView>(R.id.phoneExampleMessages).apply {
            visibility = visibleIf(messages != MessageAccess.Status.UNSUPPORTED)
            setText(
                if (messages == MessageAccess.Status.NEEDS_ACCESS) R.string.phone_example_messages_needs_access
                else R.string.phone_example_messages,
            )
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}

    companion object {
        private const val PREFS = "phone_main"
        private const val KEY_SHOW_FAVORITES = "show_favorites"
        private const val DIM = 0.6f
        private const val REQUEST_NOTIFY = 61
        private const val THUMB_DP = 84

        private fun visibleIf(visible: Boolean) = if (visible) View.VISIBLE else View.GONE

        private fun wakeWords(activity: Activity) = (activity.application as MyCarHubApplication).wakeWords

        fun showFavorites(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SHOW_FAVORITES, false)

        fun setShowFavorites(context: Context, show: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SHOW_FAVORITES, show).apply()
        }
    }
}
