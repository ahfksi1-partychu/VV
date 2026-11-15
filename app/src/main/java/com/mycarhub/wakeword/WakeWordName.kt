package com.mycarhub.wakeword

/**
 * 앱 호출어 이름. 앱 이름이 '쁘이'로 바뀌면서 호출어는 '쁘이' 하나로 정한다.
 *
 * 이전 이름(예: '붕붕아')으로 등록된 모델은 감지에 쓰지 않는다: 앱 시작 시 저장소가 보관 폴더로 옮기고
 * (녹음 원본은 지우지 않음), 사용자가 '쁘이'를 새로 5번 녹음해 등록할 때까지 호출어 감지는 쉰다.
 */
object WakeWordName {
    const val CURRENT = "쁘이"

    /** 띄어쓰기·문장부호만 다른 경우는 같은 이름으로 본다 ("쁘이", "쁘이!") */
    fun isCurrent(name: String): Boolean = normalize(name) == normalize(CURRENT)

    private fun normalize(name: String): String = name.filter { it.isLetterOrDigit() }.lowercase()
}
