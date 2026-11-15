package com.mycarhub.phone.drive

import android.content.Context
import com.mycarhub.R
import com.mycarhub.assistant.core.SyncOutbox
import com.mycarhub.drive.DriveFormat
import com.mycarhub.drive.DriveSyncManager
import com.mycarhub.drive.DriveSyncState
import com.mycarhub.drive.ParkingSpot
import com.mycarhub.drive.ParkingStore

/** 주차 기록을 화면에 보여 줄 글 (메인 카드·기록 화면·Drive To-Do 공통). */
object ParkingRecordText {

    /** 가장 먼저 보여 줄 위치: 사용자가 적은 구역·번호 > 주소 > 좌표 > 위치 없음 */
    fun where(context: Context, spot: ParkingSpot): String = spot.note ?: place(context, spot)

    /** 구역 메모가 있을 때 그 아래 줄: 주소·좌표 또는 위치 없음 */
    fun place(context: Context, spot: ParkingSpot): String = when {
        spot.address != null -> spot.address
        spot.latitude != null && spot.longitude != null -> context.getString(R.string.drive_parking_coords, spot.latitude, spot.longitude)
        else -> context.getString(R.string.parking_record_no_location)
    }

    /**
     * 이 기록의 차량·휴대폰 동기화 상태. 서버에 올라간 것까지만 확인할 수 있다 (차량이 실제로 받았는지는 알 수 없어 그렇게 말하지 않는다).
     */
    fun syncStatus(context: Context, spot: ParkingSpot): String {
        if (!DriveSyncManager.isConfigured) return context.getString(R.string.parking_sync_off)
        val state = DriveSyncState(context)
        if (!state.paired) return context.getString(R.string.parking_sync_unpaired)
        val key = SyncOutbox.key(ParkingStore.KIND, spot.id)
        if (state.pending().any { it.first == key }) {
            val error = state.lastError
            return if (error != null) context.getString(R.string.parking_sync_failed, error) else context.getString(R.string.parking_sync_pending)
        }
        if (spot.fromOtherDevice) return context.getString(R.string.parking_sync_from_car)
        val at = state.lastSyncAt
        return if (at > 0) context.getString(R.string.parking_sync_done, DriveFormat.relative(context, at)) else context.getString(R.string.parking_sync_pending)
    }
}
