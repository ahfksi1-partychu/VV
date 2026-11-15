package com.mycarhub.phone.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 음성으로 휴대폰 화면을 조작하는 접근성 서비스 (폰 빌드 전용). 사용자가 설정 > 접근성에서 직접 켜야 동작한다.
 *
 * 사용자가 음성 명령을 했을 때만 지금 화면을 읽고 동작한다: 뒤로·홈·최근 앱, 스크롤, 입력란에 글자 쓰기, 검색 실행, 버튼 누르기.
 * 화면 내용은 그 자리에서 버튼·입력란을 찾는 데만 쓰고 저장·전송하지 않으며 로그에도 남기지 않는다.
 * 접근성 이벤트는 구독하지만 처리하지 않는다 (화면 변화를 기록하지 않음).
 */
class ScreenControlService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "screen control connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ScreenControl"

        /** 켜져 있고 연결된 서비스 (꺼져 있으면 null) */
        @Volatile
        var instance: ScreenControlService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val me = ComponentName(context, ScreenControlService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }

        fun settingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/** 화면에서 찾은 누를 것·쓸 곳 */
class ScreenTarget internal constructor(val label: String, internal val node: AccessibilityNodeInfo)

sealed interface ScreenResult {
    data object Done : ScreenResult

    /** 지금 화면 정보를 읽을 수 없음 */
    data object NoScreen : ScreenResult

    /** 해당하는 버튼·입력란을 찾지 못함 */
    data object NotFound : ScreenResult

    /** 입력란이 여러 개라 어디에 쓸지 모름 */
    data object ManyInputs : ScreenResult

    /** 같은 이름의 버튼이 여러 개: 사용자에게 고르게 한다 */
    data class Choose(val targets: List<ScreenTarget>) : ScreenResult

    /** 결제·전송·삭제 등 되돌리기 어려운 동작일 수 있음: 누르기 전에 확인받는다 */
    data class Confirm(val target: ScreenTarget, val submit: Boolean) : ScreenResult
}

/** [ScreenControlService]로 하는 실제 동작. 메인 스레드에서 호출한다. */
class ScreenActions(private val service: ScreenControlService) {

    private val main = Handler(Looper.getMainLooper())
    private val ownPackage = service.packageName

    fun back(): ScreenResult = global(AccessibilityService.GLOBAL_ACTION_BACK)

    fun home(): ScreenResult = global(AccessibilityService.GLOBAL_ACTION_HOME)

    fun recents(): ScreenResult = global(AccessibilityService.GLOBAL_ACTION_RECENTS)

    private fun global(action: Int): ScreenResult = if (service.performGlobalAction(action)) ScreenResult.Done else ScreenResult.NotFound

    /** [down]: 아래 내용 보기 (손가락은 위로 민다) */
    fun scroll(down: Boolean): ScreenResult {
        val root = service.rootInActiveWindow ?: return ScreenResult.NoScreen
        val scrollable = nodes(root).filter { it.isScrollable && it.isVisibleToUser }.maxByOrNull { area(it) }
        val action = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        if (scrollable != null && scrollable.performAction(action)) return ScreenResult.Done
        // 스크롤 정보를 주지 않는 화면: 화면 가운데를 손가락으로 미는 동작
        val metrics = service.resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val (from, to) = if (down) metrics.heightPixels * 0.7f to metrics.heightPixels * 0.3f else metrics.heightPixels * 0.3f to metrics.heightPixels * 0.7f
        return if (swipe(x, from, x, to)) ScreenResult.Done else ScreenResult.NotFound
    }

    /**
     * 입력란에 [text]를 쓴다. 입력란이 없으면 검색 버튼(돋보기)을 눌러 검색창을 연 뒤 쓴다. 결과는 [done]으로 (메인 스레드).
     */
    fun type(text: String, done: (ScreenResult) -> Unit) {
        val root = service.rootInActiveWindow ?: return done(ScreenResult.NoScreen)
        when (val input = findInput(root)) {
            is Found -> return done(setText(input.node, text))
            Many -> return done(ScreenResult.ManyInputs)
            None -> Unit
        }
        val opener = searchButtons(root).singleOrNull() ?: return done(ScreenResult.NotFound)
        if (!click(opener)) return done(ScreenResult.NotFound)
        // 검색창이 열릴 때까지 잠깐씩 다시 찾는다
        var tries = 0
        val poll = object : Runnable {
            override fun run() {
                val next = service.rootInActiveWindow
                val found = next?.let { findInput(it) }
                when {
                    found is Found -> done(setText(found.node, text))
                    found == Many -> done(ScreenResult.ManyInputs)
                    ++tries < INPUT_POLLS -> main.postDelayed(this, INPUT_POLL_MS)
                    else -> done(ScreenResult.NotFound)
                }
            }
        }
        main.postDelayed(poll, INPUT_POLL_MS)
    }

    /**
     * 검색 실행: 입력란에서 키보드의 검색(엔터)을 누르거나, 검색 버튼을 누른다.
     * 검색창이 아닌 입력란(채팅 입력 등)에서는 엔터가 메시지 전송일 수 있어 [ScreenResult.Confirm]으로 먼저 확인받는다.
     */
    fun search(): ScreenResult {
        val root = service.rootInActiveWindow ?: return ScreenResult.NoScreen
        val input = findInput(root)
        if (input is Found) {
            if (!isSearchLike(input.node)) return ScreenResult.Confirm(ScreenTarget(label(input.node).orEmpty(), input.node), submit = true)
            return submit(input.node, root)
        }
        val button = searchButtons(root).singleOrNull() ?: return ScreenResult.NotFound
        return if (click(button)) ScreenResult.Done else ScreenResult.NotFound
    }

    /** 확인받은 뒤의 입력 실행 (엔터) */
    fun submit(target: ScreenTarget): ScreenResult {
        val root = service.rootInActiveWindow ?: return ScreenResult.NoScreen
        return submit(target.node, root)
    }

    private fun submit(input: AccessibilityNodeInfo, root: AccessibilityNodeInfo): ScreenResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            input.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        ) {
            return ScreenResult.Done
        }
        val button = searchButtons(root).singleOrNull() ?: return ScreenResult.NotFound
        return if (click(button)) ScreenResult.Done else ScreenResult.NotFound
    }

    /**
     * 화면에서 [label]이 적힌(또는 설명이 그런) 버튼을 찾는다. 하나면 누르고, 여러 개면 [ScreenResult.Choose],
     * 결제·전송 같은 버튼이면 [ScreenResult.Confirm]. 임의로 아무거나 누르지 않는다.
     */
    fun tap(label: String): ScreenResult {
        val root = service.rootInActiveWindow ?: return ScreenResult.NoScreen
        val key = normalize(label)
        if (key.isEmpty()) return ScreenResult.NotFound
        val matches = nodes(root)
            .filter { it.isVisibleToUser && it.packageName?.toString() != ownPackage }
            .mapNotNull { node -> label(node)?.let { text -> node to text } }
            .filter { (_, text) -> normalize(text).contains(key) }
        val targets = matches
            .mapNotNull { (node, text) -> clickable(node)?.let { ScreenTarget(text.trim(), it) } }
            .distinctBy { boundsKey(it.node) }
        val exact = targets.filter { normalize(it.label) == key }
        val pool = exact.ifEmpty { targets }
        return when {
            pool.isEmpty() -> ScreenResult.NotFound
            pool.size == 1 -> press(pool[0])
            else -> ScreenResult.Choose(pool.take(MAX_CHOICES))
        }
    }

    /** 고른 버튼 누르기. 중요한 동작으로 보이면 확인부터. */
    fun press(target: ScreenTarget, confirmed: Boolean = false): ScreenResult {
        if (!confirmed && isSensitive(target.label)) return ScreenResult.Confirm(target, submit = false)
        target.node.refresh()
        return if (click(target.node)) ScreenResult.Done else ScreenResult.NotFound
    }

    // region 찾기

    private sealed interface InputSearch
    private class Found(val node: AccessibilityNodeInfo) : InputSearch
    private data object Many : InputSearch
    private data object None : InputSearch

    private fun findInput(root: AccessibilityNodeInfo): InputSearch {
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }?.let { return Found(it) }
        val edits = nodes(root).filter { it.isEditable && it.isVisibleToUser && it.packageName?.toString() != ownPackage }
        if (edits.isEmpty()) return None
        if (edits.size == 1) return Found(edits[0])
        return edits.filter(::isSearchLike).singleOrNull()?.let { Found(it) } ?: Many
    }

    /** 검색창을 여는 버튼(돋보기 등). 입력란은 제외한다. */
    private fun searchButtons(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val candidates = nodes(root)
            .filter { it.isVisibleToUser && !it.isEditable && it.packageName?.toString() != ownPackage && isSearchLike(it) }
            .mapNotNull { clickable(it) }
            .distinctBy { boundsKey(it) }
        // 이름이 정확히 '검색'인 버튼이 하나면 그것
        return candidates.filter { label(it)?.let(::normalize) in SEARCH_EXACT }.takeIf { it.size == 1 } ?: candidates
    }

    private fun isSearchLike(node: AccessibilityNodeInfo): Boolean {
        val words = listOfNotNull(node.text, node.contentDescription, node.hintTextCompat(), node.viewIdResourceName)
            .joinToString(" ") { it.toString().lowercase() }
        return SEARCH_WORDS.any { words.contains(it) }
    }

    /**
     * 유튜브 검색 결과 화면에서 맨 위의 일반 영상(광고·Shorts 제외)을 누른다.
     * 화면이 아직 [packageName] 앱이 아니거나 영상 항목을 못 찾으면 false.
     */
    fun tapFirstVideo(packageName: String, tried: Set<String> = emptySet()): String? {
        val root = service.rootInActiveWindow ?: return null
        if (root.packageName?.toString() != packageName) return null
        val screen = Rect().also(root::getBoundsInScreen)
        // 전체 화면 트리를 훑지 않고 유튜브 쪽에서 글자로 찾게 한다 (조회 부담·렉 줄이기)
        val found = VIDEO_HINTS.flatMap { root.findAccessibilityNodeInfosByText(it).orEmpty() }
        val candidates = found.mapNotNull { node ->
            val desc = node.contentDescription?.toString() ?: return@mapNotNull null
            val lower = desc.lowercase()
            val isVideo = VIDEO_HINTS.any { lower.contains(it) }
            val excluded = EXCLUDE_HINTS.any { lower.contains(it) }
            if (!isVideo || excluded) return@mapNotNull null
            val target = clickable(node) ?: return@mapNotNull null
            val r = Rect().also(target::getBoundsInScreen)
            // 화면 위쪽 검색창·필터 줄은 건너뛴다
            if (r.isEmpty || r.top < screen.height() / 10) return@mapNotNull null
            // 찾은 글자 노드뿐 아니라 실제로 누를 카드 전체(자식 포함, 소량)에 광고·스폰서 표시가 있는지 본다
            if (hasExcludedLabel(target)) return@mapNotNull null
            Triple(target, r.top, desc)
        }.distinctBy { boundsKey(it.first) }.sortedBy { it.second }
        // 맨 위 결과는 광고성·추천 결과일 때가 많아 2번째 → 3번째 → 1번째 순으로 시도한다
        val ordered = PREFERRED_ORDER.mapNotNull { candidates.getOrNull(it) } + candidates.drop(PREFERRED_ORDER.size)
        val pick = ordered.firstOrNull { it.third !in tried } ?: return null
        return if (click(pick.first)) pick.third else null
    }

    /** 카드(자식 몇 단계까지, 최대 [CARD_NODES]개)의 글자·설명에 제외 표시(광고·스폰서·Shorts·라이브·재생목록)가 있는지. */
    private fun hasExcludedLabel(card: AccessibilityNodeInfo): Boolean {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(card to 0)
        var seen = 0
        while (queue.isNotEmpty() && seen < CARD_NODES) {
            val (node, depth) = queue.removeFirst()
            seen++
            val text = listOfNotNull(node.text, node.contentDescription).joinToString(" ").lowercase()
            if (EXCLUDE_HINTS.any { text.contains(it) } || AD_BADGES.any { text.trim() == it }) return true
            if (depth < CARD_DEPTH) for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it to depth + 1) }
        }
        return false
    }

    /** 방금 누른 검색 결과 항목이 아직 화면에 있는지 (영상 화면으로 넘어갔으면 false). */
    fun stillShows(packageName: String, description: String): Boolean {
        val root = service.rootInActiveWindow ?: return false
        if (root.packageName?.toString() != packageName) return false
        return nodes(root).any { it.isVisibleToUser && it.contentDescription?.toString() == description }
    }

    /**
     * 유튜브가 띄운 공식 '광고 건너뛰기' 버튼이 지금 눌릴 수 있으면 누른다. 카운트다운 중(아직 버튼 아님)이나 건너뛸 수 없는 광고는 그대로 둔다.
     */
    fun tapSkipAd(packageName: String): Boolean {
        val root = service.rootInActiveWindow ?: return false
        if (root.packageName?.toString() != packageName) return false
        // 가벼운 조회만: 유튜브 건너뛰기 버튼 ID, 그다음 버튼 글자로 찾는다 (전체 트리 탐색 안 함)
        val byId = root.findAccessibilityNodeInfosByViewId("$packageName:id/skip_ad_button").orEmpty()
            .filter { it.isVisibleToUser && it.isEnabled }
        val byText = if (byId.isNotEmpty()) emptyList() else SKIP_AD_SEARCH.flatMap { root.findAccessibilityNodeInfosByText(it).orEmpty() }
            .filter { node -> node.isVisibleToUser && node.isEnabled && label(node)?.let { normalize(it) in SKIP_AD_LABELS } == true }
        val button = (byId + byText).firstOrNull() ?: return false
        val target = clickable(button) ?: return false
        return click(target)
    }

    private fun AccessibilityNodeInfo.hintTextCompat(): CharSequence? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) hintText else null

    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth <= MAX_PARENT_DEPTH) {
            if (current.isClickable && current.isEnabled) return current
            current = current.parent
            depth++
        }
        return null
    }

    private fun label(node: AccessibilityNodeInfo): String? =
        (node.text ?: node.contentDescription)?.toString()?.takeIf { it.isNotBlank() }

    private fun nodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && out.size < MAX_NODES) {
            val node = queue.removeFirst()
            out += node
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
        return out
    }

    private fun area(node: AccessibilityNodeInfo): Int = Rect().also(node::getBoundsInScreen).let { it.width() * it.height() }

    private fun boundsKey(node: AccessibilityNodeInfo): String = Rect().also(node::getBoundsInScreen).flattenToString()

    // endregion

    // region 동작

    private fun setText(node: AccessibilityNodeInfo, text: String): ScreenResult {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) ScreenResult.Done else ScreenResult.NotFound
    }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        // 클릭 동작을 받지 않는 화면: 버튼 가운데를 손가락으로 누른다
        val r = Rect().also(node::getBoundsInScreen)
        if (r.isEmpty) return false
        return tapAt(r.exactCenterX(), r.exactCenterY())
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        return service.dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, SWIPE_MS)).build(), null, null)
    }

    private fun tapAt(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return service.dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, TAP_MS)).build(), null, null)
    }

    // endregion

    companion object {
        private const val MAX_NODES = 3_000
        private const val MAX_PARENT_DEPTH = 5
        private const val MAX_CHOICES = 3
        private const val INPUT_POLLS = 6
        private const val INPUT_POLL_MS = 350L
        private const val SWIPE_MS = 350L
        private const val TAP_MS = 60L

        /** 유튜브 영상 항목 설명에 들어가는 말 (예: "… - 3분 25초 - 채널 - 조회수 … - 동영상 재생") */
        private val VIDEO_HINTS = listOf("동영상 재생", "조회수", "play video", "views")
        // 제목에 흔한 'live'·'라이브'는 빼고, 실시간 방송 표시만 제외한다
        private val EXCLUDE_HINTS = listOf(
            "광고", "스폰서", "sponsored", "shorts", "쇼츠", "재생목록", "playlist", "믹스 -", "mix -",
            "실시간 스트리밍", "스트리밍 중", "live now", "live stream",
        )

        /** 유튜브 공식 광고 건너뛰기 버튼 문구 (normalize 후) */
        private val SKIP_AD_LABELS = setOf("광고건너뛰기", "건너뛰기", "skipad", "skipads", "skip")
        private val SKIP_AD_SEARCH = listOf("건너뛰기", "Skip")
        private val PREFERRED_ORDER = listOf(1, 2, 0)
        private const val CARD_NODES = 40
        private const val CARD_DEPTH = 3
        /** 카드 안에 단독으로 붙는 광고 배지 글자 */
        private val AD_BADGES = setOf("광고", "ad", "스폰서", "sponsored")

        private val SEARCH_WORDS = listOf("검색", "search", "찾기")
        private val SEARCH_EXACT = setOf("검색", "search", "검색하기")

        /** 누르기 전에 확인받을 버튼 이름 (결제·송금·전송·삭제·가입 해지 등 되돌리기 어려운 동작) */
        private val SENSITIVE = listOf(
            "결제", "구매", "구입", "주문", "송금", "이체", "보내기", "전송", "보내", "삭제", "탈퇴", "해지", "충전", "계좌", "카드등록", "구독하기",
            "pay", "buy", "purchase", "order", "checkout", "send", "delete", "remove", "transfer", "subscribe",
        )

        fun normalize(text: String): String = text.filter { it.isLetterOrDigit() }.lowercase()

        fun isSensitive(label: String): Boolean {
            val key = normalize(label)
            return SENSITIVE.any { key.contains(it) }
        }
    }
}
