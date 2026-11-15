package com.mycarhub.wakeword

import android.util.Log
import com.mycarhub.wakeword.core.QualityIssue
import com.mycarhub.wakeword.core.QualityReport
import com.mycarhub.wakeword.core.TrainingResult
import com.mycarhub.wakeword.core.WakeWordTrainer

/**
 * 호출어 등록 진행 상태 (녹음 회차별 결과). 메모리에만 있고, 학습에 성공해 저장할 때까지 기기에 남지 않는다.
 */
class EnrollmentSession(val name: String, private val trainer: WakeWordTrainer) {

    sealed interface TakeState {
        data object Empty : TakeState
        data class Accepted(val report: QualityReport) : TakeState
        data class Rejected(val issues: List<QualityIssue>) : TakeState

        /** 품질은 괜찮지만 다른 회차와 발음·길이가 크게 다름 */
        data object Inconsistent : TakeState
    }

    val takeCount: Int get() = trainer.takeCount

    private val takes = arrayOfNulls<ShortArray>(trainer.takeCount)
    private val states = MutableList<TakeState>(trainer.takeCount) { TakeState.Empty }

    fun state(index: Int): TakeState = states[index]

    val acceptedCount: Int get() = states.count { it is TakeState.Accepted }

    /** 다음에 녹음할 회차: 비었거나 다시 녹음해야 하는 첫 회차. */
    val nextTake: Int? get() = states.indexOfFirst { it !is TakeState.Accepted }.takeIf { it >= 0 }

    fun submit(index: Int, samples: ShortArray): TakeState {
        val report = trainer.analyze(samples)
        // 음성 자체가 아닌 레벨 수치만 기록 (마이크 입력 확인·게인 조정용)
        Log.i(
            TAG,
            "take ${index + 1}: issues=${report.issues} speech=${report.speechMillis}ms " +
                "peak=%.1f speech=%.1f noise=%.1f dBFS".format(report.peakDb, report.speechDb, report.noiseDb),
        )
        val state = if (report.isAcceptable) TakeState.Accepted(report) else TakeState.Rejected(report.issues)
        takes[index] = if (report.isAcceptable) samples else null
        states[index] = state
        return state
    }

    /** 모든 회차가 통과했을 때 학습. 일관성이 낮은 회차는 재녹음 대상으로 표시한다. */
    fun train(): TrainingResult {
        val ready = takes.filterNotNull()
        val result = trainer.train(name, ready)
        if (result is TrainingResult.Inconsistent) {
            for (i in result.takes) {
                states[i] = TakeState.Inconsistent
                takes[i] = null
            }
        }
        return result
    }

    fun recordings(): List<ShortArray> = takes.map { requireNotNull(it) }

    private companion object {
        const val TAG = "EnrollmentSession"
    }
}
