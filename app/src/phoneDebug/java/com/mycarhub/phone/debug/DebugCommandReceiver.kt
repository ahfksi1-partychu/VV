package com.mycarhub.phone.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.mycarhub.MyCarHubApplication
import com.mycarhub.assistant.core.IncomingMessage
import com.mycarhub.phone.conversation.ConversationTestHooks
import com.mycarhub.phone.conversation.PhoneConversation
import com.mycarhub.phone.drive.PhoneParking
import com.mycarhub.drive.CarItemStore
import com.mycarhub.drive.ParkingSpot
import com.mycarhub.drive.ParkingStore
import com.mycarhub.phone.drive.ParkingPrompt
import com.mycarhub.platform.Platform
import com.mycarhub.drive.TodoStore

/**
 * 개발 빌드(phoneDebug) 전용 ADB 명령 테스트. 릴리스·차량용 빌드에는 이 파일과 수신기 선언이 들어가지 않는다.
 * 셸(adb)만 보낼 수 있다 (android.permission.DUMP 필요).
 *
 * 문장을 음성 인식 결과처럼 [PhoneConversation]에 넣어 인식 이후의 실제 처리 경로를 그대로 돌린다.
 * 소리·마이크·권한 화면·앱 실행·대화방 열기·답장 전송은 하지 않고 기록만 한다 ([ConversationTestHooks]).
 * 결과는 logcat 태그 MyCarHubTest 로 남는다.
 *
 * ```
 * adb shell am broadcast -n com.mycarhub.phone/com.mycarhub.phone.debug.DebugCommandReceiver --es text "당근 열어줘"
 * adb shell am broadcast -n ... --es fake "com.towneers.www|당근|가짜 제목|가짜 내용|notification"   # 가짜 알림 추가 (message|notification)
 * adb shell am broadcast -n ... --ez reset true                                                       # 대화·가짜 알림 지우기
 * adb shell am broadcast -n ... --es car disconnect     # 차량 블루투스 끊김 흉내: 지금 위치를 주차 위치로 저장 (connect: 연결)
 * ```
 * 대화가 끝났으면 새 명령으로 시작하고, 되묻는 중이면 그 대답으로 넘긴다.
 */
class DebugCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as MyCarHubApplication
        val pending = goAsync()
        main.post {
            try {
                handle(app, intent)
            } finally {
                pending.finish()
            }
        }
    }

    private fun handle(app: MyCarHubApplication, intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_RESET, false)) {
            session?.release()
            session = null
            fakes.clear()
            Log.i(TAG, "RESET")
        }
        intent.getStringExtra(EXTRA_FAKE)?.let(::addFake)
        intent.getStringExtra(EXTRA_CAR)?.let { carBluetooth(app, it) }
        intent.getStringExtra(EXTRA_ITEMS)?.let { driveItems(app, it) }
        intent.getStringExtra(EXTRA_PARKING)?.let { parkingRecord(app, it) }
        Hooks.save = intent.getBooleanExtra(EXTRA_SAVE, false)
        val text = intent.getStringExtra(EXTRA_TEXT)?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val current = session
        if (current != null && current.state.phase == PhoneConversation.Phase.LISTENING && current.state.end == null) {
            Log.i(TAG, "HEAR step=${current.state.step} text=\"$text\"")
            current.hearForTest(text)
            return
        }
        current?.release()
        val conversation = PhoneConversation(app, app.wakeWords.model?.name, onUpdate = {}, testHooks = Hooks)
        session = conversation
        Log.i(TAG, "START text=\"$text\"")
        conversation.startForTest()
        conversation.hearForTest(text)
    }

    /**
     * 주차 상세 기록 테스트 도구 (좌표는 로그에 남기지 않는다).
     * - dump: 지금·이전 기록의 출처·위치 유무·메모·사진·동기화 표시와 안내 상태.
     * - car_remote / car_remote:nogps: 차량 앱이 감지한 주차가 동기화로 도착한 것처럼, 실제 동기화와 같은 경로
     *   (syncPayload → fromSyncPayload → applyRemote → Platform 안내)로 넣는다. 위치 있는 경우 고정 테스트 좌표(서울시청)를 쓴다.
     * - note:글 → 지금 기록에 구역 메모 저장 (기록 화면의 [저장]과 같은 함수).
     */
    private fun parkingRecord(app: MyCarHubApplication, command: String) {
        val store = ParkingStore(app)
        fun describe(spot: ParkingSpot?) = spot?.let {
            "${it.id.take(8)} ${it.source} loc=${it.hasLocation} note=${it.note} photo=${it.photo != null} preview=${it.photoPreview} " +
                "detailAt=${it.detailAt > 0} remote=${it.fromOtherDevice}"
        }
        when {
            command == "dump" -> {
                Log.i(TAG, "PARKING current=${describe(store.load())}")
                Log.i(TAG, "PARKING previous=${describe(store.previous())}")
                Log.i(TAG, "PARKING prompt=${ParkingPrompt.state(app)} open=${store.load()?.let { ParkingPrompt.isOpenFor(app, it) }}")
            }
            command.startsWith("car_remote") -> {
                val gps = !command.endsWith(":nogps")
                val now = System.currentTimeMillis()
                val car = ParkingSpot(
                    latitude = if (gps) 37.5665 else null, longitude = if (gps) 126.9780 else null, accuracyM = if (gps) 8f else null,
                    savedAt = now, address = null, source = ParkingSpot.Source.CAR_SIGNAL,
                )
                val received = store.fromSyncPayload(store.syncPayload(car), car.id)
                val applied = received != null && store.applyRemote(car.id, false, received, 0L)
                Log.i(TAG, "PARKING car_remote gps=$gps applied=$applied current=${describe(store.load())}")
                if (applied) store.load()?.let { Platform.onParkingFromOtherDevice(app, it) }
            }
            command.startsWith("note:") -> {
                val spot = store.load() ?: run {
                    Log.i(TAG, "PARKING note: no record")
                    return
                }
                val saved = store.updateDetail(spot.id, command.removePrefix("note:"), spot.photo)
                Log.i(TAG, "PARKING note saved=${saved != null} current=${describe(store.load())}")
            }
            else -> Log.w(TAG, "PARKING unknown (dump|car_remote[:nogps]|note:글)")
        }
    }

    /**
     * 차량 블루투스 연결·끊김 흉내: 실제 수신기와 같은 [PhoneParking] 흐름 (위치를 실제로 얻어 주차 위치로 저장하고 요약 알림을 띄운다).
     * 기기 주소 확인만 건너뛴다. 결과는 logcat PhoneParking·ParkingRecorder 태그.
     */
    private fun carBluetooth(app: MyCarHubApplication, event: String) {
        Log.i(TAG, "CAR $event")
        when (event) {
            "connect" -> PhoneParking.onCarConnected(app)
            "disconnect" -> PhoneParking.onCarDisconnected(app, null)
            else -> Log.w(TAG, "CAR unknown event (connect|disconnect)")
        }
    }

    /**
     * 차에 둔 물건 테스트 도구. dump: 물건·하차 후 할 일·주차 연결 상태를 로그로 (좌표·주소는 남기지 않음).
     * delete:이름 → 그 이름의 물건 기록(연결된 할 일 포함)을 지운다 (테스트로 만든 기록 정리용).
     * revert_parking → 마지막 주차 위치를 이전 주차 위치로 되돌린다 (테스트로 저장한 주차 정리용).
     */
    private fun driveItems(app: MyCarHubApplication, command: String) {
        val items = CarItemStore(app)
        val parking = ParkingStore(app)
        when {
            command == "dump" -> {
                val spot = parking.load()
                Log.i(TAG, "ITEMS parking=${spot?.let { "${it.id.take(8)} ${it.source} acc=${it.accuracyM?.toInt()} address=${it.address != null}" }}")
                for (item in items.items()) {
                    val todo = item.todoId?.let { id -> TodoStore(app).todos().firstOrNull { it.id == id } }
                    Log.i(
                        TAG,
                        "ITEM name=${item.name} spot=${item.spot} inCar=${item.inCar} pickup=${item.pickup} remind=${item.remind} " +
                            "parking=${item.parkingId?.take(8)} latest=${item.parkingId != null && item.parkingId == spot?.id} " +
                            "snapshot=${item.parking != null} todo=${todo?.title}/done=${todo?.done} vehicle=${item.vehicle}",
                    )
                }
                Log.i(TAG, "ITEMS remind=${items.toRemind().map { it.name }}")
            }
            command.startsWith("delete:") -> {
                val name = command.removePrefix("delete:")
                items.items().filter { it.name == name }.forEach { items.delete(it.id) }
                Log.i(TAG, "ITEMS deleted name=$name")
            }
            command == "revert_parking" -> {
                val spot = parking.load()
                Log.i(TAG, "ITEMS revert_parking ${spot != null && parking.revert(spot.savedAt)}")
            }
        }
    }

    /** "패키지|앱 이름|보낸 사람(제목)|내용|message 또는 notification" */
    private fun addFake(spec: String) {
        val parts = spec.split('|')
        if (parts.size < 5) {
            Log.w(TAG, "FAKE invalid spec")
            return
        }
        val message = IncomingMessage(
            key = "mycarhub-test:${fakes.size}",
            packageName = parts[0],
            appLabel = parts[1],
            sender = parts[2],
            text = parts[3],
            conversation = null,
            postedAt = System.currentTimeMillis(),
            // 답장 경로를 타지 않도록 가짜 알림은 답장할 수 없는 알림으로 만든다
            canReply = false,
        )
        fakes += ConversationTestHooks.TestNotification(message, isMessage = parts[4] == "message")
        Log.i(TAG, "FAKE added package=${parts[0]} kind=${parts[4]} total=${fakes.size}")
    }

    private object Hooks : ConversationTestHooks {
        @Volatile
        var save = false
        override val saveDriveData: Boolean get() = save
        override val extraMessages: List<ConversationTestHooks.TestNotification> get() = fakes.toList()
        override fun onSay(text: String) {
            Log.i(TAG, "SAY \"$text\"")
        }

        override fun onListen(step: PhoneConversation.Step) {
            Log.i(TAG, "LISTEN step=$step")
        }

        override fun onAction(action: String, detail: String) {
            Log.i(TAG, "ACTION $action $detail")
        }

        override fun onEnd(end: PhoneConversation.End?) {
            Log.i(TAG, "END $end")
        }
    }

    private companion object {
        const val TAG = "MyCarHubTest"
        const val EXTRA_TEXT = "text"
        const val EXTRA_FAKE = "fake"
        const val EXTRA_RESET = "reset"
        const val EXTRA_CAR = "car"
        const val EXTRA_ITEMS = "items"
        const val EXTRA_SAVE = "save"
        const val EXTRA_PARKING = "parking"

        val main = Handler(Looper.getMainLooper())

        // 테스트 동안만 메모리에 둔다 (앱이 끝나면 사라짐)
        var session: PhoneConversation? = null
        val fakes = java.util.concurrent.CopyOnWriteArrayList<ConversationTestHooks.TestNotification>()
    }
}
