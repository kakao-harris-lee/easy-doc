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

/** 보관 구간에만 방출되는 표식 — 소급 **루프가 보관분을 훑었는지**를 가른다. */
internal const val RETRO_CONTROL_MARKER = "T18-RETAIN-RESCAN-ALIVE"

/**
 * 보관 구간에만 방출되고 **구간이 끝난 뒤 토큰과 같은 방식으로 등록되는** 합성값.
 *
 * `RETRO_CONTROL_MARKER` 와 겨누는 것이 다르다 — 그쪽은 「루프가 돌았다」까지고, 이쪽은
 * **「늦게 등록된 카나리가 보관분에 대해 실제로 적중을 낸다」**다. 자세한 구분은
 * [CanaryProbe] KDoc 의 「두 통제가 각각 증명하는 것」에 있다.
 */
internal const val RETRO_CANARY_VALUE = "T18-RETRO-LATE-CANARY-2F7QK"

/**
 * 방출된 로그 이벤트를 훑어 카나리 적중을 지목하는 appender.
 *
 * 강제 TRACE 는 이벤트를 수만 건 낸다 — 전량을 쌓으면 힙이 위험하고 문자열로 이어 붙이면
 * 실패 메시지가 통째로 쏟아진다. 그래서 이벤트마다 즉시 대조하고 **적중만** 남긴다.
 *
 * **보관 구간**은 예외다 — 발급 전에는 값을 모르는 카나리(액세스 토큰)를 소급 대조해야 하므로,
 * `stopRetaining()` 호출 전까지는 렌더링 결과를 상한(`RETAIN_CHAR_LIMIT`) 안에서 들고 있는다.
 * 상한을 넘기면 보관을 포기하고 `retainTruncated` 를 세우는데, **그 깃발은 호출자가 단언한다**
 * — 잘림은 기록이 아니라 **실패**다. 종전 문면은 *"조용히 버리지 않는다"* 라고 적으면서
 * 실제로는 초록 경로에서 정확히 조용히 버렸다.
 *
 * ## 조각은 **읽는 시점에** 만든다
 *
 * 지켜야 할 성질 하나:
 *
 * > **카나리 집합이 불완전한 상태에서 조각을 만들지 않는다.**
 *
 * 이 성질을 두 번 놓쳤고 두 번 다 기제가 **순서**였다. ⑴ 치환이 **자르기보다 뒤**여서 창
 * 경계의 토막이 남았다. ⑵ 치환이 **등록보다 앞**설 수 있어서, 늦게 등록되는 토큰이 그 전에
 * 만들어진 조각에 **원문으로 동결**됐다. 그래서 적중은 `(label, axis, rendered)` 원문으로만
 * 쌓고, 조각은 **읽는 시점에** 렌더하며, 읽기가 등록을 **빗장으로 닫는다**.
 *
 * ## 두 통제가 각각 증명하는 것 — 겨누는 대상이 다르다
 *
 * | 통제 | 증명하는 것 | 증명하지 **못하는** 것 |
 * |---|---|---|
 * | `sawPositiveControl()` | 캡처가 살아 있다 | 레벨 상향·보관·소급 |
 * | `traceEvents() != 0` | 강제 TRACE 가 먹었다 | 적중 경로가 산다 |
 * | `retainTruncated()` | 보관이 전량이다 | 보관분이 **대조됐는지** |
 * | `sawRetroControl()` | 소급 **루프가 보관분을 훑었다** | 그 루프가 **카나리 집합을 지나는지** |
 * | `controlHitAxes()` | **늦게 등록된 카나리가 보관분에 대해 적중을 낸다** | — |
 * | `pendingRetroMatches()` | 늦게 등록된 카나리가 **빠짐없이** 소급 대조를 지났다 | — |
 *
 * 아래 두 줄이 이 클래스가 세 번째로 같은 종류를 맞고 추가된 부분이다. `sawRetroControl()` 은
 * `rendered.contains(retroControl)` 로 **`canaries` 집합을 우회해** 직접 검사하므로, 등록이
 * 소급 대조 **뒤로** 밀리거나 소급 루프가 `match()` 를 안 타면 **토큰 축이 조용히 죽는데도**
 * 참으로 남았다 — 그 상태에서 잔여 0·적중 0 이라 **전부 초록**이었다. 즉 「통제가 있는데
 * 성질을 안 겨눈다」였고, 그것이 이 세션에 세 번 났다(`retainTruncated` 미단언 · 조각 동결 ·
 * 이것). 그래서 통제 자신을 **늦게 등록되는 카나리**로 만들어 등록 순서가 깨지는 순간
 * 빨개지게 한다.
 *
 * ## 통제 적중과 유출 적중은 **다른 집합**이다 (면제 목록이 아니다)
 *
 * 통제 카나리는 적중해야 정상이고 유출 카나리는 적중하면 결함이다. 그 둘을 한 목록에 담고
 * 축 이름으로 걸러 내면 **그 필터가 곧 면제 목록**이 되어(`CLAUDE.md` 규칙 4 ⑵) 다음에
 * 실제 비밀이 그 이름으로 들어올 때 조용히 빠진다. 그래서 **등록 시점에 레지스트리를 가른다**
 * — `addCanary` 는 유출 집합, `addControlCanary` 는 통제 집합이고 각자 다른 sink 로 적중을
 * 센다. 어느 것도 다른 것의 예외로 정의되지 않는다.
 *
 * **치환과 잔여 판정은 두 집합의 합집합**을 쓴다. 통제값이 합성이라 무해해 보여도 축별 예외를
 * 두면 그것이 다음 결함의 통로다 — 이 세션이 세 번 확인했다.
 *
 * 요청은 Tomcat 워커 스레드에서 처리되므로 수집 구조는 동시 접근을 견뎌야 한다.
 */
internal class CanaryProbe(private val retroControl: String) : AppenderBase<ILoggingEvent>() {
    /** 적중하면 **결함**인 축. */
    private val canaries = ConcurrentLinkedQueue<Pair<String, String>>()

    /** 적중해야 **정상**인 축. 유출 집합의 예외가 아니라 별개 집합이다. */
    private val controls = ConcurrentLinkedQueue<Pair<String, String>>()
    private val found = ConcurrentLinkedQueue<Hit>()
    private val controlFound = ConcurrentLinkedQueue<Hit>()
    private val pinned = ConcurrentHashMap.newKeySet<String>()
    private val retained = ConcurrentLinkedQueue<Pair<String, String>>()

    /**
     * **이벤트를 본 뒤에** 등록된 축들. 소급 대조를 지나면 비워진다.
     *
     * 비어 있지 않은 채로 판정에 도달하면 「그 축은 보관분에 대해 재지지 않았다」는 뜻이다.
     */
    private val pendingRetroMatch = ConcurrentHashMap.newKeySet<String>()

    /** 보관 **시도** 총량. 상한을 넘긴 뒤에도 계속 센다 — 실패 메시지가 실제 규모를 말해야 한다. */
    private val retainedChars = AtomicLong()

    /** `stopRetaining()` 이 큐를 비우기 전에 옮겨 둔 보관 건수. */
    private val retainedEvents = AtomicLong()
    private val total = AtomicLong()
    private val trace = AtomicLong()

    @Volatile private var retaining = true

    @Volatile private var retainTruncated = false

    @Volatile private var positiveControl = false

    /** `rescanRetained()` **안에서만** 세워진다. 「루프가 돌았다」까지만 뜻한다. */
    @Volatile private var retroControlSeen = false

    /** 조각을 한 번이라도 읽으면 닫힌다. 닫힌 뒤의 등록은 거절된다. */
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
     * 적중하면 결함인 축을 등록한다. 늦게 등록할 수 있다 — 토큰은 발급 뒤에야 값을 안다.
     *
     * 단 **조각을 읽은 뒤에는 거절한다.** 이미 렌더된 조각은 그 시점의 집합으로 만들어졌으므로
     * 뒤늦게 추가된 카나리를 그 조각에서 지울 방법이 없다. 조용히 무시하지 않고 던지는 이유는,
     * 무시하면 「등록했다고 믿는 축이 실제로는 안 재지는」 더 나쁜 상태가 되기 때문이다.
     */
    fun addCanary(
        axis: String,
        needle: String,
    ) = register(canaries, LEAK, axis, needle)

    /**
     * 적중해야 정상인 통제 축을 등록한다.
     *
     * 이 축의 적중은 `hits()` 에 들어가지 않는다 — 걸러서가 아니라 **다른 집합에 담기기 때문**이다.
     */
    fun addControlCanary(
        axis: String,
        needle: String,
    ) = register(controls, CONTROL, axis, needle)

    private fun register(
        registry: ConcurrentLinkedQueue<Pair<String, String>>,
        kind: String,
        axis: String,
        needle: String,
    ) {
        check(!sealed) {
            "조각을 읽은 뒤에는 카나리를 등록할 수 없다($kind $axis). " +
                "읽기 전에 등록하라 — 불완전한 집합으로 만든 조각은 원문을 동결한다."
        }
        registry += axis to needle
        // 이벤트를 이미 본 뒤의 등록은 **늦은 등록**이다. 보관분에 대해 소급 대조를 지나야
        // 그 축이 그 구간을 실제로 잰 것이 된다.
        if (total.get() > 0) pendingRetroMatch += "$kind $axis"
    }

    /**
     * 보관분을 **현재 카나리 전체**로 다시 훑는다. 늦게 등록한 축이 과거 방출을 재는 유일한 길이다.
     *
     * 보관분이 실제로 있었을 때만 [pendingRetroMatch] 를 비운다 — 빈 큐를 훑는 것은 대조가
     * 아니다(보관이 이미 비워진 뒤의 등록이 조용히 통과하던 자리).
     */
    fun rescanRetained() {
        val hadRetained = retained.isNotEmpty()
        retained.forEach { (logger, rendered) ->
            if (rendered.contains(retroControl)) retroControlSeen = true
            match(logger, rendered)
        }
        if (hadRetained) pendingRetroMatch.clear()
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
        matchInto(canaries, found, LEAK, label, rendered)
        matchInto(controls, controlFound, CONTROL, label, rendered)
    }

    private fun matchInto(
        registry: ConcurrentLinkedQueue<Pair<String, String>>,
        sink: ConcurrentLinkedQueue<Hit>,
        kind: String,
        label: String,
        rendered: String,
    ) {
        registry.forEach { (axis, needle) ->
            // 종류·로거·축 조합당 한 번만 남긴다 — 같은 로거가 수천 번 찍어도 지목은 한 번이면 된다.
            if (rendered.contains(needle) && pinned.add("$kind|$label|$axis")) {
                // **여기서 조각을 만들지 않는다** — 이 시점의 카나리 집합은 불완전할 수 있다.
                sink += Hit(label, axis, rendered)
            }
        }
    }

    /** 두 집합의 (축, needle) 합집합. 치환과 잔여 판정이 이것을 쓴다 — 축별 예외를 두지 않는다. */
    fun registeredCanaries(): List<Pair<String, String>> = canaries.toList() + controls.toList()

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
        (hits() + controlHits()).let { lines ->
            registeredCanaries().flatMap { (axis, needle) ->
                (0..needle.length - FRAGMENT_CHARS).mapNotNull { at ->
                    val fragment = needle.substring(at, at + FRAGMENT_CHARS)
                    if (lines.any { it.contains(fragment) }) "$axis 축 offset $at" else null
                }
            }
        }

    /**
     * 유출 지목 줄. **여기서 처음 조각이 만들어지고**, 그 순간 등록이 닫힌다 — 그래서 모든
     * 조각은 완전한 카나리 집합으로 렌더된다.
     */
    fun hits(): List<String> = renderHits(found)

    /** 통제 지목 줄. 같은 규율로 렌더된다(통제값도 치환된다). */
    fun controlHits(): List<String> = renderHits(controlFound)

    /** 적중을 낸 통제 축. **늦게 등록된 카나리가 보관분에 대해 적중을 냈는가**가 이 값이다. */
    fun controlHitAxes(): Set<String> {
        sealed = true
        return controlFound.map { it.axis }.toSet()
    }

    /** 소급 대조를 아직 지나지 않은 늦은 등록. 비어 있어야 한다. */
    fun pendingRetroMatches(): List<String> = pendingRetroMatch.toList().sorted()

    private fun renderHits(sink: ConcurrentLinkedQueue<Hit>): List<String> {
        sealed = true
        return sink
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
     * 그대로 남는다. 긴 needle 부터 치환하는 이유는 한 카나리가 다른 카나리의 부분 문자열일 때
     * 짧은 쪽이 먼저 먹어 긴 쪽의 잔여가 남는 것을 막기 위해서다. 유출·통제 **두 집합 모두**
     * 치환한다.
     */
    private fun snippet(
        text: String,
        axis: String,
    ): String {
        var redacted = text
        registeredCanaries()
            .sortedByDescending { (_, needle) -> needle.length }
            .forEach { (each, needle) -> redacted = redacted.replace(needle, marker(each)) }
        val at = redacted.indexOf(marker(axis)).coerceAtLeast(0)
        val from = (at - CONTEXT_CHARS).coerceAtLeast(0)
        val to = (at + marker(axis).length + CONTEXT_CHARS).coerceAtMost(redacted.length)
        return redacted.substring(from, to).replace('\n', '⏎').replace('\r', '⏎')
    }

    private fun marker(axis: String): String = "«$axis»"

    private companion object {
        const val LEAK = "유출"
        const val CONTROL = "통제"

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
