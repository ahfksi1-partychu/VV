package com.mycarhub.drive

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.mycarhub.BuildConfig
import com.mycarhub.R
import com.mycarhub.assistant.core.LocalRecord
import com.mycarhub.assistant.core.SessionKeeper
import com.mycarhub.assistant.core.SessionLostException
import com.mycarhub.assistant.core.SupabaseApi
import com.mycarhub.assistant.core.SupabaseRemote
import com.mycarhub.assistant.core.SyncBackoff
import com.mycarhub.assistant.core.SyncEngine
import com.mycarhub.assistant.core.SyncHttpException
import com.mycarhub.assistant.core.SyncLocal
import com.mycarhub.assistant.core.SyncMeta
import com.mycarhub.assistant.core.SyncPairing
import com.mycarhub.platform.Platform
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * 차량 ↔ 휴대폰 동기화 실행 (Supabase). 설계: docs/DRIVE_SYNC.md.
 *
 * - 네트워크 작업은 모두 한 백그라운드 스레드에서 차례로 (토큰 갱신이 겹치지 않게). 페어링 화면의 호출도 같은 스레드.
 * - 언제: 앱 시작, 기록이 바뀐 직후(2초 모아서), 네트워크가 다시 연결될 때, 앱이 살아 있는 동안 5분마다,
 *   앱이 꺼져 있어도 JobScheduler로 약 15분마다(네트워크 있을 때)와 실패 후 재시도.
 * - 실패하면 기기 데이터·보낼 목록은 그대로 두고 30초부터 최대 30분 간격으로 다시 시도한다 ([SyncBackoff]).
 * - 상대 기기가 연결을 해제했거나 서버 신원을 잃으면 이 기기도 해제 상태로 바꾼다 (기기 안의 데이터는 그대로).
 */
object DriveSyncManager {

    private const val TAG = "DriveSync"
    private const val CHANGE_DELAY_MS = 2_000L
    private const val PERIODIC_MS = 5 * 60_000L
    private const val JOB_RETRY = 73_010
    private const val JOB_PERIODIC = 73_011

    private val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "drive-sync").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private var scheduled: ScheduledFuture<*>? = null
    private var scheduledAt = 0L
    private var started = false

    @Volatile
    var syncing = false
        private set

    private val listeners = mutableSetOf<() -> Unit>()

    /** 서버 주소·공개 키가 빌드에 들어 있는지 (local.properties). 없으면 동기화 기능 전체가 꺼진다. */
    val isConfigured: Boolean
        get() = BuildConfig.SUPABASE_URL.startsWith("https://") && !BuildConfig.SUPABASE_URL.contains("...") &&
            BuildConfig.SUPABASE_KEY.isNotBlank()

    fun addListener(l: () -> Unit) = synchronized(listeners) { listeners += l }
    fun removeListener(l: () -> Unit) = synchronized(listeners) { listeners -= l }
    private fun notifyListeners() {
        val copy = synchronized(listeners) { listeners.toList() }
        main.post { copy.forEach { it() } }
    }

    private fun api() = SupabaseApi(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY)

    /** 앱 시작 시 (두 빌드 공통): 네트워크 복구 감시와 첫 동기화 */
    fun start(context: Context) {
        val app = context.applicationContext
        synchronized(this) {
            if (started || !isConfigured) return
            started = true
        }
        try {
            app.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = requestSync(app, "network", 1_000L)
            })
        } catch (e: Exception) {
            Log.w(TAG, "network callback: ${e.javaClass.simpleName}")
        }
        if (DriveSyncState(app).paired) {
            schedulePeriodicJob(app)
            requestSync(app, "start", 3_000L)
        }
    }

    /** 곧 동기화 (여러 번 불러도 한 번으로 모은다). 이미 더 이른 예약이 있으면 그대로 둔다. */
    fun requestSync(context: Context, reason: String, delayMs: Long = CHANGE_DELAY_MS) {
        if (!isConfigured) return
        val app = context.applicationContext
        synchronized(this) {
            val at = System.currentTimeMillis() + delayMs
            val current = scheduled
            if (current != null && !current.isDone && scheduledAt <= at) return
            current?.cancel(false)
            scheduledAt = at
            scheduled = executor.schedule({ runOnce(app, reason) }, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    /** JobScheduler에서: 지금 한 번 동기화하고 결과를 알려 준다 (백그라운드 스레드). */
    fun runForJob(context: Context, done: (Boolean) -> Unit) {
        val app = context.applicationContext
        executor.execute { done(runOnce(app, "job")) }
    }

    private fun runOnce(app: Context, reason: String): Boolean {
        synchronized(this) { scheduled = null }
        val state = DriveSyncState(app)
        if (!state.paired) return true
        syncing = true
        notifyListeners()
        try {
            val key = state.dataKey()
            val group = state.groupId
            if (key == null || group == null) {
                unpaired(app, state, app.getString(R.string.sync_error_key_lost), forgetSession = false)
                return true
            }
            val api = api()
            val keeper = SessionKeeper(api, state)
            val m = keeper.call { api.membership(it) }
            if (m == null || m.groupId != group || !m.confirmed) {
                unpaired(app, state, app.getString(R.string.sync_error_unpaired_remote), forgetSession = false)
                return true
            }
            val r = SyncEngine(StoresLocal(app, state), state, SupabaseRemote(api, keeper, group), key).run()
            Log.i(TAG, "sync ok ($reason): pushed=${r.pushed} pulled=${r.pulled} applied=${r.applied} rejected=${r.rejected} refused=${r.refused} dropped=${r.dropped}")
            state.recordSuccess()
            cancelJob(app, JOB_RETRY)
            if (state.pendingCount() > 0) requestSync(app, "more", 1_000L) else requestSync(app, "periodic", PERIODIC_MS)
            return true
        } catch (e: SessionLostException) {
            Log.w(TAG, "session lost: ${e.message}")
            unpaired(app, state, app.getString(R.string.sync_error_session_lost), forgetSession = true)
            return true
        } catch (e: Exception) {
            val why = describe(app, e)
            Log.w(TAG, "sync failed ($reason): ${e.javaClass.simpleName} ${(e as? SyncHttpException)?.code ?: ""} ${e.message}")
            state.recordFailure(why)
            val delay = SyncBackoff.delayMs(state.failCount - 1, Random.nextDouble())
            requestSync(app, "retry", delay)
            scheduleRetryJob(app)
            return false
        } finally {
            syncing = false
            notifyListeners()
        }
    }

    private fun unpaired(app: Context, state: DriveSyncState, reason: String?, forgetSession: Boolean) {
        Log.i(TAG, "unpaired: $reason")
        state.clearPairing(reason, forgetSession)
        cancelJob(app, JOB_RETRY)
        cancelJob(app, JOB_PERIODIC)
    }

    fun describe(context: Context, e: Throwable): String = when (e) {
        is SyncHttpException -> when {
            e.isForbidden -> context.getString(R.string.sync_error_forbidden)
            e.code == 429 -> context.getString(R.string.sync_error_rate)
            e.serverCode == "anonymous_provider_disabled" -> context.getString(R.string.sync_error_anon_disabled)
            else -> context.getString(R.string.sync_error_server, e.code)
        }
        is IOException -> context.getString(R.string.sync_error_network)
        else -> context.getString(R.string.sync_error_other, e.javaClass.simpleName)
    }

    // region 페어링·해제 (화면에서 부른다)

    /** 네트워크 작업을 동기화 스레드에서 하고 결과를 메인 스레드로 */
    fun <T> submit(work: () -> T, done: (Result<T>) -> Unit) {
        executor.execute {
            val r = runCatching(work)
            main.post { done(r) }
        }
    }

    fun pairing(context: Context) = SyncPairing(api(), SessionKeeper(api(), DriveSyncState(context)))

    /** 페어링 완료 (메인 스레드에서): 저장하고 처음 동기화를 바로 시작한다. */
    fun completePairing(context: Context, groupId: String, dataKey: ByteArray) {
        val app = context.applicationContext
        DriveSyncState(app).setPaired(groupId, dataKey)
        schedulePeriodicJob(app)
        requestSync(app, "paired", 0L)
        notifyListeners()
    }

    /** 연결 해제: 서버에서 나간다 (차량이면 서버의 그룹 레코드가 모두 지워짐). 실패하면 예외 → 화면이 '이 기기에서만 해제'를 묻는다. */
    fun unpair(context: Context, done: (Result<Unit>) -> Unit) {
        val app = context.applicationContext
        submit({ pairing(app).leave() }) { r ->
            if (r.isSuccess) unpairLocally(app)
            done(r)
        }
    }

    /** 서버에 알리지 않고 이 기기만 해제 (네트워크가 안 될 때) */
    fun unpairLocally(context: Context) {
        val app = context.applicationContext
        executor.execute {
            unpaired(app, DriveSyncState(app), reason = null, forgetSession = false)
            notifyListeners()
        }
    }

    // endregion

    // region JobScheduler (앱이 꺼져 있을 때)

    private fun scheduleRetryJob(app: Context) {
        val js = app.getSystemService(JobScheduler::class.java) ?: return
        if (js.getPendingJob(JOB_RETRY) != null) return
        val info = JobInfo.Builder(JOB_RETRY, ComponentName(app, DriveSyncJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setMinimumLatency(30_000L)
            .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .build()
        runCatching { js.schedule(info) }.onFailure { Log.w(TAG, "retry job: ${it.javaClass.simpleName}") }
    }

    private fun schedulePeriodicJob(app: Context) {
        val js = app.getSystemService(JobScheduler::class.java) ?: return
        if (js.getPendingJob(JOB_PERIODIC) != null) return
        val info = JobInfo.Builder(JOB_PERIODIC, ComponentName(app, DriveSyncJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(15 * 60_000L)
            .setPersisted(false)
            .build()
        runCatching { js.schedule(info) }.onFailure { Log.w(TAG, "periodic job: ${it.javaClass.simpleName}") }
    }

    private fun cancelJob(app: Context, id: Int) {
        runCatching { app.getSystemService(JobScheduler::class.java)?.cancel(id) }
    }

    // endregion
}

/** JobScheduler 진입점: 앱이 꺼져 있어도 네트워크가 있을 때 동기화 */
class DriveSyncJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        if (!DriveSyncManager.isConfigured || !DriveSyncState(this).paired) return false
        DriveSyncManager.runForJob(this) { ok -> jobFinished(params, !ok && params.jobId != 73_011) }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}

/** 기기 안의 저장소 ↔ 동기화 엔진 연결 (할 일·주차·차에 둔 물건) */
private class StoresLocal(context: Context, private val state: DriveSyncState) : SyncLocal {
    private val app = context.applicationContext
    private val todos = TodoStore(context)
    private val parking = ParkingStore(context)
    private val items = CarItemStore(context)

    override fun snapshot(kind: String, id: String): LocalRecord? = when (kind) {
        TodoStore.KIND -> todos.metaOf(id)?.let { meta ->
            if (meta.deleted) LocalRecord(meta, null)
            else todos.todos().firstOrNull { it.id == id }?.let { LocalRecord(meta, todos.payload(it)) }
        }
        ParkingStore.KIND -> parking.metaOf(id, state.deviceId)?.let { meta ->
            if (meta.deleted) LocalRecord(meta, null)
            else parking.load()?.takeIf { it.id == id }?.let { LocalRecord(meta, parking.syncPayload(it)) }
        }
        CarItemStore.KIND -> items.metaOf(id)?.let { meta ->
            if (meta.deleted) LocalRecord(meta, null)
            else items.get(id)?.let { LocalRecord(meta, items.payload(it)) }
        }
        else -> null
    }

    override fun applyRemote(kind: String, meta: SyncMeta, payload: String?): Boolean = when (kind) {
        TodoStore.KIND -> todos.applyRemote(meta, payload?.let(todos::fromPayload))
        ParkingStore.KIND ->
            if (meta.deleted) {
                parking.applyRemote(meta.id, true, null, meta.updatedAt)
            } else {
                val applied = payload?.let { parking.fromSyncPayload(it, meta.id) }?.let { parking.applyRemote(meta.id, false, it, 0L) } ?: false
                // 다른 기기(차량)에서 감지한 주차가 지금 기록이 되었으면 이 기기(휴대폰)에서 기록 안내를 띄울 수 있다
                if (applied) parking.load()?.let { Platform.onParkingFromOtherDevice(app, it) }
                applied
            }
        CarItemStore.KIND -> items.applyRemote(meta, payload?.let(items::fromPayload))
        else -> false
    }
}
