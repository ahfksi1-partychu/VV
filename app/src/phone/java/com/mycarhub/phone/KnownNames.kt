package com.mycarhub.phone

import android.content.Context

/**
 * 사용자가 직접 등록한 사람 이름 (폰 빌드). 비슷한 이름 찾기의 후보로 쓴다.
 *
 * 카카오톡 친구 이름은 카카오톡 앱 안에만 있고, Android 연락처에 들어오지 않으며 다른 앱이 읽을 수 있는
 * 공개 인터페이스도 없다 (Kakao SDK의 친구 목록 API는 카카오 로그인·동의를 거쳐 카카오 서버에서 받아오는 방식이고,
 * 같은 앱을 쓰는 친구만 준다). 그래서 자주 연락하는 사람의 이름을 사용자가 직접 등록해 두게 한다.
 * 이름만 기기 안에 저장하며 메시지 내용은 저장하지 않는다. 등록된 이름이 있어도 실제 메시지는 그 사람의 알림이 있을 때만 읽는다.
 */
object KnownNames {
    private const val PREFS = "phone_known_names"
    private const val KEY_NAMES = "names"
    const val MAX_NAMES = 100
    const val MAX_LENGTH = 30

    fun all(context: Context): List<String> =
        prefs(context).getStringSet(KEY_NAMES, emptySet()).orEmpty().sorted()

    /** 추가했으면 true (빈 이름·중복·개수 초과면 false). */
    fun add(context: Context, name: String): Boolean {
        val clean = name.trim().replace(Regex("\\s+"), " ").take(MAX_LENGTH)
        if (clean.isEmpty()) return false
        val names = all(context)
        if (names.size >= MAX_NAMES || names.any { it.replace(" ", "") == clean.replace(" ", "") }) return false
        save(context, names + clean)
        return true
    }

    fun remove(context: Context, name: String) {
        save(context, all(context) - name)
    }

    private fun save(context: Context, names: List<String>) {
        prefs(context).edit().putStringSet(KEY_NAMES, names.toSet()).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
