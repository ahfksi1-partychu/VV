package com.mycarhub.drive

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.mycarhub.assistant.core.AuthSession
import com.mycarhub.assistant.core.SessionStore
import com.mycarhub.assistant.core.SyncOutbox
import com.mycarhub.assistant.core.SyncProgress
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 차량 ↔ 휴대폰 동기화 상태 (기기 안에만, prefs "drive_sync"). 설계: docs/DRIVE_SYNC.md.
 *
 * - 페어링 전에는 [paired]가 false이고 보낼 목록도 쌓지 않는다 (데이터는 이 기기 밖으로 나가지 않음).
 * - 페어링하면 처음 한 번 모든 기록을 보낼 목록에 넣고([enqueueAll]), 이후 바뀐 것만 [markChanged]로 쌓는다.
 *   보낼 목록은 서버가 받았다고 확인한 뒤에만 지운다 ([SyncOutbox]).
 * - 서버 로그인 정보(갱신 토큰)와 데이터 키는 Android Keystore 키로 암호화해 저장한다 ([SyncSecrets]). 백업에서도 빠진다.
 */
class DriveSyncState(context: Context) : SyncProgress, SessionStore {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 이 기기의 고정 id (같은 시각 충돌의 순서 결정에만 쓴다. 서버 인증 정보가 아니다) */
    val deviceId: String
        get() = synchronized(LOCK) {
            prefs.getString(KEY_DEVICE, null) ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_DEVICE, it).apply() }
        }

    /** 차량·휴대폰이 짝지어져 있는지 */
    val paired: Boolean
        get() = prefs.getBoolean(KEY_PAIRED, false)

    val groupId: String? get() = prefs.getString(KEY_GROUP, null)

    val pairedAt: Long get() = prefs.getLong(KEY_PAIRED_AT, 0L)

    /** 페어링 때 만든 데이터 키 (서버로 가지 않음). 복호화가 안 되면 null. */
    fun dataKey(): ByteArray? = prefs.getString(KEY_DATA_KEY, null)?.let(SyncSecrets::decrypt)

    /** 레코드가 바뀌었음: 짝지어져 있을 때만 보낼 목록에 넣고 곧 보낸다. */
    fun markChanged(kind: String, id: String, updatedAt: Long) {
        if (!paired) return
        synchronized(LOCK) {
            val outbox = readOutbox()
            outbox.mark(SyncOutbox.key(kind, id), updatedAt)
            writeOutbox(outbox)
        }
        DriveSyncManager.requestSync(appContext, "change")
    }

    // region SyncProgress (보낼 목록·받은 위치)

    override fun pending(): List<Pair<String, Long>> = synchronized(LOCK) { readOutbox().pending() }

    override fun ack(key: String, updatedAt: Long) = synchronized(LOCK) {
        val outbox = readOutbox()
        outbox.ack(key, updatedAt)
        writeOutbox(outbox)
    }

    fun pendingCount(): Int = pending().size

    /** 서버에서 마지막으로 받은 변경 번호 (이 번호 다음부터 받는다). 적용을 끝낸 뒤에만 올린다. */
    override var lastPulledSeq: Long
        get() = prefs.getLong(KEY_SEQ, 0L)
        set(value) {
            prefs.edit().putLong(KEY_SEQ, value).commit()
        }

    private fun readOutbox(): SyncOutbox {
        val o = try {
            JSONObject(prefs.getString(KEY_OUTBOX, null) ?: "{}")
        } catch (e: Exception) {
            JSONObject()
        }
        return SyncOutbox(o.keys().asSequence().associateWith { o.getLong(it) })
    }

    private fun writeOutbox(outbox: SyncOutbox) {
        // commit: 보낼 목록은 앱이 곧 종료되어도 남아야 한다
        prefs.edit().putString(KEY_OUTBOX, JSONObject(outbox.toMap()).toString()).commit()
    }

    // endregion

    // region SessionStore (서버 로그인 정보)

    override fun load(): AuthSession? = prefs.getString(KEY_SESSION, null)?.let(SyncSecrets::decrypt)
        ?.let { AuthSession.fromJson(String(it, Charsets.UTF_8)) }

    override fun save(session: AuthSession) {
        val sealed = SyncSecrets.encrypt(session.toJson().toByteArray(Charsets.UTF_8))
        // 갱신 토큰은 쓸 때마다 바뀌므로 즉시 디스크에 쓴다 (잃으면 다시 페어링해야 함)
        prefs.edit().putString(KEY_SESSION, sealed).commit()
    }

    // endregion

    /** 페어링 완료: 데이터 키·그룹을 저장하고 이 기기의 모든 기록을 처음 한 번 보낼 목록에 넣는다. */
    fun setPaired(groupId: String, dataKey: ByteArray) {
        synchronized(LOCK) {
            prefs.edit()
                .putString(KEY_GROUP, groupId)
                .putString(KEY_DATA_KEY, SyncSecrets.encrypt(dataKey))
                .putBoolean(KEY_PAIRED, true)
                .putLong(KEY_PAIRED_AT, System.currentTimeMillis())
                .putLong(KEY_SEQ, 0L)
                .remove(KEY_OUTBOX)
                .remove(KEY_ERROR)
                .remove(KEY_LAST_SYNC)
                .remove(KEY_FAILS)
                .commit()
        }
        enqueueAll()
    }

    /** 처음 보낼 목록: 할 일·주차·차에 둔 물건 (삭제 표시 포함) */
    fun enqueueAll() {
        val entries = TodoStore(appContext).syncIds().map { SyncOutbox.key(TodoStore.KIND, it.first) to it.second } +
            ParkingStore(appContext).syncIds().map { SyncOutbox.key(ParkingStore.KIND, it.first) to it.second } +
            CarItemStore(appContext).syncIds().map { SyncOutbox.key(CarItemStore.KIND, it.first) to it.second }
        synchronized(LOCK) {
            val outbox = readOutbox()
            entries.forEach { (k, at) -> outbox.mark(k, at) }
            writeOutbox(outbox)
        }
    }

    /**
     * 연결 해제 (사용자가 해제했거나 상대가 해제함). 기기 안의 할 일·주차·물건은 그대로 둔다.
     * [forgetSession]: 서버 신원까지 버린다 (갱신 토큰이 무효가 된 경우).
     */
    fun clearPairing(reason: String?, forgetSession: Boolean = false) {
        synchronized(LOCK) {
            val e = prefs.edit()
                .putBoolean(KEY_PAIRED, false)
                .remove(KEY_GROUP).remove(KEY_DATA_KEY).remove(KEY_OUTBOX).remove(KEY_SEQ).remove(KEY_FAILS)
                .remove(KEY_PAIRED_AT)
            if (reason != null) e.putString(KEY_ERROR, reason) else e.remove(KEY_ERROR)
            if (forgetSession) e.remove(KEY_SESSION)
            e.commit()
        }
    }

    // region 표시용 상태

    val lastSyncAt: Long get() = prefs.getLong(KEY_LAST_SYNC, 0L)

    /** 마지막 실패 이유 (성공하면 지운다). 해제된 이유도 여기에. */
    val lastError: String? get() = prefs.getString(KEY_ERROR, null)

    val failCount: Int get() = prefs.getInt(KEY_FAILS, 0)

    fun recordSuccess() {
        prefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).remove(KEY_ERROR).putInt(KEY_FAILS, 0).apply()
    }

    fun recordFailure(reason: String) {
        prefs.edit().putString(KEY_ERROR, reason).putInt(KEY_FAILS, failCount + 1).apply()
    }

    // endregion

    private companion object {
        const val PREFS = "drive_sync"
        const val KEY_DEVICE = "device_id"
        const val KEY_PAIRED = "paired"
        const val KEY_PAIRED_AT = "paired_at"
        const val KEY_GROUP = "group_id"
        const val KEY_DATA_KEY = "data_key"
        const val KEY_SESSION = "session"
        const val KEY_OUTBOX = "outbox"
        const val KEY_SEQ = "last_pulled_seq"
        const val KEY_LAST_SYNC = "last_sync_at"
        const val KEY_ERROR = "last_error"
        const val KEY_FAILS = "fail_count"
        val LOCK = Any()
    }
}

/**
 * 동기화 비밀값(갱신 토큰·데이터 키) 암호화: Android Keystore의 AES-256-GCM 키 (키는 기기 밖으로 꺼낼 수 없음).
 * Keystore 키를 잃으면(앱 데이터 삭제 등) 복호화가 null → 다시 페어링해야 한다.
 */
object SyncSecrets {
    private const val ALIAS = "mycarhub_drive_sync"
    private const val TAG = "SyncSecrets"

    @Synchronized
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun encrypt(plain: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val iv = Base64.encodeToString(c.iv, Base64.NO_WRAP)
        return iv + ":" + Base64.encodeToString(c.doFinal(plain), Base64.NO_WRAP)
    }

    fun decrypt(sealed: String): ByteArray? = try {
        val (iv, body) = sealed.split(':', limit = 2)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        c.doFinal(Base64.decode(body, Base64.NO_WRAP))
    } catch (e: Exception) {
        Log.w(TAG, "decrypt failed: ${e.javaClass.simpleName}")
        null
    }
}
