package com.mycarhub.apps.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import com.mycarhub.apps.model.FavoriteApp
import com.mycarhub.apps.model.LaunchableApp
import java.text.Collator
import java.util.Locale

/**
 * 설치된 앱 조회.
 *
 * QUERY_ALL_PACKAGES 없이 동작하도록 AndroidManifest의 <queries>에 선언한
 * LAUNCHER / HOME intent에 응답하는 앱만 조회한다.
 */
@Suppress("DEPRECATION") // queryIntentActivities(Intent, Int): minSdk 28 호환
class InstalledAppCatalog(private val context: Context) {

    private val pm: PackageManager = context.packageManager

    /**
     * 앱 추가 화면에 보여줄 목록. MyCarHub 자신, 홈(런처) 앱, 초기 설정 마법사 같은 시스템 내부 앱,
     * 비활성/비공개 Activity는 제외한다.
     */
    fun queryLaunchableApps(): List<LaunchableApp> = query(launcherIntent())

    /**
     * 종류(Intent.CATEGORY_APP_MAPS 등)를 선언한 앱. 런처 진입점에 함께 선언하는 카테고리라
     * <queries>의 LAUNCHER 선언만으로 조회된다 (음성 명령 "내비게이션 실행해 줘" 등).
     */
    fun queryAppsInCategory(category: String): List<LaunchableApp> =
        query(Intent(Intent.ACTION_MAIN).addCategory(category))

    private fun query(intent: Intent): List<LaunchableApp> {
        val homePackages = queryHomePackages()
        val collator = Collator.getInstance(Locale.KOREAN)
        return pm.queryIntentActivities(intent, 0)
            .asSequence()
            .map { it.activityInfo }
            .filter { it.packageName != context.packageName }
            .filter { it.packageName !in homePackages }
            .filter { info -> INTERNAL_PACKAGE_MARKERS.none { info.packageName.contains(it) } }
            .filter { it.exported && it.isEnabled && it.applicationInfo.enabled }
            .map { info ->
                LaunchableApp(
                    packageName = info.packageName,
                    activityName = info.name,
                    label = info.loadLabel(pm).toString().trim().ifEmpty { info.packageName },
                    isPreinstalled = info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                )
            }
            .distinctBy { it.id }
            .sortedWith(compareBy(collator) { it.label })
            .toList()
    }

    fun isInstalled(packageName: String): Boolean = try {
        pm.getApplicationInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun isPackageEnabled(packageName: String): Boolean = try {
        pm.getApplicationInfo(packageName, 0).enabled
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** 등록된 Activity가 아직 런처 진입점인지 확인하고, 바뀌었으면 같은 패키지의 다른 진입점을 찾는다. */
    fun resolveLaunchActivity(app: FavoriteApp): ActivityInfo? {
        val exact = Intent(launcherIntent()).setClassName(app.packageName, app.activityName)
        pm.queryIntentActivities(exact, 0).firstOrNull()?.let { return it.activityInfo }
        val byPackage = Intent(launcherIntent()).setPackage(app.packageName)
        return pm.queryIntentActivities(byPackage, 0).firstOrNull()?.activityInfo
    }

    fun loadIcon(app: FavoriteApp): Drawable = try {
        pm.getActivityIcon(ComponentName(app.packageName, app.activityName))
    } catch (e: PackageManager.NameNotFoundException) {
        pm.defaultActivityIcon
    }

    fun loadIcon(app: LaunchableApp): Drawable = try {
        pm.getActivityIcon(ComponentName(app.packageName, app.activityName))
    } catch (e: PackageManager.NameNotFoundException) {
        pm.defaultActivityIcon
    }

    private fun queryHomePackages(): Set<String> =
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }
            .toSet()

    private fun launcherIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    private companion object {
        /** 런처 항목이 있어도 사용자가 직접 실행할 앱이 아닌 시스템 내부 패키지. */
        val INTERNAL_PACKAGE_MARKERS = listOf(".setupwizard", ".provision")
    }
}
