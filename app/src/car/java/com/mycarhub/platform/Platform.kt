package com.mycarhub.platform

import android.content.Context
import com.mycarhub.MyCarHubApplication
import com.mycarhub.assistant.messages.MessageAccess
import com.mycarhub.assistant.messages.UnsupportedMessageAccess
import com.mycarhub.car.CarDriveSetup
import com.mycarhub.car.CarParkingService
import com.mycarhub.drive.ParkingSpot
import com.mycarhub.drive.TodoItem

/**
 * 차량용(car) 빌드의 플랫폼 기능.
 *
 * Android Automotive에서 메시지 알림 읽기·답장은 차량 기본 음성비서(시스템 권한 앱)가 맡고,
 * 일반 앱이 쓸 수 있는 공식 인터페이스가 없다. Pleos Connect SDK에도 메시지 API가 없어 지원하지 않는다.
 */
object Platform {
    fun messageAccess(context: Context): MessageAccess = UnsupportedMessageAccess

    /** 동기화에서 이 기기의 역할 (차량이 코드를 만들고 승인한다) */
    const val syncRole = "car"

    /** 차량에서 말로 등록한 할 일의 출처 */
    val voiceTodoSource = TodoItem.Source.VOICE_CAR

    /** 앱 시작 시: Drive To-Do 주차 위치 자동 저장 준비 (메인 화면이 보일 때 감시 서비스 시작) */
    fun onAppCreate(app: MyCarHubApplication) {
        app.registerActivityLifecycleCallbacks(CarDriveSetup(app))
    }

    /** 운전 중인지: 차량 신호로 기어가 P가 아닌 것이 확인된 경우만 (신호를 모르면 false) */
    fun isDriving(context: Context): Boolean = CarParkingService.inPark == false

    /** 물건 기록에 남길 차량 이름 (차량용 앱은 그 차 자체라 따로 이름이 없다) */
    fun vehicleName(context: Context): String? = null

    /**
     * 휴대폰에서 온 주차 기록이 지금 기록이 됨. 차량 화면에는 묻지 않는다 (구역·사진 입력은 휴대폰에서, 주행 중 입력 금지).
     * 받은 구역 메모는 "차 어디 세웠지?" 대답에 쓰인다.
     */
    fun onParkingFromOtherDevice(context: Context, spot: ParkingSpot) = Unit
}
