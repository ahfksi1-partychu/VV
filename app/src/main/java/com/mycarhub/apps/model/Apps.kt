package com.mycarhub.apps.model

/** MyCarHub 홈에 등록된 앱. 아이콘은 저장하지 않고 실행 시점에 PackageManager에서 읽는다. */
data class FavoriteApp(
    val packageName: String,
    val activityName: String,
    val label: String,
    val alias: String? = null,
) {
    val id: String get() = "$packageName/$activityName"

    /** 화면과 음성 응답에 쓰는 이름. 별칭이 있으면 별칭을 우선한다. */
    val displayName: String get() = alias?.takeIf { it.isNotBlank() } ?: label
}

/** 현재 기기에 설치되어 있고 런처에서 실행 가능한 앱. */
data class LaunchableApp(
    val packageName: String,
    val activityName: String,
    val label: String,
    val isPreinstalled: Boolean,
) {
    val id: String get() = "$packageName/$activityName"
}

sealed interface LaunchResult {
    data class Launched(val app: FavoriteApp) : LaunchResult
    data class NotInstalled(val app: FavoriteApp) : LaunchResult
    data class Disabled(val app: FavoriteApp) : LaunchResult
    data class NoLaunchableActivity(val app: FavoriteApp) : LaunchResult
    data class BlockedWhileDriving(val app: FavoriteApp) : LaunchResult
    data class Failed(val app: FavoriteApp, val reason: String) : LaunchResult

    /** 이름으로 실행할 때(음성 명령 등) 일치하는 등록 앱이 없거나 여러 개인 경우. */
    data class NoMatch(val query: String) : LaunchResult
    data class Ambiguous(val query: String, val candidates: List<FavoriteApp>) : LaunchResult
}

sealed interface AliasResult {
    data object Saved : AliasResult
    data object Cleared : AliasResult
    data object TooLong : AliasResult
    data class Duplicate(val other: FavoriteApp) : AliasResult
    data object NotFound : AliasResult
}
