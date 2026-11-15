package com.mycarhub.assistant.core

/** 음성 명령으로 실행할 수 있는 앱 하나 (등록 앱 또는 설치된 런처 앱). */
data class AppEntry(
    val id: String,
    val label: String,
    val alias: String?,
    val packageName: String,
    /** MyCarHub '내 앱'에 등록된 앱. 같은 점수면 등록 앱을 우선한다. */
    val registered: Boolean,
)

/** 앱 이름 대신 종류로 부르는 경우 ("내비게이션", "음악"). [intentCategory]는 Android Intent.CATEGORY_APP_* 값. */
enum class AppCategory(
    val intentCategory: String,
    val spoken: List<String>,
    /** 종류 정보를 선언하지 않은 앱을 찾기 위한 이름·패키지 단서 */
    val hints: List<String>,
) {
    MAPS(
        "android.intent.category.APP_MAPS",
        listOf("내비게이션", "네비게이션", "내비", "네비", "길안내", "길찾기", "지도", "navigation", "navi", "maps", "map"),
        listOf("navi", "map", "내비", "네비", "지도", "길안내"),
    ),
    MUSIC(
        "android.intent.category.APP_MUSIC",
        listOf("음악", "뮤직", "노래", "music", "음악앱"),
        listOf("music", "음악", "뮤직", "player"),
    ),
    MESSAGING(
        "android.intent.category.APP_MESSAGING",
        listOf("문자", "메시지", "메세지", "message", "sms"),
        listOf("message", "messaging", "sms", "문자", "메시지"),
    ),
    BROWSER("android.intent.category.APP_BROWSER", listOf("브라우저", "인터넷", "웹", "browser"), listOf("browser", "브라우저", "인터넷")),
    CALENDAR("android.intent.category.APP_CALENDAR", listOf("캘린더", "달력", "일정", "calendar"), listOf("calendar", "캘린더", "달력")),
    CONTACTS("android.intent.category.APP_CONTACTS", listOf("연락처", "주소록", "contacts"), listOf("contacts", "연락처")),
    GALLERY("android.intent.category.APP_GALLERY", listOf("갤러리", "사진", "앨범", "gallery", "photos"), listOf("gallery", "photo", "갤러리", "사진")),
    EMAIL("android.intent.category.APP_EMAIL", listOf("이메일", "메일", "email", "mail"), listOf("mail", "메일")),
    CALCULATOR("android.intent.category.APP_CALCULATOR", listOf("계산기", "calculator"), listOf("calculator", "계산기")),
    FILES("android.intent.category.APP_FILES", listOf("파일", "파일관리자", "내파일", "files"), listOf("files", "파일")),
    ;

    companion object {
        fun forName(name: String): AppCategory? {
            val key = AppMatcher.normalize(name)
            return entries.firstOrNull { category -> category.spoken.any { AppMatcher.normalize(it) == key } }
        }
    }
}

sealed interface AppMatch {
    data class Single(val app: AppEntry) : AppMatch
    data class Multiple(val apps: List<AppEntry>) : AppMatch
    data object None : AppMatch
}

/**
 * 말한 이름 → 실행할 앱. 별칭·앱 이름·널리 쓰는 줄임말(카톡 → 카카오톡)·종류(내비게이션)를 차례로 본다.
 * 등록 앱과 설치 앱 목록은 호출하는 쪽(Android)이 넘겨준다.
 */
object AppMatcher {

    /** 같은 앱을 부르는 다른 이름과 대표 패키지. 앱 이름(label)이 영어이거나 줄여 부르는 경우를 위해. */
    private class KnownApp(val names: List<String>, val packages: List<String>)

    private val KNOWN_APPS = listOf(
        KnownApp(listOf("카카오톡", "카톡", "kakaotalk", "카카오 톡"), listOf("com.kakao.talk")),
        KnownApp(listOf("카카오내비", "카카오 내비", "카카오네비"), listOf("com.locnall.KimGiSa")),
        KnownApp(listOf("카카오맵", "카카오 지도"), listOf("net.daum.android.map")),
        KnownApp(listOf("티맵", "t맵", "tmap", "티 맵"), listOf("com.skt.tmap.ku", "com.skt.skaf.l001mtm091")),
        KnownApp(listOf("네이버지도", "네이버 지도", "naver map"), listOf("com.nhn.android.nmap")),
        KnownApp(listOf("네이버", "naver"), listOf("com.nhn.android.search")),
        KnownApp(listOf("유튜브", "youtube", "유투브"), listOf("com.google.android.youtube")),
        KnownApp(listOf("유튜브뮤직", "유튜브 뮤직", "youtube music", "유투브뮤직"), listOf("com.google.android.apps.youtube.music")),
        KnownApp(listOf("멜론", "melon"), listOf("com.iloen.melon")),
        KnownApp(listOf("지니", "지니뮤직", "genie"), listOf("com.ktmusic.geniemusic")),
        KnownApp(listOf("플로", "flo"), listOf("skplanet.musicmate")),
        KnownApp(listOf("스포티파이", "spotify"), listOf("com.spotify.music")),
        KnownApp(listOf("크롬", "chrome"), listOf("com.android.chrome")),
        KnownApp(listOf("설정", "환경설정", "settings"), listOf("com.android.car.settings", "com.android.settings")),
        KnownApp(listOf("전화", "전화앱", "다이얼", "phone", "dialer"), listOf("com.android.car.dialer", "com.android.dialer")),
        KnownApp(listOf("라디오", "radio"), listOf("com.android.car.radio")),
        KnownApp(listOf("플레이스토어", "플레이 스토어", "구글 플레이", "play store"), listOf("com.android.vending")),
    )

    fun normalize(text: String): String = text.lowercase().filterNot { it.isWhitespace() }

    fun match(spoken: String, apps: List<AppEntry>): AppMatch {
        val key = normalize(spoken)
        if (key.isEmpty() || apps.isEmpty()) return AppMatch.None
        val known = KNOWN_APPS.firstOrNull { app -> app.names.any { normalize(it) == key } }
        val names = (listOf(key) + known?.names.orEmpty().map(::normalize)).distinct()
        val packages = known?.packages.orEmpty()

        val scored = apps.mapNotNull { app -> score(app, names, packages)?.let { app to it } }
        return pickBest(scored)
    }

    /** 종류(Intent 카테고리)를 선언하지 않은 앱을 이름·패키지 단서로 찾는다. 예: 패키지에 "navi"가 들어간 앱. */
    fun matchByHints(category: AppCategory, apps: List<AppEntry>): AppMatch {
        val hints = category.hints.map(::normalize)
        val found = apps.filter { app ->
            val label = normalize(app.label)
            val alias = app.alias?.let(::normalize)
            val pkg = app.packageName.lowercase()
            hints.any { label.contains(it) || alias?.contains(it) == true || pkg.contains(it) }
        }
        return pickBest(found.map { it to 0 })
    }

    /** 점수가 가장 좋은 앱들 중 등록 앱이 있으면 등록 앱만 남긴다. */
    fun pickBest(scored: List<Pair<AppEntry, Int>>): AppMatch {
        if (scored.isEmpty()) return AppMatch.None
        val best = scored.minOf { it.second }
        val top = scored.filter { it.second == best }.map { it.first }.distinctBy { it.packageName }
        val preferred = top.filter { it.registered }.ifEmpty { top }
        return if (preferred.size == 1) AppMatch.Single(preferred.first()) else AppMatch.Multiple(preferred)
    }

    private fun score(app: AppEntry, names: List<String>, packages: List<String>): Int? {
        val alias = app.alias?.let(::normalize)
        val label = normalize(app.label)
        var best: Int? = null
        fun consider(value: Int) {
            if (best == null || value < best!!) best = value
        }
        if (app.packageName in packages) consider(1)
        for (name in names) {
            when {
                alias == name -> consider(0)
                label == name -> consider(1)
                alias?.startsWith(name) == true -> consider(2)
                label.startsWith(name) -> consider(3)
                alias?.contains(name) == true -> consider(4)
                label.contains(name) -> consider(5)
                // "카카오톡메신저" 처럼 앱 이름을 포함해 길게 말한 경우
                label.length >= MIN_CONTAINED_LABEL && name.contains(label) -> consider(6)
            }
        }
        // 한 글자 이름은 우연히 포함되기 쉬우니 정확히 일치할 때만 인정
        if (names.first().length < 2 && best != null && best!! > 1) return null
        return best
    }

    private const val MIN_CONTAINED_LABEL = 2
}
