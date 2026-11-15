package com.mycarhub.apps.system

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import com.mycarhub.apps.model.FavoriteApp
import com.mycarhub.apps.model.LaunchResult

/** 등록 앱 실행. 실행 전 설치·활성·주행 제한 상태를 확인하고 결과를 LaunchResult로 돌려준다. */
class AppLauncher(
    private val context: Context,
    private val catalog: InstalledAppCatalog,
    private val restrictions: CarRestrictionMonitor,
) {

    fun launch(app: FavoriteApp): LaunchResult {
        if (!catalog.isInstalled(app.packageName)) return LaunchResult.NotInstalled(app)
        if (!catalog.isPackageEnabled(app.packageName)) return LaunchResult.Disabled(app)

        val activity = catalog.resolveLaunchActivity(app) ?: return LaunchResult.NoLaunchableActivity(app)
        if (!activity.isEnabled) return LaunchResult.Disabled(app)
        if (restrictions.isDrivingRestricted && !restrictions.isDistractionOptimized(activity)) {
            return LaunchResult.BlockedWhileDriving(app)
        }

        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(activity.packageName, activity.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            context.startActivity(intent)
            LaunchResult.Launched(app)
        } catch (e: ActivityNotFoundException) {
            LaunchResult.NoLaunchableActivity(app)
        } catch (e: SecurityException) {
            LaunchResult.Failed(app, e.message ?: "보안 정책으로 실행이 거부되었습니다")
        }
    }
}
