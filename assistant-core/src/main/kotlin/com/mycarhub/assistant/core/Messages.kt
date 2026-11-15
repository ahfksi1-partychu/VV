package com.mycarhub.assistant.core

/**
 * 메시지 앱 알림에서 읽어낸 수신 메시지 1건. 메모리에서만 쓰고 저장하지 않는다.
 *
 * [key]는 답장할 때 같은 알림을 다시 찾기 위한 알림 키다.
 */
data class IncomingMessage(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val sender: String,
    val text: String,
    /** 단체방 이름 (1:1 대화면 null) */
    val conversation: String?,
    val postedAt: Long,
    /** 알림에 RemoteInput 답장 동작이 있는지 */
    val canReply: Boolean,
)

sealed interface MessageSelection {
    data class Found(val message: IncomingMessage) : MessageSelection

    /** 말한 앱의 메시지 알림이 없음 */
    data class NoneForApp(val app: String) : MessageSelection

    /** 메시지 알림이 하나도 없음 */
    data object None : MessageSelection
}

/** 현재 알림 중 요청에 맞는 가장 최근 메시지를 고른다. */
object MessageSelector {

    fun latest(messages: List<IncomingMessage>, app: String?): MessageSelection {
        if (app == null) {
            return messages.maxByOrNull { it.postedAt }?.let { MessageSelection.Found(it) } ?: MessageSelection.None
        }
        val apps = messages.distinctBy { it.packageName }
            .map { AppEntry(it.packageName, it.appLabel, null, it.packageName, registered = false) }
        val packages = when (val match = AppMatcher.match(app, apps)) {
            is AppMatch.Single -> setOf(match.app.packageName)
            is AppMatch.Multiple -> match.apps.map { it.packageName }.toSet()
            AppMatch.None -> categoryPackages(app, apps)
        }
        return messages.filter { it.packageName in packages }
            .maxByOrNull { it.postedAt }
            ?.let { MessageSelection.Found(it) }
            ?: MessageSelection.NoneForApp(app)
    }

    /** "문자", "메시지"처럼 종류로 말한 경우: 이름·패키지 단서로 메시지 앱을 찾는다. */
    private fun categoryPackages(app: String, apps: List<AppEntry>): Set<String> {
        val category = AppCategory.forName(app) ?: return emptySet()
        return when (val match = AppMatcher.matchByHints(category, apps)) {
            is AppMatch.Single -> setOf(match.app.packageName)
            is AppMatch.Multiple -> match.apps.map { it.packageName }.toSet()
            AppMatch.None -> emptySet()
        }
    }
}
