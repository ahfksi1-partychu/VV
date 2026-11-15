package com.mycarhub.phone.reminder

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import com.mycarhub.R
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 음성으로 등록한 알림·타이머 (폰 빌드 전용).
 *
 * AlarmManager.setAlarmClock(정확한 알람)으로 등록한다: 앱이 백그라운드이거나 프로세스가 없어도, 화면이 꺼져 있거나
 * 절전(Doze) 중이어도 지정한 시각에 [ReminderReceiver]가 실행되어 소리와 함께 알림창에 알림을 띄운다.
 * 등록한 알림은 기기 안(SharedPreferences)에만 저장하고, 재부팅·앱 업데이트 뒤에는 [ReminderRestoreReceiver]가 다시 등록한다.
 * 알림이 울려도 음성비서·연속 대화를 시작하지 않는다 (알림을 누르면 앱 화면만 열림).
 */
object Reminders {

    data class Item(val id: Int, val at: Long, val message: String, val timer: Boolean)

    enum class Result {
        SCHEDULED,

        /** 정확한 알람 권한 없음 (설정 > 알람 및 리마인더) */
        NEED_EXACT_ALARM,

        /** 앱 알림이 꺼져 있음 (설정 > 알림) */
        NOTIFICATIONS_BLOCKED,
        FAILED,
    }

    private const val TAG = "Reminders"
    private const val PREFS = "voice_reminders"
    private const val KEY_ITEMS = "items"
    private const val KEY_NEXT_ID = "next_id"
    const val CHANNEL_ID = "voice_reminders"
    private const val NOTIFICATION_BASE = 5_000
    internal const val EXTRA_ID = "com.mycarhub.phone.extra.REMINDER_ID"

    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms(context).canScheduleExactAlarms()

    fun notificationsEnabled(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return false
        val channel = manager.getNotificationChannel(CHANNEL_ID) ?: return true
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    /** 정확한 알람 허용 화면 (Android 12+) */
    fun exactAlarmSettings(context: Context): Intent =
        Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:" + context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** [at]에 [message] 알림을 등록한다. 실제로 등록됐을 때만 [Result.SCHEDULED]. */
    @Synchronized
    fun schedule(context: Context, at: LocalDateTime, message: String, timer: Boolean): Result {
        if (!notificationsEnabled(context)) return Result.NOTIFICATIONS_BLOCKED
        if (!canScheduleExact(context)) return Result.NEED_EXACT_ALARM
        ensureChannel(context)
        val prefs = prefs(context)
        val id = prefs.getInt(KEY_NEXT_ID, 1)
        val item = Item(id, at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), message, timer)
        if (!setAlarm(context, item)) return Result.FAILED
        prefs.edit().putInt(KEY_NEXT_ID, id + 1).commit()
        save(context, load(context) + item)
        Log.i(TAG, "scheduled id=$id inSec=${(item.at - System.currentTimeMillis()) / 1000} timer=$timer")
        return Result.SCHEDULED
    }

    /** 마지막으로 등록한(아직 울리지 않은) 알림을 취소한다. 없으면 null. */
    @Synchronized
    fun cancelLast(context: Context): Item? {
        val items = load(context)
        val last = items.maxByOrNull { it.id } ?: return null
        alarms(context).cancel(operation(context, last.id))
        save(context, items - last)
        Log.i(TAG, "cancelled id=${last.id}")
        return last
    }

    /** 알람 시각: 알림창에 소리와 함께 띄우고 목록에서 지운다. */
    @Synchronized
    fun fire(context: Context, id: Int) {
        val items = load(context)
        val item = items.firstOrNull { it.id == id }
        if (item == null) {
            Log.w(TAG, "fired unknown id=$id")
            return
        }
        save(context, items - item)
        post(context, item)
        Log.i(TAG, "fired id=$id lateSec=${(System.currentTimeMillis() - item.at) / 1000}")
    }

    /** 재부팅·앱 업데이트 뒤: 남은 알림을 다시 등록하고, 꺼져 있는 동안 지난 알림은 바로 띄운다. */
    @Synchronized
    fun restore(context: Context) {
        val now = System.currentTimeMillis()
        val (missed, future) = load(context).partition { it.at <= now }
        missed.forEach { post(context, it) }
        val kept = future.filter { canScheduleExact(context) && setAlarm(context, it) }
        save(context, kept)
        Log.i(TAG, "restored future=${kept.size} missed=${missed.size}")
    }

    private fun setAlarm(context: Context, item: Item): Boolean = try {
        val show = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, item.id, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        alarms(context).setAlarmClock(AlarmManager.AlarmClockInfo(item.at, show), operation(context, item.id))
        true
    } catch (e: SecurityException) {
        Log.w(TAG, "exact alarm refused", e)
        false
    }

    private fun operation(context: Context, id: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            id,
            Intent(context, ReminderReceiver::class.java).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun post(context: Context, item: Item) {
        ensureChannel(context)
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, NOTIFICATION_BASE + item.id, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_phone_mic)
            .setContentTitle(context.getString(if (item.timer) R.string.reminder_notification_timer else R.string.reminder_notification_title))
            .setContentText(item.message)
            .setStyle(Notification.BigTextStyle().bigText(item.message))
            .setCategory(if (item.timer) Notification.CATEGORY_ALARM else Notification.CATEGORY_REMINDER)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_BASE + item.id, notification)
    }

    /** 소리(알람 음량)·진동·화면 상단 표시가 있는 알림 채널 */
    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.reminder_channel_name), NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(R.string.reminder_channel_description)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 200, 400)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    private fun alarms(context: Context) = context.getSystemService(AlarmManager::class.java)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(context: Context): List<Item> = try {
        val array = JSONArray(prefs(context).getString(KEY_ITEMS, "[]"))
        (0 until array.length()).map { i ->
            array.getJSONObject(i).let { Item(it.getInt("id"), it.getLong("at"), it.getString("message"), it.optBoolean("timer")) }
        }
    } catch (e: org.json.JSONException) {
        Log.w(TAG, "reminder store unreadable", e)
        emptyList()
    }

    private fun save(context: Context, items: List<Item>) {
        val array = JSONArray()
        items.forEach { array.put(JSONObject().put("id", it.id).put("at", it.at).put("message", it.message).put("timer", it.timer)) }
        prefs(context).edit().putString(KEY_ITEMS, array.toString()).commit()
    }
}

/** 알람 시각에 실행된다 (앱이 꺼져 있어도 시스템이 실행). */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(Reminders.EXTRA_ID, -1)
        if (id > 0) Reminders.fire(context, id)
    }
}

/** 재부팅·앱 업데이트 뒤 남은 알림을 다시 등록한다. */
class ReminderRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Reminders.restore(context)
        }
    }
}
