package com.mycarhub.phone.drive

import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.mycarhub.R
import com.mycarhub.assistant.core.ParkingCombine
import com.mycarhub.assistant.core.ParkingMerge
import com.mycarhub.assistant.core.ParkingPromptRules
import com.mycarhub.drive.DriveFormat
import com.mycarhub.drive.ParkingSpot
import com.mycarhub.drive.ParkingStore
import com.mycarhub.phone.floating.AppForegroundTracker
import org.json.JSONArray
import org.json.JSONObject

/**
 * '주차 위치를 기록하시겠습니까?' 안내 (폰 빌드).
 *
 * - 언제: 휴대폰이 차량 블루투스 끊김으로 주차를 저장했을 때, 또는 차량 앱이 기어 P·시동 끔으로 감지한 주차가
 *   동기화로 도착했을 때. 같은 주차(같은 기록 또는 10분 안의 차량·휴대폰 기록)에는 한 번만 ([ParkingPromptRules]).
 * - 어떻게: 메인 화면이 앞에 있으면 앱 안 팝업, 아니면(백그라운드·잠금) 알림. 알림을 누르면 기록 화면이 열린다.
 *   답하지 않은 안내는 메인 화면의 주차 카드에 그대로 남는다 (다시 알림을 띄우지 않는다).
 * - 선택: 직접 입력 / 사진 촬영 / 나중에 / 건너뛰기.
 */
object ParkingPrompt {

    private const val TAG = "ParkingPrompt"
    private const val PREFS = "parking_prompt"
    private const val KEY_SHOWN = "shown"
    private const val KEY_LAST_ID = "last_id"
    private const val KEY_STATE = "state"
    private const val CHANNEL = "parking_prompt"

    /** 안내에 대한 사용자의 답 */
    enum class State {
        /** 안내함, 아직 답 없음 */
        PENDING,

        /** 나중에 (메인 카드에 계속 묻는다) */
        LATER,

        /** 건너뛰기 (더 묻지 않는다, 카드에서 언제든 수정 가능) */
        SKIPPED,

        /** 기록함 */
        DONE,
    }

    private val main = Handler(Looper.getMainLooper())

    /**
     * 이 주차 기록에 안내를 띄워도 되는지 판단하고, 띄울 거면 '안내함'으로 기억한다 (다음 호출은 false).
     * 앱이 꺼졌다 켜져도 같은 주차에 다시 묻지 않도록 기기 안에 남긴다.
     */
    @Synchronized
    fun claim(context: Context, spot: ParkingSpot): Boolean {
        val prefs = prefs(context)
        val shown = readShown(context)
        val ok = ParkingPromptRules.shouldPrompt(mergeSpot(spot), spot.hasDetail, System.currentTimeMillis(), shown)
        if (!ok) return false
        val next = ParkingPromptRules.remember(shown, mergeSpot(spot))
        val array = JSONArray()
        next.forEach { array.put(JSONObject().put("id", it.id).put("at", it.savedAt)) }
        prefs.edit().putString(KEY_SHOWN, array.toString()).putString(KEY_LAST_ID, spot.id).putString(KEY_STATE, State.PENDING.name).apply()
        Log.i(TAG, "prompt claimed source=${spot.source} location=${spot.hasLocation}")
        return true
    }

    /** 차량에서 감지한 주차가 동기화로 도착함 (동기화 스레드에서 부름): 안내 한 번 */
    fun onParkingFromOtherDevice(context: Context, spot: ParkingSpot) {
        val app = context.applicationContext
        main.post {
            if (!claim(app, spot)) return@post
            if (!showInApp(spot)) DriveSummary.notify(app, spot, driveStartedAt = 0L, prompt = true)
        }
    }

    /** 메인 화면이 앞에 있으면 팝업을 띄우고 true. 아니면 false (알림으로). 메인 스레드에서. */
    fun showInApp(spot: ParkingSpot): Boolean {
        val activity = AppForegroundTracker.resumedMain ?: return false
        if (activity.isFinishing) return false
        showDialog(activity, spot)
        return true
    }

    /** 앱 안 팝업: 직접 입력 / 사진 촬영 / 나중에 입력 / 건너뛰기 */
    fun showDialog(activity: Activity, spot: ParkingSpot) {
        val choices = listOf(
            R.string.parking_prompt_text to { open(activity, spot.id, ParkingRecordActivity.Mode.TEXT) },
            R.string.parking_prompt_photo to { open(activity, spot.id, ParkingRecordActivity.Mode.PHOTO) },
            R.string.parking_prompt_later to { setState(activity, State.LATER) },
            R.string.parking_prompt_skip to { setState(activity, State.SKIPPED) },
        )
        AlertDialog.Builder(activity)
            .setTitle(R.string.parking_prompt_title)
            .setItems(choices.map { activity.getString(it.first) }.toTypedArray()) { _, which -> choices[which].second() }
            .setOnCancelListener { setState(activity, State.LATER) }
            .show()
    }

    private fun open(activity: Activity, spotId: String, mode: ParkingRecordActivity.Mode) {
        activity.startActivity(ParkingRecordActivity.intent(activity, spotId, mode))
    }

    /** 메인 카드에 '기록하시겠습니까?'를 계속 보여 줄지: 이 주차(또는 같은 주차)에 대한 안내에 아직 답하지 않았고 메모·사진이 없음 */
    fun isOpenFor(context: Context, spot: ParkingSpot): Boolean {
        if (spot.hasDetail) return false
        val state = state(context)
        if (state != State.PENDING && state != State.LATER) return false
        val lastId = prefs(context).getString(KEY_LAST_ID, null) ?: return false
        if (lastId == spot.id) return true
        // 휴대폰에서 물은 뒤 같은 주차의 차량 기록이 이겨 지금 기록이 바뀐 경우
        val previous = ParkingStore(context).previous()
        return previous?.id == lastId && ParkingCombine.sameParking(mergeSpot(spot), mergeSpot(previous))
    }

    fun state(context: Context): State? = prefs(context).getString(KEY_STATE, null)?.let { runCatching { State.valueOf(it) }.getOrNull() }

    fun setState(context: Context, state: State) {
        prefs(context).edit().putString(KEY_STATE, state.name).apply()
        if (state != State.PENDING) dismissNotification(context)
    }

    /** 블루투스가 곧 다시 연결되어 주차가 되돌려짐: 그 주차의 안내를 거둔다 */
    fun withdraw(context: Context, spotId: String) {
        if (prefs(context).getString(KEY_LAST_ID, null) != spotId) return
        prefs(context).edit().putString(KEY_STATE, State.SKIPPED.name).apply()
        dismissNotification(context)
    }

    fun dismissNotification(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(DriveSummary.NOTICE_ID) }
    }

    // region 알림

    /** 안내 알림 채널 (중요도 높음: 화면 위에 잠깐 보임. 잠금 화면에는 내용 없이) */
    fun channel(context: Context): String {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.parking_prompt_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
        return CHANNEL
    }

    fun recordPendingIntent(context: Context, spotId: String, mode: ParkingRecordActivity.Mode): PendingIntent =
        PendingIntent.getActivity(
            context, REQUEST_BASE + mode.ordinal,
            ParkingRecordActivity.intent(context, spotId, mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** [직접 입력] [사진 촬영] [건너뛰기], 밀어서 지우면 '나중에' */
    fun addActions(context: Context, builder: Notification.Builder, spotId: String) {
        builder.addAction(Notification.Action.Builder(null, context.getString(R.string.parking_prompt_text), recordPendingIntent(context, spotId, ParkingRecordActivity.Mode.TEXT)).build())
        builder.addAction(Notification.Action.Builder(null, context.getString(R.string.parking_prompt_photo), recordPendingIntent(context, spotId, ParkingRecordActivity.Mode.PHOTO)).build())
        builder.addAction(Notification.Action.Builder(null, context.getString(R.string.parking_prompt_skip), broadcast(context, ParkingPromptReceiver.ACTION_SKIP)).build())
        builder.setDeleteIntent(broadcast(context, ParkingPromptReceiver.ACTION_LATER))
    }

    private fun broadcast(context: Context, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, REQUEST_BASE + if (action == ParkingPromptReceiver.ACTION_SKIP) 10 else 11,
            Intent(context, ParkingPromptReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    // endregion

    /** 카드·기록 화면에 쓰는 짧은 설명: "오늘 오후 3시 10분 · 차량에서 감지" */
    fun whenAndHow(context: Context, spot: ParkingSpot): String {
        val how = context.getString(
            when (spot.source) {
                ParkingSpot.Source.CAR_SIGNAL -> R.string.parking_record_source_car
                ParkingSpot.Source.PHONE_BLUETOOTH -> R.string.parking_record_source_phone
                ParkingSpot.Source.MANUAL -> R.string.parking_record_source_manual
            },
        )
        return context.getString(R.string.parking_record_when, DriveFormat.relative(context, spot.savedAt), how)
    }

    private const val REQUEST_BASE = 7_300

    private fun mergeSpot(spot: ParkingSpot) = ParkingMerge.Spot(spot.id, spot.savedAt, ParkingMerge.Source.valueOf(spot.source.name))

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readShown(context: Context): List<ParkingPromptRules.Shown> = try {
        val array = JSONArray(prefs(context).getString(KEY_SHOWN, null) ?: "[]")
        (0 until array.length()).map { i -> array.getJSONObject(i).let { ParkingPromptRules.Shown(it.getString("id"), it.getLong("at")) } }
    } catch (e: Exception) {
        emptyList()
    }
}

/** 안내 알림의 [건너뛰기]와 밀어서 지우기(나중에). 외부에 공개하지 않는다. */
class ParkingPromptReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SKIP -> ParkingPrompt.setState(context, ParkingPrompt.State.SKIPPED)
            ACTION_LATER -> if (ParkingPrompt.state(context) == ParkingPrompt.State.PENDING) ParkingPrompt.setState(context, ParkingPrompt.State.LATER)
        }
    }

    companion object {
        const val ACTION_SKIP = "com.mycarhub.parking.SKIP"
        const val ACTION_LATER = "com.mycarhub.parking.LATER"
    }
}
