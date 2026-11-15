package com.mycarhub.phone

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.mycarhub.MyCarHubApplication
import com.mycarhub.R
import com.mycarhub.assistant.phone.KoreanNameMatcher
import java.util.concurrent.Executors

/**
 * 자주 연락하는 사람 이름 등록 (폰 빌드): 카카오톡에만 저장된 친구 이름을 비슷한 이름 찾기에 쓰기 위해.
 *
 * 직접 입력하거나, 지금 알림창에 있는 메시지의 보낸 사람·단체방 이름 중에서 골라 추가한다.
 * 이름만 [KnownNames]에 저장하고 메시지 내용은 저장하지 않는다.
 */
class KnownNamesActivity : Activity() {

    private val app get() = application as MyCarHubApplication
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var input: EditText
    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_known_names)
        input = findViewById(R.id.knownNameInput)
        findViewById<Button>(R.id.knownNameAdd).setOnClickListener { addTyped() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) addTyped()
            actionId == EditorInfo.IME_ACTION_DONE
        }
        findViewById<Button>(R.id.knownNamesImport).setOnClickListener { importFromNotifications() }
        render()
    }

    override fun onDestroy() {
        dialog?.dismiss()
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun addTyped() {
        val name = input.text.toString()
        if (name.isBlank()) return
        if (KnownNames.add(this, name)) {
            input.text.clear()
        } else {
            Toast.makeText(this, R.string.known_names_not_added, Toast.LENGTH_SHORT).show()
        }
        render()
    }

    /** 지금 알림창에 있는 메시지의 보낸 사람·단체방 이름 (실제로 받은 알림에 있는 이름만). */
    private fun importFromNotifications() {
        // 알림 접근이 없으면 허용 화면을 바로 띄우고, 허용하면 이어서 가져온다
        PermissionGate.request(this, PermissionGate.Kind.NOTIFICATION_ACCESS, force = true) { granted ->
            if (!granted) {
                Toast.makeText(this, R.string.known_names_needs_access, Toast.LENGTH_LONG).show()
                return@request
            }
            worker.execute {
                val registered = KnownNames.all(this).map(KoreanNameMatcher::normalize).toSet()
                val names = app.messages.currentMessages()
                    .sortedByDescending { it.postedAt }
                    .flatMap { listOfNotNull(it.sender, it.conversation) }
                    .filter { it.isNotBlank() && KoreanNameMatcher.normalize(it) !in registered }
                    .distinct()
                main.post { if (!isFinishing) chooseNames(names) }
            }
        }
    }

    private fun chooseNames(names: List<String>) {
        if (names.isEmpty()) {
            Toast.makeText(this, R.string.known_names_import_none, Toast.LENGTH_LONG).show()
            return
        }
        val checked = BooleanArray(names.size)
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.known_names_import_title)
            .setMultiChoiceItems(names.toTypedArray(), checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(R.string.known_names_add) { _, _ ->
                names.filterIndexed { i, _ -> checked[i] }.forEach { KnownNames.add(this, it) }
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun render() {
        val names = KnownNames.all(this)
        findViewById<TextView>(R.id.knownNamesCount).text = getString(R.string.known_names_count, names.size)
        findViewById<View>(R.id.knownNamesEmpty).visibility = if (names.isEmpty()) View.VISIBLE else View.GONE
        val list = findViewById<LinearLayout>(R.id.knownNamesList)
        list.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (name in names) {
            val row = inflater.inflate(R.layout.item_known_name, list, false)
            row.findViewById<TextView>(R.id.knownName).text = name
            row.findViewById<Button>(R.id.knownNameDelete).setOnClickListener {
                KnownNames.remove(this, name)
                render()
            }
            list.addView(row)
        }
    }
}
