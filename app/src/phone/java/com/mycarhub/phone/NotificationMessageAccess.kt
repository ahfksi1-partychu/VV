package com.mycarhub.phone

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.mycarhub.assistant.core.IncomingMessage
import com.mycarhub.assistant.messages.MessageAccess

/**
 * 폰 알림에서 메시지를 읽고 RemoteInput으로 답장한다 (기기 안에서만 처리).
 *
 * - 요청 시점에 현재 알림만 읽고 결과를 저장하지 않는다.
 * - 로그에는 앱 패키지·개수 같은 정보만 남기고 보낸 사람·내용은 남기지 않는다.
 * - 답장은 알림이 제공하는 RemoteInput 답장 동작이 있을 때만, 사용자 확인 뒤에 보낸다.
 */
class NotificationMessageAccess(private val context: Context) : MessageAccess {

    private val component = ComponentName(context, MessageListenerService::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)

    override val status: MessageAccess.Status
        get() = when {
            !notifications.isNotificationListenerAccessGranted(component) -> MessageAccess.Status.NEEDS_ACCESS
            MessageListenerService.connected == null -> MessageAccess.Status.NOT_CONNECTED
            else -> MessageAccess.Status.READY
        }

    /** 설정 화면 열기 → 돌아오면 허용 여부 확인·안내 ([NotificationAccessActivity]) */
    override fun accessSettingsIntent(): Intent = Intent(context, NotificationAccessActivity::class.java)

    override fun currentMessages(): List<IncomingMessage> {
        val listener = awaitListener() ?: return emptyList()
        val active = try {
            listener.activeNotifications.orEmpty()
        } catch (e: SecurityException) {
            Log.w(TAG, "notification access revoked", e)
            return emptyList()
        }
        val messages = active.mapNotNull(::toMessage)
        Log.i(TAG, "active=${active.size} messages=${messages.size} apps=${messages.map { it.packageName }.distinct()}")
        return messages
    }

    override fun reply(message: IncomingMessage, text: String): MessageAccess.ReplyResult {
        val listener = awaitListener() ?: return MessageAccess.ReplyResult.NO_ACCESS
        val sbn = try {
            listener.activeNotifications.orEmpty().firstOrNull { it.key == message.key }
        } catch (e: SecurityException) {
            return MessageAccess.ReplyResult.NO_ACCESS
        } ?: return MessageAccess.ReplyResult.MESSAGE_GONE
        val action = replyAction(sbn.notification) ?: return MessageAccess.ReplyResult.NO_REPLY_ACTION
        val inputs = action.remoteInputs ?: return MessageAccess.ReplyResult.NO_REPLY_ACTION
        val freeForm = inputs.first { it.allowFreeFormInput }

        val fillIn = Intent()
        RemoteInput.addResultsToIntent(inputs, fillIn, Bundle().apply { putCharSequence(freeForm.resultKey, text) })
        RemoteInput.setResultsSource(fillIn, RemoteInput.SOURCE_FREE_FORM_INPUT)
        return try {
            action.actionIntent.send(context, 0, fillIn)
            Log.i(TAG, "reply sent via ${sbn.packageName}")
            MessageAccess.ReplyResult.SENT
        } catch (e: PendingIntent.CanceledException) {
            Log.w(TAG, "reply action cancelled by ${sbn.packageName}")
            MessageAccess.ReplyResult.MESSAGE_GONE
        } catch (e: RuntimeException) {
            Log.w(TAG, "reply failed for ${sbn.packageName}", e)
            MessageAccess.ReplyResult.FAILED
        }
    }

    /**
     * [message] 알림을 누른 것과 같이, 그 메시지 앱이 알림에 넣어 둔 링크(contentIntent)로 대화방을 연다 (폰 빌드 전용).
     * 메시지 앱 화면을 자동으로 조작하지 않는다. 알림이 없거나 링크가 없으면 false.
     */
    fun openConversation(message: IncomingMessage): Boolean = openNotification(message.key)

    /** 알림 키로 그 알림의 링크를 연다 (Drive To-Do의 [열기]). 알림이 이미 사라졌으면 false. */
    fun openNotification(key: String): Boolean {
        val listener = awaitListener() ?: return false
        val sbn = try {
            listener.activeNotifications.orEmpty().firstOrNull { it.key == key }
        } catch (e: SecurityException) {
            return false
        } ?: return false
        val open = sbn.notification.contentIntent ?: return false
        // Android 14+: 백그라운드(다른 앱 위 음성비서 창)에서 보낼 때 MyCarHub의 '다른 앱 위에 표시' 허용으로 화면을 열 수 있게 한다
        val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle()
        } else {
            null
        }
        return try {
            open.send(context, 0, null, null, null, null, options)
            Log.i(TAG, "conversation opened via ${sbn.packageName}")
            true
        } catch (e: PendingIntent.CanceledException) {
            Log.w(TAG, "open link cancelled by ${sbn.packageName}")
            false
        }
    }

    /**
     * "당근에서 온 알림 읽어줘"처럼 앱을 지정했을 때: 그 앱의 현재 알림 중 제목과 내용이 있는 것 (폰 빌드 전용).
     * 메시지 알림이 아니어도 포함한다 (보낸 사람 자리에는 알림 제목). 진행 중 알림·묶음 요약은 뺀다. 저장하지 않는다.
     */
    fun appNotifications(packages: Set<String>): List<IncomingMessage> {
        val listener = awaitListener() ?: return emptyList()
        val active = try {
            listener.activeNotifications.orEmpty()
        } catch (e: SecurityException) {
            return emptyList()
        }
        val found = active.filter { it.packageName in packages && it.packageName != context.packageName }.mapNotNull { sbn ->
            toMessage(sbn) ?: notificationOf(sbn)
        }
        Log.i(TAG, "app notifications packages=$packages found=${found.size}")
        return found
    }

    private fun notificationOf(sbn: StatusBarNotification): IncomingMessage? {
        val n = sbn.notification
        if (n.flags and (Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_ONGOING_EVENT) != 0) return null
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return null
        return IncomingMessage(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = appLabel(sbn.packageName),
            sender = title,
            text = text,
            conversation = null,
            postedAt = sbn.postTime,
            canReply = replyAction(n) != null,
        )
    }

    /** 허용 직후에는 시스템이 아직 연결 전일 수 있어 다시 연결을 요청하고 잠시 기다린다. */
    private fun awaitListener(): MessageListenerService? {
        MessageListenerService.connected?.let { return it }
        if (!notifications.isNotificationListenerAccessGranted(component)) return null
        NotificationListenerService.requestRebind(component)
        val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            MessageListenerService.connected?.let { return it }
            try {
                Thread.sleep(CONNECT_POLL_MS)
            } catch (e: InterruptedException) {
                return null
            }
        }
        return MessageListenerService.connected
    }

    /** 알림 하나가 메시지 알림이면 그 내용 (Drive To-Do 할 일 후보 판단용, 저장하지 않는다). */
    fun messageOf(sbn: StatusBarNotification): IncomingMessage? = toMessage(sbn)

    /** 메시지 알림만 고른다: 메시지 분류이거나, 대화형(MessagingStyle)이거나, 답장 동작이 있는 알림. */
    private fun toMessage(sbn: StatusBarNotification): IncomingMessage? {
        val n = sbn.notification
        if (sbn.packageName == context.packageName) return null
        if (n.flags and (Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_ONGOING_EVENT) != 0) return null
        val extras = n.extras
        val bundles = messageBundles(extras)
        val reply = replyAction(n)
        if (n.category != Notification.CATEGORY_MESSAGE && bundles.isEmpty() && reply == null) return null

        // 대화형 알림: 마지막 '받은' 메시지 (보낸 사람이 없으면 내가 보낸 메시지)
        val incoming = bundles.lastOrNull { senderOf(it) != null }
        val sender = incoming?.let(::senderOf) ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = incoming?.getCharSequence(KEY_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        if (sender.isNullOrBlank() || text.isNullOrBlank()) return null
        val time = incoming?.getLong(KEY_TIME)?.takeIf { it > 0 } ?: sbn.postTime

        return IncomingMessage(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = appLabel(sbn.packageName),
            sender = sender.trim(),
            text = text.trim(),
            conversation = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
                ?.takeIf { extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION) },
            postedAt = time,
            canReply = reply != null,
        )
    }

    @Suppress("DEPRECATION") // getParcelableArray(String): API 33 미만 호환
    private fun messageBundles(extras: Bundle): List<Bundle> =
        (extras.getParcelableArray(Notification.EXTRA_MESSAGES) as Array<Parcelable>?)
            ?.filterIsInstance<Bundle>()
            .orEmpty()

    @Suppress("DEPRECATION")
    private fun senderOf(bundle: Bundle): String? =
        ((bundle.getParcelable(KEY_SENDER_PERSON) as? Person)?.name ?: bundle.getCharSequence(KEY_SENDER))
            ?.toString()
            ?.takeIf { it.isNotBlank() }

    /** 자유 입력 RemoteInput이 있는 답장 동작. 의미가 '답장'으로 표시된 동작을 우선한다. */
    private fun replyAction(n: Notification): Notification.Action? {
        @Suppress("DEPRECATION") // 일부 메신저는 답장 동작을 Wear 확장에만 넣는다
        val candidates = n.actions.orEmpty().toList() + Notification.WearableExtender(n).actions
        val usable = candidates.filter { action -> action.remoteInputs?.any { it.allowFreeFormInput } == true }
        return usable.firstOrNull { it.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY } ?: usable.firstOrNull()
    }

    private fun appLabel(packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        packageName
    }

    private companion object {
        const val TAG = "MessageAccess"
        const val CONNECT_TIMEOUT_MS = 2_000L
        const val CONNECT_POLL_MS = 100L

        // Notification.MessagingStyle.Message 번들 키 (프레임워크·AndroidX 공통)
        const val KEY_TEXT = "text"
        const val KEY_TIME = "time"
        const val KEY_SENDER = "sender"
        const val KEY_SENDER_PERSON = "sender_person"
    }
}
