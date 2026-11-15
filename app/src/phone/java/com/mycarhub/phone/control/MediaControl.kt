package com.mycarhub.phone.control

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import com.mycarhub.phone.MessageListenerService

/** 재생 중인 미디어 앱 제어 명령. */
enum class MediaCommand { NEXT, PREVIOUS, PAUSE, PLAY }

/**
 * "다음 곡", "이전 곡", "음악 멈춰", "다시 재생해" 등. 문장 전체가 미디어 명령일 때만 인식한다
 * ("다음"만 말하면 다음(Daum) 앱일 수 있어 여기서 잡지 않는다).
 */
object MediaCommandParser {

    private val PUNCTUATION = Regex("[.,!?~…·\"'“”‘’]")
    private const val TAIL = "(?:좀|해|해줘|줘|주세요|줄래|요|봐|라|봐줘)*"
    private const val MEDIA = "(?:곡|노래|영상|동영상|음악|트랙|거)"

    private val NEXT = Regex("^(?:다음$MEDIA(?:으로|으로넘겨|넘겨|틀어|재생)?|(?:곡|노래|영상)?넘겨|스킵)$TAIL$")
    private val PREVIOUS = Regex("^(?:(?:이전|전|앞|지난)$MEDIA(?:으로|으로돌아가|틀어|재생)?)$TAIL$")
    private val PAUSE = Regex("^(?:(?:음악|노래|영상|동영상|재생)?(?:잠깐|잠깐만)?(?:멈춰|멈춰봐|정지|일시정지|일시중지|중지|멈추기|꺼))$TAIL$")
    private val PLAY = Regex("^(?:(?:다시|계속|이어서)(?:음악|노래|영상)?(?:재생|틀어|켜|들려)|(?:음악|노래|영상)?다시재생|재생|재생계속)$TAIL$")

    fun parse(raw: String, wakeWord: String?): MediaCommand? {
        var s = raw.replace(PUNCTUATION, " ").trim()
        wakeWord?.filter { it.isLetterOrDigit() }?.takeIf { it.isNotEmpty() }?.let { name ->
            Regex("^" + Regex.escape(name) + "(?:야|아)?\\s*", RegexOption.IGNORE_CASE).find(s)?.let { s = s.substring(it.range.last + 1) }
        }
        val c = s.replace(Regex("\\s+"), "").lowercase()
        if (c.isEmpty()) return null
        return when {
            NEXT.matches(c) -> MediaCommand.NEXT
            PREVIOUS.matches(c) -> MediaCommand.PREVIOUS
            PAUSE.matches(c) -> MediaCommand.PAUSE
            PLAY.matches(c) -> MediaCommand.PLAY
            else -> null
        }
    }
}

/**
 * 알림 접근 권한(MessageListenerService)으로 활성 미디어 세션을 가져와 TransportControls로 제어한다.
 * 재생 중인 세션을 우선 고르고, 없으면(음성 인식 중 잠깐 멈춘 경우 등) 가장 최근 일시정지 세션을 고른다.
 */
class MediaControl(private val context: Context) {

    sealed interface Result {
        /** 명령 전달함. [verify]로 실제 적용 여부를 확인한다. */
        data class Sent(val app: String, val verify: () -> Boolean) : Result
        data class Unsupported(val app: String) : Result
        data object NoSession : Result
        data object NoAccess : Result
    }

    fun send(command: MediaCommand): Result {
        val manager = context.getSystemService(MediaSessionManager::class.java) ?: return Result.NoSession
        val sessions = try {
            manager.getActiveSessions(ComponentName(context, MessageListenerService::class.java))
        } catch (e: SecurityException) {
            return Result.NoAccess
        }.filter { it.packageName != context.packageName }
        val playing = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val paused = sessions.firstOrNull {
            it.playbackState?.state in setOf(PlaybackState.STATE_PAUSED, PlaybackState.STATE_BUFFERING)
        }
        val target = (if (command == MediaCommand.PLAY) paused ?: playing else playing ?: paused) ?: return Result.NoSession
        val app = label(target.packageName)
        val actions = target.playbackState?.actions ?: 0L
        fun has(flag: Long) = actions and flag != 0L
        val supported = when (command) {
            MediaCommand.NEXT -> has(PlaybackState.ACTION_SKIP_TO_NEXT)
            MediaCommand.PREVIOUS -> has(PlaybackState.ACTION_SKIP_TO_PREVIOUS)
            MediaCommand.PAUSE -> has(PlaybackState.ACTION_PAUSE) || has(PlaybackState.ACTION_PLAY_PAUSE)
            MediaCommand.PLAY -> has(PlaybackState.ACTION_PLAY) || has(PlaybackState.ACTION_PLAY_PAUSE)
        }
        if (!supported) return Result.Unsupported(app)

        val beforeTitle = title(target)
        val beforeQueue = target.playbackState?.activeQueueItemId
        val controls = target.transportControls
        when (command) {
            MediaCommand.NEXT -> controls.skipToNext()
            MediaCommand.PREVIOUS -> controls.skipToPrevious()
            MediaCommand.PAUSE -> controls.pause()
            MediaCommand.PLAY -> controls.play()
        }
        return Result.Sent(app) {
            val state = target.playbackState
            when (command) {
                MediaCommand.NEXT -> title(target) != beforeTitle || state?.activeQueueItemId != beforeQueue
                // 이전 곡: 다른 곡이 되었거나, 같은 곡을 처음부터 다시 재생
                MediaCommand.PREVIOUS -> title(target) != beforeTitle || state?.activeQueueItemId != beforeQueue ||
                    (state?.position ?: Long.MAX_VALUE) < RESTART_POSITION_MS
                MediaCommand.PAUSE -> state?.state != PlaybackState.STATE_PLAYING
                MediaCommand.PLAY -> state?.state == PlaybackState.STATE_PLAYING || state?.state == PlaybackState.STATE_BUFFERING
            }
        }
    }

    /** [pkg] 앱이 지금 재생 중인지. 알림 접근 권한이 없으면 null (확인 불가). */
    fun isPlaying(pkg: String): Boolean? = try {
        val manager = context.getSystemService(MediaSessionManager::class.java)
        manager?.getActiveSessions(ComponentName(context, MessageListenerService::class.java))
            ?.any { it.packageName == pkg && it.playbackState?.state in setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING) }
    } catch (e: SecurityException) {
        null
    }

    /** [pkg] 앱의 세션이 일시정지 상태면 그 앱의 재생(play) 동작을 한 번 보낸다. 보냈으면 true. */
    fun playIfPaused(pkg: String): Boolean = try {
        val manager = context.getSystemService(MediaSessionManager::class.java)
        val session = manager?.getActiveSessions(ComponentName(context, MessageListenerService::class.java))
            ?.firstOrNull { it.packageName == pkg && it.playbackState?.state == PlaybackState.STATE_PAUSED }
        session?.transportControls?.play()
        session != null
    } catch (e: SecurityException) {
        false
    }

    private fun title(c: MediaController): String? =
        c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: c.metadata?.description?.title?.toString()

    private fun label(pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    companion object {
        /** 명령 후 앱이 상태를 반영할 때까지 기다리는 시간 */
        const val VERIFY_DELAY_MS = 1_200L
        private const val RESTART_POSITION_MS = 4_000L
    }
}
