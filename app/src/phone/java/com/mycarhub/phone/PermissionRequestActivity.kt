package com.mycarhub.phone

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.mycarhub.MyCarHubApplication
import com.mycarhub.assistant.messages.MessageAccess

/**
 * 기능을 쓰는 중에 필요한 권한을 바로 요청하는 투명 화면 (폰 테스트용 빌드 전용, 화면 내용 없음).
 *
 * 음성 대화(앱 화면·다른 앱 위 창 모두)에서 [PermissionGate.request]로 연다. 런타임 권한은 Android 권한 창을,
 * 알림 접근·다른 앱 위에 표시는 해당 시스템 설정 화면을 열고, 돌아오면 실제로 허용되었는지 확인해 결과를 알린다.
 * 권한은 사용자가 직접 허용해야 하며 자동 클릭 등으로 대신 켜지 않는다.
 *
 * 자체 작업(taskAffinity 없음)으로 열려 끝나면 원래 쓰던 앱(카카오톡 등)으로 그대로 돌아간다.
 */
class PermissionRequestActivity : Activity() {

    private lateinit var kind: PermissionGate.Kind
    private var delivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        kind = PermissionGate.Kind.entries.getOrNull(intent.getIntExtra(EXTRA_KIND, -1)) ?: return finish()
        PermissionGate.attach(this)
        // 화면 회전 등으로 다시 만들어진 경우에는 다시 요청하지 않는다
        if (savedInstanceState != null) return
        val runtime = kind.runtimePermission
        if (runtime != null) {
            requestPermissions(arrayOf(runtime), REQUEST)
        } else if (!openSettings()) {
            deliver(false)
        }
    }

    override fun onRestart() {
        super.onRestart()
        // 설정 화면에서 돌아옴
        if (kind.runtimePermission == null) deliver(PermissionGate.isGranted(this, kind))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST) deliver(grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
    }

    override fun onDestroy() {
        PermissionGate.detach(this)
        // 결과 없이 닫힘 (뒤로 가기 등): 허용 여부를 그대로 알린다
        if (!delivered && ::kind.isInitialized) PermissionGate.complete(this, kind, PermissionGate.isGranted(this, kind))
        super.onDestroy()
    }

    private fun deliver(granted: Boolean) {
        if (delivered) return
        delivered = true
        PermissionGate.complete(this, kind, granted)
        finish()
    }

    private fun openSettings(): Boolean {
        val app = Uri.fromParts("package", packageName, null)
        val intents = when (kind) {
            PermissionGate.Kind.NOTIFICATION_ACCESS -> NotificationAccessActivity.settingsIntents(this)
            // 패키지를 지정하면 MyCarHub 항목으로 바로 간다
            PermissionGate.Kind.OVERLAY -> listOf(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, app),
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
            )
            else -> emptyList()
        } + Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app)
        for (intent in intents) {
            try {
                startActivity(intent)
                Log.i(TAG, "permission settings opened: kind=$kind action=${intent.action}")
                return true
            } catch (e: ActivityNotFoundException) {
                // 다음 후보로
            }
        }
        return false
    }

    companion object {
        private const val TAG = "PhoneTest"
        private const val EXTRA_KIND = "com.mycarhub.phone.extra.PERMISSION_KIND"
        private const val REQUEST = 71

        internal fun intent(context: Context, kind: PermissionGate.Kind): Intent =
            Intent(context, PermissionRequestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(EXTRA_KIND, kind.ordinal)
    }
}

/**
 * 기능 실행 중 권한 요청 (폰 빌드). 한 번에 하나만 요청한다.
 *
 * 사용자가 거부한 권한은 기억해 두고 음성 대화에서 다시 자동으로 묻지 않는다 (무한 반복 방지).
 * 사용자가 화면의 버튼·설정에서 직접 누르면 [request]의 force로 다시 열 수 있고, 허용되면 거부 기록을 지운다.
 * 메인 스레드에서 호출한다.
 */
object PermissionGate {

    enum class Kind(val runtimePermission: String?) {
        MICROPHONE(Manifest.permission.RECORD_AUDIO),
        CONTACTS(Manifest.permission.READ_CONTACTS),
        NOTIFICATION_ACCESS(null),
        OVERLAY(null),

        // Drive To-Do: 주차 위치(위치), 차량 블루투스 연결 끊김 감지(근처 기기), 백그라운드 위치(항상 허용), 요약 알림
        LOCATION(Manifest.permission.ACCESS_FINE_LOCATION),
        BACKGROUND_LOCATION(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
        BLUETOOTH(Manifest.permission.BLUETOOTH_CONNECT),
        NOTIFICATIONS(Manifest.permission.POST_NOTIFICATIONS),
    }

    private const val PREFS = "phone_permission_requests"
    private const val TAG = "PhoneTest"

    // 설정 화면에서 돌아오지 않을 때 (홈으로 나감 등) 대화를 무한히 기다리지 않는다
    private const val SETTINGS_TIMEOUT_MS = 120_000L

    private val main = Handler(Looper.getMainLooper())
    private var pending: ((Boolean) -> Unit)? = null
    private var screen: PermissionRequestActivity? = null
    private val timeout = Runnable {
        Log.i(TAG, "permission request timed out")
        val callback = pending
        pending = null
        screen?.finish()
        callback?.invoke(false)
    }

    /** 요청 화면이 열려 있는 동안 (호출어 감지를 쉰다) */
    val isRequesting: Boolean get() = pending != null

    fun isGranted(context: Context, kind: Kind): Boolean = when (kind) {
        Kind.NOTIFICATION_ACCESS -> (context.applicationContext as MyCarHubApplication).messages.status.let {
            it == MessageAccess.Status.READY || it == MessageAccess.Status.NOT_CONNECTED
        }
        Kind.OVERLAY -> Settings.canDrawOverlays(context)
        // 이 Android 버전에는 없는 권한 (설치 때 허용됨)
        Kind.BLUETOOTH -> Build.VERSION.SDK_INT < Build.VERSION_CODES.S || granted(context, kind)
        Kind.NOTIFICATIONS -> Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(context, kind)
        Kind.BACKGROUND_LOCATION -> Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(context, kind)
        else -> granted(context, kind)
    }

    private fun granted(context: Context, kind: Kind) =
        context.checkSelfPermission(kind.runtimePermission!!) == PackageManager.PERMISSION_GRANTED

    /** 이전에 거부했는지 (그렇다면 음성 대화에서 자동으로 다시 묻지 않는다) */
    fun wasDeclined(context: Context, kind: Kind): Boolean = prefs(context).getBoolean(kind.name, false)

    /**
     * 허용 화면을 띄우고 결과를 [onResult]로 알린다. 이미 허용되어 있으면 바로 true.
     * [force]가 false면 이전에 거부한 권한은 묻지 않고 바로 false (사용자가 직접 누른 경우에만 true로 부른다).
     */
    fun request(context: Context, kind: Kind, force: Boolean, onResult: (Boolean) -> Unit) {
        if (isGranted(context, kind)) return onResult(true)
        if (!force && wasDeclined(context, kind)) return onResult(false)
        // 앞의 요청이 남아 있으면 끝낸다
        pending?.let { previous ->
            pending = null
            previous(false)
        }
        pending = onResult
        main.removeCallbacks(timeout)
        if (kind.runtimePermission == null) main.postDelayed(timeout, SETTINGS_TIMEOUT_MS)
        try {
            context.startActivity(PermissionRequestActivity.intent(context, kind))
        } catch (e: RuntimeException) {
            Log.w(TAG, "permission screen unavailable: $kind", e)
            complete(context, kind, false)
        }
    }

    internal fun attach(activity: PermissionRequestActivity) {
        screen = activity
    }

    internal fun detach(activity: PermissionRequestActivity) {
        if (screen === activity) screen = null
    }

    internal fun complete(context: Context, kind: Kind, granted: Boolean) {
        main.removeCallbacks(timeout)
        prefs(context).edit().putBoolean(kind.name, !granted).apply()
        Log.i(TAG, "permission request result: kind=$kind granted=$granted")
        val callback = pending ?: return
        pending = null
        callback(granted)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
