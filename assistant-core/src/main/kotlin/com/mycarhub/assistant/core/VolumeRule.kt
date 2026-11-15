package com.mycarhub.assistant.core

/**
 * 음성 안내 음량이 들리기 어려울 만큼 작은지 (순수 규칙, PC selfTest로 검증).
 * 앱은 음량을 직접 바꾸지 않고, 작으면 안내와 시스템 음량 조절 화면만 보여준다.
 */
object VolumeRule {

    /** 이 비율(최소~최대 사이) 미만이면 작다고 본다. 15단계 기기에서 0·1·2단계. */
    const val LOW_FRACTION = 0.2f

    /**
     * [current]·[min]·[max]는 그 출력 경로의 음량 단계 (AudioManager.getStreamVolume 등).
     * 음소거이거나 최소 단계이거나 [LOW_FRACTION] 미만이면 true. 단계 정보가 이상하면(최대 ≤ 최소) 음소거·0만 본다.
     */
    fun isLow(current: Int, min: Int, max: Int, muted: Boolean): Boolean {
        if (muted || current <= 0) return true
        if (max <= min) return false
        if (current <= min) return true
        return (current - min).toFloat() / (max - min) < LOW_FRACTION
    }
}
