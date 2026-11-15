package com.mycarhub.drive

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 주차 위치 기록: 현재 위치를 한 번 얻어 [ParkingStore]에 저장한다.
 *
 * - 위치 권한이 없거나 제한 시간 안에 위치를 얻지 못하면 위치를 저장하지 않는다 (임의 위치·오래된 위치를 쓰지 않음).
 *   자동 감지는 [record]의 keepWithoutLocation으로 '위치 없는 주차 기록'(시각만)을 남겨 구역 메모·사진을 붙일 수 있게 한다.
 * - 마지막으로 알려진 위치는 [MAX_LAST_KNOWN_AGE_MS] 이내일 때만 쓴다.
 * - 주소는 Geocoder가 있을 때만 따로 채운다. Geocoder는 기기 제조사·구글의 주소 변환 서비스를 쓰므로 좌표가 그 서비스로 전달될 수 있다.
 */
object ParkingRecorder {

    private const val TAG = "ParkingRecorder"
    const val TIMEOUT_MS = 15_000L
    private const val MAX_LAST_KNOWN_AGE_MS = 2 * 60 * 1000L

    /** 이 오차(m) 이하인 위치가 오면 더 기다리지 않는다 */
    private const val GOOD_ACCURACY_M = 25f

    /** 이 오차(m)보다 크면 화면·알림에서 '정확하지 않을 수 있음'으로 알린다 */
    const val LOW_ACCURACY_M = 100f

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    fun hasLocationPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * 현재 위치를 주차 위치로 저장한다. 결과(저장한 위치 또는 null)는 메인 스레드로 알려준다.
     * [timeoutMs] 안에 얻은 위치 중 가장 정확한 것을 쓴다 ([currentLocation]).
     */
    fun record(
        context: Context,
        source: ParkingSpot.Source,
        timeoutMs: Long = TIMEOUT_MS,
        keepWithoutLocation: Boolean = false,
        onDone: (ParkingSpot?) -> Unit = {},
    ) {
        val app = context.applicationContext
        currentLocation(app, timeoutMs) { location ->
            if (location == null) {
                if (keepWithoutLocation) return@currentLocation onDone(saveWithoutLocation(app, source))
                Log.i(TAG, "parking not saved: no location source=$source")
                return@currentLocation onDone(null)
            }
            onDone(save(app, location, source))
        }
    }

    /**
     * 이미 얻은 위치(기어 P로 바뀔 때 잡아 둔 위치 등)를 주차 위치로 저장한다.
     * 자동 감지는 같은 주차를 다른 기기가 먼저 저장했으면 그 기록에 합친다 ([ParkingStore.saveDetected]). 남은 기록을 돌려준다.
     */
    fun save(context: Context, location: Location, source: ParkingSpot.Source): ParkingSpot {
        val app = context.applicationContext
        val spot = ParkingSpot(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyM = if (location.hasAccuracy()) location.accuracy else null,
            savedAt = System.currentTimeMillis(),
            address = null,
            source = source,
        )
        val stored = store(app, spot)
        Log.i(TAG, "parking saved source=$source accuracy=${spot.accuracyM} provider=${location.provider}")
        if (stored.hasLocation && stored.address == null) lookUpAddress(app, stored)
        return stored
    }

    /**
     * 위치를 얻지 못한 자동 감지 주차 (지하 주차장 등): 주차 시각만 있는 기록을 남긴다. 좌표를 만들지 않는다.
     * 사용자가 구역 메모·사진을 남길 수 있고, 같은 주차를 다른 기기가 위치와 함께 저장했으면 그 위치가 합쳐진다.
     */
    fun saveWithoutLocation(context: Context, source: ParkingSpot.Source): ParkingSpot {
        val app = context.applicationContext
        val spot = ParkingSpot(latitude = null, longitude = null, accuracyM = null, savedAt = System.currentTimeMillis(), address = null, source = source)
        val stored = store(app, spot)
        Log.i(TAG, "parking saved without location source=$source")
        return stored
    }

    private fun store(app: Context, spot: ParkingSpot): ParkingSpot {
        val store = ParkingStore(app)
        return if (spot.source == ParkingSpot.Source.MANUAL) spot.also(store::save) else store.saveDetected(spot)
    }

    /**
     * 위치 한 번 얻기 (메인 스레드에서 결과). 권한이 없으면 바로 null.
     *
     * GPS·통합·네트워크 위치를 함께 요청하고, 먼저 온 값이 아니라 [timeoutMs] 안에 온 값 중 오차가 가장 작은 것을 고른다.
     * 오차가 [GOOD_ACCURACY_M] 이하인 값이 오거나 모든 제공자가 답하면 바로 끝낸다.
     * 아무 값도 없으면 [MAX_LAST_KNOWN_AGE_MS] 이내의 마지막 위치를, 그것도 없으면 null.
     */
    fun currentLocation(context: Context, timeoutMs: Long = TIMEOUT_MS, onResult: (Location?) -> Unit) {
        if (!hasLocationPermission(context)) return onResult(null)
        val manager = context.getSystemService(LocationManager::class.java) ?: return onResult(null)
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        val recent = recentLastKnown(manager, providers)
        if (providers.isEmpty()) return onResult(recent)

        var finished = false
        var best: Location? = null
        var answered = 0
        val cancel = CancellationSignal()
        val listeners = mutableListOf<LocationListener>()
        fun finish() {
            if (finished) return
            finished = true
            cancel.cancel()
            listeners.forEach { runCatching { manager.removeUpdates(it) } }
            onResult(best ?: recent)
        }
        fun offer(location: Location?) {
            if (finished) return
            answered++
            if (location != null && accuracyOf(location) < accuracyOf(best)) best = location
            if (accuracyOf(best) <= GOOD_ACCURACY_M || answered >= providers.size) finish()
        }
        main.postDelayed({ finish() }, timeoutMs)
        try {
            for (provider in providers) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    manager.getCurrentLocation(provider, cancel, context.mainExecutor) { offer(it) }
                } else {
                    val listener = LocationListener { offer(it) }
                    listeners += listener
                    @Suppress("DEPRECATION")
                    manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "location permission lost", e)
            finish()
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "location provider unavailable", e)
            finish()
        }
    }

    /** 오차 반경(m). 오차를 모르는 위치는 가장 나쁜 값으로 본다. */
    private fun accuracyOf(location: Location?): Float =
        if (location != null && location.hasAccuracy()) location.accuracy else Float.MAX_VALUE

    private fun recentLastKnown(manager: LocationManager, providers: List<String>): Location? = try {
        providers.mapNotNull { manager.getLastKnownLocation(it) }
            .filter { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos < MAX_LAST_KNOWN_AGE_MS * 1_000_000 }
            .minByOrNull { accuracyOf(it) }
    } catch (e: SecurityException) {
        null
    }

    /** 주소를 따로 찾아 채운다 (Geocoder가 있을 때만, 위치가 있는 기록만) */
    fun lookUpAddress(context: Context, spot: ParkingSpot) {
        val lat = spot.latitude ?: return
        val lng = spot.longitude ?: return
        if (!Geocoder.isPresent()) return
        worker.execute {
            val address = try {
                @Suppress("DEPRECATION") // getFromLocation(…, listener)는 API 33+
                Geocoder(context, Locale.KOREA).getFromLocation(lat, lng, 1)?.firstOrNull()
                    ?.getAddressLine(0)?.removePrefix("대한민국 ")?.trim()
            } catch (e: Exception) {
                Log.i(TAG, "address lookup failed: ${e.javaClass.simpleName}")
                null
            }
            if (!address.isNullOrEmpty()) ParkingStore(context).setAddress(spot.savedAt, address)
        }
    }

    /** 두 위치 사이 거리(m) */
    fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val out = FloatArray(1)
        Location.distanceBetween(lat1, lng1, lat2, lng2, out)
        return out[0]
    }
}
