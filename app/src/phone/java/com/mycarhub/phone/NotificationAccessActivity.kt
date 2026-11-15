package com.mycarhub.phone

import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.mycarhub.R

/**
 * 알림 접근 설정으로 보내고, 돌아오면 실제로 허용되었는지 다시 확인한다 (폰 테스트용 빌드 전용, 화면 없음).
 *
 * - 1순위: MyCarHub 서비스의 상세 설정 화면 (ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS, Android 11+).
 *   갤럭시 One UI(Android 13 확인)는 이 요청을 받아도 전체 목록 화면을 보여준다.
 * - 처리할 화면이 없으면 전체 목록(ACTION_NOTIFICATION_LISTENER_SETTINGS), 그것도 없으면 앱 정보.
 * - 허용되지 않은 채 돌아오면 목록에서 찾는 방법과 '제한된 설정 허용' 방법을 안내한다.
 *   권한은 사용자가 설정 화면에서 직접 켜야 하며, 자동 클릭 등으로 대신 켜지 않는다.
 */
class NotificationAccessActivity : Activity() {

    private val listener by lazy { ComponentName(this, MessageListenerService::class.java) }
    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 화면 회전 등으로 다시 만들어진 경우에는 설정 화면을 또 열지 않고 돌아온 것으로 본다
        if (savedInstanceState == null) openAccessSettings()
    }

    override fun onRestart() {
        super.onRestart()
        checkResult()
    }

    override fun onDestroy() {
        dialog?.dismiss()
        super.onDestroy()
    }

    private val granted: Boolean
        get() = getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(listener)

    private fun openAccessSettings() {
        val opened = (settingsIntents(this) + appDetailsIntent()).firstOrNull(::tryStart)
        Log.i(TAG, "notification access settings: ${opened?.action}")
        if (opened == null) {
            Toast.makeText(this, R.string.notification_access_no_settings, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun checkResult() {
        dialog?.dismiss()
        if (granted) {
            Log.i(TAG, "notification access granted")
            Toast.makeText(this, R.string.notification_access_granted, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        Log.i(TAG, "notification access still not granted")
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.notification_access_not_yet_title)
            .setMessage(getString(R.string.notification_access_not_yet_message, getString(R.string.app_name)))
            .setPositiveButton(R.string.notification_access_retry) { _, _ -> openAccessSettings() }
            .setNeutralButton(R.string.notification_access_app_info) { _, _ ->
                if (!tryStart(appDetailsIntent())) finish()
            }
            .setNegativeButton(R.string.close) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun appDetailsIntent() =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))

    private fun tryStart(intent: Intent): Boolean =
        try {
            startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }

    companion object {
        private const val TAG = "PhoneTest"
        private const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
        private const val EXTRA_SHOW_FRAGMENT_ARGUMENTS = ":settings:show_fragment_args"

        /** 열어 볼 알림 접근 설정 화면 순서: 상세 설정(Android 11+) → 전체 목록 (기기에 없으면 다음 화면으로). */
        fun settingsIntents(context: Context): List<Intent> {
            val listener = ComponentName(context, MessageListenerService::class.java)
            val detail = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, listener.flattenToString())
                    .highlight(listener)
            } else {
                null
            }
            return listOfNotNull(detail, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).highlight(listener))
        }

        /**
         * 목록 화면이 열리면 MyCarHub 항목으로 스크롤해 강조하도록 요청한다.
         * AOSP Settings(HighlightablePreferenceGroupAdapter)는 fragment 인자 번들의 이 키를 읽고,
         * 알림 접근 목록은 서비스 ComponentName 문자열을 항목 키로 쓴다. 공개 API가 아닌 Settings 내부 키라
         * 지원하지 않는 기기는 그냥 무시한다 (권한·동작에는 영향 없음).
         */
        private fun Intent.highlight(listener: ComponentName): Intent {
            val key = listener.flattenToString()
            return putExtra(EXTRA_FRAGMENT_ARG_KEY, key)
                .putExtra(EXTRA_SHOW_FRAGMENT_ARGUMENTS, Bundle().apply { putString(EXTRA_FRAGMENT_ARG_KEY, key) })
        }
    }
}
