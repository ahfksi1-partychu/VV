package com.mycarhub.phone

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.SpeechRecognizer
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.mycarhub.MainActivity
import com.mycarhub.R
import com.mycarhub.phone.floating.AppForegroundTracker

/**
 * 스마트폰 테스트용 빌드 전용 (src/phone). 앱 시작 시 ContentProvider로 자동 실행된다.
 *
 * - 모든 화면 맨 위에 '폰 테스트 빌드 · 차량 기능 미지원' 띠를 붙인다.
 *   주행 제한(CarUxRestrictions)은 폰에서 확인할 수 없어 항상 '제한 없음'으로 동작하는데,
 *   이것이 검증된 결과가 아님을 화면에서 분명히 하기 위해서다.
 * - 기기 기능(차량 여부, 음성 인식기 종류)을 logcat에 남긴다 (태그 PhoneTest).
 *
 * main 코드는 바꾸지 않으며 차량용(car) 빌드에는 포함되지 않는다.
 */
class PhoneTestNotice : ContentProvider() {

    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return false
        val pm = app.packageManager
        val automotive = pm.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
        val carLibrary = try {
            Class.forName("android.car.Car")
            true
        } catch (e: ClassNotFoundException) {
            false
        }
        val onDeviceStt = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(app)
        Log.i(
            TAG,
            "phone test build: sdk=${Build.VERSION.SDK_INT} model=${Build.MODEL} automotive=$automotive " +
                "carLibrary=$carLibrary onDeviceStt=$onDeviceStt systemStt=${SpeechRecognizer.isRecognitionAvailable(app)}",
        )
        val notice = app.getString(if (automotive) R.string.phone_test_notice_on_car else R.string.phone_test_notice)
        // 상시 듣기 서비스: 어떤 MyCarHub 화면이 열려 있는지 앱 시작부터 추적
        AppForegroundTracker.install(app)
        app.registerActivityLifecycleCallbacks(BannerInstaller(notice))
        // MyCarHub 화면의 호출어 듣기도 폰 빌드의 보완된 감지기가 맡는다 (MainActivity보다 먼저 실행됨)
        PhoneVoice.takeOverListening(app)
        // 음성비서 중심 메인 화면 (호출어 이름·상태, 상시 듣기 서비스 시작, 설정 진입)
        app.registerActivityLifecycleCallbacks(PhoneMainScreen())
        return true
    }

    /** setContentView 이후(onStart)에 화면 내용 위에 안내 띠를 한 번만 붙인다. */
    private class BannerInstaller(private val notice: String) : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            // 메인 화면은 일반 사용자용 화면이라 띠 대신 화면 아래에 작은 안내 문구를 둔다 (레이아웃)
            if (activity is MainActivity) return
            val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
            if (content.getTag(R.id.phone_test_banner) != null || content.childCount == 0) return
            content.setTag(R.id.phone_test_banner, true)

            val original = content.getChildAt(0)
            content.removeView(original)
            val pad = (BANNER_PADDING_DP * activity.resources.displayMetrics.density).toInt()
            val banner = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(BANNER_COLOR)
                setPadding(pad * 2, pad, pad, pad)
            }
            banner.addView(
                TextView(activity).apply {
                    id = R.id.phone_test_banner
                    text = notice
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, BANNER_TEXT_SP)
                    setTextColor(BANNER_TEXT_COLOR)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            column.addView(banner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            column.addView(original, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            content.addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private companion object {
        const val TAG = "PhoneTest"
        const val BANNER_TEXT_SP = 13f
        const val BANNER_PADDING_DP = 4
        const val BANNER_COLOR = 0xFFFFB300.toInt()
        const val BANNER_TEXT_COLOR = 0xFF000000.toInt()
    }
}
