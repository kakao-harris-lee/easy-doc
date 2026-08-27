package kr.easydoc.infrastructure.quality

import kr.easydoc.application.conversion.ConversionFailureKind
import java.time.Duration
import java.util.Locale

/**
 * 레인 결과 집계.
 *
 * **「모델 품질 문제」와 「인프라 문제」를 따로 센다.** 둘이 한 목록에 섞이면 통과율이 무엇을
 * 뜻하는지 말할 수 없다 — 2026-08-27 실측의 56건 중 17건이 그랬다.
 *
 * 어느 쪽에 넣을지는 우리가 새로 정하지 않고 제품 어휘를 따른다: `ConversionFailureKind.retryable`
 * 이 참인 실패(`PROVIDER_ERROR`)는 다시 부르면 달라질 수 있는 것이므로 **인프라**, 거짓인
 * 실패(`TRUNCATED`·`EMPTY_RESULT`)는 같은 입력에 같은 결과가 오므로 **모델 품질**이다.
 *
 * 담는 것은 문서 id·실패 종류·원인 표시·건수·지연뿐이다. 문서 본문과 변환문은 담지 않는다.
 */
internal class LaneReport(
    private val description: String,
    private val journal: LaneJournal,
) {
    private val quality = mutableListOf<String>()
    private val infrastructure = mutableListOf<String>()
    private val durationsMillis = mutableListOf<Long>()
    private var styleChecked = 0
    private var stylePassed = 0

    /** 문서 한 건을 다 돈 뒤의 소요. 변환이 실패한 문서도 센다 — 실패에 쓴 시간도 시간이다. */
    fun recordDocument(elapsed: Duration) {
        durationsMillis += elapsed.toMillis()
    }

    /** 스타일 통과 여부. **판정에는 쓰지 않는다** — 기존 채점 기준을 바꾸지 않고 관측만 더한다. */
    fun recordStyle(passed: Boolean) {
        styleChecked++
        if (passed) {
            stylePassed++
        }
    }

    fun recordQualityFailure(
        documentId: String,
        detail: String,
    ) {
        quality += "$documentId: $detail"
    }

    fun recordConversionFailure(
        documentId: String,
        kind: ConversionFailureKind,
        fault: LaneFault?,
        retries: Int,
    ) {
        val line = "$documentId: 변환 실패 $kind — 원인 ${fault?.label ?: "미기록"}, 재시도 ${retries}회"
        if (kind.retryable) {
            infrastructure += line
        } else {
            quality += line
        }
    }

    /** judge 호출 자체가 실패했다. 모델의 판정이 아니라 인프라 사건이다. */
    fun recordJudgeFailure(
        documentId: String,
        fault: LaneFault,
        retries: Int,
    ) {
        infrastructure += "$documentId: judge 호출 실패 — 원인 ${fault.label}, 재시도 ${retries}회"
    }

    /** 이 레인이 실패로 보고할 것 전부. 두 갈래를 이어 붙이되 순서는 품질 → 인프라다. */
    fun failures(): List<String> = quality + infrastructure

    /** 사람이 읽는 요약. 통과한 실행에서도 이 줄이 남아야 다음 사람이 무엇으로 잰 값인지 안다. */
    fun render(): String =
        buildString {
            appendLine("골든 LLM 레인 — $description")
            appendLine(
                "문서 ${durationsMillis.size}건 · 품질 실패 ${quality.size}건 · " +
                    "인프라 실패 ${infrastructure.size}건 · 스타일 통과 $stylePassed/$styleChecked",
            )
            appendLine(callLine())
            appendLine(durationLine())
            appendLine("인프라 오류 분포 — ${distributionLine()}")
            appendSection("품질 실패(사실 누락·judge 판정·모델 출력)", quality)
            appendSection("인프라 실패(provider 오류)", infrastructure)
        }.trimEnd()

    private fun callLine(): String {
        val exhausted = if (journal.budgetExhausted) "(소진)" else ""
        return "LLM 호출 ${journal.calls}회 · 재시도 ${journal.retries}회/예산 ${journal.budget}$exhausted · " +
            "입력 ${journal.inputTokens} 토큰 · 출력 ${journal.outputTokens} 토큰"
    }

    private fun durationLine(): String {
        if (durationsMillis.isEmpty()) {
            return "문서 소요 — 없음"
        }
        val sorted = durationsMillis.sorted()
        return "문서 소요 — 합계 ${seconds(sorted.sum())} · 중앙값 ${seconds(sorted[sorted.size / 2])} · " +
            "최대 ${seconds(sorted.last())}"
    }

    private fun distributionLine(): String =
        journal
            .distribution()
            .entries
            .joinToString(" · ") { (label, count) -> "$label ×$count" }
            .ifEmpty { "없음" }

    private fun StringBuilder.appendSection(
        title: String,
        lines: List<String>,
    ) {
        appendLine("$title: ${lines.size}건")
        lines.forEach { appendLine("  - $it") }
    }

    private fun seconds(millis: Long): String = String.format(Locale.ROOT, "%.1f초", millis / MILLIS_PER_SECOND)

    private companion object {
        const val MILLIS_PER_SECOND: Double = 1000.0
    }
}
