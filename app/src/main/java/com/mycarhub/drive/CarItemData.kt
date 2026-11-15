package com.mycarhub.drive

import android.content.Context
import com.mycarhub.assistant.core.CarItemLink
import com.mycarhub.assistant.core.CarItems
import com.mycarhub.assistant.core.CarSpot
import com.mycarhub.assistant.core.SyncMerge
import com.mycarhub.assistant.core.SyncMeta
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 차량에 남긴 물건 기록. 차량 센서로 확인한 것이 아니라 사용자가 말한(입력한) 것을 기록한 것이다.
 *
 * - [parkingId]·[parking]: 이 물건을 두었을 때의 주차 기록 (그 주차의 사본을 함께 보관해, 새 주차가 저장되어도 남는다).
 *   운전 중에 말한 물건은 null로 두었다가 다음 주차가 저장될 때 연결한다 ([CarItemLink]).
 * - [pickup]: 사용자가 '챙겨야 해', '알려줘'라고 한 물건. 연결된 하차 후 할 일([todoId])이 있다.
 * - [remind]: 하차 후 알림을 띄울지 (사용자가 끌 수 있다). [pickup]이 아닌 물건은 알리지 않는다.
 * - [retrievedAt]: '가져왔어요' 처리한 시각 (null이면 아직 차에 있다고 기록된 상태).
 */
data class CarItem(
    val id: String,
    val name: String,
    val spot: CarSpot?,
    val vehicle: String?,
    val source: TodoItem.Source,
    val pickup: Boolean,
    val remind: Boolean,
    val parkingId: String?,
    val parking: ParkingSpot?,
    val todoId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val retrievedAt: Long?,
    val origin: String = "",
) {
    val inCar: Boolean get() = retrievedAt == null

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }
}

/**
 * 차량에 남긴 물건 목록 (기기 안에만, prefs "drive_car_items"). 지운 물건은 동기화를 위해 '삭제됨'(30일)으로 남긴다.
 * 챙길 물건은 하차 후 할 일([TodoStore], [TodoItem.carItemId])과 연결되어, 한쪽을 완료하면 다른 쪽도 완료된다.
 */
class CarItemStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val sync = DriveSyncState(appContext)

    fun items(): List<CarItem> = synchronized(LOCK) { read() }

    fun inCar(): List<CarItem> = items().filter { it.inCar }

    fun get(id: String): CarItem? = items().firstOrNull { it.id == id }

    /** 차에 있다고 기록된 물건 중 이름이 맞는 가장 최근 것 */
    fun find(name: String): CarItem? = inCar().filter { CarItems.matches(it.name, name) }.maxByOrNull { it.createdAt }

    /**
     * 물건을 기록한다. 같은 이름의 물건이 이미 차에 있다고 기록되어 있으면 새로 만들지 않고 그 기록을 돌려준다
     * (그때 '챙겨야 해'가 새로 붙었으면 챙길 물건으로 바꾼다). [driving]이면 지금 주차 위치에 연결하지 않는다.
     */
    fun register(name: String, spot: CarSpot?, pickup: Boolean, source: TodoItem.Source, vehicle: String?, driving: Boolean): Registered {
        find(name)?.let { existing ->
            if (pickup && !existing.pickup) return Registered(setPickup(existing.id, true) ?: existing, isNew = false)
            return Registered(existing, isNew = false)
        }
        val now = System.currentTimeMillis()
        val latest = ParkingStore(appContext).load()
        val parkingId = CarItemLink.onRegister(driving, latest?.id)
        var item = CarItem(
            id = CarItem.newId(), name = name, spot = spot, vehicle = vehicle, source = source,
            pickup = pickup, remind = pickup, parkingId = parkingId, parking = latest?.takeIf { parkingId != null },
            todoId = null, createdAt = now, updatedAt = now, retrievedAt = null, origin = sync.deviceId,
        )
        if (pickup) item = item.copy(todoId = addPickupTodo(item))
        synchronized(LOCK) { write(read() + item) }
        sync.markChanged(KIND, item.id, item.updatedAt)
        notifyChanged()
        return Registered(item, isNew = true)
    }

    data class Registered(val item: CarItem, val isNew: Boolean)

    /** 챙길 물건으로 바꾸거나(하차 후 할 일을 만든다) 되돌린다. */
    fun setPickup(id: String, pickup: Boolean): CarItem? {
        val item = get(id) ?: return null
        if (item.pickup == pickup) return item
        val todoId = if (pickup) item.todoId ?: addPickupTodo(item) else item.todoId
        return change(id) { it.copy(pickup = pickup, remind = pickup, todoId = todoId) }
    }

    /** 하차 후 알림 켜기·끄기 (챙길 물건만) */
    fun setRemind(id: String, remind: Boolean) = change(id) { it.copy(remind = remind && it.pickup) }

    /** '가져왔어요' / 되돌리기. 연결된 하차 후 할 일도 완료·미완료로 맞춘다. */
    fun setRetrieved(id: String, retrieved: Boolean): CarItem? {
        val item = get(id) ?: return null
        if (item.inCar != retrieved) return item
        val changed = change(id) { it.copy(retrievedAt = if (retrieved) System.currentTimeMillis() else null) }
        item.todoId?.let { todoId ->
            val todos = TodoStore(appContext)
            todos.todos().firstOrNull { it.id == todoId && it.done != retrieved }?.let { todos.update(it.copy(done = retrieved)) }
        }
        return changed
    }

    fun rename(id: String, name: String, spot: CarSpot?) = change(id) { it.copy(name = name, spot = spot) }

    /** 기록을 지운다. 연결된 하차 후 할 일도 지운다 (지운 기록은 동기화를 위해 '삭제됨'으로 남김). */
    fun delete(id: String) {
        val item = get(id) ?: return
        val now = System.currentTimeMillis()
        synchronized(LOCK) {
            write(read().filter { it.id != id })
            writeTombstones(readTombstones() + (id to Tombstone(now, sync.deviceId)))
        }
        item.todoId?.let { TodoStore(appContext).delete(it) }
        sync.markChanged(KIND, id, now)
        notifyChanged()
    }

    /** 새 주차가 저장됨: 아직 주차와 연결되지 않은 '차에 있는' 물건을 이 주차에 연결하고, 이미 이 주차에 연결된 물건은 사본(주소 등)을 고친다. */
    fun onParkingSaved(spot: ParkingSpot) {
        val now = System.currentTimeMillis()
        val changed = synchronized(LOCK) {
            val list = read()
            val ids = mutableListOf<String>()
            val next = list.map { item ->
                when {
                    item.parkingId == spot.id && item.parking != spot -> item.copy(parking = spot).also { ids += item.id }
                    CarItemLink.linkOnPark(item.parkingId, item.inCar, item.createdAt, spot.savedAt) ->
                        item.copy(parkingId = spot.id, parking = spot, updatedAt = now, origin = sync.deviceId).also { ids += item.id }
                    else -> item
                }
            }
            if (ids.isNotEmpty()) write(next)
            next.filter { it.id in ids }
        }
        changed.forEach { sync.markChanged(KIND, it.id, it.updatedAt) }
        if (changed.isNotEmpty()) notifyChanged()
    }

    /** 잘못 저장된 주차를 되돌림: 그 주차에 자동으로 연결된 물건은 다시 '다음 주차에 연결' 상태로 */
    fun onParkingReverted(parkingId: String) {
        val now = System.currentTimeMillis()
        val changed = synchronized(LOCK) {
            val list = read()
            val hit = list.filter { it.parkingId == parkingId && it.inCar }.map { it.id }.toSet()
            if (hit.isNotEmpty()) write(list.map { if (it.id in hit) it.copy(parkingId = null, parking = null, updatedAt = now, origin = sync.deviceId) else it })
            hit
        }
        changed.forEach { sync.markChanged(KIND, it, now) }
        if (changed.isNotEmpty()) notifyChanged()
    }

    /** 하차 후 알릴 물건: 챙길 물건이고 알림이 켜져 있고 아직 차에 있다고 기록된 것 */
    fun toRemind(): List<CarItem> = inCar().filter { it.pickup && it.remind }

    // region 동기화 (페어링한 뒤 DriveSyncManager가 쓴다)

    /** 페어링 직후 처음 보낼 기록: 모든 물건과 삭제 표시 */
    fun syncIds(): List<Pair<String, Long>> = synchronized(LOCK) {
        read().map { it.id to it.updatedAt } + readTombstones().map { it.key to it.value.at }
    }

    fun metaOf(id: String): SyncMeta? = synchronized(LOCK) {
        read().firstOrNull { it.id == id }?.let { return SyncMeta(id, it.updatedAt, deleted = false, origin = it.origin) }
        readTombstones()[id]?.let { SyncMeta(id, it.at, deleted = true, origin = it.origin) }
    }

    /** 다른 기기에서 받은 물건 기록을 적용한다 ([SyncMerge]). 같은 변경을 여러 번 받아도 결과가 같다. */
    fun applyRemote(meta: SyncMeta, item: CarItem?): Boolean {
        val applied = synchronized(LOCK) {
            if (!SyncMerge.remoteWins(metaOf(meta.id), meta)) return@synchronized false
            val others = read().filter { it.id != meta.id }
            if (meta.deleted) {
                write(others)
                writeTombstones(readTombstones() + (meta.id to Tombstone(meta.updatedAt, meta.origin)))
                return@synchronized true
            }
            if (item == null) return@synchronized false
            writeTombstones(readTombstones() - meta.id)
            write(others + item.copy(id = meta.id, updatedAt = meta.updatedAt, origin = meta.origin))
            true
        }
        if (applied) notifyChanged()
        return applied
    }

    fun payload(item: CarItem): String = encode(item).toString()

    fun fromPayload(json: String): CarItem? = try {
        decode(JSONObject(json))
    } catch (e: Exception) {
        null
    }

    // endregion

    /** 데이터 지우기: 모든 물건 기록을 지운다 (삭제 표시는 남김) */
    fun clearAll() {
        val now = System.currentTimeMillis()
        val ids = synchronized(LOCK) {
            val ids = read().map { it.id }
            writeTombstones(readTombstones() + ids.associateWith { Tombstone(now, sync.deviceId) })
            prefs.edit().remove(KEY_ITEMS).apply()
            ids
        }
        ids.forEach { sync.markChanged(KIND, it, now) }
        notifyChanged()
    }

    /** 하차 후 할 일을 만든다. 같은 제목의 열린 할 일이 이미 있으면 그것과 연결한다. */
    private fun addPickupTodo(item: CarItem): String? {
        val now = System.currentTimeMillis()
        val title = CarItems.pickupTitle(item.name, item.spot)
        val todo = TodoItem(
            id = TodoItem.newId(), title = title, note = null, dueAt = null, hasTime = false,
            placeKind = null, placeName = null, source = item.source, appPackage = null, notificationKey = null,
            createdAt = now, updatedAt = now, done = false, carItemId = item.id,
        )
        val todos = TodoStore(appContext)
        if (todos.add(todo)) return todo.id
        val existing = todos.openTodos().firstOrNull { it.title == title } ?: return null
        if (existing.carItemId == null) todos.update(existing.copy(carItemId = item.id))
        return existing.id
    }

    /** 연결된 하차 후 할 일을 지움: 물건 기록은 남기고 '챙길 물건'만 해제한다 */
    fun onTodoDeleted(itemId: String) {
        if (get(itemId)?.todoId == null) return
        change(itemId) { it.copy(pickup = false, remind = false, todoId = null) }
    }

    private fun change(id: String, block: (CarItem) -> CarItem): CarItem? {
        val now = System.currentTimeMillis()
        val updated = synchronized(LOCK) {
            val list = read()
            val current = list.firstOrNull { it.id == id } ?: return null
            val next = block(current).copy(updatedAt = now, origin = sync.deviceId)
            write(list.map { if (it.id == id) next else it })
            next
        }
        sync.markChanged(KIND, id, now)
        notifyChanged()
        return updated
    }

    private fun read(): List<CarItem> {
        val json = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i -> runCatching { decode(array.getJSONObject(i)) }.getOrNull() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun write(list: List<CarItem>) {
        val array = JSONArray()
        list.forEach { array.put(encode(it)) }
        prefs.edit().putString(KEY_ITEMS, array.toString()).apply()
    }

    private fun decode(o: JSONObject) = CarItem(
        id = o.getString("id"),
        name = o.getString("name"),
        spot = o.optString("spot").takeIf { it.isNotEmpty() }?.let { runCatching { CarSpot.valueOf(it) }.getOrNull() },
        vehicle = o.optString("vehicle").takeIf { it.isNotEmpty() },
        source = TodoItem.Source.valueOf(o.getString("source")),
        pickup = o.optBoolean("pickup"),
        remind = o.optBoolean("remind"),
        parkingId = o.optString("parkingId").takeIf { it.isNotEmpty() },
        parking = o.optString("parking").takeIf { it.isNotEmpty() }?.let { ParkingStore(appContext).fromPayload(it) },
        todoId = o.optString("todoId").takeIf { it.isNotEmpty() },
        createdAt = o.getLong("created"),
        updatedAt = o.getLong("updated"),
        retrievedAt = if (o.has("retrieved")) o.getLong("retrieved") else null,
        origin = o.optString("origin"),
    )

    private fun encode(item: CarItem): JSONObject {
        val o = JSONObject()
            .put("id", item.id).put("name", item.name).put("source", item.source.name)
            .put("pickup", item.pickup).put("remind", item.remind)
            .put("created", item.createdAt).put("updated", item.updatedAt)
        item.spot?.let { o.put("spot", it.name) }
        item.vehicle?.let { o.put("vehicle", it) }
        item.parkingId?.let { o.put("parkingId", it) }
        item.parking?.let { o.put("parking", ParkingStore(appContext).payload(it)) }
        item.todoId?.let { o.put("todoId", it) }
        item.retrievedAt?.let { o.put("retrieved", it) }
        if (item.origin.isNotEmpty()) o.put("origin", item.origin)
        return o
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

    private fun writeTombstones(map: Map<String, Tombstone>) {
        val now = System.currentTimeMillis()
        val o = JSONObject()
        map.filterValues { now - it.at < TOMBSTONE_KEEP_MS }.forEach { (id, t) ->
            o.put(id, JSONObject().put("at", t.at).put("origin", t.origin))
        }
        prefs.edit().putString(KEY_DELETED, o.toString()).apply()
    }

    companion object {
        /** 동기화 레코드 종류 */
        const val KIND = "car_item"

        private const val PREFS = "drive_car_items"
        private const val KEY_ITEMS = "items"
        private const val KEY_DELETED = "deleted"
        private const val TOMBSTONE_KEEP_MS = 30L * 24 * 60 * 60 * 1000
        private val LOCK = Any()

        private val listeners = mutableSetOf<() -> Unit>()

        fun addListener(listener: () -> Unit) = synchronized(listeners) { listeners += listener }
        fun removeListener(listener: () -> Unit) = synchronized(listeners) { listeners -= listener }
        private fun notifyChanged() = synchronized(listeners) { listeners.toList() }.forEach { it() }
    }
}
