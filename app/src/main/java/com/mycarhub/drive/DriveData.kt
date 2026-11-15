package com.mycarhub.drive

import android.content.Context
import com.mycarhub.assistant.core.ParkingCombine
import com.mycarhub.assistant.core.ParkingMerge
import com.mycarhub.assistant.core.ParkingNote
import com.mycarhub.assistant.core.ParkingPreview
import com.mycarhub.assistant.core.SyncMerge
import com.mycarhub.assistant.core.SyncMeta
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.UUID

/**
 * 주차 기록 하나. 위치는 실제로 얻은 값만 저장한다 (임의 위치를 만들지 않는다).
 * 지하 주차장 등에서 위치를 얻지 못하면 [latitude]·[longitude]가 null인 기록이 되고, 사용자가 구역 메모·사진을 남길 수 있다.
 * [accuracyM]은 위치 제공자가 알려준 오차 반경(m), [address]는 주소 변환에 성공했을 때만.
 * [id]는 주차 한 번마다 새로 만드는 값 (기기 간 동기화에서 같은 주차를 한 번만 저장).
 */
data class ParkingSpot(
    val latitude: Double?,
    val longitude: Double?,
    val accuracyM: Float?,
    val savedAt: Long,
    val address: String?,
    val source: Source,
    val id: String = UUID.randomUUID().toString(),
    /** 이 기록을 마지막으로 고친 시각 (주소가 늦게 채워지거나 메모·사진을 바꾸면 바뀐다). 동기화에서 같은 주차의 새 내용을 가리는 데 쓴다. */
    val updatedAt: Long = savedAt,
    /** 사용자가 입력한 주차 구역·번호 ("B구역 13번"). [ParkingNote.normalize]로 정리한 값 */
    val note: String? = null,
    /** 주차 사진 파일 이름 ([ParkingPhotos], 앱 전용 저장소) */
    val photo: String? = null,
    /** 사진이 다른 기기에서 받은 작은 미리보기뿐인지 (원본은 찍은 기기에만 있다) */
    val photoPreview: Boolean = false,
    /** 메모·사진을 마지막으로 바꾼 시각 (없으면 0). 두 기기의 같은 주차를 합칠 때 나중에 고친 쪽을 쓴다 */
    val detailAt: Long = 0L,
    /** 다른 기기(차량↔휴대폰)에서 받은 기록인지. 이 기기에서만 의미가 있어 보내지 않는다 */
    val fromOtherDevice: Boolean = false,
) {
    enum class Source {
        /** 차량 신호: 기어 P + 시동 끔 (차량용 앱) */
        CAR_SIGNAL,

        /** 휴대폰: 지정한 차량 블루투스 연결이 끊김 (폰 앱) */
        PHONE_BLUETOOTH,

        /** 사용자가 직접 "여기 주차했어" / [지금 위치 저장] */
        MANUAL,
    }

    val hasLocation: Boolean get() = latitude != null && longitude != null

    val hasDetail: Boolean get() = note != null || photo != null

    /** 차에 둔 물건 기록에 붙여 두는 사본: 사진 파일은 빼고 (물건 기록이 사진 파일을 붙잡지 않게) */
    fun snapshot(): ParkingSpot = copy(photo = null, photoPreview = false, fromOtherDevice = false)
}

/**
 * 마지막 주차 기록 1개 (+ 바로 이전 1개, 기기 안 SharedPreferences). 새 주차가 확인되면 덮어쓰고, 사용자가 지울 수 있다.
 * 동기화: 지우거나 되돌린 주차는 '삭제됨'으로 남기고([KEY_DELETED]), 바뀔 때마다 [DriveSyncState]에 알린다
 * (페어링 전에는 아무것도 쌓이지 않는다).
 */
class ParkingStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): ParkingSpot? = prefs.getString(KEY_SPOT, null)?.let { decode(it, local = true) }

    /** 바로 이전 주차 기록 (블루투스 끊김 되돌리기·같은 주차 합치기용) */
    fun previous(): ParkingSpot? = prefs.getString(KEY_PREVIOUS, null)?.let { decode(it, local = true) }

    /** 새 주차 위치를 저장한다. 다른 주차였으면 이전 위치를 하나 남겨 둔다 ([revert]). */
    fun save(spot: ParkingSpot) {
        synchronized(LOCK) {
            val editor = prefs.edit()
            val current = prefs.getString(KEY_SPOT, null)
            if (current != null && load()?.id != spot.id) editor.putString(KEY_PREVIOUS, current)
            editor.putString(KEY_SPOT, encode(spot, local = true)).apply()
        }
        DriveSyncState(appContext).markChanged(KIND, spot.id, spot.updatedAt)
        // 운전 중에 말한 '차에 둔 물건'을 이 주차에 연결한다 (이미 연결된 물건 기록은 그대로)
        CarItemStore(appContext).onParkingSaved(spot.snapshot())
        cleanPhotos()
        notifyChanged()
    }

    /**
     * 자동 감지(차량 신호·블루투스)한 주차를 저장한다. 지금 기록이 같은 주차를 다른 기기에서 저장한 것이고
     * 그쪽이 우선이면([ParkingMerge]: 차량 신호 우선) 새로 바꾸지 않고 빠진 위치만 채운다. 실제로 남은 기록을 돌려준다.
     */
    fun saveDetected(spot: ParkingSpot): ParkingSpot {
        val current = load()
        if (current != null && ParkingCombine.sameParking(mergeSpot(current), mergeSpot(spot)) &&
            !ParkingMerge.remoteWins(mergeSpot(current), mergeSpot(spot))
        ) {
            val combined = combine(kept = current, other = spot) ?: return current
            save(combined)
            return combined
        }
        val combined = current?.let { combine(kept = spot, other = it) } ?: spot
        save(combined)
        return combined
    }

    /**
     * 구역 메모·사진을 바꾼다 ([photo] null이면 사진 없음). [id]가 지금 기록이거나, 지금 기록이 그 주차를 다른 기기에서
     * 저장한 같은 주차이면 지금 기록에 저장한다. 그사이 다른 주차가 저장되었으면 저장하지 않고 null.
     */
    fun updateDetail(id: String, note: String?, photo: String?): ParkingSpot? {
        val updated = synchronized(LOCK) {
            val current = load() ?: return null
            val previous = previous()
            val sameEvent = current.id == id ||
                previous?.id == id && ParkingCombine.sameParking(mergeSpot(current), mergeSpot(previous))
            if (!sameEvent) return null
            val now = maxOf(System.currentTimeMillis(), current.updatedAt + 1)
            current.copy(
                note = ParkingNote.normalize(note),
                photo = photo,
                photoPreview = photo != null && photo == current.photo && current.photoPreview,
                detailAt = now,
                updatedAt = now,
            )
        }
        save(updated)
        return updated
    }

    /**
     * 위치 없이 저장된 지금 기록에 위치를 채운다 (사용자가 [지금 위치로 저장]을 눌렀을 때). 메모·사진은 그대로.
     * 지금 기록에 이미 위치가 있거나 [id]와 다르면 null.
     */
    fun fillLocation(id: String, latitude: Double, longitude: Double, accuracyM: Float?): ParkingSpot? {
        val updated = synchronized(LOCK) {
            val current = load()?.takeIf { it.id == id && !it.hasLocation } ?: return null
            current.copy(
                latitude = latitude, longitude = longitude, accuracyM = accuracyM,
                updatedAt = maxOf(System.currentTimeMillis(), current.updatedAt + 1),
            )
        }
        save(updated)
        return updated
    }

    /**
     * 잘못 저장된 주차를 되돌린다 (운전 중 블루투스가 잠깐 끊겼다가 곧 다시 연결된 경우).
     * 지금 위치가 [savedAt]에 저장한 것일 때만 이전 위치로 바꾼다. 이전 위치가 없으면 지운다.
     */
    fun revert(savedAt: Long): Boolean {
        val reverted = synchronized(LOCK) {
            val spot = load()?.takeIf { it.savedAt == savedAt } ?: return false
            val previous = prefs.getString(KEY_PREVIOUS, null)
            val editor = prefs.edit().remove(KEY_PREVIOUS)
            if (previous != null) editor.putString(KEY_SPOT, previous) else editor.remove(KEY_SPOT)
            editor.apply()
            tombstone(spot.id, System.currentTimeMillis())
        }
        DriveSyncState(appContext).markChanged(KIND, reverted.first, reverted.second)
        CarItemStore(appContext).onParkingReverted(reverted.first)
        cleanPhotos()
        notifyChanged()
        return true
    }

    /** 주소 변환이 늦게 끝났을 때: 그사이 새 주차로 바뀌지 않았으면 주소만 채운다. */
    fun setAddress(savedAt: Long, address: String) {
        val spot = load() ?: return
        if (spot.savedAt == savedAt) save(spot.copy(address = address, updatedAt = maxOf(System.currentTimeMillis(), spot.updatedAt + 1)))
    }

    fun clear() {
        val removed = synchronized(LOCK) {
            val spot = load()
            prefs.edit().remove(KEY_SPOT).remove(KEY_PREVIOUS).apply()
            spot?.let { tombstone(it.id, System.currentTimeMillis()) }
        }
        removed?.let { DriveSyncState(appContext).markChanged(KIND, it.first, it.second) }
        cleanPhotos()
        notifyChanged()
    }

    // region 동기화 (페어링한 뒤 DriveSyncManager가 쓴다)

    /** 이 주차 기록의 현재 상태: 지금 위치이면 수정 시각, 지운 것이면 삭제 시각. 모르면 null. */
    fun metaOf(id: String, origin: String): SyncMeta? {
        load()?.takeIf { it.id == id }?.let { return SyncMeta(id, it.updatedAt, deleted = false, origin = origin) }
        return tombstones()[id]?.let { SyncMeta(id, it, deleted = true, origin = origin) }
    }

    /**
     * 다른 기기에서 받은 주차 기록을 적용한다. 지운 기록이면 지금 위치가 그것일 때만 지운다.
     * 새 주차는 [ParkingMerge] 규칙(최신 우선, 같은 주차면 차량 신호 우선)으로 지금 위치와 비교한다.
     * 같은 주차를 두 기기가 따로 저장한 경우에는 고른 기록에 다른 쪽의 위치·메모·사진을 채워 넣고([ParkingCombine])
     * 그 합친 기록을 다시 보낸다 (두 기기가 같은 내용으로 모인다). 적용했으면 true.
     */
    fun applyRemote(id: String, deleted: Boolean, spot: ParkingSpot?, deletedAt: Long): Boolean {
        var pushBack: ParkingSpot? = null
        val applied = synchronized(LOCK) {
            val current = load()
            if (deleted) {
                tombstone(id, deletedAt)
                if (current?.id != id) return@synchronized false
                prefs.edit().remove(KEY_SPOT).apply()
                return@synchronized true
            }
            if (spot == null || id in tombstones()) return@synchronized false
            // 같은 주차의 새 내용 (예: 주소가 나중에 채워짐, 메모·사진 수정)
            if (current?.id == id) {
                if (spot.updatedAt <= current.updatedAt) return@synchronized false
                prefs.edit().putString(KEY_SPOT, encode(spot.copy(id = id, fromOtherDevice = current.fromOtherDevice), local = true)).apply()
                return@synchronized true
            }
            val wins = ParkingMerge.remoteWins(current?.let(::mergeSpot), mergeSpot(spot))
            if (wins) {
                val combined = current?.let { combine(kept = spot, other = it) }
                val editor = prefs.edit()
                if (current != null) editor.putString(KEY_PREVIOUS, encode(current, local = true))
                editor.putString(KEY_SPOT, encode(combined ?: spot, local = true)).apply()
                pushBack = combined
                true
            } else {
                // 지금 기록이 이기지만 같은 주차면 받은 쪽의 위치·메모·사진을 채운다
                val combined = current?.let { combine(kept = it, other = spot) } ?: return@synchronized false
                prefs.edit().putString(KEY_SPOT, encode(combined, local = true)).apply()
                pushBack = combined
                true
            }
        }
        pushBack?.let { DriveSyncState(appContext).markChanged(KIND, it.id, it.updatedAt) }
        cleanPhotos()
        if (applied) notifyChanged()
        return applied
    }

    /** 물건 기록에 붙일 주차 사본 (사진·미리보기 없음) */
    fun payload(spot: ParkingSpot): String = encode(spot.snapshot(), local = false)

    /** 동기화로 보낼 내용: 사진이 있으면 작은 미리보기(JPEG)를 함께 넣는다. 기기 안에서 암호화한 뒤 보낸다. */
    fun syncPayload(spot: ParkingSpot): String {
        val json = JSONObject(encode(spot.snapshot(), local = false))
        ParkingPhotos.previewFor(appContext, spot.photo)?.let { json.put("thumb", Base64.getEncoder().encodeToString(it)) }
        return json.toString()
    }

    /** 페어링 직후 처음 보낼 기록: 지금 주차 위치와 삭제 표시 (id, 수정 시각) */
    fun syncIds(): List<Pair<String, Long>> =
        listOfNotNull(load()?.let { it.id to it.updatedAt }) + tombstones().map { it.key to it.value }

    /** 물건 기록의 주차 사본 읽기 (사진 정보는 읽지 않는다) */
    fun fromPayload(json: String): ParkingSpot? = decode(json, local = false)

    /** 다른 기기에서 받은 주차 기록: 미리보기가 있으면 기기 안에 저장한다 (받은 파일 이름·경로는 쓰지 않는다). */
    fun fromSyncPayload(json: String, id: String): ParkingSpot? {
        val spot = decode(json, local = false) ?: return null
        val thumb = try {
            JSONObject(json).optString("thumb").takeIf { it.isNotEmpty() }?.let { Base64.getDecoder().decode(it) }
        } catch (e: IllegalArgumentException) {
            null
        }
        val photo = thumb?.let { ParkingPhotos.saveRemotePreview(appContext, id, it) }
        return spot.copy(id = id, photo = photo, photoPreview = photo != null, fromOtherDevice = true)
    }

    private fun mergeSpot(spot: ParkingSpot) =
        ParkingMerge.Spot(spot.id, spot.savedAt, ParkingMerge.Source.valueOf(spot.source.name))

    private fun facts(spot: ParkingSpot) = ParkingCombine.Facts(mergeSpot(spot), spot.hasLocation, spot.hasDetail, spot.detailAt)

    /** [kept]에 같은 주차인 [other]의 빠진 위치·더 나중의 메모·사진을 채운 새 기록. 가져올 것이 없으면 null. */
    private fun combine(kept: ParkingSpot, other: ParkingSpot): ParkingSpot? {
        val take = ParkingCombine.plan(facts(kept), facts(other))
        if (!take.any) return null
        var result = kept
        if (take.location) {
            result = result.copy(
                latitude = other.latitude, longitude = other.longitude, accuracyM = other.accuracyM,
                address = other.address ?: result.address,
            )
        }
        if (take.detail) {
            result = result.copy(note = other.note, photo = other.photo, photoPreview = other.photoPreview, detailAt = other.detailAt)
        }
        return result.copy(updatedAt = maxOf(System.currentTimeMillis(), maxOf(kept.updatedAt, other.updatedAt) + 1))
    }

    /** 지금·이전 기록이 쓰지 않는 사진 정리 */
    private fun cleanPhotos() {
        val keep = listOfNotNull(load()?.photo, previous()?.photo).toSet()
        runCatching { ParkingPhotos.cleanUp(appContext, keep) }
    }

    /** 삭제됨 표시를 남긴다 (오래된 것은 정리). 반환: (id, 삭제 시각) */
    private fun tombstone(id: String, at: Long): Pair<String, Long> {
        val map = tombstones().filterValues { at - it < TOMBSTONE_KEEP_MS } + (id to at)
        prefs.edit().putString(KEY_DELETED, JSONObject(map.mapValues { it.value }).toString()).apply()
        return id to at
    }

    private fun tombstones(): Map<String, Long> = try {
        val o = JSONObject(prefs.getString(KEY_DELETED, null) ?: return emptyMap())
        o.keys().asSequence().associateWith { o.getLong(it) }
    } catch (e: Exception) {
        emptyMap()
    }

    // endregion

    /**
     * [local]: 이 기기 prefs에 저장한 값 (사진 파일 이름·받은 기록 표시를 읽는다).
     * 다른 기기에서 받은 값은 local=false로 읽어 사진 파일 이름을 절대 쓰지 않는다.
     */
    private fun decode(json: String, local: Boolean): ParkingSpot? = try {
        val o = JSONObject(json)
        val savedAt = o.getLong("at")
        val hasLocation = o.has("lat") && o.has("lng")
        val photo = if (local) o.optString("photo").takeIf { ParkingPreview.isSafeFileName(it) } else null
        ParkingSpot(
            latitude = if (hasLocation) o.getDouble("lat") else null,
            longitude = if (hasLocation) o.getDouble("lng") else null,
            accuracyM = if (hasLocation && o.has("acc")) o.getDouble("acc").toFloat() else null,
            savedAt = savedAt,
            address = o.optString("address").takeIf { it.isNotEmpty() },
            source = ParkingSpot.Source.valueOf(o.getString("source")),
            // id가 없던 이전 기록: 저장 시각으로 고정된 id를 쓴다
            id = o.optString("id").ifEmpty { "spot-$savedAt" },
            updatedAt = if (o.has("upd")) o.getLong("upd") else savedAt,
            note = ParkingNote.normalize(o.optString("note").takeIf { it.isNotEmpty() }),
            photo = photo,
            photoPreview = photo != null && o.optBoolean("pv"),
            detailAt = o.optLong("dat", 0L),
            fromOtherDevice = local && o.optBoolean("remote"),
        )
    } catch (e: Exception) {
        null
    }

    private fun encode(spot: ParkingSpot, local: Boolean): String {
        val o = JSONObject()
            .put("id", spot.id)
            .put("at", spot.savedAt)
            .put("source", spot.source.name)
        if (spot.latitude != null && spot.longitude != null) {
            o.put("lat", spot.latitude).put("lng", spot.longitude)
            spot.accuracyM?.let { o.put("acc", it.toDouble()) }
        }
        if (spot.updatedAt != spot.savedAt) o.put("upd", spot.updatedAt)
        spot.address?.let { o.put("address", it) }
        spot.note?.let { o.put("note", it) }
        if (spot.detailAt > 0) o.put("dat", spot.detailAt)
        if (local) {
            spot.photo?.let { o.put("photo", it) }
            if (spot.photoPreview) o.put("pv", true)
            if (spot.fromOtherDevice) o.put("remote", true)
        }
        return o.toString()
    }

    companion object {
        /** 동기화 레코드 종류 */
        const val KIND = "parking"

        private const val PREFS = "drive_parking"
        private const val KEY_SPOT = "last_spot"
        private const val KEY_PREVIOUS = "previous_spot"
        private const val KEY_DELETED = "deleted"
        private const val TOMBSTONE_KEEP_MS = 30L * 24 * 60 * 60 * 1000
        private val LOCK = Any()

        private val listeners = mutableSetOf<() -> Unit>()

        fun addListener(listener: () -> Unit) = synchronized(listeners) { listeners += listener }
        fun removeListener(listener: () -> Unit) = synchronized(listeners) { listeners -= listener }
        internal fun notifyChanged() = synchronized(listeners) { listeners.toList() }.forEach { it() }
    }
}

/** 할 일 하나. [id]는 기기 간에 옮겨도 겹치지 않는 값 (중복 저장 방지). */
data class TodoItem(
    val id: String,
    val title: String,
    val note: String?,
    val dueAt: Long?,
    val hasTime: Boolean,
    val placeKind: String?,
    val placeName: String?,
    val source: Source,
    /** 관련 앱 (알림에서 만든 할 일) */
    val appPackage: String?,
    /** 관련 알림 키 (알림이 남아 있으면 그 메시지를 연다). 내용은 저장하지 않는다. 이 기기에서만 의미가 있어 동기화하지 않는다. */
    val notificationKey: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val done: Boolean,
    /** 마지막으로 바꾼 기기 ([DriveSyncState.deviceId]). 같은 시각에 양쪽에서 바꿨을 때 같은 결론을 내는 데 쓴다. */
    val origin: String = "",
    /** 차량에 남긴 물건의 하차 후 할 일이면 그 물건 기록 id ([CarItemStore]). 완료·삭제가 서로 맞춰진다. */
    val carItemId: String? = null,
) {
    enum class Source { VOICE_CAR, VOICE_PHONE, SMS, KAKAO, MANUAL }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }
}

/** 알림에서 만든 할 일 후보 (사용자가 [추가]해야 할 일이 된다). 메시지 본문은 저장하지 않는다. 동기화하지 않는다. */
data class TodoCandidate(
    val id: String,
    val title: String,
    val kind: String,
    val source: TodoItem.Source,
    val appPackage: String?,
    val notificationKey: String?,
    val createdAt: Long,
)

/**
 * 할 일·후보 목록 (기기 안에만, SharedPreferences JSON). 메인 스레드·백그라운드 어디서 불러도 된다.
 * 동기화 준비: 지운 할 일은 '삭제됨'([KEY_DELETED], 30일)으로 남기고, 바뀔 때마다 [DriveSyncState]에 알린다
 * (페어링 전에는 아무것도 쌓이지 않는다).
 */
class TodoStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val sync = DriveSyncState(appContext)

    fun todos(): List<TodoItem> = synchronized(LOCK) { readTodos() }

    fun openTodos(): List<TodoItem> = todos().filter { !it.done }

    fun add(item: TodoItem): Boolean = synchronized(LOCK) {
        val list = readTodos()
        // 같은 id(다른 기기에서 받은 것)·지운 id 또는 짧은 시간 안의 같은 제목은 다시 넣지 않는다
        if (list.any { it.id == item.id } || item.id in readTombstones()) return false
        if (list.any { !it.done && it.title == item.title && item.createdAt - it.createdAt in 0..DUPLICATE_WINDOW_MS }) return false
        writeTodos(list + item.copy(origin = sync.deviceId))
        true
    }.also {
        if (it) {
            sync.markChanged(KIND, item.id, item.updatedAt)
            notifyChanged()
        }
    }

    fun update(item: TodoItem) {
        val now = System.currentTimeMillis()
        val before = synchronized(LOCK) {
            val list = readTodos()
            writeTodos(list.map { if (it.id == item.id) item.copy(updatedAt = now, origin = sync.deviceId) else it })
            list.firstOrNull { it.id == item.id }
        }
        sync.markChanged(KIND, item.id, now)
        // 차에서 물건 가져오기 할 일을 완료·되돌리면 물건 기록도 '가져왔어요'·차에 있음으로 맞춘다
        if (item.carItemId != null && before != null && before.done != item.done) {
            CarItemStore(appContext).setRetrieved(item.carItemId, item.done)
        }
        notifyChanged()
    }

    fun delete(id: String) {
        val now = System.currentTimeMillis()
        val removed = synchronized(LOCK) {
            val list = readTodos()
            writeTodos(list.filter { it.id != id })
            writeTombstones(readTombstones() + (id to Tombstone(now, sync.deviceId)))
            list.firstOrNull { it.id == id }
        }
        sync.markChanged(KIND, id, now)
        removed?.carItemId?.let { CarItemStore(appContext).onTodoDeleted(it) }
        notifyChanged()
    }

    fun candidates(): List<TodoCandidate> = synchronized(LOCK) { readCandidates() }

    /** 같은 앱·같은 제목의 후보가 최근에 이미 있거나 같은 할 일이 열려 있으면 넣지 않는다. */
    fun addCandidate(candidate: TodoCandidate): Boolean = synchronized(LOCK) {
        val list = readCandidates()
        if (list.any { it.appPackage == candidate.appPackage && it.title == candidate.title && candidate.createdAt - it.createdAt < CANDIDATE_WINDOW_MS }) {
            return false
        }
        if (readTodos().any { !it.done && it.title == candidate.title }) return false
        writeCandidates((list + candidate).takeLast(MAX_CANDIDATES))
        true
    }.also { if (it) notifyChanged() }

    fun removeCandidate(id: String) {
        synchronized(LOCK) { writeCandidates(readCandidates().filter { it.id != id }) }
        notifyChanged()
    }

    /** 후보를 할 일로 옮긴다. */
    fun acceptCandidate(candidate: TodoCandidate) {
        val now = System.currentTimeMillis()
        val id = TodoItem.newId()
        synchronized(LOCK) {
            writeCandidates(readCandidates().filter { it.id != candidate.id })
            writeTodos(
                readTodos() + TodoItem(
                    id, candidate.title, null, null, false, null, null, candidate.source,
                    candidate.appPackage, candidate.notificationKey, now, now, done = false, origin = sync.deviceId,
                ),
            )
        }
        sync.markChanged(KIND, id, now)
        notifyChanged()
    }

    /** 설정의 [모든 할 일 데이터 지우기]: 할 일·후보를 지운다. 지운 할 일은 동기화를 위해 '삭제됨'으로 남긴다. */
    fun clearAll() {
        val now = System.currentTimeMillis()
        val removed = synchronized(LOCK) {
            val ids = readTodos().map { it.id }
            writeTombstones(readTombstones() + ids.associateWith { Tombstone(now, sync.deviceId) })
            prefs.edit().remove(KEY_TODOS).remove(KEY_CANDIDATES).apply()
            ids
        }
        removed.forEach { sync.markChanged(KIND, it, now) }
        notifyChanged()
    }

    // region 동기화 (페어링한 뒤 DriveSyncManager가 쓴다)

    /** 페어링 직후 처음 보낼 기록: 모든 할 일과 삭제 표시 */
    fun syncIds(): List<Pair<String, Long>> = synchronized(LOCK) {
        readTodos().map { it.id to it.updatedAt } + readTombstones().map { it.key to it.value.at }
    }

    /** 이 할 일의 현재 상태 (있으면 수정 시각, 지웠으면 삭제 시각). 모르면 null. */
    fun metaOf(id: String): SyncMeta? = synchronized(LOCK) {
        readTodos().firstOrNull { it.id == id }?.let { return SyncMeta(id, it.updatedAt, deleted = false, origin = it.origin) }
        readTombstones()[id]?.let { SyncMeta(id, it.at, deleted = true, origin = it.origin) }
    }

    /**
     * 다른 기기에서 받은 할 일 변경을 적용한다 ([SyncMerge]: 더 나중 변경이 이김, 같은 시각이면 삭제 우선).
     * 같은 변경을 여러 번 받아도 결과가 같다. 적용했으면 true. 받은 변경은 다시 보내지 않는다.
     */
    fun applyRemote(meta: SyncMeta, item: TodoItem?): Boolean {
        val applied = synchronized(LOCK) {
            if (!SyncMerge.remoteWins(metaOf(meta.id), meta)) return@synchronized false
            val others = readTodos().filter { it.id != meta.id }
            if (meta.deleted) {
                writeTodos(others)
                writeTombstones(readTombstones() + (meta.id to Tombstone(meta.updatedAt, meta.origin)))
                return@synchronized true
            }
            if (item == null) return@synchronized false
            writeTombstones(readTombstones() - meta.id)
            // 알림 키는 이 기기의 것이 아니므로 받지 않는다 (같은 할 일이 이미 있으면 이 기기의 키를 유지)
            val key = readTodos().firstOrNull { it.id == meta.id }?.notificationKey
            writeTodos(others + item.copy(id = meta.id, updatedAt = meta.updatedAt, origin = meta.origin, notificationKey = key))
            true
        }
        if (applied) notifyChanged()
        return applied
    }

    /** 동기화로 보낼 내용 (알림 키 제외, 기기 안에서 암호화한 뒤 보낸다) */
    fun payload(item: TodoItem): String = encode(item.copy(notificationKey = null)).toString()

    fun fromPayload(json: String): TodoItem? = try {
        decode(JSONObject(json))
    } catch (e: Exception) {
        null
    }

    // endregion

    private fun readTodos(): List<TodoItem> = parse(prefs.getString(KEY_TODOS, null), ::decode)

    private fun decode(o: JSONObject) = TodoItem(
        id = o.getString("id"),
        title = o.getString("title"),
        note = o.optString("note").takeIf { it.isNotEmpty() },
        dueAt = if (o.has("due")) o.getLong("due") else null,
        hasTime = o.optBoolean("hasTime"),
        placeKind = o.optString("placeKind").takeIf { it.isNotEmpty() },
        placeName = o.optString("placeName").takeIf { it.isNotEmpty() },
        source = TodoItem.Source.valueOf(o.getString("source")),
        appPackage = o.optString("app").takeIf { it.isNotEmpty() },
        notificationKey = o.optString("key").takeIf { it.isNotEmpty() },
        createdAt = o.getLong("created"),
        updatedAt = o.getLong("updated"),
        done = o.optBoolean("done"),
        origin = o.optString("origin"),
        carItemId = o.optString("carItem").takeIf { it.isNotEmpty() },
    )

    private fun encode(t: TodoItem): JSONObject {
        val o = JSONObject()
            .put("id", t.id).put("title", t.title).put("hasTime", t.hasTime).put("source", t.source.name)
            .put("created", t.createdAt).put("updated", t.updatedAt).put("done", t.done)
        t.note?.let { o.put("note", it) }
        t.dueAt?.let { o.put("due", it) }
        t.placeKind?.let { o.put("placeKind", it) }
        t.placeName?.let { o.put("placeName", it) }
        t.appPackage?.let { o.put("app", it) }
        t.notificationKey?.let { o.put("key", it) }
        if (t.origin.isNotEmpty()) o.put("origin", t.origin)
        t.carItemId?.let { o.put("carItem", it) }
        return o
    }

    private fun writeTodos(list: List<TodoItem>) {
        val array = JSONArray()
        list.forEach { array.put(encode(it)) }
        prefs.edit().putString(KEY_TODOS, array.toString()).apply()
    }

    private data class Tombstone(val at: Long, val origin: String)

    private fun readTombstones(): Map<String, Tombstone> = try {
        val o = JSONObject(prefs.getString(KEY_DELETED, null) ?: "{}")
        o.keys().asSequence().associateWith { id ->
            val t = o.getJSONObject(id)
            Tombstone(t.getLong("at"), t.optString("origin"))
        }
    } catch (e: Exception) {
        emptyMap()
    }

    /** 30일 지난 삭제 표시는 정리한다 (서버에도 삭제 레코드가 남아 옛 데이터가 되살아나지 않는다). */
    private fun writeTombstones(map: Map<String, Tombstone>) {
        val now = System.currentTimeMillis()
        val o = JSONObject()
        map.filterValues { now - it.at < TOMBSTONE_KEEP_MS }.forEach { (id, t) ->
            o.put(id, JSONObject().put("at", t.at).put("origin", t.origin))
        }
        prefs.edit().putString(KEY_DELETED, o.toString()).apply()
    }

    private fun readCandidates(): List<TodoCandidate> = parse(prefs.getString(KEY_CANDIDATES, null)) { o ->
        TodoCandidate(
            id = o.getString("id"),
            title = o.getString("title"),
            kind = o.getString("kind"),
            source = TodoItem.Source.valueOf(o.getString("source")),
            appPackage = o.optString("app").takeIf { it.isNotEmpty() },
            notificationKey = o.optString("key").takeIf { it.isNotEmpty() },
            createdAt = o.getLong("created"),
        )
    }

    private fun writeCandidates(list: List<TodoCandidate>) {
        val array = JSONArray()
        for (c in list) {
            val o = JSONObject().put("id", c.id).put("title", c.title).put("kind", c.kind).put("source", c.source.name).put("created", c.createdAt)
            c.appPackage?.let { o.put("app", it) }
            c.notificationKey?.let { o.put("key", it) }
            array.put(o)
        }
        prefs.edit().putString(KEY_CANDIDATES, array.toString()).apply()
    }

    private fun <T> parse(json: String?, read: (JSONObject) -> T): List<T> {
        if (json == null) return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i -> runCatching { read(array.getJSONObject(i)) }.getOrNull() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    companion object {
        /** 동기화 레코드 종류 */
        const val KIND = "todo"

        private const val PREFS = "drive_todos"
        private const val KEY_TODOS = "todos"
        private const val KEY_CANDIDATES = "candidates"
        private const val KEY_DELETED = "deleted"
        private const val DUPLICATE_WINDOW_MS = 10 * 60 * 1000L
        private const val CANDIDATE_WINDOW_MS = 12 * 60 * 60 * 1000L
        private const val TOMBSTONE_KEEP_MS = 30L * 24 * 60 * 60 * 1000
        private const val MAX_CANDIDATES = 30
        private val LOCK = Any()

        private val listeners = mutableSetOf<() -> Unit>()

        fun addListener(listener: () -> Unit) = synchronized(listeners) { listeners += listener }
        fun removeListener(listener: () -> Unit) = synchronized(listeners) { listeners -= listener }
        private fun notifyChanged() = synchronized(listeners) { listeners.toList() }.forEach { it() }
    }
}

/** 사용자가 지정한 장소 (집·회사·마트 등). 위치는 사용자가 [현재 위치로 지정]을 눌렀을 때 얻은 값만. */
data class SavedPlace(val name: String, val kind: String, val latitude: Double, val longitude: Double)

class SavedPlaces(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(): List<SavedPlace> {
        val json = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                SavedPlace(o.getString("name"), o.getString("kind"), o.getDouble("lat"), o.getDouble("lng"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 같은 이름이 있으면 위치를 바꾼다. */
    fun put(place: SavedPlace) = write(all().filter { it.name != place.name } + place)

    fun remove(name: String) = write(all().filter { it.name != name })

    fun clear() = prefs.edit().clear().apply()

    /** 할 일의 장소 조건과 맞는 장소: 집·회사는 종류로, 나머지는 이름으로. */
    fun find(kind: String?, name: String?): SavedPlace? = all().firstOrNull {
        (kind == KIND_HOME || kind == KIND_WORK) && it.kind == kind || name != null && it.name == name
    }

    private fun write(list: List<SavedPlace>) {
        val array = JSONArray()
        list.forEach { array.put(JSONObject().put("name", it.name).put("kind", it.kind).put("lat", it.latitude).put("lng", it.longitude)) }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    companion object {
        private const val PREFS = "drive_places"
        private const val KEY = "places"
        const val KIND_HOME = "HOME"
        const val KIND_WORK = "WORK"
        const val KIND_OTHER = "OTHER"
    }
}
