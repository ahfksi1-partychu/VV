package com.mycarhub.phone.drive

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.mycarhub.R
import com.mycarhub.drive.DriveAssistant
import com.mycarhub.drive.DriveSyncManager
import com.mycarhub.drive.DriveSyncState
import com.mycarhub.drive.ParkingPhotos
import com.mycarhub.drive.ParkingSpot
import com.mycarhub.drive.ParkingStore
import java.util.concurrent.Executors

/**
 * 주차 위치 기록 (폰): 주차 구역·번호 직접 입력, 기둥·안내판 사진 촬영/선택, 저장 시각·위치·동기화 상태 표시.
 *
 * - 위치(GPS)가 없는 기록(지하 주차장)에도 글·사진을 저장할 수 있다.
 * - 사진은 카메라 앱에 이 앱 전용 임시 주소 하나의 쓰기 권한만 잠시 준다 (앱에 카메라·사진 읽기 권한이 필요 없다).
 *   갤러리 선택은 시스템 사진 선택기(Android 13+) 또는 문서 선택기로, 고른 사진 하나만 읽는다.
 * - 사진은 앱 전용 저장소에 줄이고 EXIF(촬영 위치 등)를 뺀 사본으로 저장한다 ([ParkingPhotos]).
 */
class ParkingRecordActivity : Activity() {

    enum class Mode { VIEW, TEXT, PHOTO }

    private lateinit var parking: ParkingStore
    private lateinit var note: EditText
    private lateinit var photoView: ImageView
    private val worker = Executors.newSingleThreadExecutor()
    private var spotId: String? = null

    /** 이 화면에서 고른 사진 (저장 전). [photoChanged]가 false면 기록의 사진 그대로 */
    private var photo: String? = null
    private var photoChanged = false
    private var busy = false
    private var dialog: AlertDialog? = null

    private val changed: () -> Unit = { runOnUiThread { if (!isFinishing) renderInfo() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_parking_record)
        parking = ParkingStore(this)
        note = findViewById(R.id.parkingRecordNote)
        photoView = findViewById(R.id.parkingRecordPhoto)

        val current = parking.load()
        spotId = intent.getStringExtra(EXTRA_SPOT)?.takeIf { id -> current?.id == id || parking.previous()?.id == id } ?: current?.id
        if (current == null) {
            Toast.makeText(this, R.string.parking_record_none, Toast.LENGTH_LONG).show()
            return finish()
        }
        // 안내 알림의 버튼으로 열렸으면 알림을 거둔다 (같은 주차에 다시 묻지 않음)
        ParkingPrompt.dismissNotification(this)

        if (savedInstanceState != null) {
            photo = savedInstanceState.getString(STATE_PHOTO)
            photoChanged = savedInstanceState.getBoolean(STATE_PHOTO_CHANGED)
        } else {
            note.setText(current.note.orEmpty())
            photo = current.photo
        }

        findViewById<Button>(R.id.parkingRecordCamera).setOnClickListener { takePhoto() }
        findViewById<Button>(R.id.parkingRecordGallery).setOnClickListener { pickPhoto() }
        findViewById<Button>(R.id.parkingRecordRemovePhoto).setOnClickListener {
            photo = null
            photoChanged = true
            renderPhoto()
        }
        photoView.setOnClickListener { showPhotoLarge() }
        findViewById<Button>(R.id.parkingRecordSave).setOnClickListener { save() }
        findViewById<Button>(R.id.parkingRecordLater).setOnClickListener {
            if (ParkingPrompt.isOpenFor(this, current)) ParkingPrompt.setState(this, ParkingPrompt.State.LATER)
            finish()
        }
        findViewById<Button>(R.id.parkingRecordMap).setOnClickListener { parking.load()?.let(::showOnMap) }

        renderInfo()
        renderPhoto()
        if (savedInstanceState == null) {
            when (Mode.entries.getOrNull(intent.getIntExtra(EXTRA_MODE, 0))) {
                Mode.TEXT -> focusNote()
                Mode.PHOTO -> takePhoto()
                else -> Unit
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ParkingStore.addListener(changed)
        DriveSyncManager.addListener(changed)
    }

    override fun onPause() {
        ParkingStore.removeListener(changed)
        DriveSyncManager.removeListener(changed)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PHOTO, photo)
        outState.putBoolean(STATE_PHOTO_CHANGED, photoChanged)
    }

    override fun onDestroy() {
        dialog?.dismiss()
        worker.shutdown()
        super.onDestroy()
    }

    private fun renderInfo() {
        val spot = parking.load() ?: return
        findViewById<TextView>(R.id.parkingRecordWhen).text = ParkingPrompt.whenAndHow(this, spot)
        findViewById<TextView>(R.id.parkingRecordPlace).text = if (spot.hasLocation) {
            ParkingRecordText.place(this, spot)
        } else {
            getString(R.string.parking_record_no_location_hint)
        }
        findViewById<View>(R.id.parkingRecordMap).visibility = if (spot.hasLocation) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.parkingRecordSync).text = ParkingRecordText.syncStatus(this, spot)
    }

    private fun renderPhoto() {
        val name = photo
        findViewById<View>(R.id.parkingRecordRemovePhoto).visibility = if (name != null) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.parkingRecordCamera).setText(if (name != null) R.string.parking_record_retake else R.string.parking_prompt_photo)
        val preview = name != null && !photoChanged && parking.load()?.photoPreview == true
        findViewById<View>(R.id.parkingRecordPhotoNote).visibility = if (preview) View.VISIBLE else View.GONE
        if (name == null) {
            photoView.setImageDrawable(null)
            photoView.visibility = View.GONE
            return
        }
        val side = resources.displayMetrics.widthPixels
        worker.execute {
            val bitmap = ParkingPhotos.load(this, name, side)
            runOnUiThread {
                if (isFinishing || photo != name) return@runOnUiThread
                photoView.setImageBitmap(bitmap)
                photoView.visibility = if (bitmap != null) View.VISIBLE else View.GONE
            }
        }
    }

    /** [직접 입력]으로 열렸을 때: 창이 포커스를 받은 뒤 입력란에 커서를 두고 키보드를 연다 */
    private fun focusNote() {
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        note.postDelayed({
            if (isFinishing) return@postDelayed
            note.requestFocus()
            note.setSelection(note.text.length)
            getSystemService(InputMethodManager::class.java)?.showSoftInput(note, InputMethodManager.SHOW_IMPLICIT)
        }, FOCUS_DELAY_MS)
    }

    // region 사진

    /** 카메라 앱으로 촬영: 결과는 앱 전용 임시 파일 하나로만 받는다 */
    private fun takePhoto() {
        if (busy) return
        val uri = ParkingPhotos.captureUri(this)
        ParkingPhotos.captureFile(this).delete()
        val capture = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        capture.clipData = ClipData.newRawUri("", uri)
        try {
            startActivityForResult(capture, REQUEST_CAMERA)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.parking_record_no_camera, Toast.LENGTH_LONG).show()
        } catch (e: SecurityException) {
            Toast.makeText(this, R.string.parking_record_no_camera, Toast.LENGTH_LONG).show()
        }
    }

    /** 이미 찍어 둔 사진에서 고르기 (사진 권한 없이 고른 한 장만) */
    private fun pickPhoto() {
        if (busy) return
        val pick = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Intent(MediaStore.ACTION_PICK_IMAGES)
        } else {
            Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            startActivityForResult(pick, REQUEST_PICK)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.parking_record_no_gallery, Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Activity result API (androidx) is not in this project")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_CAMERA -> {
                revokeUriPermission(ParkingPhotos.captureUri(this), Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val file = ParkingPhotos.captureFile(this)
                if (resultCode != RESULT_OK || !file.isFile || file.length() == 0L) {
                    file.delete()
                    return
                }
                importPhoto { file.inputStream().use { ParkingPhotos.import(this, it) }.also { file.delete() } }
            }
            REQUEST_PICK -> {
                val uri: Uri = data?.data ?: return
                if (resultCode != RESULT_OK) return
                importPhoto { contentResolver.openInputStream(uri)?.use { ParkingPhotos.import(this, it) } }
            }
        }
    }

    private fun importPhoto(work: () -> String?) {
        busy = true
        Toast.makeText(this, R.string.parking_record_photo_saving, Toast.LENGTH_SHORT).show()
        worker.execute {
            val name = runCatching(work).getOrNull()
            runOnUiThread {
                busy = false
                if (isFinishing) return@runOnUiThread
                if (name == null) {
                    Toast.makeText(this, R.string.parking_record_photo_failed, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                photo = name
                photoChanged = true
                renderPhoto()
            }
        }
    }

    private fun showPhotoLarge() {
        val name = photo ?: return
        val view = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        worker.execute {
            val bitmap: Bitmap? = ParkingPhotos.load(this, name, maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels))
            runOnUiThread {
                if (isFinishing || bitmap == null) return@runOnUiThread
                view.setImageBitmap(bitmap)
                dialog = AlertDialog.Builder(this).setView(view).setPositiveButton(R.string.drive_close, null).show()
            }
        }
    }

    // endregion

    private fun save() {
        if (busy) return Toast.makeText(this, R.string.parking_record_photo_saving, Toast.LENGTH_SHORT).show()
        val id = spotId ?: return finish()
        val current = parking.load() ?: return finish()
        val finalPhoto = if (photoChanged) photo else current.photo
        val saved = parking.updateDetail(id, note.text.toString(), finalPhoto)
        if (saved == null) {
            Toast.makeText(this, R.string.parking_record_replaced, Toast.LENGTH_LONG).show()
            return
        }
        ParkingPrompt.setState(this, ParkingPrompt.State.DONE)
        if (DriveSyncState(this).paired) DriveSyncManager.requestSync(this, "parking-detail", 0L)
        Toast.makeText(this, if (saved.hasDetail) R.string.parking_record_saved else R.string.parking_record_cleared, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun showOnMap(spot: ParkingSpot) {
        val map = DriveAssistant.mapIntent(spot, getString(R.string.drive_parking_label))
        val target = if (map != null && map.resolveActivity(packageManager) != null) map else DriveAssistant.webMapIntent(spot)
        if (target == null) return Toast.makeText(this, R.string.parking_record_no_location, Toast.LENGTH_SHORT).show()
        try {
            startActivity(target)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.drive_no_map_app, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val EXTRA_SPOT = "spot_id"
        private const val EXTRA_MODE = "mode"
        private const val STATE_PHOTO = "photo"
        private const val STATE_PHOTO_CHANGED = "photo_changed"
        private const val REQUEST_CAMERA = 41
        private const val REQUEST_PICK = 42
        private const val FOCUS_DELAY_MS = 300L

        fun intent(context: Context, spotId: String?, mode: Mode = Mode.VIEW): Intent =
            Intent(context, ParkingRecordActivity::class.java)
                .putExtra(EXTRA_SPOT, spotId)
                .putExtra(EXTRA_MODE, mode.ordinal)
    }
}
