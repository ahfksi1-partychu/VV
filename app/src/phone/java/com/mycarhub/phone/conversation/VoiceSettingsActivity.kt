package com.mycarhub.phone.conversation

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import com.mycarhub.R
import com.mycarhub.phone.conversation.PhoneSpeaker.Gender
import java.util.Locale

/**
 * 설정 › 음성 및 말하기 (폰 빌드): 목소리 선택, 말하기 속도, 자동차 블루투스 출력 상태.
 *
 * 목소리는 이 기기 TTS 엔진이 실제로 가진, 기기 안에서 쓸 수 있는 한국어 음성만 보여준다.
 * 엔진이 성별을 알려주지 않는 음성은 번호로 보여주고 미리 듣기로 고르게 한다. 바꾼 값은 바로 저장된다.
 */
class VoiceSettingsActivity : Activity() {

    private lateinit var settings: VoiceOutputSettings
    private lateinit var speaker: PhoneSpeaker
    private lateinit var voiceGroup: RadioGroup
    private lateinit var rateValue: TextView
    private lateinit var rateSeek: SeekBar
    private val voiceIds = mutableMapOf<Int, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_settings)
        settings = VoiceOutputSettings(this)
        speaker = PhoneSpeaker(this, settings)
        voiceGroup = findViewById(R.id.voiceGroup)
        rateValue = findViewById(R.id.voiceRateValue)
        rateSeek = findViewById(R.id.voiceRateSeek)

        findViewById<TextView>(R.id.voiceEngine).setText(R.string.voice_loading)
        speaker.whenReady(::showVoices)

        rateSeek.max = ((VoiceOutputSettings.MAX_RATE - VoiceOutputSettings.MIN_RATE) * 10).toInt()
        rateSeek.progress = rateToProgress(settings.rate)
        showRate(settings.rate)
        rateSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                val rate = progressToRate(progress)
                showRate(rate)
                if (fromUser) settings.rate = rate
            }

            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })
        findViewById<Button>(R.id.voiceRateReset).setOnClickListener {
            settings.rate = VoiceOutputSettings.DEFAULT_RATE
            rateSeek.progress = rateToProgress(VoiceOutputSettings.DEFAULT_RATE)
        }
        findViewById<Button>(R.id.voicePreview).setOnClickListener { preview() }
        findViewById<Button>(R.id.voiceRatePreview).setOnClickListener { preview() }
    }

    override fun onResume() {
        super.onResume()
        showRoute()
    }

    override fun onPause() {
        speaker.stop()
        super.onPause()
    }

    override fun onDestroy() {
        speaker.shutdown()
        super.onDestroy()
    }

    private fun preview() {
        speaker.speak(getString(R.string.voice_preview_text), settings.voiceName, settings.rate) {}
    }

    private fun showVoices() {
        val options = speaker.voiceOptions()
        val engine = findViewById<TextView>(R.id.voiceEngine)
        val missing = findViewById<TextView>(R.id.voiceMissing)
        val preview = findViewById<View>(R.id.voicePreview)
        if (speaker.state != PhoneSpeaker.State.READY || options.isEmpty()) {
            engine.setText(R.string.voice_unavailable)
            missing.visibility = View.GONE
            preview.isEnabled = false
            findViewById<View>(R.id.voiceRatePreview).isEnabled = false
            return
        }
        engine.text = getString(R.string.voice_engine, speaker.engine.orEmpty(), options.size)

        voiceGroup.removeAllViews()
        voiceIds.clear()
        val females = options.filter { it.gender == Gender.FEMALE }
        val males = options.filter { it.gender == Gender.MALE }
        val others = options.filter { it.gender == null }
        // 성별을 알 수 있는 음성을 먼저 보여준다
        val labeled = females.mapIndexed { i, o -> o to if (females.size == 1) getString(R.string.voice_female) else getString(R.string.voice_female_n, i + 1) } +
            males.mapIndexed { i, o -> o to if (males.size == 1) getString(R.string.voice_male) else getString(R.string.voice_male_n, i + 1) } +
            others.mapIndexed { i, o -> o to getString(R.string.voice_other_n, i + 1) }
        // 저장된 음성이 없거나 사라졌으면 실제로 쓰이는 음성(품질 가장 높은 것)을 표시
        val selected = settings.voiceName?.takeIf { name -> options.any { it.name == name } } ?: options.first().name
        for ((option, label) in labeled) {
            val button = RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                setTextColor(getColor(R.color.phone_text))
                textSize = 17f
                minHeight = resources.getDimensionPixelSize(R.dimen.touch_target)
                buttonTintList = getColorStateList(R.color.phone_accent)
            }
            voiceIds[button.id] = option.name
            voiceGroup.addView(button)
            if (option.name == selected) button.isChecked = true
        }
        voiceGroup.setOnCheckedChangeListener { _, checkedId ->
            val name = voiceIds[checkedId] ?: return@setOnCheckedChangeListener
            settings.voiceName = name
            preview()
        }

        val absent = listOfNotNull(
            getString(R.string.voice_female).takeIf { females.isEmpty() },
            getString(R.string.voice_male).takeIf { males.isEmpty() },
        )
        missing.visibility = if (absent.isEmpty() && others.isEmpty()) View.GONE else View.VISIBLE
        missing.text = buildList {
            absent.forEach { add(getString(R.string.voice_none_for_gender, it)) }
            if (others.isNotEmpty()) add(getString(R.string.voice_unknown_gender_note))
        }.joinToString("\n")
    }

    private fun showRate(rate: Float) {
        rateValue.text = getString(R.string.voice_rate_value, String.format(Locale.ROOT, "%.1f", rate))
    }

    private fun showRoute() {
        val status = AudioRoute.status(this)
        findViewById<TextView>(R.id.voiceOutput).text = status.bluetoothOutput
            ?.let { getString(R.string.voice_output_bluetooth, it.ifEmpty { "-" }) }
            ?: getString(R.string.voice_output_phone)
        findViewById<TextView>(R.id.voiceInput).setText(
            if (status.bluetoothMicAvailable) R.string.voice_input_bluetooth_mic else R.string.voice_input_phone_mic,
        )
    }

    private fun rateToProgress(rate: Float) = ((rate - VoiceOutputSettings.MIN_RATE) * 10 + 0.5f).toInt()

    private fun progressToRate(progress: Int) = VoiceOutputSettings.normalizeRate(VoiceOutputSettings.MIN_RATE + progress / 10f)
}
