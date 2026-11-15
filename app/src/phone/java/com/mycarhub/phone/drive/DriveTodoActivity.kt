package com.mycarhub.phone.drive

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.mycarhub.MyCarHubApplication
import com.mycarhub.R
import com.mycarhub.assistant.messages.MessageAccess
import com.mycarhub.assistant.core.CarItems
import com.mycarhub.assistant.core.CarSpot
import com.mycarhub.drive.CarItem
import com.mycarhub.drive.CarItemStore
import com.mycarhub.drive.DriveAssistant
import com.mycarhub.drive.DriveFormat
import com.mycarhub.drive.DriveSyncActivity
import com.mycarhub.drive.DriveSyncManager
import com.mycarhub.drive.DriveSyncState
import com.mycarhub.drive.ParkingRecorder
import com.mycarhub.drive.ParkingSpot
import com.mycarhub.drive.ParkingStore
import com.mycarhub.drive.SavedPlace
import com.mycarhub.drive.SavedPlaces
import com.mycarhub.drive.TodoCandidate
import com.mycarhub.drive.TodoItem
import com.mycarhub.drive.TodoStore
import com.mycarhub.phone.NotificationMessageAccess
import com.mycarhub.phone.PermissionGate
import com.mycarhub.platform.Platform
import java.util.Calendar
import java.util.concurrent.Executors

/**
 * Drive To-Do (폰): 마지막 주차 위치 확인하기(항상), 할 일 목록(완료·수정·삭제·기한·출처·관련 앱 열기),
 * 알림에서 찾은 할 일 후보, 주차 감지·알림 분석·장소 설정, 데이터 지우기.
 *
 * 모든 데이터는 이 휴대폰 안에만 있다. 차량용 앱의 할 일·주차 위치는 아직 자동으로 넘어오지 않는다 (화면에 그렇게 표시).
 */
class DriveTodoActivity : Activity() {

    private lateinit var parking: ParkingStore
    private lateinit var todos: TodoStore
    private lateinit var places: SavedPlaces
    private lateinit var settings: PhoneDriveSettings
    private lateinit var items: CarItemStore
    private val worker = Executors.newSingleThreadExecutor()
    private var dialog: AlertDialog? = null

    private val changed: () -> Unit = { runOnUiThread { if (!isFinishing) render() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_drive_todo)
        parking = ParkingStore(this)
        todos = TodoStore(this)
        places = SavedPlaces(this)
        settings = PhoneDriveSettings(this)
        items = CarItemStore(this)

        findViewById<View>(R.id.driveParkingCard).setOnClickListener { showParkingActions() }
        findViewById<View>(R.id.driveParkingMapButton).setOnClickListener { parking.load()?.let(::showOnMap) }
        findViewById<View>(R.id.driveParkingWalkButton).setOnClickListener {
            parking.load()?.let { open(DriveAssistant.directionsIntent(it)) }
        }
        findViewById<View>(R.id.driveParkingSaveButton).setOnClickListener { saveParkingHere() }
        findViewById<View>(R.id.driveAddButton).setOnClickListener { editTodo(null) }
        findViewById<View>(R.id.driveItemAddButton).setOnClickListener { editItem(null) }
        findViewById<View>(R.id.driveFindCarButton).setOnClickListener { findCar() }
        findViewById<View>(R.id.driveCarButton).setOnClickListener { chooseCar() }
        findViewById<View>(R.id.driveLocationButton).setOnClickListener { requestLocation(always = true) {} }
        findViewById<View>(R.id.driveNotifyButton).setOnClickListener {
            PermissionGate.request(this, PermissionGate.Kind.NOTIFICATIONS, force = true) { render() }
        }
        findViewById<Switch>(R.id.driveAnalyzeSwitch).setOnClickListener { view -> onAnalyzeToggled((view as Switch).isChecked) }
        findViewById<View>(R.id.drivePlaceAddButton).setOnClickListener { addPlace() }
        findViewById<View>(R.id.driveClearButton).setOnClickListener { confirmClearAll() }
        findViewById<View>(R.id.driveSyncButton).setOnClickListener { startActivity(Intent(this, DriveSyncActivity::class.java)) }
        handleSaveHere(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSaveHere(intent)
    }

    /** 주차 후 알림의 [지금 위치로 저장]: 사용자가 직접 누른 경우에만 지금 위치를 저장한다. */
    private fun handleSaveHere(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_SAVE_HERE, false) != true) return
        intent.removeExtra(EXTRA_SAVE_HERE)
        saveParkingHere()
    }

    override fun onResume() {
        super.onResume()
        ParkingStore.addListener(changed)
        TodoStore.addListener(changed)
        CarItemStore.addListener(changed)
        DriveSyncManager.addListener(changed)
        // 화면을 열 때 차량에서 바뀐 것을 받아 온다
        if (DriveSyncState(this).paired) DriveSyncManager.requestSync(this, "screen", 0L)
        render()
    }

    override fun onPause() {
        ParkingStore.removeListener(changed)
        TodoStore.removeListener(changed)
        CarItemStore.removeListener(changed)
        DriveSyncManager.removeListener(changed)
        super.onPause()
    }

    override fun onDestroy() {
        dialog?.dismiss()
        worker.shutdown()
        super.onDestroy()
    }

    // region 화면

    private fun render() {
        renderParking()
        renderItems()
        renderPickups()
        renderTodos()
        renderCandidates()
        renderSettings()
        renderPlaces()
    }

    private fun renderParking() {
        val spot = parking.load()
        val text = findViewById<TextView>(R.id.driveParkingText)
        val meta = findViewById<TextView>(R.id.driveParkingMeta)
        findViewById<View>(R.id.driveParkingMapButton).isEnabled = spot?.hasLocation == true
        findViewById<View>(R.id.driveParkingWalkButton).isEnabled = spot?.hasLocation == true
        // 자동 저장 실패가 지금 보이는 위치보다 나중이면 알린다 (보이는 위치는 이전 주차)
        val failedAt = settings.lastAutoFailAt.takeIf { it > (spot?.savedAt ?: 0L) }
        val warning = when {
            failedAt != null && spot != null -> getString(R.string.drive_parking_auto_failed, DriveFormat.relative(this, failedAt))
            failedAt != null -> getString(R.string.drive_parking_auto_failed_none, DriveFormat.relative(this, failedAt))
            spot != null && !spot.hasLocation -> getString(R.string.parking_record_no_location_hint)
            spot?.accuracyM?.let { it > ParkingRecorder.LOW_ACCURACY_M } == true -> getString(R.string.drive_parking_inaccurate)
            else -> null
        }
        findViewById<TextView>(R.id.driveParkingWarning).apply {
            this.text = warning
            visibility = if (warning != null) View.VISIBLE else View.GONE
        }
        if (spot == null) {
            text.setText(R.string.drive_parking_card_none)
            meta.setText(if (settings.carAddress == null) R.string.drive_parking_card_setup else R.string.drive_parking_card_waiting)
            return
        }
        text.text = ParkingRecordText.where(this, spot)
        val source = getString(
            when (spot.source) {
                ParkingSpot.Source.CAR_SIGNAL -> R.string.drive_source_car_signal
                ParkingSpot.Source.PHONE_BLUETOOTH -> R.string.drive_source_phone_bluetooth
                ParkingSpot.Source.MANUAL -> R.string.drive_source_manual
            },
        )
        val accuracy = spot.accuracyM?.let { getString(R.string.drive_parking_accuracy_meta, it.toInt()) }.orEmpty()
        meta.text = getString(R.string.drive_parking_meta, DriveFormat.relative(this, spot.savedAt), source, accuracy).trim()
    }

    private fun renderTodos() {
        val list = findViewById<LinearLayout>(R.id.driveTodoList)
        list.removeAllViews()
        // 차에서 물건 가져오기 할 일은 '하차 후 처리할 일'에 따로 보여준다. 안 한 일 먼저 (기한 빠른 순), 끝낸 일은 아래로
        val shown = todos.todos().filter { it.carItemId == null }
            .sortedWith(compareBy({ it.done }, { it.dueAt ?: Long.MAX_VALUE }, { it.createdAt }))
        findViewById<TextView>(R.id.driveTodoHeader).text = getString(R.string.drive_todo_header, shown.count { !it.done })
        findViewById<View>(R.id.driveTodoEmpty).visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        for (item in shown) list.addView(todoRow(item, list))
    }

    /** 할 일 한 줄: 완료 체크, 제목, 정보, [열기] [수정] [삭제]. 물건 할 일의 [수정]은 물건 기록을 고친다. */
    private fun todoRow(item: TodoItem, list: LinearLayout): View {
        val row = LayoutInflater.from(this).inflate(R.layout.item_drive_todo, list, false)
        row.findViewById<CheckBox>(R.id.todoDone).apply {
            isChecked = item.done
            setOnClickListener { todos.update(item.copy(done = isChecked)) }
        }
        row.findViewById<TextView>(R.id.todoTitle).apply {
            text = item.title
            alpha = if (item.done) DONE_ALPHA else 1f
        }
        row.findViewById<TextView>(R.id.todoMeta).text = metaOf(item)
        row.findViewById<Button>(R.id.todoOpen).apply {
            visibility = if (item.appPackage != null) View.VISIBLE else View.GONE
            setOnClickListener { openRelated(item.appPackage, item.notificationKey) }
        }
        val carItem = item.carItemId?.let { items.get(it) }
        row.findViewById<View>(R.id.todoEdit).setOnClickListener { if (carItem != null) editItem(carItem) else editTodo(item) }
        row.findViewById<View>(R.id.todoDelete).setOnClickListener { todos.delete(item.id) }
        return row
    }

    // region 차량에 남긴 물건 · 하차 후 처리할 일

    private fun renderItems() {
        val list = findViewById<LinearLayout>(R.id.driveItemList)
        list.removeAllViews()
        val all = items.items()
        val inCar = all.filter { it.inCar }.sortedByDescending { it.createdAt }
        // 가져온 물건은 최근 것 몇 개만 흐리게 (되돌리기용)
        val retrieved = all.filter { !it.inCar }.sortedByDescending { it.retrievedAt }.take(MAX_RETRIEVED_SHOWN)
        findViewById<TextView>(R.id.driveItemHeader).text = getString(R.string.drive_items_header, inCar.size)
        findViewById<View>(R.id.driveItemEmpty).visibility = if (inCar.isEmpty()) View.VISIBLE else View.GONE
        findViewById<View>(R.id.driveFindCarButton).isEnabled = parking.load() != null
        val latest = parking.load()
        val inflater = LayoutInflater.from(this)
        for (item in inCar + retrieved) {
            val row = inflater.inflate(R.layout.item_drive_car_item, list, false)
            row.alpha = if (item.inCar) 1f else DONE_ALPHA
            row.findViewById<TextView>(R.id.carItemName).text = item.name
            row.findViewById<TextView>(R.id.carItemMeta).text = itemMeta(item, latest?.id)
            row.findViewById<Button>(R.id.carItemRetrieved).apply {
                setText(if (item.inCar) R.string.drive_item_retrieve else R.string.drive_item_unretrieve)
                setOnClickListener { items.setRetrieved(item.id, item.inCar) }
            }
            row.findViewById<Button>(R.id.carItemRemind).apply {
                visibility = if (item.inCar) View.VISIBLE else View.GONE
                setText(
                    when {
                        !item.pickup -> R.string.drive_item_make_pickup
                        item.remind -> R.string.drive_item_remind_off
                        else -> R.string.drive_item_remind_on
                    },
                )
                setOnClickListener {
                    if (!item.pickup) items.setPickup(item.id, true) else items.setRemind(item.id, !item.remind)
                }
            }
            row.findViewById<View>(R.id.carItemEdit).setOnClickListener { editItem(item) }
            row.findViewById<View>(R.id.carItemDelete).setOnClickListener { confirmDeleteItem(item) }
            list.addView(row)
        }
    }

    /** 자리 · 기록 시각 · 연결된 주차 · 차량 · 알림. 주차 기록과의 연결을 구분해 보여준다. */
    private fun itemMeta(item: CarItem, latestParkingId: String?): String = listOfNotNull(
        getString(R.string.drive_item_meta_place, CarItems.placeLabel(item.spot)),
        getString(R.string.drive_item_meta_time, DriveFormat.relative(this, item.createdAt)),
        item.retrievedAt?.let { getString(R.string.drive_item_meta_retrieved, DriveFormat.relative(this, it)) },
        if (item.inCar) parkingLink(item, latestParkingId) else null,
        item.vehicle?.let { getString(R.string.drive_item_meta_vehicle, it) },
        if (item.inCar && item.pickup) getString(if (item.remind) R.string.drive_item_meta_reminding else R.string.drive_item_meta_reminder_off) else null,
    ).joinToString(" · ")

    private fun parkingLink(item: CarItem, latestParkingId: String?): String {
        val linked = item.parking ?: return getString(
            if (item.parkingId == null && Platform.isDriving(this)) R.string.drive_item_meta_parking_pending else R.string.drive_item_meta_no_parking,
        )
        val where = ParkingRecordText.where(this, linked)
        return if (item.parkingId == latestParkingId) {
            getString(R.string.drive_item_meta_parking, where)
        } else {
            getString(R.string.drive_item_meta_parking_old, DriveFormat.relative(this, linked.savedAt))
        }
    }

    private fun renderPickups() {
        val list = findViewById<LinearLayout>(R.id.drivePickupList)
        list.removeAllViews()
        val pickups = todos.todos().filter { it.carItemId != null }.sortedWith(compareBy({ it.done }, { -it.createdAt }))
            .let { all -> all.filter { !it.done } + all.filter { it.done }.take(MAX_RETRIEVED_SHOWN) }
        findViewById<TextView>(R.id.drivePickupHeader).text = getString(R.string.drive_pickup_header, pickups.count { !it.done })
        findViewById<View>(R.id.drivePickupEmpty).visibility = if (pickups.none { !it.done }) View.VISIBLE else View.GONE
        for (todo in pickups) list.addView(todoRow(todo, list))
    }

    /** 차 찾기: 가장 최근 주차 위치(차가 있는 곳)를 지도·길찾기로. 위치가 없으면 임의 위치를 보여주지 않는다. */
    private fun findCar() {
        val spot = parking.load() ?: return toast(R.string.drive_find_car_none)
        if (!spot.hasLocation) return toast(R.string.parking_record_no_location)
        spot.accuracyM?.takeIf { it > ParkingRecorder.LOW_ACCURACY_M }?.let {
            toast(getString(R.string.drive_find_car_inaccurate, it.toInt()))
        }
        val actions = listOf<Pair<Int, () -> Unit>>(
            R.string.drive_parking_map to { showOnMap(spot) },
            R.string.drive_parking_directions to { open(DriveAssistant.directionsIntent(spot)) },
        )
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.drive_find_car)
            .setItems(actions.map { getString(it.first) }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton(R.string.drive_close, null)
            .show()
    }

    /** 물건 기록 추가([item] null)·수정: 이름, 둔 자리, 하차 후 챙기기 */
    private fun editItem(item: CarItem?) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val name = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = getString(R.string.drive_item_name_hint)
            setText(item?.name.orEmpty())
        }
        val spots = listOf<CarSpot?>(null) + CarSpot.entries
        val spot = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@DriveTodoActivity, android.R.layout.simple_spinner_dropdown_item,
                spots.map { it?.label ?: getString(R.string.drive_item_spot_car) },
            )
            setSelection(spots.indexOf(item?.spot).coerceAtLeast(0))
        }
        val pickup = CheckBox(this).apply {
            setText(R.string.drive_item_pickup_check)
            isChecked = item?.pickup == true
        }
        form.addView(name)
        form.addView(TextView(this).apply { setText(R.string.drive_item_spot_label) })
        form.addView(spot)
        form.addView(pickup)
        dialog = AlertDialog.Builder(this)
            .setTitle(if (item == null) R.string.drive_item_add_title else R.string.drive_item_edit_title)
            .setView(form)
            .setPositiveButton(R.string.drive_save) { _, _ ->
                val text = name.text.toString().trim()
                if (text.isEmpty()) return@setPositiveButton toast(R.string.drive_edit_title_empty)
                val chosen = spots[spot.selectedItemPosition]
                if (item == null) {
                    items.register(text, chosen, pickup.isChecked, TodoItem.Source.MANUAL, settings.carName, Platform.isDriving(this))
                } else {
                    items.rename(item.id, text, chosen)
                    items.setPickup(item.id, pickup.isChecked)
                }
            }
            .setNegativeButton(R.string.drive_cancel, null)
            .show()
    }

    private fun confirmDeleteItem(item: CarItem) {
        dialog = AlertDialog.Builder(this)
            .setMessage(R.string.drive_item_delete_confirm)
            .setPositiveButton(R.string.drive_delete) { _, _ -> items.delete(item.id) }
            .setNegativeButton(R.string.drive_cancel, null)
            .show()
    }

    // endregion

    private fun metaOf(item: TodoItem): String = listOfNotNull(
        getString(sourceLabel(item.source)),
        item.dueAt?.let { getString(R.string.drive_meta_due, DriveFormat.due(this, it, item.hasTime)) },
        item.placeName?.let { getString(R.string.drive_meta_place, it) },
    ).joinToString(" · ")

    private fun sourceLabel(source: TodoItem.Source): Int = when (source) {
        TodoItem.Source.VOICE_CAR -> R.string.drive_source_voice_car
        TodoItem.Source.VOICE_PHONE -> R.string.drive_source_voice_phone
        TodoItem.Source.SMS -> R.string.drive_source_sms
        TodoItem.Source.KAKAO -> R.string.drive_source_kakao
        TodoItem.Source.MANUAL -> R.string.drive_source_manual_todo
    }

    private fun renderCandidates() {
        val list = findViewById<LinearLayout>(R.id.driveCandidateList)
        list.removeAllViews()
        val candidates = todos.candidates().sortedByDescending { it.createdAt }
        val inflater = LayoutInflater.from(this)
        for (candidate in candidates) {
            val row = inflater.inflate(R.layout.item_drive_row, list, false)
            row.findViewById<TextView>(R.id.rowText).text =
                getString(R.string.drive_candidate_row, candidate.title, getString(sourceLabel(candidate.source)))
            row.findViewById<Button>(R.id.rowPrimary).apply {
                setText(R.string.drive_candidate_accept)
                setOnClickListener { todos.acceptCandidate(candidate) }
            }
            row.findViewById<Button>(R.id.rowSecondary).apply {
                setText(R.string.drive_candidate_dismiss)
                setOnClickListener { todos.removeCandidate(candidate.id) }
            }
            row.setOnClickListener { openRelated(candidate.appPackage, candidate.notificationKey) }
            list.addView(row)
        }
        findViewById<TextView>(R.id.driveCandidateNote).setText(
            when {
                !settings.analyzeNotifications -> R.string.drive_candidates_off
                candidates.isEmpty() -> R.string.drive_candidates_empty
                else -> R.string.drive_candidates_hint
            },
        )
    }

    private fun renderSettings() {
        findViewById<TextView>(R.id.driveCarText).text = settings.carName?.let { getString(R.string.drive_car_selected, it) }
            ?: getString(R.string.drive_car_none)
        val fine = PermissionGate.isGranted(this, PermissionGate.Kind.LOCATION) ||
            ParkingRecorder.hasLocationPermission(this)
        val always = fine && PermissionGate.isGranted(this, PermissionGate.Kind.BACKGROUND_LOCATION)
        findViewById<TextView>(R.id.driveLocationText).setText(
            when {
                always -> R.string.drive_location_always
                fine -> R.string.drive_location_in_use
                else -> R.string.drive_location_off
            },
        )
        findViewById<View>(R.id.driveLocationButton).visibility = if (always) View.GONE else View.VISIBLE
        val notify = PermissionGate.isGranted(this, PermissionGate.Kind.NOTIFICATIONS)
        findViewById<TextView>(R.id.driveNotifyText).setText(if (notify) R.string.drive_notify_on else R.string.drive_notify_off)
        findViewById<View>(R.id.driveNotifyButton).visibility = if (notify) View.GONE else View.VISIBLE
        findViewById<Switch>(R.id.driveAnalyzeSwitch).isChecked = settings.analyzeNotifications
        renderSync()
    }

    private fun renderSync() {
        val sync = DriveSyncState(this)
        val view = findViewById<TextView>(R.id.driveSyncStatus)
        val button = findViewById<View>(R.id.driveSyncButton)
        if (!DriveSyncManager.isConfigured) {
            view.setText(R.string.drive_sync_off)
            button.visibility = View.GONE
            return
        }
        button.visibility = View.VISIBLE
        view.text = when {
            !sync.paired -> getString(R.string.drive_sync_off)
            sync.lastError != null -> getString(R.string.drive_sync_on_error)
            sync.lastSyncAt > 0 -> getString(R.string.drive_sync_on, DriveFormat.relative(this, sync.lastSyncAt))
            else -> getString(R.string.drive_sync_on_never)
        }
    }

    private fun renderPlaces() {
        val list = findViewById<LinearLayout>(R.id.drivePlaceList)
        list.removeAllViews()
        val saved = places.all()
        // 집·회사는 지정 전에도 항상 보여준다
        val rows = listOf(
            getString(R.string.drive_place_home) to SavedPlaces.KIND_HOME,
            getString(R.string.drive_place_work) to SavedPlaces.KIND_WORK,
        ) + saved.filter { it.kind == SavedPlaces.KIND_OTHER }.map { it.name to SavedPlaces.KIND_OTHER }
        val inflater = LayoutInflater.from(this)
        for ((name, kind) in rows) {
            val place = saved.firstOrNull { it.name == name && it.kind == kind }
            val row = inflater.inflate(R.layout.item_drive_row, list, false)
            row.findViewById<TextView>(R.id.rowText).text =
                getString(if (place != null) R.string.drive_place_set else R.string.drive_place_unset, name)
            row.findViewById<Button>(R.id.rowPrimary).apply {
                setText(R.string.drive_place_here)
                setOnClickListener { savePlaceHere(name, kind) }
            }
            row.findViewById<Button>(R.id.rowSecondary).apply {
                setText(R.string.drive_delete)
                visibility = if (place != null) View.VISIBLE else View.GONE
                setOnClickListener {
                    places.remove(name)
                    render()
                }
            }
            list.addView(row)
        }
    }

    // endregion

    // region 주차 위치

    private fun showParkingActions() {
        val spot = parking.load()
        val actions = mutableListOf<Pair<Int, () -> Unit>>()
        if (spot != null && spot.hasLocation) {
            actions += R.string.drive_parking_map to { showOnMap(spot) }
            actions += R.string.drive_parking_directions to { open(DriveAssistant.directionsIntent(spot)) }
        }
        if (spot != null) actions += R.string.parking_record_edit to { startActivity(ParkingRecordActivity.intent(this, spot.id)) }
        actions += R.string.drive_parking_save_here to { saveParkingHere() }
        if (spot != null) actions += R.string.drive_parking_delete to { confirmDeleteParking() }
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.drive_parking_check)
            .setMessage(if (spot == null) getString(R.string.drive_parking_dialog_none) else null)
            .setItems(actions.map { getString(it.first) }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton(R.string.drive_close, null)
            .show()
    }

    /** geo: 링크를 여는 지도 앱으로, 없으면 웹 지도(브라우저)로 연다. */
    private fun showOnMap(spot: ParkingSpot) {
        val map = DriveAssistant.mapIntent(spot, getString(R.string.drive_parking_label)) ?: return toast(R.string.parking_record_no_location)
        if (map.resolveActivity(packageManager) != null) return open(map)
        open(DriveAssistant.webMapIntent(spot))
    }

    /**
     * [지금 위치로 저장]. 지금 기록이 위치 없이 저장된 최근 자동 감지 주차이면 새 기록을 만들지 않고 그 기록에 위치만 채운다
     * (사용자가 적은 구역·사진이 그대로 남는다). 그 밖에는 기존처럼 새 '직접 저장' 기록.
     */
    private fun saveParkingHere() {
        requestLocation(always = false) { granted ->
            if (!granted) return@requestLocation toast(R.string.drive_parking_need_location)
            toast(R.string.drive_locating)
            val current = parking.load()
            if (current != null && !current.hasLocation && System.currentTimeMillis() - current.savedAt < FILL_LOCATION_WINDOW_MS) {
                ParkingRecorder.currentLocation(this) { location ->
                    val filled = location?.let {
                        parking.fillLocation(current.id, it.latitude, it.longitude, if (it.hasAccuracy()) it.accuracy else null)
                    }
                    if (filled != null) {
                        settings.lastAutoFailAt = 0L
                        if (filled.address == null) ParkingRecorder.lookUpAddress(this, filled)
                    }
                    toast(if (filled != null) R.string.drive_parking_saved else R.string.drive_parking_save_failed)
                    render()
                }
                return@requestLocation
            }
            ParkingRecorder.record(this, ParkingSpot.Source.MANUAL) { spot ->
                if (spot != null) settings.lastAutoFailAt = 0L
                toast(if (spot != null) R.string.drive_parking_saved else R.string.drive_parking_save_failed)
                render()
            }
        }
    }

    private fun confirmDeleteParking() {
        dialog = AlertDialog.Builder(this)
            .setMessage(R.string.drive_parking_delete_confirm)
            .setPositiveButton(R.string.drive_delete) { _, _ -> parking.clear() }
            .setNegativeButton(R.string.drive_cancel, null)
            .show()
    }

    // endregion

    // region 할 일

    /** 할 일 추가([item] null)·수정: 제목, 기한(날짜→시각, 시각은 건너뛸 수 있음), 장소 조건. */
    private fun editTodo(item: TodoItem?) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val title = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = getString(R.string.drive_edit_title_hint)
            setText(item?.title.orEmpty())
        }
        var dueAt = item?.dueAt
        var hasTime = item?.hasTime == true
        val due = Button(this)
        fun showDue() {
            due.text = dueAt?.let { getString(R.string.drive_edit_due, DriveFormat.due(this, it, hasTime)) } ?: getString(R.string.drive_edit_no_due)
        }
        showDue()
        due.setOnClickListener {
            val c = Calendar.getInstance().apply { dueAt?.let { timeInMillis = it } }
            DatePickerDialog(this, { _, y, m, d ->
                c.set(y, m, d, com.mycarhub.assistant.core.DriveCommands.DEFAULT_DUE_HOUR, 0, 0)
                dueAt = c.timeInMillis
                hasTime = false
                showDue()
                // 시각은 선택: 취소하면 날짜만
                TimePickerDialog(this, { _, h, min ->
                    c.set(Calendar.HOUR_OF_DAY, h)
                    c.set(Calendar.MINUTE, min)
                    dueAt = c.timeInMillis
                    hasTime = true
                    showDue()
                }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), false).show()
            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
        }
        val clearDue = Button(this).apply {
            setText(R.string.drive_edit_clear_due)
            setOnClickListener {
                dueAt = null
                hasTime = false
                showDue()
            }
        }
        val placeNames = listOf(getString(R.string.drive_edit_no_place), getString(R.string.drive_place_home), getString(R.string.drive_place_work)) +
            places.all().filter { it.kind == SavedPlaces.KIND_OTHER }.map { it.name } +
            listOfNotNull(item?.placeName?.takeIf { it !in places.all().map { p -> p.name } && it != "집" && it != "회사" })
        val place = Spinner(this).apply {
            adapter = ArrayAdapter(this@DriveTodoActivity, android.R.layout.simple_spinner_dropdown_item, placeNames)
            setSelection(item?.placeName?.let { name -> placeNames.indexOf(name).coerceAtLeast(0) } ?: 0)
        }
        form.addView(title)
        form.addView(due)
        form.addView(clearDue)
        form.addView(TextView(this).apply { setText(R.string.drive_edit_place) })
        form.addView(place)

        dialog = AlertDialog.Builder(this)
            .setTitle(if (item == null) R.string.drive_add_title else R.string.drive_edit_title)
            .setView(form)
            .setPositiveButton(R.string.drive_save) { _, _ ->
                val text = title.text.toString().trim()
                if (text.isEmpty()) return@setPositiveButton toast(R.string.drive_edit_title_empty)
                val index = place.selectedItemPosition
                val placeName = placeNames[index].takeIf { index > 0 }
                val placeKind = when (index) {
                    0 -> null
                    1 -> SavedPlaces.KIND_HOME
                    2 -> SavedPlaces.KIND_WORK
                    else -> SavedPlaces.KIND_OTHER
                }
                val now = System.currentTimeMillis()
                if (item == null) {
                    todos.add(
                        TodoItem(TodoItem.newId(), text, null, dueAt, hasTime, placeKind, placeName, TodoItem.Source.MANUAL, null, null, now, now, false),
                    )
                } else {
                    todos.update(item.copy(title = text, dueAt = dueAt, hasTime = hasTime, placeKind = placeKind, placeName = placeName))
                }
            }
            .setNegativeButton(R.string.drive_cancel, null)
            .show()
    }

    /** 관련 메시지(알림이 남아 있으면 그 대화방) 또는 앱을 연다. */
    private fun openRelated(appPackage: String?, key: String?) {
        val access = (application as MyCarHubApplication).messages as? NotificationMessageAccess
        worker.execute {
            val opened = key != null && access?.openNotification(key) == true
            runOnUiThread {
                if (opened || appPackage == null) {
                    if (!opened) toast(R.string.drive_open_failed)
                    return@runOnUiThread
                }
                val launch = packageManager.getLaunchIntentForPackage(appPackage)
                if (launch == null) toast(R.string.drive_open_failed) else open(launch)
            }
        }
    }

    // endregion

    // region 설정

    /** 주차 감지에 쓸 차량 블루투스: 페어링된 기기 중에서 고른다 (근처 기기 권한 필요). */
    private fun chooseCar() {
        PermissionGate.request(this, PermissionGate.Kind.BLUETOOTH, force = true) { granted ->
            if (!granted) return@request toast(R.string.drive_car_need_bluetooth)
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter
            val bonded = try {
                adapter?.bondedDevices.orEmpty().toList()
            } catch (e: SecurityException) {
                emptyList()
            }
            val names = bonded.map { device -> runCatching { device.name }.getOrNull() ?: device.address }
            val items = names + getString(R.string.drive_car_clear)
            dialog = AlertDialog.Builder(this)
                .setTitle(R.string.drive_car_choose)
                .setMessage(if (bonded.isEmpty()) getString(R.string.drive_car_no_devices) else null)
                .setItems(items.toTypedArray()) { _, which ->
                    if (which == bonded.size) {
                        settings.carAddress = null
                        settings.carName = null
                    } else {
                        settings.carAddress = bonded[which].address
                        settings.carName = names[which]
                        // 자동 저장은 앱이 꺼져 있어도 위치가 필요하다: 바로 위치(항상 허용)까지 묻는다
                        requestLocation(always = true) {}
                    }
                    render()
                }
                .setNegativeButton(R.string.drive_cancel, null)
                .show()
        }
    }

    /** 위치 권한 (필요하면 이어서 '항상 허용'). 결과는 위치(앱 사용 중) 허용 여부. */
    private fun requestLocation(always: Boolean, onResult: (Boolean) -> Unit) {
        PermissionGate.request(this, PermissionGate.Kind.LOCATION, force = true) { granted ->
            if (!granted || !always) {
                render()
                return@request onResult(granted)
            }
            PermissionGate.request(this, PermissionGate.Kind.BACKGROUND_LOCATION, force = true) {
                render()
                onResult(true)
            }
        }
    }

    private fun onAnalyzeToggled(on: Boolean) {
        if (!on) {
            settings.analyzeNotifications = false
            // 알림에서 만든 후보도 지운다 (이미 추가한 할 일은 그대로)
            todos.candidates().forEach { todos.removeCandidate(it.id) }
            return render()
        }
        findViewById<Switch>(R.id.driveAnalyzeSwitch).isChecked = false
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.drive_analyze)
            .setMessage(R.string.drive_analyze_consent)
            .setPositiveButton(R.string.drive_agree) { _, _ ->
                val messages = (application as MyCarHubApplication).messages
                if (messages.status == MessageAccess.Status.NEEDS_ACCESS) {
                    PermissionGate.request(this, PermissionGate.Kind.NOTIFICATION_ACCESS, force = true) { granted ->
                        settings.analyzeNotifications = granted
                        render()
                    }
                } else {
                    settings.analyzeNotifications = true
                    render()
                }
            }
            .setNegativeButton(R.string.drive_cancel, null)
            .show()
    }

    private fun addPlace() {
        val name = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = getString(R.string.drive_place_name_hint)
        }
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.drive_place_add)
            .setMessage(R.string.drive_place_add_note)
            .setView(name)
            .setPositiveButton(R.string.drive_place_here) { _, _ ->
                val text = name.text.toString().trim()
                if (text.isNotEmpty()) savePlaceHere(text, SavedPlaces.KIND_OTHER)
            }
            .setNegativeButton(R.string.drive_cancel, null)
            .show()
    }

    /** 지금 위치를 장소로 지정한다. 위치를 못 얻으면 지정하지 않는다. */
    private fun savePlaceHere(name: String, kind: String) {
        requestLocation(always = false) { granted ->
            if (!granted) return@requestLocation toast(R.string.drive_parking_need_location)
            toast(R.string.drive_locating)
            ParkingRecorder.currentLocation(this) { location ->
                if (location == null) return@currentLocation toast(R.string.drive_place_failed)
                places.put(SavedPlace(name, kind, location.latitude, location.longitude))
                toast(getString(R.string.drive_place_saved, name))
                render()
            }
        }
    }

    private fun confirmClearAll() {
        dialog = AlertDialog.Builder(this)
            .setMessage(
                if (DriveSyncState(this).paired) getString(R.string.drive_clear_confirm) + "\n\n" + getString(R.string.drive_clear_confirm_synced)
                else getString(R.string.drive_clear_confirm),
            )
            .setPositiveButton(R.string.drive_delete) { _, _ ->
                todos.clearAll()
                items.clearAll()
                parking.clear()
                places.clear()
                settings.clear()
                render()
            }
            .setNegativeButton(R.string.drive_cancel, null)
            .show()
    }

    // endregion

    private fun open(intent: Intent?) {
        if (intent == null) return toast(R.string.parking_record_no_location)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast(R.string.drive_no_map_app)
        }
    }

    private fun toast(id: Int) = Toast.makeText(this, id, Toast.LENGTH_SHORT).show()

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        private const val DONE_ALPHA = 0.5f
        private const val MAX_RETRIEVED_SHOWN = 3
        private const val EXTRA_SAVE_HERE = "save_parking_here"

        /** 위치 없이 저장된 자동 감지 주차에 [지금 위치로 저장]으로 위치를 채울 수 있는 시간 (그 뒤에는 새 기록) */
        private const val FILL_LOCATION_WINDOW_MS = 30 * 60_000L

        fun intent(context: Context): Intent = Intent(context, DriveTodoActivity::class.java)

        /** 화면을 열고 바로 지금 위치를 주차 위치로 저장한다 (주차 후 알림의 버튼). */
        fun saveHereIntent(context: Context): Intent = intent(context).putExtra(EXTRA_SAVE_HERE, true)
    }
}
