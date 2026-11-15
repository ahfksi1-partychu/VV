package com.mycarhub.car

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.car.Car
import android.car.VehicleGear
import android.car.VehicleIgnitionState
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.mycarhub.R
import com.mycarhub.assistant.core.ParkingDecider
import com.mycarhub.drive.ParkingRecorder
import com.mycarhub.drive.ParkingSpot

/**
 * 차량용(car) 빌드: 차량 신호로 주차를 감지해 마지막 주차 위치를 저장하는 포그라운드 서비스 (위치 사용 표시).
 *
 * Android Automotive 표준 차량 속성(CarPropertyManager)을 쓴다: 기어(GEAR_SELECTION, 없으면 CURRENT_GEAR),
 * 시동 상태(IGNITION_STATE), 주차 브레이크(PARKING_BRAKE_ON). 모두 일반 권한 android.car.permission.CAR_POWERTRAIN.
 * 판단 규칙은 [ParkingDecider] (속도 0만으로는 저장하지 않음). Pleos Connect SDK는 쓰지 않는다 (프로젝트에 아직 없음).
 *
 * 위치 권한이 없거나 차량 속성을 읽을 수 없으면 시작하지 않거나 스스로 멈춘다. 위치·신호 값은 로그에 남기지 않는다.
 */
class CarParkingService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private val decider = ParkingDecider()
    private var car: Car? = null
    private var properties: CarPropertyManager? = null

    // 기어가 P로 바뀔 때 잡아 둔 위치 (확정 전까지 메모리에만)
    private var captured: Location? = null
    private var capturedAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            handle(decider.onTick(now()))
            main.postDelayed(this, TICK_MS)
        }
    }

    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            main.post { onProperty(value) }
        }

        override fun onErrorEvent(propertyId: Int, zone: Int) {
            Log.w(TAG, "property error id=$propertyId")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!ParkingRecorder.hasLocationPermission(this)) {
            Log.i(TAG, "no location permission: not monitoring")
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForegroundWithNotice()
        } catch (e: RuntimeException) {
            // 백그라운드(부팅 직후 등)에서 위치 서비스 시작이 허용되지 않음: 앱 화면이 보일 때 다시 시작한다
            Log.i(TAG, "foreground start not allowed: ${e.javaClass.simpleName}")
            stopSelf()
            return START_NOT_STICKY
        }
        connectCar()
        return START_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacks(tick)
        properties?.unregisterCallback(callback)
        car?.disconnect()
        car = null
        properties = null
        running = false
        status = Status.STOPPED
        inPark = null
        super.onDestroy()
    }

    private fun startForegroundWithNotice() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.car_parking_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val notice = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.car_parking_notice_title))
            .setContentText(getString(R.string.car_parking_notice_text))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTICE_ID, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTICE_ID, notice)
        }
    }

    private fun connectCar() {
        if (car != null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            return unsupported("not automotive")
        }
        try {
            car = Car.createCar(this, main, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready ->
                if (ready) onCarReady(connected) else properties = null
            }
        } catch (e: LinkageError) {
            unsupported("car library unavailable")
        } catch (e: RuntimeException) {
            unsupported("car service failed: ${e.javaClass.simpleName}")
        }
    }

    private fun onCarReady(connected: Car) {
        val manager = connected.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager ?: return unsupported("no property service")
        properties = manager
        var gear = false
        for (id in listOf(VehiclePropertyIds.GEAR_SELECTION, VehiclePropertyIds.CURRENT_GEAR)) {
            if (!gear && subscribe(manager, id)) gear = true
        }
        val ignition = subscribe(manager, VehiclePropertyIds.IGNITION_STATE)
        val brake = subscribe(manager, VehiclePropertyIds.PARKING_BRAKE_ON)
        status = when {
            gear && ignition -> Status.FULL
            gear -> Status.GEAR_ONLY
            ignition -> Status.IGNITION_ONLY
            else -> Status.UNSUPPORTED
        }
        Log.i(TAG, "car signals gear=$gear ignition=$ignition brake=$brake status=$status")
        if (status == Status.UNSUPPORTED) return unsupported("no gear/ignition signal")
        main.removeCallbacks(tick)
        main.postDelayed(tick, TICK_MS)
    }

    /** 속성을 구독한다. 차량에 없는 속성이거나 권한이 없으면 false. 구독하면 현재 값이 한 번 먼저 온다. */
    private fun subscribe(manager: CarPropertyManager, id: Int): Boolean = try {
        manager.getCarPropertyConfig(id) != null && manager.registerCallback(callback, id, CarPropertyManager.SENSOR_RATE_ONCHANGE)
    } catch (e: SecurityException) {
        Log.w(TAG, "no permission for property $id")
        false
    } catch (e: RuntimeException) {
        false
    }

    private fun onProperty(value: CarPropertyValue<*>) {
        if (value.status != CarPropertyValue.STATUS_AVAILABLE) return
        val now = now()
        val actions = when (value.propertyId) {
            VehiclePropertyIds.GEAR_SELECTION, VehiclePropertyIds.CURRENT_GEAR -> {
                val park = (value.value as? Int) == VehicleGear.GEAR_PARK
                inPark = park
                decider.onGear(park, now)
            }
            VehiclePropertyIds.IGNITION_STATE -> decider.onIgnition(ignitionOf(value.value as? Int), now)
            VehiclePropertyIds.PARKING_BRAKE_ON -> decider.onParkingBrake(value.value as? Boolean == true, now)
            else -> emptyList()
        }
        handle(actions)
    }

    private fun handle(actions: List<ParkingDecider.Action>) {
        for (action in actions) {
            Log.i(TAG, "parking action=$action")
            when (action) {
                ParkingDecider.Action.CAPTURE -> ParkingRecorder.currentLocation(this) { location ->
                    captured = location
                    capturedAt = now()
                }
                ParkingDecider.Action.CANCEL -> captured = null
                ParkingDecider.Action.COMMIT -> commit()
            }
        }
    }

    /**
     * 잡아 둔 위치가 있으면 그 위치로, 없으면 지금 위치를 얻어 저장한다.
     * 위치를 끝내 얻지 못하면(지하 주차장 등) 주차 시각만 있는 기록을 남긴다: 동기화로 휴대폰에 '주차 위치를 기록하시겠습니까?'가
     * 뜨고, 휴대폰이 같은 주차를 위치와 함께 저장했으면 그 위치가 합쳐진다. 좌표를 만들어 내지 않는다.
     * [ParkingDecider]가 P에서 벗어나기 전까지 한 번만 확정하므로 같은 주차에 기록이 두 번 생기지 않는다.
     */
    private fun commit() {
        val location = captured?.takeIf { now() - capturedAt < CAPTURE_MAX_AGE_MS }
        captured = null
        if (location != null) {
            ParkingRecorder.save(this, location, ParkingSpot.Source.CAR_SIGNAL)
        } else {
            ParkingRecorder.record(this, ParkingSpot.Source.CAR_SIGNAL, keepWithoutLocation = true)
        }
        // 챙기기로 한 물건이 있으면 내리기 전에 알린다 (위치 저장 성공 여부와 관계없이)
        CarItemReminder.notify(this)
    }

    private fun unsupported(reason: String) {
        Log.i(TAG, "automatic parking detection unavailable: $reason")
        status = Status.UNSUPPORTED
        stopSelf()
    }

    private fun now() = System.currentTimeMillis()

    /** 자동 감지에 쓸 수 있는 차량 신호 */
    enum class Status { STOPPED, FULL, GEAR_ONLY, IGNITION_ONLY, UNSUPPORTED }

    companion object {
        private const val TAG = "CarParking"
        private const val CHANNEL = "car_parking"
        private const val NOTICE_ID = 71
        private const val TICK_MS = 30_000L
        private const val CAPTURE_MAX_AGE_MS = 30 * 60_000L

        @Volatile
        var running = false
            private set

        @Volatile
        var status = Status.STOPPED
            private set

        /** 마지막으로 받은 기어 신호가 P인지 (모르면 null). 운전 중 물건 기록을 다음 주차에 연결하는 데 쓴다. */
        @Volatile
        var inPark: Boolean? = null
            private set

        private fun ignitionOf(value: Int?): ParkingDecider.Ignition = when (value) {
            VehicleIgnitionState.LOCK -> ParkingDecider.Ignition.LOCK
            VehicleIgnitionState.OFF -> ParkingDecider.Ignition.OFF
            VehicleIgnitionState.ACC -> ParkingDecider.Ignition.ACC
            VehicleIgnitionState.ON -> ParkingDecider.Ignition.ON
            VehicleIgnitionState.START -> ParkingDecider.Ignition.START
            else -> ParkingDecider.Ignition.UNKNOWN
        }

        /** 위치 권한이 있을 때만 시작한다 (앱 화면이 보일 때 또는 부팅 직후). */
        fun start(context: Context): Boolean {
            if (!ParkingRecorder.hasLocationPermission(context)) return false
            return try {
                context.startForegroundService(Intent(context, CarParkingService::class.java))
                true
            } catch (e: RuntimeException) {
                Log.w(TAG, "start failed: ${e.javaClass.simpleName}")
                false
            }
        }
    }
}
