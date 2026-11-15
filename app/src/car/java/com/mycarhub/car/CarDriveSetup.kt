package com.mycarhub.car

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import com.mycarhub.MainActivity
import com.mycarhub.MyCarHubApplication
import com.mycarhub.R
import com.mycarhub.drive.DriveSyncActivity
import com.mycarhub.drive.ParkingRecorder

/**
 * 차량용 빌드의 Drive To-Do 준비: 메인 화면이 보일 때 주차 감시 서비스를 시작하고,
 * 위치 권한이 없으면 정차 중에 한 번만 권한 창을 띄운다 (주행 중에는 띄우지 않음, 거부하면 다시 묻지 않음).
 */
class CarDriveSetup(private val app: MyCarHubApplication) : Application.ActivityLifecycleCallbacks {

    override fun onActivityResumed(activity: Activity) {
        if (activity !is MainActivity) return
        bindSyncButton(activity)
        if (ParkingRecorder.hasLocationPermission(activity)) {
            if (!CarParkingService.running) CarParkingService.start(activity)
            return
        }
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ASKED, false) || app.restrictions.isDrivingRestricted) return
        prefs.edit().putBoolean(KEY_ASKED, true).apply()
        activity.requestPermissions(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            REQUEST_LOCATION,
        )
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

    /** 메인 화면의 [휴대폰 동기화] 버튼: 정차 중에만 연결 화면을 연다 (그 화면도 주행 중에는 시스템이 막는다). */
    private fun bindSyncButton(activity: Activity) {
        activity.findViewById<Button>(R.id.carSyncButton)?.setOnClickListener {
            if (app.restrictions.isDrivingRestricted) {
                Toast.makeText(activity, R.string.sync_driving_blocked, Toast.LENGTH_LONG).show()
            } else {
                activity.startActivity(Intent(activity, DriveSyncActivity::class.java))
            }
        }
    }
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}

    /** 부팅 후: 위치 권한이 있으면 주차 감시를 다시 시작한다. */
    class BootReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
            Log.i(TAG, "boot: start parking monitor=${CarParkingService.start(context)}")
        }
    }

    private companion object {
        const val TAG = "CarDriveSetup"
        const val PREFS = "car_drive"
        const val KEY_ASKED = "location_asked"
        const val REQUEST_LOCATION = 81
    }
}
