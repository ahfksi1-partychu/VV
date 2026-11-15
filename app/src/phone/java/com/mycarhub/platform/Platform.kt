package com.mycarhub.platform

import android.content.Context
import com.mycarhub.MyCarHubApplication
import com.mycarhub.assistant.messages.MessageAccess
import com.mycarhub.drive.ParkingSpot
import com.mycarhub.drive.TodoItem
import com.mycarhub.phone.NotificationMessageAccess
import com.mycarhub.phone.drive.ParkingPrompt
import com.mycarhub.phone.drive.PhoneDriveSettings

/** 스마트폰 테스트용(phone) 빌드의 플랫폼 기능: 알림 접근으로 메시지 읽기·답장. */
object Platform {
    fun messageAccess(context: Context): MessageAccess = NotificationMessageAccess(context.applicationContext)

    /** 동기화에서 이 기기의 역할 (차량의 코드를 입력한다) */
    const val syncRole = "phone"

    /** 휴대폰에서 말로 등록한 할 일의 출처 */
    val voiceTodoSource = TodoItem.Source.VOICE_PHONE

    /** 앱 시작 시 할 일 없음 (폰의 Drive To-Do는 화면·블루투스 수신기·알림 접근에서 시작한다) */
    fun onAppCreate(app: MyCarHubApplication) = Unit

    /** 운전 중인지: 고른 차량 블루투스가 연결되어 있는 동안 (끊김 신호를 놓쳤을 때를 위해 12시간까지만) */
    fun isDriving(context: Context): Boolean =
        PhoneDriveSettings(context).driveStartedAt.let { it > 0 && System.currentTimeMillis() - it < 12 * 60 * 60 * 1000L }

    /** 물건 기록에 남길 차량 이름: 고른 차량 블루투스 이름 */
    fun vehicleName(context: Context): String? = PhoneDriveSettings(context).carName

    /** 차량 앱이 감지한 주차가 동기화로 도착해 지금 기록이 됨: '주차 위치를 기록하시겠습니까?' (같은 주차에는 한 번만) */
    fun onParkingFromOtherDevice(context: Context, spot: ParkingSpot) = ParkingPrompt.onParkingFromOtherDevice(context, spot)
}
