package com.mycarhub.assistant.core

/**
 * 차량 신호로 '주차됨'을 판단한다 (순수 로직, 차량용 앱의 감시 서비스가 신호를 넣는다).
 *
 * 속도 0만으로는 판단하지 않는다 (신호 대기·정체). 기준:
 * 1. 기어가 P로 바뀌면 위치를 미리 잡아 둔다 ([Action.CAPTURE]) — 시동을 끄면 위치를 얻을 시간이 없을 수 있어서.
 * 2. 기어 P 상태에서 시동이 꺼지면(OFF/ACC/LOCK) 주차로 확정 ([Action.COMMIT]).
 * 3. 시동 끔 신호를 못 받는 경우: 기어 P가 [holdMs] 이상 이어지고 주차 브레이크가 걸려 있으면 확정.
 *    브레이크가 풀려 있거나 신호가 없으면 [holdNoBrakeMs] 이상 P일 때 확정. 시동이 켜져 있다고 오면 시간으로는 확정하지 않는다.
 * 4. 확정 전에 P에서 벗어나면 잡아 둔 위치를 버린다 ([Action.CANCEL]). 한 번 확정하면 다시 P를 벗어날 때까지 또 확정하지 않는다.
 * 기어 신호를 아예 받을 수 없으면 시동 꺼짐만으로 확정한다 (정확도가 낮다는 점을 화면에 알린다).
 */
class ParkingDecider(
    private val holdMs: Long = 3 * 60_000L,
    private val holdNoBrakeMs: Long = 5 * 60_000L,
) {
    enum class Action { CAPTURE, COMMIT, CANCEL }

    enum class Ignition { UNKNOWN, LOCK, OFF, ACC, ON, START }

    private var gearKnown = false
    private var inPark = false
    private var parkSince = 0L
    private var brakeKnown = false
    private var brakeOn = false
    private var ignition = Ignition.UNKNOWN
    private var committed = false
    private var captured = false

    val isParked: Boolean get() = committed

    fun onGear(isPark: Boolean, now: Long): List<Action> {
        gearKnown = true
        if (isPark == inPark) return emptyList()
        inPark = isPark
        if (isPark) {
            parkSince = now
            captured = true
            // 이미 시동이 꺼진 상태에서 P (드문 순서): 바로 확정
            return listOf(Action.CAPTURE) + commitIf(ignitionOff())
        }
        val wasCaptured = captured && !committed
        committed = false
        captured = false
        return if (wasCaptured) listOf(Action.CANCEL) else emptyList()
    }

    fun onIgnition(state: Ignition, now: Long): List<Action> {
        ignition = state
        if (!ignitionOff()) return emptyList()
        // 기어 신호가 없는 차량: 시동 꺼짐만으로 확정 (위치는 이때 얻는다)
        if (!gearKnown) return commitIf(true)
        return if (inPark) commitIf(true) else emptyList()
    }

    fun onParkingBrake(on: Boolean, now: Long): List<Action> {
        brakeKnown = true
        brakeOn = on
        return onTick(now)
    }

    /**
     * 시동 신호가 없는 차량을 위해 주기적으로 부른다.
     * 주차 브레이크가 걸려 있으면 [holdMs], 브레이크가 풀려 있거나 신호가 없으면 [holdNoBrakeMs] 동안 P가 이어지면 확정.
     * (Pleos 에뮬레이터에서 일반 앱은 시동 신호를 받지 못하고 브레이크는 풀린 값으로 와서, 이전 규칙으로는 확정되지 않았다.)
     * 시동 신호를 받는 차량에서 시동이 켜져 있으면(P로 공회전 대기) 시간으로 확정하지 않고 시동 끔을 기다린다.
     */
    fun onTick(now: Long): List<Action> {
        if (!inPark || committed) return emptyList()
        if (ignition == Ignition.ON || ignition == Ignition.START) return emptyList()
        val held = now - parkSince
        return commitIf(if (brakeKnown && brakeOn) held >= holdMs else held >= holdNoBrakeMs)
    }

    private fun ignitionOff() = ignition == Ignition.OFF || ignition == Ignition.ACC || ignition == Ignition.LOCK

    private fun commitIf(condition: Boolean): List<Action> {
        if (!condition || committed) return emptyList()
        committed = true
        return listOf(Action.COMMIT)
    }
}
