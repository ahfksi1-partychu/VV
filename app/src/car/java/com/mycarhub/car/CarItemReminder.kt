package com.mycarhub.car

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import com.mycarhub.R
import com.mycarhub.drive.CarItemStore

/**
 * 차량용: 주차가 확정되면(기어 P + 시동 끔 등) 챙기기로 한 물건을 차량 화면 알림으로 알린다.
 * 사용자가 '챙겨야 해', '알려줘'라고 하고 알림을 끄지 않은 물건만. 실제로 차에 있는지 확인한 것은 아니다.
 * 휴대폰과 동기화되어 있으면 물건 기록이 휴대폰에도 전달된다 (docs/DRIVE_SYNC.md). 이 알림 자체는 차량에만 뜬다.
 */
object CarItemReminder {

    private const val CHANNEL = "car_item_remind"
    private const val NOTICE_ID = 74

    fun notify(context: Context) {
        val items = CarItemStore(context).toRemind()
        if (items.isEmpty()) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.drive_item_remind_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val text = items.joinToString(", ") { it.name }
        val notice = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.drive_item_remind_title))
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(NOTICE_ID, notice)
            Log.i("CarItemReminder", "pickup reminder count=${items.size}")
        } catch (e: SecurityException) {
            Log.w("CarItemReminder", "notification not allowed")
        }
    }
}
