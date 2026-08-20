package kr.easydoc.api.support

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import ch.qos.logback.core.AppenderBase
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/** 이 문자열이 캡처에 없으면 「유출 0건」은 캡처가 비었다는 뜻이다. */
internal const val POSITIVE_CONTROL_MARKER = "T18-LOG-CAPTURE-ALIVE"

/** 보관 구간에만 방출되는 표식 — 소급 대조가 빈 큐를 훑었는지 가른다. */
internal const val RETRO_CONTROL_MARKER = "T18-RETAIN-RESCAN-ALIVE"

/**
 * 방출된 로그 이벤트를 **그 자리에서 훑는** appender.
 *
 * 강제 TRACE 는 이벤트를 수만 건 낸다 — 전량을 쌓으면 힙이 위험하고 문자열로 이어 붙이면
 * 실패 메시지가 통째로 쏟아진다. 그래서 이벤트마다 즉시 대조하고 **적중만** (로거·레벨·축·
 * 경계 잘라낸 조각) 남긴다. 적중 지목이 이 장치의 산출물이다.
 *
 * **보관 구간**은 예외다 — 발급 전에는 값을 모르는 카나리(액세스 토큰)를 소급 대조해야 하므로,
 * `stopRetaining()` 호출 전까지는 렌더링 결과를 상한(`RETAIN_CHAR_LIMIT`) 안에서 들고 있는다.
 * 상한을 넘기면 보관을 포기하고 `retainTruncated` 를 세우는데, **그 깃발은 호출자가 단언한다**
 * — 잘림은 기록이 아니라 **실패**다. 종전 문면은 *"조용히 버리지 않는다"* 라고 적으면서
 * 실제로는 초록 경로에서 정확히 조용히 버렸다(`report()` 는 `hits()` 가 비면 렌더되지 않는다).
 *
 * **적중 조각은 카나리 값을 담지 않는다.** 지켜야 할 성질은 하나다:
 *
 * > **카나리 집합이 불완전한 상태에서 조각을 만들지 않는다.**
 *
 * 이 성질을 두 번 놓쳤고 두 번 다 기제가 **순서**였다. ⑴ 치환이 **자르기보다 뒤**여서 창
 * 경계의 토막이 남았다. ⑵ 치환이 **등록보다 앞**설 수 있어서, 늦게 등록되는 토큰이 그 전에
 * 만들어진 조각에 **원문으로 동결**됐다 — `pinned` 가 재등록을 막고 동결된 문자열을 다시
 * 치환할 경로가 없어 `rescanRetained()` 로도 되돌리지 못했다.
 *
 * 그래서 **조각을 적중 시점에 만들지 않는다.** 적중은 `(label, axis, rendered)` 원문으로만
 * 쌓아 두고, **읽는 시점에** — 등록이 끝난 뒤 — 렌더한다. 그리고 읽기가 등록을 **빗장으로
 * 닫는다**(`addCanary` 가 이후 거절된다). 늦은 등록이 몇 번이든, 어느 구간이든 조각은 항상
 * 완전한 집합으로 만들어진다 — 「지금은 그런 줄이 안 찍힌다」에 기대지 않는다.
 *
 * 축별 예외는 두지 않는다. 본문·제목은 합성 문자열이라 실어도 무해하지만 예외를 두면 그것이
 * 면제 목록이 되어 다음에 실제 비밀이 새 축으로 들어올 때 조용히 샌다(`CLAUDE.md` 규칙 4 ⑵).
 *
 * 이 성질을 재는 장치는 `CanaryProbeRedactionTest` 다 — 두 번 다 「구현의 조심함」뿐이었고
 * 장치가 0개여서 같은 결함이 두 번 났다.
 *
 * 요청은 Tomcat 워커 스레드에서 처리되므로 수집 구조는 동시 접근을 견뎌야 한다.
 */
internal class CanaryProbe(private val retroControl: String) : AppenderBase<ILoggingEvent>() {
    private val canaries = ConcurrentLinkedQueue<Pair<String, String>>()
    private val found = ConcurrentLinkedQueue<Hit>()
    private val pinned = ConcurrentHashMap.newKeySet<String>()
    private val retained = ConcurrentLinkedQueue<Pair<String, String>>()

    /** 보관 **시도** 총량. 상한을 넘긴 뒤에도 계속 센다 — 실패 메시지가 실제 규모를 말해야 한다. */
    private val retainedChars = AtomicLong()

    /** `stopRetaining()` 이 큐를 비우기 전에 옮겨 둔 보관 건수. */
    private val retainedEvents = AtomicLong()
    private val total = AtomicLong()
    private val trace = AtomicLong()

    @Volatile private var retaining = true

    @Volatile private var retainTruncated = false

    @Volatile private var positiveControl = false

    /** `rescanRetained()` **안에서만** 세워진다 — 그래서 그 경로가 죽으면 거짓으로 남는다. */
    @Volatile private var retroControlSeen = false

    /** 조각을 한 번이라도 렌더하면 닫힌다. 닫힌 뒤의 `addCanary` 는 거절된다. */
    @Volatile private var sealed = false

    /** 적중 하나. **조각이 아니라 원문**을 들고 있다 — 렌더는 읽는 시점으로 미룬다. */
    private data class Hit(
        val label: String,
        val axis: String,
        val rendered: String,
    )

    init {
        name = "canary-probe"
        start()
    }

    /**
     * 카나리를 늦게 등록할 수 있다 — 토큰은 발급 뒤에야 값을 안다.
     *
     * 단 **조각을 읽은 뒤에는 거절한다.** 이미 렌더된 조각은 그 시점의 집합으로 만들어졌으므로,
     * 뒤늦게 추가된 카나리를 그 조각에서 지울 방법이 없다 — 그 상태를 허용하면 결함 ⑵ 가
     * 되돌아온다. 조용히 무시하지 않고 던지는 이유는, 무시하면 「등록했다고 믿는 축이 실제로는
     * 안 재지는」 더 나쁜 상태가 되기 때문이다.
     */
    fun addCanary(
        axis: String,
        needle: String,
    ) {
        check(!sealed) {
            "조각을 읽은 뒤에는 카나리를 등록할 수 없다(축: $axis). " +
                "읽기 전에 등록하라 — 불완전한 집합으로 만든 조각은 원문을 동결한다."
        }
        canaries += axis to needle
    }

    /** 보관분을 현재 카나리 전체로 다시 훑는다. 늦게 등록한 축의 과거 방출을 잡는 유일한 길이다. */
    fun rescanRetained() {
        retained.forEach { (logger, rendered) ->
            if (rendered.contains(retroControl)) retroControlSeen = true
            match(logger, rendered)
        }
    }

    /** 보관을 끝내고 힙을 놓는다. 이후 구간은 흐름 대조만 한다. */
    fun stopRetaining() {
        retaining = false
        // 건수는 실패 메시지가 쓰므로 비우기 전에 옮겨 둔다. 문자 수는 **줄이지 않는다** —
        // 0 으로 되돌리면 잘림 메시지가 규모를 거짓으로 말한다.
        retainedEvents.set(retained.size.toLong())
        retained.clear()
    }

    override fun append(event: ILoggingEvent) {
        total.incrementAndGet()
        if (event.level == Level.TRACE) trace.incrementAndGet()
        val rendered = render(event)
        if (rendered.contains(POSITIVE_CONTROL_MARKER)) positiveControl = true
        val label = "${event.loggerName}@${event.level}"
        if (retaining) {
            if (retainedChars.addAndGet(rendered.length.toLong()) <= RETAIN_CHAR_LIMIT) {
                retained += label to rendered
            } else {
                retainTruncated = true
            }
        }
        match(label, rendered)
    }

    private fun match(
        label: String,
        rendered: String,
    ) {
        canaries.forEach { (axis, needle) ->
            if (rendered.contains(needle)) {
                // 로거·축 조합당 한 줄만 남긴다 — 같은 로거가 수천 번 찍어도 지목은 한 번이면 된다.
                if (pinned.add("$label|$axis")) {
                    // **여기서 조각을 만들지 않는다** — 이 시점의 카나리 집합은 불완전할 수 있다.
                    found += Hit(label, axis, rendered)
                }
            }
        }
    }

    /** 등록된 (축, needle). */
    fun registeredCanaries(): List<Pair<String, String>> = canaries.toList()

    /**
     * 지목 줄에 남은 **카나리 원문 조각**의 자리(축·오프셋). 비어 있어야 한다.
     *
     * **왜 「원문 포함」이 아니라 「조각 포함」인가**: 창은 ±[CONTEXT_CHARS]자라 긴 카나리
     * (액세스 토큰은 150자 내외)는 통째로 들어오지 않는다. 그래서 `contains(needle)` 은 유출을
     * 놓친다 — 실제로 놓쳤다. 실측 유출 조각은 15자였고 `eyJ`(JWT 머리)도 `Bearer <값>` 도
     * 걸리지 않았다. 그래서 길이 [FRAGMENT_CHARS] 이상 **부분 문자열 전부**를 훑는다.
     *
     * **정의를 여기 한 곳에 둔다.** 이 성질을 재는 곳이 둘(단위 케이스와 실제 도달 케이스)이고,
     * 두 곳이 각자 정의하면 한쪽만 느슨해져도 아무도 모른다.
     *
     * 반환값은 **자리만** 말하고 조각 값은 담지 않는다 — 유출을 재는 것이 유출하면 안 된다.
     */
    fun residualCanaryFragments(): List<String> =
        hits().let { lines ->
            canaries.flatMap { (axis, needle) ->
                (0..needle.length - FRAGMENT_CHARS).mapNotNull { at ->
                    val fragment = needle.substring(at, at + FRAGMENT_CHARS)
                    if (lines.any { it.contains(fragment) }) "$axis 축 offset $at" else null
                }
            }
        }

    /**
     * 지목 줄. **여기서 처음 조각이 만들어지고**, 그 순간 등록이 닫힌다 — 그래서 모든 조각은
     * 완전한 카나리 집합으로 렌더된다.
     */
    fun hits(): List<String> {
        sealed = true
        return found
            .toList()
            .map { "  · ${it.label} — ${it.axis} 축: …${snippet(it.rendered, it.axis)}…" }
            .sorted()
    }

    fun report(): String = hits().joinToString(System.lineSeparator()).ifEmpty { "  (지목 없음)" }

    fun sawPositiveControl(): Boolean = positiveControl

    fun sawRetroControl(): Boolean = retroControlSeen

    fun retainTruncated(): Boolean = retainTruncated

    fun retainedCharsSeen(): Long = retainedChars.get()

    fun retainedEvents(): Long = if (retaining) retained.size.toLong() else retainedEvents.get()

    fun retainCharLimit(): Long = RETAIN_CHAR_LIMIT

    fun totalEvents(): Long = total.get()

    fun traceEvents(): Long = trace.get()

    /** 로그 한 줄이 실제로 파일·콘솔에 남기는 것 전부 — 메시지와 예외 체인(스택 프레임 포함). */
    private fun render(event: ILoggingEvent): String =
        buildString {
            append(event.loggerName).append(' ').append(event.formattedMessage)
            var throwable: IThrowableProxy? = event.throwableProxy
            while (throwable != null) {
                append('\n').append(throwable.className).append(": ").append(throwable.message)
                throwable.stackTraceElementProxyArray?.forEach { append('\n').append(it.steAsString) }
                throwable = throwable.cause
            }
        }

    /**
     * 적중 지점의 **문맥만** 돌려준다 — 카나리 값은 한 글자도 담지 않는다.
     *
     * 치환이 **자르기보다 먼저**여야 한다. 나중에 치환하면 창 경계에 걸린 카나리 **토막**이
     * 그대로 남는다(N-A 실측에서 본문 축 조각의 앞 문맥에 Bearer 토큰 꼬리가 딸려 나왔다).
     * 긴 needle 부터 치환하는 이유는 한 카나리가 다른 카나리의 부분 문자열일 때 짧은 쪽이
     * 먼저 먹어 긴 쪽의 잔여가 남는 것을 막기 위해서다.
     */
    private fun snippet(
        text: String,
        axis: String,
    ): String {
        var redacted = text
        canaries
            .sortedByDescending { (_, needle) -> needle.length }
            .forEach { (each, needle) -> redacted = redacted.replace(needle, marker(each)) }
        val at = redacted.indexOf(marker(axis)).coerceAtLeast(0)
        val from = (at - CONTEXT_CHARS).coerceAtLeast(0)
        val to = (at + marker(axis).length + CONTEXT_CHARS).coerceAtMost(redacted.length)
        return redacted.substring(from, to).replace('\n', '⏎').replace('\r', '⏎')
    }

    private fun marker(axis: String): String = "«$axis»"

    private companion object {
        /**
         * 계정 생성 구간만 보관한다. **실측 25,749자 / 83건**(음성 대조에서 상한을 1자로 낮춰
         * 잰 값이다 — 추정이 아니다). 상한은 그 1,300배쯤으로 잡아 두었고, **넘기면 통과가
         * 아니라 실패**다. 그래서 상한은 「조용히 잘릴 여유」가 아니라 「이만큼 커졌으면
         * 무언가 변했다」는 경보선이다.
         */
        const val RETAIN_CHAR_LIMIT = 32L * 1024 * 1024
        const val CONTEXT_CHARS = 60

        /**
         * 잔여 판정의 최소 일치 길이. 이보다 짧으면 우연 일치가 섞이고, 이보다 길면 실측
         * 15자 조각(`-_uItImvEwdgfyU`)을 놓친다.
         */
        const val FRAGMENT_CHARS = 12
    }
}
