package com.mycarhub.drive

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.mycarhub.MyCarHubApplication
import com.mycarhub.R
import com.mycarhub.assistant.core.SyncPairing
import com.mycarhub.platform.Platform

/**
 * 차량 ↔ 휴대폰 동기화 연결 화면 (두 빌드 공통, 역할은 [Platform.syncRole]).
 *
 * - 차량: [연결 시작] → 6자리 코드 표시(5분) → 휴대폰이 입력하면 확인 번호 표시 → 휴대폰 화면과 같으면 [같아요].
 * - 휴대폰: 차량의 코드 입력 → 확인 번호 표시 → 차량에서 승인하면 연결됨.
 * - 차량용은 주행 중 사용 불가 (distractionOptimized 아님 + 화면 안에서도 확인).
 */
class DriveSyncActivity : Activity() {

    private val isCar = Platform.syncRole == "car"
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var big: TextView
    private lateinit var detail: TextView
    private lateinit var input: EditText
    private lateinit var primary: Button
    private lateinit var secondary: Button
    private lateinit var privacy: TextView

    private sealed class Mode {
        object Idle : Mode()
        object Busy : Mode()
        class CarCode(val offer: SyncPairing.CarOffer) : Mode()
        class CarVerify(val offer: SyncPairing.CarOffer, val derived: SyncPairing.Derived) : Mode()
        class PhoneWait(val claim: SyncPairing.PhoneClaim, val since: Long) : Mode()
        object Paired : Mode()
    }

    private var mode: Mode = Mode.Idle
    private var message: String? = null
    private var visible = false
    private val syncListener: () -> Unit = { if (mode == Mode.Paired || mode == Mode.Idle) refreshMode() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        refreshMode()
    }

    override fun onStart() {
        super.onStart()
        visible = true
        DriveSyncManager.addListener(syncListener)
        tick()
    }

    override fun onStop() {
        visible = false
        DriveSyncManager.removeListener(syncListener)
        handler.removeCallbacksAndMessages(null)
        super.onStop()
    }

    private fun refreshMode() {
        val state = DriveSyncState(this)
        if (mode is Mode.Paired || mode is Mode.Idle) mode = if (state.paired) Mode.Paired else Mode.Idle
        if (mode is Mode.Idle && message == null) state.lastError?.let { message = getString(R.string.sync_unpaired_note, it) }
        render()
    }

    // region 화면

    private fun render() {
        title.setText(if (isCar) R.string.sync_title_car else R.string.sync_title_phone)
        input.visibility = View.GONE
        big.visibility = View.GONE
        secondary.visibility = View.GONE
        primary.visibility = View.VISIBLE
        primary.isEnabled = true
        privacy.visibility = View.VISIBLE
        status.text = message ?: ""
        status.visibility = if (message.isNullOrEmpty()) View.GONE else View.VISIBLE

        if (!DriveSyncManager.isConfigured) {
            detail.setText(R.string.sync_not_configured)
            primary.visibility = View.GONE
            return
        }
        when (val m = mode) {
            Mode.Idle -> {
                detail.setText(if (isCar) R.string.sync_idle_car else R.string.sync_idle_phone)
                if (!isCar) input.visibility = View.VISIBLE
                primary.setText(if (isCar) R.string.sync_start_car else R.string.sync_start_phone)
                primary.setOnClickListener { if (isCar) carStart() else phoneClaim() }
            }
            Mode.Busy -> {
                detail.setText(R.string.sync_working)
                primary.visibility = View.GONE
            }
            is Mode.CarCode -> {
                big.visibility = View.VISIBLE
                big.text = m.offer.code.chunked(3).joinToString(" ")
                val left = ((m.offer.expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                detail.text = getString(R.string.sync_code_label) + "\n" + getString(R.string.sync_code_expires, left / 60, left % 60)
                primary.setText(R.string.sync_cancel)
                primary.setOnClickListener { cancelCar(m.offer) }
                privacy.visibility = View.GONE
            }
            is Mode.CarVerify -> {
                big.visibility = View.VISIBLE
                big.text = m.derived.keys.verifyCode
                detail.setText(R.string.sync_verify_car)
                primary.setText(R.string.sync_verify_same)
                primary.setOnClickListener { carConfirm(m) }
                secondary.visibility = View.VISIBLE
                secondary.setText(R.string.sync_verify_different)
                secondary.setOnClickListener { carReject(m.offer) }
                privacy.visibility = View.GONE
            }
            is Mode.PhoneWait -> {
                big.visibility = View.VISIBLE
                big.text = m.claim.keys.verifyCode
                detail.setText(R.string.sync_verify_phone)
                primary.setText(R.string.sync_cancel)
                primary.setOnClickListener { cancelPhone() }
                privacy.visibility = View.GONE
            }
            Mode.Paired -> renderPaired()
        }
    }

    private fun renderPaired() {
        val state = DriveSyncState(this)
        privacy.visibility = View.GONE
        val lines = mutableListOf<String>()
        lines += getString(if (isCar) R.string.sync_paired_car else R.string.sync_paired_phone)
        lines += if (DriveSyncManager.syncing) getString(R.string.sync_status_syncing)
        else if (state.lastSyncAt > 0) getString(R.string.sync_status_last, DriveFormat.relative(this, state.lastSyncAt))
        else getString(R.string.sync_status_never)
        val pending = state.pendingCount()
        if (pending > 0) lines += getString(R.string.sync_status_pending, pending)
        state.lastError?.let { lines += getString(R.string.sync_status_error, it) }
        detail.text = lines.joinToString("\n")
        primary.setText(R.string.sync_now)
        primary.isEnabled = !DriveSyncManager.syncing
        primary.setOnClickListener { DriveSyncManager.requestSync(this, "manual", 0L) }
        secondary.visibility = View.VISIBLE
        secondary.setText(R.string.sync_unpair)
        secondary.setOnClickListener { confirmUnpair() }
    }

    /** 1초마다: 코드 남은 시간 표시, 2초마다 상대 확인 */
    private fun tick() {
        if (!visible) return
        when (val m = mode) {
            is Mode.CarCode -> {
                if (System.currentTimeMillis() > m.offer.expiresAt) {
                    mode = Mode.Idle
                    message = getString(R.string.sync_code_expired)
                } else if ((System.currentTimeMillis() / 1000) % 2 == 0L) pollCar(m.offer)
                render()
            }
            is Mode.PhoneWait -> {
                if (System.currentTimeMillis() - m.since > PHONE_WAIT_MS) {
                    mode = Mode.Idle
                    message = getString(R.string.sync_wait_timeout)
                    render()
                } else if ((System.currentTimeMillis() / 1000) % 2 == 0L) pollPhone()
            }
            Mode.Paired -> renderPaired()
            else -> Unit
        }
        handler.postDelayed(::tick, 1_000L)
    }

    // endregion

    // region 차량

    private fun drivingBlocked(): Boolean {
        val restricted = isCar && (application as? MyCarHubApplication)?.restrictions?.isDrivingRestricted == true
        if (restricted) Toast.makeText(this, R.string.sync_driving_blocked, Toast.LENGTH_LONG).show()
        return restricted
    }

    private fun carStart() {
        if (drivingBlocked()) return
        message = null
        mode = Mode.Busy
        render()
        // 이미 다른 휴대폰과 연결돼 있었다면 서버의 이전 그룹을 지우고 새로 만든다 (이 기기의 연결 상태도 초기화)
        DriveSyncManager.submit({ DriveSyncManager.pairing(this).carStart() }) { r ->
            r.onSuccess {
                if (DriveSyncState(this).paired) DriveSyncState(this).clearPairing(null)
                mode = Mode.CarCode(it)
            }.onFailure { fail(it) }
            render()
        }
    }

    private var polling = false

    private fun pollCar(offer: SyncPairing.CarOffer) {
        if (polling) return
        polling = true
        DriveSyncManager.submit({ DriveSyncManager.pairing(this).carPoll(offer) }) { r ->
            polling = false
            if ((mode as? Mode.CarCode)?.offer !== offer) return@submit
            r.onSuccess { d -> if (d != null) mode = Mode.CarVerify(offer, d) }
                .onFailure {
                    if (it is SyncPairing.PairingGoneException) {
                        mode = Mode.Idle
                        message = getString(R.string.sync_code_expired)
                    }
                    // 일시적 네트워크 오류는 다음 확인에서 다시 시도
                }
            render()
        }
    }

    private fun carConfirm(m: Mode.CarVerify) {
        if (drivingBlocked()) return
        mode = Mode.Busy
        render()
        DriveSyncManager.submit({ DriveSyncManager.pairing(this).carConfirm(m.offer) }) { r ->
            r.onSuccess { membership ->
                DriveSyncManager.completePairing(this, membership.groupId, m.derived.keys.dataKey)
                Toast.makeText(this, R.string.sync_paired_toast, Toast.LENGTH_LONG).show()
                message = null
                mode = Mode.Paired
            }.onFailure { fail(it) }
            render()
        }
    }

    private fun carReject(offer: SyncPairing.CarOffer) {
        mode = Mode.Busy
        render()
        DriveSyncManager.submit({ DriveSyncManager.pairing(this).carReject(offer) }) {
            mode = Mode.Idle
            message = getString(R.string.sync_rejected_car)
            render()
        }
    }

    private fun cancelCar(offer: SyncPairing.CarOffer) {
        mode = Mode.Idle
        message = null
        render()
        DriveSyncManager.submit({ DriveSyncManager.pairing(this).carReject(offer) }) {}
    }

    // endregion

    // region 휴대폰

    private fun phoneClaim() {
        val code = input.text.toString().filter { it.isDigit() }
        if (code.length != 6) {
            message = getString(R.string.sync_code_invalid)
            render()
            return
        }
        message = null
        mode = Mode.Busy
        render()
        DriveSyncManager.submit({ DriveSyncManager.pairing(this).phoneClaim(code) }) { r ->
            r.onSuccess { claim ->
                if (claim == null) {
                    mode = Mode.Idle
                    message = getString(R.string.sync_code_wrong)
                } else {
                    if (DriveSyncState(this).paired) DriveSyncState(this).clearPairing(null)
                    input.setText("")
                    mode = Mode.PhoneWait(claim, System.currentTimeMillis())
                }
            }.onFailure { fail(it) }
            render()
        }
    }

    private fun pollPhone() {
        if (polling) return
        val wait = mode as? Mode.PhoneWait ?: return
        polling = true
        DriveSyncManager.submit({ DriveSyncManager.pairing(this).phonePoll() }) { r ->
            polling = false
            if (mode !== wait) return@submit
            r.onSuccess { m ->
                if (m != null) {
                    DriveSyncManager.completePairing(this, m.groupId, wait.claim.keys.dataKey)
                    Toast.makeText(this, R.string.sync_paired_toast, Toast.LENGTH_LONG).show()
                    message = null
                    mode = Mode.Paired
                }
            }.onFailure {
                if (it is SyncPairing.PairingGoneException) {
                    mode = Mode.Idle
                    message = getString(R.string.sync_rejected_phone)
                }
            }
            render()
        }
    }

    private fun cancelPhone() {
        mode = Mode.Idle
        message = null
        render()
        // 확인받지 못한 휴대폰 기록을 서버에서 지운다
        DriveSyncManager.submit({ DriveSyncManager.pairing(this).leave() }) {}
    }

    // endregion

    private fun confirmUnpair() {
        AlertDialog.Builder(this)
            .setTitle(R.string.sync_unpair)
            .setMessage(if (isCar) R.string.sync_unpair_confirm_car else R.string.sync_unpair_confirm_phone)
            .setPositiveButton(R.string.sync_unpair) { _, _ ->
                mode = Mode.Busy
                render()
                DriveSyncManager.unpair(this) { r ->
                    mode = Mode.Idle
                    message = null
                    r.onFailure { e ->
                        AlertDialog.Builder(this)
                            .setMessage(getString(R.string.sync_unpair_failed, DriveSyncManager.describe(this, e)))
                            .setPositiveButton(R.string.sync_unpair_local) { _, _ -> DriveSyncManager.unpairLocally(this) }
                            .setNegativeButton(android.R.string.cancel) { _, _ -> refreshMode() }
                            .show()
                        mode = Mode.Paired
                    }
                    render()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun fail(e: Throwable) {
        mode = if (DriveSyncState(this).paired) Mode.Paired else Mode.Idle
        message = DriveSyncManager.describe(this, e)
    }

    private fun buildLayout(): View {
        val scale = if (isCar) 1.5f else 1f
        val pad = (24 * resources.displayMetrics.density).toInt()
        fun text(sp: Float, bold: Boolean = false) = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * scale)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, pad / 3, 0, pad / 3)
        }
        title = text(22f, bold = true)
        status = text(15f)
        status.setTextColor(0xFFD84315.toInt())
        big = text(44f, bold = true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.1f
            typeface = Typeface.MONOSPACE
        }
        detail = text(16f)
        input = EditText(this).apply {
            hint = getString(R.string.sync_code_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(7))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f * scale)
            gravity = Gravity.CENTER
        }
        val minH = (if (isCar) 76 else 48) * resources.displayMetrics.density
        primary = Button(this).apply { minHeight = minH.toInt(); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * scale) }
        secondary = Button(this).apply { minHeight = minH.toInt(); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * scale) }
        privacy = text(13f).apply {
            setText(R.string.sync_privacy)
            alpha = 0.7f
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            listOf(title, status, big, detail, input, primary, secondary, privacy).forEach { addView(it) }
        }
        return ScrollView(this).apply {
            fitsSystemWindows = true
            addView(column)
        }
    }

    private companion object {
        const val PHONE_WAIT_MS = 10 * 60_000L
    }
}
