package com.mycarhub.assistant

import com.mycarhub.apps.model.FavoriteApp
import com.mycarhub.apps.model.LaunchableApp
import com.mycarhub.apps.system.InstalledAppCatalog
import com.mycarhub.assistant.core.AppCategory
import com.mycarhub.assistant.core.AppEntry
import com.mycarhub.assistant.core.AppMatch
import com.mycarhub.assistant.core.AppMatcher

/**
 * 음성 명령의 앱 이름 → 실행할 앱. '내 앱' 등록 앱을 먼저 보고, 없으면 설치된 런처 앱,
 * 그다음 종류("내비게이션", "음악")로 찾는다. PackageManager 조회라 백그라운드 스레드에서 호출한다.
 *
 * 등록되지 않은 앱도 실행 경로(AppLauncher)를 같이 쓰도록 [FavoriteApp] 형태로 돌려준다.
 */
class AppCommandResolver(private val catalog: InstalledAppCatalog) {

    sealed interface Resolution {
        data class Found(val app: FavoriteApp) : Resolution

        /** 후보가 여러 개 → 사용자가 고른다 */
        data class Choices(val apps: List<FavoriteApp>) : Resolution
        data class NotFound(val target: String) : Resolution
    }

    /** [registered]는 메인 스레드에서 미리 복사한 '내 앱' 목록. */
    fun resolve(target: String, registered: List<FavoriteApp>): Resolution {
        val byId = LinkedHashMap<String, FavoriteApp>()
        val entries = mutableListOf<AppEntry>()
        val registeredPackages = registered.map { it.packageName }.toSet()
        for (app in registered) {
            byId[app.id] = app
            entries += AppEntry(app.id, app.label, app.alias, app.packageName, registered = true)
        }
        for (app in catalog.queryLaunchableApps()) {
            if (app.packageName in registeredPackages || app.id in byId) continue
            byId[app.id] = app.toTarget()
            entries += app.toEntry(registered = false)
        }

        AppMatcher.match(target, entries).toResolution(byId)?.let { return it }

        val category = AppCategory.forName(target) ?: return Resolution.NotFound(target)
        // 1) 종류를 선언한 앱 (등록 앱이 있으면 등록 앱 우선)  2) 이름·패키지 단서
        val declared = catalog.queryAppsInCategory(category.intentCategory).map { app ->
            val entry = entries.firstOrNull { it.packageName == app.packageName } ?: app.toEntry(registered = false)
            byId.putIfAbsent(entry.id, app.toTarget())
            entry to 0
        }
        AppMatcher.pickBest(declared).toResolution(byId)?.let { return it }
        return AppMatcher.matchByHints(category, entries).toResolution(byId) ?: Resolution.NotFound(target)
    }

    private fun AppMatch.toResolution(byId: Map<String, FavoriteApp>): Resolution? = when (this) {
        is AppMatch.Single -> byId[app.id]?.let { Resolution.Found(it) }
        is AppMatch.Multiple -> Resolution.Choices(apps.mapNotNull { byId[it.id] }.take(MAX_CHOICES))
        AppMatch.None -> null
    }

    private fun LaunchableApp.toTarget() = FavoriteApp(packageName, activityName, label)

    private fun LaunchableApp.toEntry(registered: Boolean) = AppEntry(id, label, null, packageName, registered)

    private companion object {
        /** 주행 중에도 고를 수 있도록 후보는 3개까지만 보여준다 */
        const val MAX_CHOICES = 3
    }
}
