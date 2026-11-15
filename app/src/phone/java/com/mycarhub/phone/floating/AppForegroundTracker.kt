package com.mycarhub.phone.floating

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.mycarhub.MainActivity
import com.mycarhub.phone.PermissionRequestActivity

/**
 * 어떤 MyCarHub 화면이 열려 있는지 추적한다 (폰 테스트용 빌드 전용).
 *
 * 호출어 감지는 [FloatingVoiceService] 하나가 맡는다. 메인 화면에서는 계속 듣지만, 그 밖의 MyCarHub 화면
 * (음성 대화·호출어 등록·측정처럼 마이크를 직접 쓰는 화면, 설정)이 열려 있으면 감지를 쉰다.
 * 화면이 만들어지는 시점(onCreate)에 바로 알려서 그 화면이 마이크를 열기 전에 감지 스레드가 마이크를 놓게 한다.
 */
object AppForegroundTracker : Application.ActivityLifecycleCallbacks {

    fun interface Listener {
        fun onAppScreenChanged()
    }

    /** 열린 화면 (생성~중지 사이): identityHashCode → 메인 화면인지 */
    private val open = mutableMapOf<Int, Boolean>()
    private val listeners = mutableSetOf<Listener>()

    /** MyCarHub 화면이 하나라도 열려 있는지 */
    val isAppScreenOpen: Boolean get() = open.isNotEmpty()

    /** 메인 화면이 아닌 MyCarHub 화면이 열려 있는지 (그동안 호출어 감지를 쉰다) */
    val isOtherScreenOpen: Boolean get() = open.values.any { !it } || permissionScreens.isNotEmpty()

    /**
     * 권한 요청 화면 (생성~종료 사이). 사용자가 시스템 설정에 가 있는 동안에도 감지를 쉬지만,
     * 다른 앱 위 음성비서 창은 권한 허용 뒤 대화를 이어가야 하므로 '앱 화면'으로 보지 않는다.
     */
    private val permissionScreens = mutableSetOf<Int>()

    /** 지금 앞에 있는 메인 화면 (호출어를 들으면 여기서 음성 대화 화면을 연다) */
    var resumedMain: MainActivity? = null
        private set

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(this)
    }

    fun addListener(listener: Listener) {
        listeners += listener
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = mark(activity, true)
    override fun onActivityStarted(activity: Activity) = mark(activity, true)
    override fun onActivityStopped(activity: Activity) = mark(activity, false)
    override fun onActivityDestroyed(activity: Activity) = mark(activity, false)

    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) resumedMain = activity
    }

    override fun onActivityPaused(activity: Activity) {
        if (activity === resumedMain) resumedMain = null
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    private fun mark(activity: Activity, opened: Boolean) {
        val key = System.identityHashCode(activity)
        if (activity is PermissionRequestActivity) {
            // 중지(설정 화면으로 감)가 아니라 종료·재생성될 때만 닫힌 것으로 본다
            val closing = activity.isFinishing || activity.isChangingConfigurations
            val changed = if (opened) permissionScreens.add(key) else closing && permissionScreens.remove(key)
            if (changed) listeners.toList().forEach { it.onAppScreenChanged() }
            return
        }
        val before = open.toMap()
        if (opened) open[key] = activity is MainActivity else open -= key
        if (before != open) listeners.toList().forEach { it.onAppScreenChanged() }
    }
}
