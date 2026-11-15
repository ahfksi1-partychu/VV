package com.mycarhub.drive

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.mycarhub.R
import com.mycarhub.assistant.core.CarItemCommand
import com.mycarhub.assistant.core.CarItems
import com.mycarhub.assistant.core.DriveCommands
import com.mycarhub.assistant.core.Josa
import com.mycarhub.platform.Platform
import com.mycarhub.assistant.core.PlaceCondition
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Calendar

/**
 * Drive To-Do 음성 명령: "차 어디 세웠지?", "여기 주차했어", "할 일 뭐 있어?", "집에 가서 택배 확인해야 해".
 * 차량용·폰 음성비서가 기존 명령보다 먼저 묻는다. 해당하지 않으면 null.
 */
class DriveAssistant(context: Context, private val voiceSource: TodoItem.Source) {

    private val context = context.applicationContext
    private val parking = ParkingStore(this.context)
    private val todos = TodoStore(this.context)
    private val items = CarItemStore(this.context)

    sealed interface Answer {
        /** 말할 문장. [spot]이 있으면 길찾기를 이어서 제안할 수 있다. */
        data class Say(val text: String, val spot: ParkingSpot? = null) : Answer

        /** 지금 위치를 주차 위치로 저장해야 함 (위치는 비동기로 얻는다: [ParkingRecorder.record]) */
        data object RecordParkingHere : Answer
    }

    /** [save]가 false면 할 일을 저장하지 않고 대답만 만든다 (개발 빌드 ADB 테스트). */
    fun answer(heard: String, wakeWord: String?, save: Boolean = true): Answer? {
        if (DriveCommands.isParkHere(heard, wakeWord)) return Answer.RecordParkingHere
        if (DriveCommands.isParkingQuery(heard, wakeWord)) return parkingAnswer()
        CarItems.parse(heard, wakeWord)?.let { command -> carItemAnswer(command, save)?.let { return it } }
        if (DriveCommands.isTodoListQuery(heard, wakeWord)) return todoListAnswer()
        val draft = DriveCommands.parseTodo(heard, LocalDateTime.now(), wakeWord) ?: return null
        val now = System.currentTimeMillis()
        val item = TodoItem(
            id = TodoItem.newId(),
            title = draft.title,
            note = draft.note,
            dueAt = draft.due?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli(),
            hasTime = draft.hasTime,
            placeKind = draft.place?.kind?.name,
            placeName = draft.place?.name,
            source = voiceSource,
            appPackage = null,
            notificationKey = null,
            createdAt = now,
            updatedAt = now,
            done = false,
        )
        val added = !save || todos.add(item)
        val extra = listOfNotNull(
            item.dueAt?.let { str(R.string.drive_todo_due_said, DriveFormat.due(context, it, item.hasTime)) },
            draft.place?.let { str(R.string.drive_todo_place_said, it.name) },
        ).joinToString(" ")
        return Answer.Say(
            if (added) str(R.string.drive_todo_added, item.title, extra).trim() else str(R.string.drive_todo_duplicate, item.title),
        )
    }

    fun parkingAnswer(): Answer.Say {
        val spot = parking.load() ?: return Answer.Say(str(R.string.drive_parking_none))
        val `when` = DriveFormat.relative(context, spot.savedAt)
        val where = whereOf(spot)
        val accuracy = spot.accuracyM?.takeIf { it > ACCURACY_WARN_M }?.let { str(R.string.drive_parking_accuracy, it.toInt()) }.orEmpty()
        // 위치가 없는 기록(지하 주차장 등)은 길찾기를 제안하지 않는다
        return Answer.Say(str(R.string.drive_parking_answer, where, `when`, accuracy).trim(), spot.takeIf { it.hasLocation })
    }

    /** 말로 알려줄 주차 위치: 사용자가 적은 구역·번호와 주소. 위치가 없으면 그렇다고 말한다. */
    private fun whereOf(spot: ParkingSpot): String {
        val place = spot.address ?: if (spot.hasLocation) str(R.string.drive_parking_no_address) else null
        return when {
            spot.note != null && place != null -> str(R.string.drive_parking_where_note, spot.note, place)
            spot.note != null -> spot.note
            place != null -> place
            else -> str(R.string.drive_parking_no_location)
        }
    }

    /** "여기 주차했어": 지금 위치를 저장하고 결과 문장을 메인 스레드로 알려준다. 권한이 없거나 위치를 못 얻으면 저장하지 않는다. */
    fun recordParkingHere(onDone: (String) -> Unit) {
        if (!ParkingRecorder.hasLocationPermission(context)) return onDone(str(R.string.drive_parking_need_location))
        ParkingRecorder.record(context, ParkingSpot.Source.MANUAL) { spot ->
            onDone(str(if (spot != null) R.string.drive_parking_saved else R.string.drive_parking_save_failed))
        }
    }

    // region 차량에 남긴 물건 (사용자가 말한 기록일 뿐, 실제로 있는지 확인한 것처럼 말하지 않는다)

    private fun carItemAnswer(command: CarItemCommand, save: Boolean): Answer? = when (command) {
        is CarItemCommand.Leave -> leaveAnswer(command, save)
        CarItemCommand.ListAll -> {
            val list = items.inCar().sortedByDescending { it.createdAt }
            if (list.isEmpty()) {
                Answer.Say(str(R.string.drive_item_list_empty))
            } else {
                val names = list.take(MAX_ITEMS_READ).joinToString(", ") { "${CarItems.placeLabel(it.spot)}에 ${it.name}" }
                Answer.Say(str(R.string.drive_item_list, names, list.size))
            }
        }
        CarItemCommand.ListPickup -> {
            val list = items.inCar().filter { it.pickup }.sortedByDescending { it.createdAt }
            if (list.isEmpty()) Answer.Say(str(R.string.drive_item_pickup_empty))
            else Answer.Say(str(R.string.drive_item_pickup_list, list.take(MAX_ITEMS_READ).joinToString(", ") { it.name }))
        }
        is CarItemCommand.Find -> findAnswer(command.name)
        is CarItemCommand.Retrieved -> {
            val item = items.find(command.name)
            when {
                item == null && !command.saidCar -> null
                item == null -> Answer.Say(str(R.string.drive_item_not_found, Josa.eulReul(command.name)))
                else -> {
                    if (save) items.setRetrieved(item.id, true)
                    Answer.Say(str(R.string.drive_item_retrieved, Josa.eulReul(item.name)))
                }
            }
        }
    }

    private fun leaveAnswer(command: CarItemCommand.Leave, save: Boolean): Answer.Say {
        val driving = Platform.isDriving(context)
        val latest = parking.load()
        if (!save) return Answer.Say(leaveText(command.name, command.spot, command.pickup, linked = !driving && latest != null, driving, latest))
        val result = items.register(command.name, command.spot, command.pickup, voiceSource, Platform.vehicleName(context), driving)
        val item = result.item
        if (!result.isNew) {
            return Answer.Say(
                if (command.pickup && item.pickup) str(R.string.drive_item_now_pickup, Josa.eunNeun(item.name), CarItems.pickupTitle(item.name, item.spot))
                else str(R.string.drive_item_already, Josa.eunNeun(item.name), CarItems.placeLabel(item.spot)),
            )
        }
        return Answer.Say(leaveText(item.name, item.spot, item.pickup, item.parkingId != null, driving, item.parking))
    }

    private fun leaveText(name: String, spot: com.mycarhub.assistant.core.CarSpot?, pickup: Boolean, linked: Boolean, driving: Boolean, parked: ParkingSpot?): String {
        val what = if (pickup) {
            str(R.string.drive_item_pickup_saved, CarItems.pickupTitle(name, spot))
        } else {
            str(R.string.drive_item_saved, CarItems.placeLabel(spot), Josa.eulReul(name))
        }
        val link = when {
            linked && parked?.accuracyM?.let { it > ParkingRecorder.LOW_ACCURACY_M } == true -> str(R.string.drive_item_linked_inaccurate)
            linked -> str(R.string.drive_item_linked)
            driving -> str(R.string.drive_item_link_next)
            else -> str(R.string.drive_item_no_parking)
        }
        return "$what $link"
    }

    /** "내 우산 어디 있어?": 기록이 있으면 언제·어디에 두었다고 했는지와 차의 마지막 주차 위치 (길찾기 제안). */
    private fun findAnswer(name: String): Answer.Say {
        val item = items.find(name)
        if (item == null) {
            val retrieved = items.items().filter { !it.inCar && CarItems.matches(it.name, name) }.maxByOrNull { it.retrievedAt ?: 0L }
            if (retrieved != null) {
                return Answer.Say(str(R.string.drive_item_found_retrieved, Josa.eunNeun(retrieved.name), DriveFormat.relative(context, retrieved.retrievedAt!!)))
            }
            return Answer.Say(str(R.string.drive_item_not_found, Josa.eulReul(name)))
        }
        val recorded = str(
            R.string.drive_item_found, Josa.eunNeun(item.name), DriveFormat.relative(context, item.createdAt), CarItems.placeLabel(item.spot),
        )
        val spot = parking.load() ?: return Answer.Say("$recorded ${str(R.string.drive_item_car_unknown)}")
        return Answer.Say("$recorded ${str(R.string.drive_item_car_at, whereOf(spot))}", spot.takeIf { it.hasLocation })
    }

    // endregion

    private fun todoListAnswer(): Answer.Say {
        val open = todos.openTodos().sortedWith(compareBy({ it.dueAt ?: Long.MAX_VALUE }, { it.createdAt }))
        if (open.isEmpty()) return Answer.Say(str(R.string.drive_todo_list_empty))
        val names = open.take(MAX_READ).joinToString(", ") { it.title }
        return Answer.Say(str(R.string.drive_todo_list, open.size, names))
    }

    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    companion object {
        private const val ACCURACY_WARN_M = 50f
        private const val MAX_READ = 3
        private const val MAX_ITEMS_READ = 5

        /** 장소 조건 종류 → 저장 값 */
        fun placeKind(kind: PlaceCondition.Kind): String = kind.name

        /** 지도 앱에서 주차 위치 보기 (geo: 링크를 처리하는 앱이 연다). 위치가 없는 기록이면 null. */
        fun mapIntent(spot: ParkingSpot, label: String): Intent? {
            val lat = spot.latitude ?: return null
            val lng = spot.longitude ?: return null
            return Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng(${Uri.encode(label)})"))
        }

        /** 지도 앱이 없을 때: 웹 지도(브라우저)에서 주차 위치 보기. 위치가 없는 기록이면 null. */
        fun webMapIntent(spot: ParkingSpot): Intent? {
            val lat = spot.latitude ?: return null
            val lng = spot.longitude ?: return null
            return Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=$lat,$lng"))
        }

        /** 주차 위치까지 걸어서 길찾기 (구글 지도 길찾기 링크: 지도 앱 또는 브라우저가 연다). 위치가 없는 기록이면 null. */
        fun directionsIntent(spot: ParkingSpot): Intent? {
            val lat = spot.latitude ?: return null
            val lng = spot.longitude ?: return null
            return Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lng&travelmode=walking"))
        }
    }
}

/** 시각 표현 ("12분 전", "오늘 오후 3시 10분", "11월 9일 오전 8시"). */
object DriveFormat {

    fun relative(context: Context, time: Long, now: Long = System.currentTimeMillis()): String {
        val minutes = (now - time) / 60_000
        return when {
            minutes < 1 -> context.getString(R.string.drive_time_just_now)
            minutes < 60 -> context.getString(R.string.drive_time_minutes_ago, minutes)
            else -> at(context, time, now)
        }
    }

    fun due(context: Context, time: Long, hasTime: Boolean, now: Long = System.currentTimeMillis()): String =
        if (hasTime) at(context, time, now) else day(context, time, now)

    private fun at(context: Context, time: Long, now: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = time }
        val hour = c.get(Calendar.HOUR_OF_DAY)
        val minute = c.get(Calendar.MINUTE)
        val period = context.getString(if (hour < 12) R.string.drive_time_am else R.string.drive_time_pm)
        val h12 = (hour % 12).let { if (it == 0) 12 else it }
        val clock = if (minute == 0) {
            context.getString(R.string.drive_time_clock_hour, period, h12)
        } else {
            context.getString(R.string.drive_time_clock, period, h12, minute)
        }
        return "${day(context, time, now)} $clock"
    }

    private fun day(context: Context, time: Long, now: Long): String {
        val target = Calendar.getInstance().apply { timeInMillis = time }
        val today = Calendar.getInstance().apply { timeInMillis = now }
        val diff = dayNumber(target) - dayNumber(today)
        return when (diff) {
            0L -> context.getString(R.string.drive_time_today)
            1L -> context.getString(R.string.drive_time_tomorrow)
            -1L -> context.getString(R.string.drive_time_yesterday)
            else -> context.getString(R.string.drive_time_date, target.get(Calendar.MONTH) + 1, target.get(Calendar.DAY_OF_MONTH))
        }
    }

    private fun dayNumber(c: Calendar): Long =
        (c.timeInMillis + c.get(Calendar.ZONE_OFFSET) + c.get(Calendar.DST_OFFSET)) / 86_400_000L
}
