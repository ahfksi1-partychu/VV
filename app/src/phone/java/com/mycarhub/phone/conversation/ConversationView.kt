package com.mycarhub.phone.conversation

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import com.mycarhub.R
import com.mycarhub.phone.conversation.PhoneConversation.Action
import com.mycarhub.phone.conversation.PhoneConversation.Phase
import com.mycarhub.phone.conversation.PhoneConversation.Step

/**
 * view_conversation 레이아웃에 [PhoneConversation.Snapshot]을 그린다 (전체 화면·다른 앱 위 창 공용).
 * 버튼은 꼭 필요할 때만 보인다: 답장 확인 중 [보내기]/[보내지 않기], 말로 해결할 수 없을 때 권한 버튼, 끝난 뒤 [다시 말하기].
 */
class ConversationView(
    private val root: View,
    conversation: () -> PhoneConversation,
    /** 권한·설정이 필요할 때 [conversationAction] 버튼 */
    onAction: (Action) -> Unit,
    onStop: () -> Unit,
    /** 권한 버튼 글자를 고정할 때 (다른 앱 위 창: [MyCarHub 열기]) */
    private val actionLabel: Int? = null,
) {
    private val context = root.context
    private val mic: View = root.findViewById(R.id.conversationMic)
    private val phase: TextView = root.findViewById(R.id.conversationPhase)
    private val level: ProgressBar = root.findViewById(R.id.conversationLevel)
    private val assistant: TextView = root.findViewById(R.id.conversationAssistant)
    private val heard: TextView = root.findViewById(R.id.conversationHeard)
    private val textOnly: View = root.findViewById(R.id.conversationTextOnly)
    private val send: Button = root.findViewById(R.id.conversationSend)
    private val dontSend: Button = root.findViewById(R.id.conversationDontSend)
    private val action: Button = root.findViewById(R.id.conversationAction)
    private val again: Button = root.findViewById(R.id.conversationAgain)
    private val stop: Button = root.findViewById(R.id.conversationStop)
    private val volumeRow: View = root.findViewById(R.id.conversationVolume)
    private val volumeText: TextView = root.findViewById(R.id.conversationVolumeText)
    private val volumeUp: Button = root.findViewById(R.id.conversationVolumeUp)
    private var shownAction = Action.NONE

    // region 음성 안내 음량 확인 (음량이 0이거나 너무 작으면 안내·시스템 음량 화면·[음량 높이기])
    private val main = Handler(Looper.getMainLooper())
    private var voiceOutput = true
    private var volume: VoiceVolume.Level? = null
    private var systemPanelShown = false
    private var checking = false
    private val volumePoll = object : Runnable {
        override fun run() {
            checkVolume()
            if (checking) main.postDelayed(this, VOLUME_POLL_MS)
        }
    }
    // endregion

    init {
        send.setOnClickListener { conversation().confirmSend() }
        dontSend.setOnClickListener { conversation().cancelReply() }
        again.setOnClickListener { conversation().restart() }
        action.setOnClickListener { onAction(shownAction) }
        stop.setOnClickListener { onStop() }
        volumeUp.setOnClickListener {
            // 사용자가 누를 때만, 한 번에 한 단계씩 올린다
            volume?.let { VoiceVolume.raiseOneStep(context, it) }
            main.postDelayed(::checkVolume, VOLUME_RECHECK_MS)
        }
    }

    /**
     * 음성비서를 시작할 때마다 부른다: 음성 안내 음량을 확인하고, 작으면 시스템 음량 조절 화면을 한 번 띄운다.
     * 대화하는 동안 1초마다 다시 확인해 사용자가 소리를 키우면 안내를 숨긴다. 음량이 정상이면 아무것도 띄우지 않는다.
     */
    fun start() {
        systemPanelShown = false
        checking = true
        main.removeCallbacks(volumePoll)
        main.post(volumePoll)
    }

    /** 화면·창을 닫을 때: 음량 확인을 멈춘다 */
    fun release() {
        checking = false
        main.removeCallbacksAndMessages(null)
    }

    private fun checkVolume() {
        if (!checking) return
        // 음성 출력을 쓸 수 없어 글자로만 보여주는 중이면 음량은 상관없다
        val level = if (voiceOutput) VoiceVolume.read(context) else null
        volume = level
        if (level == null || !level.low) {
            volumeRow.visibility = View.GONE
            return
        }
        val where = when {
            !level.bluetooth -> context.getString(R.string.conv_volume_phone)
            level.bluetoothName != null -> context.getString(R.string.conv_volume_bluetooth, level.bluetoothName)
            else -> context.getString(R.string.conv_volume_bluetooth_unnamed)
        }
        val detail = if (level.muted) {
            context.getString(R.string.conv_volume_detail_muted, where)
        } else {
            context.getString(R.string.conv_volume_detail, where, level.current, level.max)
        }
        volumeText.text = context.getString(R.string.conv_volume_low) + "\n" + detail
        volumeRow.visibility = View.VISIBLE
        if (!systemPanelShown) {
            systemPanelShown = true
            VoiceVolume.showSystemPanel(context, level.stream)
        }
    }

    fun render(state: PhoneConversation.Snapshot) {
        if (voiceOutput != state.voice) {
            voiceOutput = state.voice
            checkVolume()
        }
        val listening = state.phase == Phase.LISTENING
        mic.setBackgroundResource(if (listening) R.drawable.phone_mic_circle_listening else R.drawable.phone_mic_circle)
        val phaseText = context.getString(
            when (state.phase) {
                Phase.SPEAKING -> R.string.conv_phase_speaking
                Phase.LISTENING -> R.string.conv_phase_listening
                Phase.WORKING -> R.string.conv_phase_working
                Phase.PERMISSION -> R.string.conv_phase_permission
                Phase.ENDED -> R.string.conv_phase_ended
            },
        )
        // 연속 대화 중 표시 (호출어 없이 다음 명령을 들음)
        phase.text = if (state.continuous && state.phase != Phase.ENDED) context.getString(R.string.conv_continuous_badge) + phaseText else phaseText
        phase.setTextColor(context.getColor(if (listening) R.color.phone_listening else R.color.phone_text_secondary))
        level.visibility = if (listening) View.VISIBLE else View.INVISIBLE
        level.progress = state.level
        assistant.text = state.assistant.orEmpty()
        heard.text = state.heard?.let { context.getString(R.string.conv_you_said, it) }
        heard.visibility = if (state.heard.isNullOrBlank()) View.GONE else View.VISIBLE
        textOnly.visibility = if (state.voice) View.GONE else View.VISIBLE

        val ended = state.phase == Phase.ENDED
        val confirming = state.step == Step.CONFIRM && !ended
        send.visibility = visibleIf(confirming)
        dontSend.visibility = visibleIf(confirming)
        shownAction = state.action
        action.visibility = visibleIf(state.action != Action.NONE)
        action.setText(
            actionLabel ?: when (state.action) {
                Action.MIC_PERMISSION -> R.string.conv_allow_mic
                Action.NOTIFICATION_ACCESS -> R.string.conv_allow_notifications
                Action.STT_CONSENT -> R.string.conv_allow_system_stt
                Action.NONE -> R.string.conv_close
            },
        )
        again.visibility = visibleIf(ended && state.action == Action.NONE)
        stop.setText(if (ended) R.string.conv_close else R.string.conv_stop)
    }

    private fun visibleIf(visible: Boolean) = if (visible) View.VISIBLE else View.GONE

    private companion object {
        const val VOLUME_POLL_MS = 1_000L
        const val VOLUME_RECHECK_MS = 300L
    }
}
