package com.mycarhub.apps.system

import android.car.Car
import android.car.content.pm.CarPackageManager
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/**
 * Android Automotive 주행 중 UX 제한 상태 감시.
 *
 * 차량 서비스가 없거나(일반 기기) android.car 라이브러리가 없으면 제한 없음으로 동작한다.
 * 실제 차단은 시스템이 강제하므로, 여기서는 실행 전에 사용자에게 이유를 안내하기 위한 사전 확인만 한다.
 */
class CarRestrictionMonitor(private val context: Context) {

    fun interface Listener {
        fun onRestrictionsChanged(drivingRestricted: Boolean)
    }

    private var car: Car? = null
    private var uxManager: CarUxRestrictionsManager? = null
    private var carPackageManager: CarPackageManager? = null
    private var current: CarUxRestrictions? = null
    private val listeners = mutableSetOf<Listener>()

    /** 주행 중이라 Distraction Optimized 화면만 허용되는 상태. */
    val isDrivingRestricted: Boolean
        get() = current?.isRequiresDistractionOptimization == true

    /** 주행 중 키보드 입력(별칭 편집 등) 제한 여부. */
    val isKeyboardRestricted: Boolean
        get() = isDrivingRestricted &&
            (current?.activeRestrictions ?: 0) and CarUxRestrictions.UX_RESTRICTIONS_NO_KEYBOARD != 0

    val isConnected: Boolean get() = uxManager != null

    fun connect() {
        if (car != null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return
        try {
            car = Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connectedCar, ready ->
                if (ready) onCarReady(connectedCar) else onCarLost()
            }
        } catch (e: LinkageError) {
            Log.w(TAG, "android.car library unavailable", e)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Car service connection failed", e)
        }
    }

    fun disconnect() {
        uxManager?.unregisterListener()
        car?.disconnect()
        car = null
        onCarLost()
    }

    fun addListener(listener: Listener) {
        listeners += listener
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    /** 대상 Activity가 주행 중 실행 가능한지. 차량 서비스 판단을 우선하고, 없으면 manifest 메타데이터로 판단한다. */
    fun isDistractionOptimized(activity: ActivityInfo): Boolean {
        carPackageManager?.let { manager ->
            try {
                return manager.isActivityDistractionOptimized(activity.packageName, activity.name)
            } catch (e: RuntimeException) {
                Log.w(TAG, "isActivityDistractionOptimized failed", e)
            }
        }
        return readDistractionOptimizedMetaData(activity)
    }

    private fun onCarReady(connectedCar: Car) {
        val manager = connectedCar.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
        uxManager = manager
        carPackageManager = connectedCar.getCarManager(Car.PACKAGE_SERVICE) as? CarPackageManager
        current = manager?.currentCarUxRestrictions
        Log.i(TAG, "Car UX restrictions connected: drivingRestricted=$isDrivingRestricted")
        manager?.registerListener { restrictions ->
            current = restrictions
            Log.i(TAG, "Car UX restrictions changed: drivingRestricted=$isDrivingRestricted")
            notifyListeners()
        }
        notifyListeners()
    }

    private fun onCarLost() {
        uxManager = null
        carPackageManager = null
        current = null
        notifyListeners()
    }

    private fun notifyListeners() {
        val restricted = isDrivingRestricted
        listeners.toList().forEach { it.onRestrictionsChanged(restricted) }
    }

    @Suppress("DEPRECATION")
    private fun readDistractionOptimizedMetaData(activity: ActivityInfo): Boolean = try {
        val info = context.packageManager.getActivityInfo(
            android.content.ComponentName(activity.packageName, activity.name),
            PackageManager.GET_META_DATA,
        )
        info.metaData?.getBoolean(META_DISTRACTION_OPTIMIZED, false) == true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private companion object {
        const val TAG = "CarRestrictionMonitor"
        const val META_DISTRACTION_OPTIMIZED = "distractionOptimized"
    }
}
