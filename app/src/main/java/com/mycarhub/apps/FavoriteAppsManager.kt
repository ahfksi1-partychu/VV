package com.mycarhub.apps

import com.mycarhub.apps.data.FavoriteAppStore
import com.mycarhub.apps.model.AliasResult
import com.mycarhub.apps.model.FavoriteApp
import com.mycarhub.apps.model.LaunchResult
import com.mycarhub.apps.model.LaunchableApp
import com.mycarhub.apps.system.AppLauncher
import com.mycarhub.apps.system.InstalledAppCatalog

/**
 * 자주 쓰는 앱의 등록·검색·실행 로직.
 *
 * UI(MainActivity)와 이후 음성 명령 처리가 같은 진입점을 쓰도록 화면 코드에 의존하지 않는다.
 * 메인 스레드에서 호출하는 것을 전제로 한다(queryAvailableApps 제외).
 */
class FavoriteAppsManager(
    private val store: FavoriteAppStore,
    private val catalog: InstalledAppCatalog,
    private val launcher: AppLauncher,
) {

    fun interface Listener {
        fun onFavoritesChanged(favorites: List<FavoriteApp>)
    }

    private val apps: MutableList<FavoriteApp> = store.load().toMutableList()
    private val listeners = mutableSetOf<Listener>()

    val favorites: List<FavoriteApp> get() = apps.toList()

    fun addListener(listener: Listener) {
        listeners += listener
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    /** 앱 추가 후보. PackageManager 조회라서 백그라운드 스레드에서 호출해도 된다. */
    fun queryAvailableApps(): List<LaunchableApp> = catalog.queryLaunchableApps()

    fun isRegistered(app: LaunchableApp): Boolean =
        apps.any { it.id == app.id || it.packageName == app.packageName }

    fun add(app: LaunchableApp): Boolean {
        if (isRegistered(app)) return false
        apps += FavoriteApp(app.packageName, app.activityName, app.label)
        commit()
        return true
    }

    fun remove(id: String): Boolean {
        val removed = apps.removeAll { it.id == id }
        if (removed) commit()
        return removed
    }

    /** offset만큼 위치 이동(-1: 위로, +1: 아래로). */
    fun move(id: String, offset: Int): Boolean {
        val from = apps.indexOfFirst { it.id == id }
        val to = from + offset
        if (from < 0 || to !in apps.indices) return false
        apps.add(to, apps.removeAt(from))
        commit()
        return true
    }

    fun setAlias(id: String, alias: String?): AliasResult {
        val index = apps.indexOfFirst { it.id == id }
        if (index < 0) return AliasResult.NotFound
        val trimmed = alias?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            apps[index] = apps[index].copy(alias = null)
            commit()
            return AliasResult.Cleared
        }
        if (trimmed.length > MAX_ALIAS_LENGTH) return AliasResult.TooLong
        // 음성 명령에서 헷갈리지 않도록 다른 등록 앱의 이름·별칭과 겹치지 않게 한다.
        val key = normalize(trimmed)
        apps.firstOrNull { it.id != id && (normalize(it.label) == key || it.alias?.let(::normalize) == key) }
            ?.let { return AliasResult.Duplicate(it) }
        apps[index] = apps[index].copy(alias = trimmed)
        commit()
        return AliasResult.Saved
    }

    /**
     * 이름(별칭 또는 앱 이름)으로 등록 앱 검색. 정확히 일치 > 앞부분 일치 > 포함 순으로 정렬한다.
     * 공백과 대소문자는 무시한다. 예: "카톡", "내비", "kakao".
     */
    fun search(query: String): List<FavoriteApp> {
        val key = normalize(query)
        if (key.isEmpty()) return emptyList()
        return apps.mapNotNull { app -> matchScore(app, key)?.let { app to it } }
            .sortedBy { it.second }
            .map { it.first }
    }

    fun launch(id: String): LaunchResult {
        val app = apps.firstOrNull { it.id == id } ?: return LaunchResult.NoMatch(id)
        return launcher.launch(app)
    }

    /** 음성 명령 등 이름 기반 실행. 가장 좋은 일치가 하나일 때만 실행한다. */
    fun launchByName(query: String): LaunchResult {
        val key = normalize(query)
        val scored = apps.mapNotNull { app -> matchScore(app, key)?.let { app to it } }
        if (scored.isEmpty()) return LaunchResult.NoMatch(query)
        val best = scored.minOf { it.second }
        val top = scored.filter { it.second == best }.map { it.first }
        return if (top.size == 1) launcher.launch(top.first()) else LaunchResult.Ambiguous(query, top)
    }

    private fun matchScore(app: FavoriteApp, key: String): Int? {
        if (key.isEmpty()) return null
        val alias = app.alias?.let(::normalize)
        val label = normalize(app.label)
        return when {
            alias == key -> 0
            label == key -> 1
            alias?.startsWith(key) == true -> 2
            label.startsWith(key) -> 3
            alias?.contains(key) == true -> 4
            label.contains(key) -> 5
            else -> null
        }
    }

    private fun commit() {
        store.save(apps)
        val snapshot = favorites
        listeners.toList().forEach { it.onFavoritesChanged(snapshot) }
    }

    companion object {
        const val MAX_ALIAS_LENGTH = 20

        fun normalize(text: String): String = text.lowercase().filterNot { it.isWhitespace() }
    }
}
