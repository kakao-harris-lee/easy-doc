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

/** 보관 구간에만 방출되는 표식 — 소급 루프가 보관분을 훑었는지를 가른다. */
internal const val RETRO_CONTROL_MARKER = "T18-RETAIN-RESCAN-ALIVE"

/** 보관 구간에만 방출되고 구간이 끝난 뒤 토큰과 같은 방식으로 등록되는 합성값. */
internal const val RETRO_CANARY_VALUE = "T18-RETRO-LATE-CANARY-2F7QK"

/** 방출된 로그 이벤트를 훑어 카나리 적중을 지목하는 appender. */
internal class CanaryProbe(private val retroControl: String) : AppenderBase<ILoggingEvent>() {
    /** 적중하면 결함인 축. */
    private val canaries = ConcurrentLinkedQueue<Pair<String, String>>()

    /** 적중해야 정상인 축. 유출 집합의 예외가 아니라 별개 집합이다. */
    private val controls = ConcurrentLinkedQueue<Pair<String, String>>()
    private val found = ConcurrentLinkedQueue<Hit>()
    private val controlFound = ConcurrentLinkedQueue<Hit>()
    private val pinned = ConcurrentHashMap.newKeySet<String>()
    private val retained = ConcurrentLinkedQueue<Pair<String, String>>()

    /** 이벤트를 본 뒤에 등록된 축들. 소급 대조를 지나면 비워진다. */
    private val pendingRetroMatch = ConcurrentHashMap.newKeySet<String>()

    /** 보관 시도 총량. 상한을 넘긴 뒤에도 계속 센다 — 실패 메시지가 실제 규모를 말해야 한다. */
    private val retainedChars = AtomicLong()

    /** `stopRetaining()` 이 큐를 비우기 전에 옮겨 둔 보관 건수. */
    private val retainedEvents = AtomicLong()
    private val total = AtomicLong()
    private val trace = AtomicLong()

    @Volatile private var retaining = true

    @Volatile private var retainTruncated = false

    @Volatile private var positiveControl = false

    /** `rescanRetained()` 안에서만 세워진다. 「루프가 돌았다」까지만 뜻한다. */
    @Volatile private var retroControlSeen = false

    /** 조각을 한 번이라도 읽으면 닫힌다. 닫힌 뒤의 등록은 거절된다. */
    @Volatile private var sealed = false

    /** 적중 하나. 조각이 아니라 원문을 들고 있다 — 렌더는 읽는 시점으로 미룬다. */
    private data class Hit(
        val label: String,
        val axis: String,
        val rendered: String,
    )

    init {
        name = "canary-probe"
        start()
    }

    /** 적중하면 결함인 축을 등록한다. 늦게 등록할 수 있다 — 토큰은 발급 뒤에야 값을 안다. */
    fun addCanary(
        axis: String,
        needle: String,
    ) = register(canaries, LEAK, axis, needle)

    /** 적중해야 정상인 통제 축을 등록한다. */
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

        if (total.get() > 0) pendingRetroMatch += label(kind, axis)
    }

    /** 보관분을 현재 카나리 전체로 다시 훑는다. 늦게 등록한 축이 과거 방출을 재는 유일한 길이다. */
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

            if (rendered.contains(needle) && pinned.add("$kind|$label|$axis")) {
                sink += Hit(label, axis, rendered)
            }
        }
    }

    /** 두 집합의 (축, needle) 합집합. 치환과 잔여 판정이 이것을 쓴다 — 축별 예외를 두지 않는다. */
    fun registeredCanaries(): List<Pair<String, String>> = canaries.toList() + controls.toList()

    /**
     * 실제로 등록된 축의 재고. 유출·통제 양쪽을 담고, `값은 담지 않는다` — 축 이름은
     * 비밀이 아니지만 needle 은 비밀이다.
     */
    fun registeredAxes(): Set<String> =
        (canaries.map { label(LEAK, it.first) } + controls.map { label(CONTROL, it.first) }).toSet()

    private fun label(
        kind: String,
        axis: String,
    ): String = "$kind $axis"

    /** 지목 줄에 남은 카나리 원문 조각의 자리(축·오프셋). 비어 있어야 한다. */
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
     * 유출 지목 줄. 여기서 처음 조각이 만들어지고, 그 순간 등록이 닫힌다 — 그래서 모든
     * 조각은 완전한 카나리 집합으로 렌더된다.
     */
    fun hits(): List<String> = renderHits(found)

    /** 통제 지목 줄. 같은 규율로 렌더된다(통제값도 치환된다). */
    fun controlHits(): List<String> = renderHits(controlFound)

    /** 적중을 낸 통제 축. 늦게 등록된 카나리가 보관분에 대해 적중을 냈는가가 이 값이다. */
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

    /** 적중 지점의 문맥만 돌려준다 — 카나리 값은 한 글자도 담지 않는다. */
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
         * 계정 생성 구간만 보관한다. 실측 25,749자 / 83건(음성 대조에서 상한을 1자로 낮춰
         * 잰 값이다 — 추정이 아니다). 상한은 그 1,300배쯤으로 잡아 두었고, 넘기면 통과가
         * 아니라 실패다. 그래서 상한은 「조용히 잘릴 여유」가 아니라 「이만큼 커졌으면
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
