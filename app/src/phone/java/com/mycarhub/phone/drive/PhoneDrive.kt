package com.mycarhub.phone.drive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.mycarhub.R
import com.mycarhub.drive.CarItemStore
import com.mycarhub.drive.ParkingRecorder
import com.mycarhub.drive.ParkingSpot
import com.mycarhub.drive.ParkingStore
import com.mycarhub.drive.SavedPlaces
import com.mycarhub.drive.TodoItem
import com.mycarhub.drive.TodoStore
import com.mycarhub.phone.PermissionGate

/** 폰 Drive To-Do 설정 (기기 안에만). */
class PhoneDriveSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 주차 감지에 쓸 차량 블루투스 기기 (사용자가 직접 고름). 없으면 블루투스 주차 감지 꺼짐. */
    var carAddress: String?
        get() = prefs.getString(KEY_CAR_ADDRESS, null)
        set(value) = prefs.edit().putString(KEY_CAR_ADDRESS, value).apply()

    var carName: String?
        get() = prefs.getString(KEY_CAR_NAME, null)
        set(value) = prefs.edit().putString(KEY_CAR_NAME, value).apply()

    /** 문자·카카오톡 알림에서 할 일 후보 만들기. 사용자가 동의해 켜야 한다 (기본 꺼짐). */
    var analyzeNotifications: Boolean
        get() = prefs.getBoolean(KEY_ANALYZE, false)
        set(value) = prefs.edit().putBoolean(KEY_ANALYZE, value).apply()

    /** 차량 블루투스가 연결된 시각 (운전 시작). 연결이 끊기면 지운다. */
    var driveStartedAt: Long
        get() = prefs.getLong(KEY_DRIVE_START, 0L)
        set(value) = prefs.edit().putLong(KEY_DRIVE_START, value).apply()

    /** 마지막 자동 저장(블루투스 끊김)이 실패한 시각. 성공하면 0. 화면에 '자동 저장 실패'를 표시하는 데 쓴다. */
    var lastAutoFailAt: Long
        get() = prefs.getLong(KEY_AUTO_FAIL, 0L)
        set(value) = prefs.edit().putLong(KEY_AUTO_FAIL, value).apply()

    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val PREFS = "phone_drive"
        const val KEY_CAR_ADDRESS = "car_bt_address"
        const val KEY_CAR_NAME = "car_bt_name"
        const val KEY_ANALYZE = "analyze_notifications"
        const val KEY_DRIVE_START = "drive_started_at"
        const val KEY_AUTO_FAIL = "auto_fail_at"
    }
}

/**
 * 휴대폰에서 주차 감지 (차량 신호를 받을 수 없는 휴대폰용 대체 방법): 사용자가 고른 차량 블루투스가
 * 연결되면 운전 시작, 끊기면 그때의 휴대폰 위치를 주차 위치로 저장하고 요약 알림을 띄운다 ([PhoneParking]).
 *
 * 한계: 시동을 끈 뒤 블루투스가 끊기는 시점의 '휴대폰' 위치라 차에서 멀어진 뒤면 오차가 생기고, 지하 주차장에서는 위치를 못 얻을 수 있다.
 * 앱이 꺼져 있을 때 위치를 얻으려면 위치 '항상 허용'이 필요하다 (없으면 저장하지 않고 알림으로 알린다).
 */
class CarBluetoothReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val settings = PhoneDriveSettings(context)
        val car = settings.carAddress ?: return
        val device = deviceOf(intent) ?: return
        if (device.address != car) return
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> PhoneParking.onCarConnected(context)
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> PhoneParking.onCarDisconnected(context, this)
        }
    }

    @Suppress("DEPRECATION")
    private fun deviceOf(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    private companion object {
        const val TAG = "CarBluetooth"
    }
}

/**
 * 휴대폰 자동 주차 저장 흐름 (차량 블루투스 연결·끊김). 개발 빌드 ADB 테스트도 같은 함수를 부른다.
 *
 * 끊기면 위치 포그라운드 서비스([PhoneParkingService])로 위치를 얻는다: 앱이 백그라운드여도 위치 제한·프로세스 종료 없이
 * GPS를 기다릴 수 있다. 블루투스 방송(근처 기기 권한 필요)을 받은 직후라 Android 12+에서도 시작이 허용되는 경우지만,
 * 시스템이 거부하면 수신기 안에서 짧게([RECEIVER_TIMEOUT_MS]) 시도한다.
 */
object PhoneParking {

    private const val TAG = "PhoneParking"

    /** 서비스 안에서 위치를 기다리는 최대 시간 (지하·실내에서 GPS가 늦게 잡히는 경우) */
    const val SERVICE_TIMEOUT_MS = 30_000L

    /** 서비스를 못 쓸 때 수신기 안에서 기다리는 최대 시간 (방송 처리 제한 안) */
    private const val RECEIVER_TIMEOUT_MS = 8_000L

    /** 블루투스 자동 저장 직후 이 시간 안에 다시 연결되면 주차가 아니었던 것으로 보고 되돌린다 */
    private const val FLAP_WINDOW_MS = 3 * 60 * 1000L

    fun onCarConnected(context: Context) {
        val settings = PhoneDriveSettings(context)
        settings.driveStartedAt = System.currentTimeMillis()
        val spot = ParkingStore(context).load()
        if (spot != null && spot.source == ParkingSpot.Source.PHONE_BLUETOOTH &&
            System.currentTimeMillis() - spot.savedAt in 0..FLAP_WINDOW_MS
        ) {
            // 신호 대기·잠깐 끊김: 방금 저장한 위치는 주차가 아니었다 (기록 안내 알림도 거둔다)
            if (ParkingStore(context).revert(spot.savedAt)) {
                Log.i(TAG, "car reconnected soon: last auto save reverted")
                ParkingPrompt.withdraw(context, spot.id)
            }
        }
        Log.i(TAG, "car connected: drive started")
    }

    /** [receiver]가 있으면 서비스를 못 쓸 때 그 수신기를 잠시 붙잡아 둔다 (goAsync). */
    fun onCarDisconnected(context: Context, receiver: BroadcastReceiver?) {
        val settings = PhoneDriveSettings(context)
        val started = settings.driveStartedAt
        settings.driveStartedAt = 0L
        Log.i(TAG, "car disconnected: recording parking")
        // 위치 권한이 없어도 주차 시각만 있는 기록을 남겨 구역 메모·사진을 붙일 수 있게 한다
        if (!ParkingRecorder.hasLocationPermission(context)) {
            return finish(context, ParkingRecorder.saveWithoutLocation(context, ParkingSpot.Source.PHONE_BLUETOOTH), started)
        }
        if (PhoneParkingService.start(context, started)) return
        val pending = receiver?.goAsync()
        capture(context, started, RECEIVER_TIMEOUT_MS) { pending?.finish() }
    }

    /** 위치를 얻어 저장하고 결과를 기록·알린다. 위치를 못 얻으면 위치 없는 주차 기록을 남긴다. [onDone]은 메인 스레드. */
    fun capture(context: Context, started: Long, timeoutMs: Long, onDone: () -> Unit) {
        ParkingRecorder.record(context, ParkingSpot.Source.PHONE_BLUETOOTH, timeoutMs, keepWithoutLocation = true) { spot ->
            try {
                finish(context, spot, started)
            } finally {
                onDone()
            }
        }
    }

    private fun finish(context: Context, spot: ParkingSpot?, started: Long) {
        PhoneDriveSettings(context).lastAutoFailAt = if (spot == null) System.currentTimeMillis() else 0L
        Log.i(TAG, "auto parking ${if (spot == null) "failed" else if (spot.hasLocation) "saved" else "saved without location"}")
        // '주차 위치를 기록하시겠습니까?': 같은 주차에서 한 번만 (앱 화면이 보이면 팝업, 아니면 요약 알림에 함께)
        val prompt = spot != null && ParkingPrompt.claim(context, spot)
        if (prompt && ParkingPrompt.showInApp(spot!!)) return DriveSummary.notify(context, spot, started, prompt = false, quietIfNothing = true)
        DriveSummary.notify(context, spot, started, prompt)
    }
}

/** 차량 블루투스가 끊긴 뒤 주차 위치를 한 번 얻고 끝나는 짧은 포그라운드 서비스 (type=location, 최대 [PhoneParking.SERVICE_TIMEOUT_MS]). */
class PhoneParkingService : Service() {

    private var busy = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForegroundWithNotice()
        } catch (e: RuntimeException) {
            // 포그라운드로 올리지 못해도 위치는 시도한다 (프로세스가 살아 있는 동안)
            Log.w(TAG, "foreground not allowed: ${e.javaClass.simpleName}")
        }
        if (busy) return START_NOT_STICKY
        busy = true
        val started = intent?.getLongExtra(EXTRA_STARTED, 0L) ?: 0L
        PhoneParking.capture(this, started, PhoneParking.SERVICE_TIMEOUT_MS) {
            busy = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundWithNotice() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.drive_capture_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val notice = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.drive_capture_title))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTICE_ID, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTICE_ID, notice)
        }
    }

    companion object {
        private const val TAG = "PhoneParking"
        private const val CHANNEL = "drive_capture"
        private const val NOTICE_ID = 73
        private const val EXTRA_STARTED = "drive_started_at"

        /** 시작했으면 true. 시스템이 백그라운드 시작을 막으면 false. */
        fun start(context: Context, driveStartedAt: Long): Boolean = try {
            context.startForegroundService(Intent(context, PhoneParkingService::class.java).putExtra(EXTRA_STARTED, driveStartedAt))
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "service start not allowed: ${e.javaClass.simpleName}")
            false
        }
    }
}
/**
 * 주차 후 요약 알림: 주차 위치 저장 결과 + "운전 중 놓친 일 N개" + 주차한 곳이 지정 장소 근처면 그 장소의 할 일.
 * 저장에 실패했거나 오차가 크면 [지금 위치로 저장] 버튼을 붙인다 (아직 차 근처일 때 직접 저장).
 *
 * [prompt]이면 같은 알림이 '주차 위치를 기록하시겠습니까?' 안내가 된다 (주차 한 번에 알림 하나):
 * 제목이 안내 문구, 버튼은 [직접 입력] [사진 촬영] [건너뛰기], 알림을 누르면 기록 화면, 밀어서 지우면 '나중에'.
 * 잠금 화면에는 위치·할 일 내용 없이 안내 문구만 보인다.
 * 알림 권한이 없으면 띄우지 않는다 (메인 화면의 주차 카드에서 같은 안내를 볼 수 있다).
 */
object DriveSummary {

    private const val CHANNEL = "drive_summary"
    const val NOTICE_ID = 72

    /** 지정 장소에 '도착'으로 보는 거리 */
    const val PLACE_RADIUS_M = 300f

    /** [quietIfNothing]: 알릴 할 일·물건이 없으면 띄우지 않는다 (기록 안내를 앱 화면 팝업으로 이미 보여 준 경우) */
    fun notify(context: Context, spot: ParkingSpot?, driveStartedAt: Long, prompt: Boolean = false, quietIfNothing: Boolean = false) {
        if (!PermissionGate.isGranted(context, PermissionGate.Kind.NOTIFICATIONS)) return
        val store = TodoStore(context)
        val since = driveStartedAt.takeIf { it > 0 } ?: (System.currentTimeMillis() - FALLBACK_WINDOW_MS)
        val missed = store.openTodos().count { it.createdAt >= since } + store.candidates().count { it.createdAt >= since }
        val nearby = spot?.let { placeTodos(context, it) }.orEmpty()
        val inaccurate = spot?.accuracyM?.let { it > ParkingRecorder.LOW_ACCURACY_M } == true
        // 챙기기로 한 물건 (사용자가 말한 기록, 알림을 끈 물건은 제외)
        val pickups = CarItemStore(context).toRemind()
        if (quietIfNothing && missed == 0 && nearby.isEmpty() && pickups.isEmpty()) return

        val lines = mutableListOf<String>()
        lines += when {
            spot == null && !ParkingRecorder.hasLocationPermission(context) -> context.getString(R.string.drive_summary_parking_no_permission)
            spot == null && !PermissionGate.isGranted(context, PermissionGate.Kind.BACKGROUND_LOCATION) ->
                context.getString(R.string.drive_summary_parking_need_always)
            spot == null -> context.getString(R.string.drive_summary_parking_failed)
            !spot.hasLocation -> context.getString(R.string.parking_prompt_no_location)
            inaccurate -> context.getString(R.string.drive_summary_parking_inaccurate, spot.accuracyM!!.toInt())
            else -> context.getString(R.string.drive_summary_parking_saved)
        }
        if (missed > 0 && prompt) lines += context.getString(R.string.drive_summary_missed, missed)
        if (nearby.isNotEmpty()) lines += context.getString(R.string.drive_summary_place, nearby.first().placeName, nearby.joinToString(", ") { it.title })
        if (pickups.isNotEmpty()) lines += context.getString(R.string.drive_summary_pickup, pickups.joinToString(", ") { it.name })
        val title = when {
            prompt -> context.getString(R.string.parking_prompt_title)
            missed > 0 -> context.getString(R.string.drive_summary_missed, missed)
            else -> context.getString(R.string.drive_summary_title)
        }

        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = if (prompt) ParkingPrompt.channel(context) else CHANNEL.also {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.drive_summary_channel), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val open = if (prompt && spot != null) {
            ParkingPrompt.recordPendingIntent(context, spot.id, ParkingRecordActivity.Mode.VIEW)
        } else {
            PendingIntent.getActivity(
                context, 0, Intent(context, DriveTodoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        val builder = Notification.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(title)
            .setContentText(lines.first())
            .setStyle(Notification.BigTextStyle().bigText(lines.joinToString("\n")))
            .setContentIntent(open)
            .setAutoCancel(true)
        if (prompt && spot != null) {
            ParkingPrompt.addActions(context, builder, spot.id)
            // 잠금 화면: 위치·할 일 내용 없이 안내만
            builder.setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(
                    Notification.Builder(context, channel)
                        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                        .setContentTitle(title)
                        .setContentText(context.getString(R.string.parking_prompt_public))
                        .build(),
                )
        } else if (spot == null || inaccurate) {
            val saveHere = PendingIntent.getActivity(
                context, 1, DriveTodoActivity.saveHereIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(Notification.Action.Builder(null, context.getString(R.string.drive_summary_save_here), saveHere).build())
        }
        val notice = builder.build()
        try {
            manager.notify(NOTICE_ID, notice)
        } catch (e: SecurityException) {
            Log.w("DriveSummary", "notification not allowed")
        }
    }

    /** 주차 위치가 지정 장소 반경 안이면 그 장소 조건의 할 일 (위치가 없는 기록이면 없음) */
    fun placeTodos(context: Context, spot: ParkingSpot): List<TodoItem> {
        val lat = spot.latitude ?: return emptyList()
        val lng = spot.longitude ?: return emptyList()
        val places = SavedPlaces(context)
        return TodoStore(context).openTodos().filter { todo ->
            if (todo.placeKind == null && todo.placeName == null) return@filter false
            val place = places.find(todo.placeKind, todo.placeName) ?: return@filter false
            ParkingRecorder.distanceM(lat, lng, place.latitude, place.longitude) <= PLACE_RADIUS_M
        }
    }

    /** 운전 시작 시각을 모를 때 (블루투스 연결 신호를 놓침): 최근 2시간 */
    private const val FALLBACK_WINDOW_MS = 2 * 60 * 60 * 1000L
}
