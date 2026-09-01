package kr.easydoc.infrastructure.quality

import kr.easydoc.application.conversion.ConversionFailureKind
import java.time.Duration
import java.util.Locale
import kotlin.math.ceil

/**
 * 문서 한 건의 게이트 ⓪ 측정값. 숫자와 문서 id 만 담는다 — 본문·프롬프트·응답은 담지 않는다.
 */
internal data class LaneMeasurement(
    val documentId: String,
    /** 원문 글자 수. 계약 `char_count` 와 같은 기준(공백 포함 **코드 포인트**)으로 센다. */
    val sourceChars: Int,
    /** 변환 결과 글자 수. 같은 기준으로 센다. 변환이 실패했으면 `null`. */
    val convertedChars: Int?,
    /**
     * 변환에 쓴 출력 토큰. 유스케이스가 보고하는 **문서당 합계**이므로 변환 1회 + 보정 최대
     * 1회의 합이고, 단일 호출 상한([LaneReport] 생성자로 주입되는 실효 값, `easydoc.llm.max-output-tokens`)
     * 대비로는 **상계치**다. 단일 호출의 최댓값은 [LaneJournal.largestConversionCallOutputTokens] 가
     * 따로 센다.
     */
    val outputTokens: Int,
    /** 출력 상한에서 잘려 **변환이 실패로 끝났는가.** 문서 단위 절단 발생률의 분자다. */
    val truncated: Boolean,
    /**
     * 이 문서의 변환·보정 호출 중 `truncated` 였던 호출 수.
     *
     * [truncated] 와 다르다. 보정 호출이 잘리면 유스케이스가 원본 초안을 채택해 변환은
     * **성공으로** 끝나므로([LaneJournal.truncatedConversionCalls]), 그 절단은 [truncated]
     * 에 남지 않는다. 상한에 닿은 빈도를 보려면 이 값이 필요하다.
     */
    val truncatedCalls: Int,
    /** 스타일 규칙 통과 여부. 변환이 실패했으면 `null` — 잴 본문이 없다. */
    val stylePassed: Boolean?,
) {
    /**
     * 출력 팽창비 = 변환 글자 수 / 원문 글자 수.
     *
     * master-plan §3.2 가 「상한값은 골든셋 LLM 평가로 **출력 팽창비를 측정한 뒤** 재조정한다」고
     * 정한 그 값이다. 변환이 실패했으면 `null` — 없는 값을 1.0 으로 채우면 분포가 거짓이 된다.
     */
    val expansion: Double? get() = convertedChars?.takeIf { sourceChars > 0 }?.let { it.toDouble() / sourceChars }
}

/**
 * 레인 결과 집계.
 *
 * ## 두 갈래로 세는 이유
 *
 * **「모델 품질 문제」와 「인프라 문제」를 따로 센다.** 둘이 한 목록에 섞이면 통과율이 무엇을
 * 뜻하는지 말할 수 없다 — 2026-08-27 실측의 56건 중 17건이 그랬다. 어느 쪽에 넣을지는 우리가 새로
 * 정하지 않고 제품 어휘를 따른다: `ConversionFailureKind.retryable` 이 참인 실패
 * (`PROVIDER_ERROR`)는 다시 부르면 달라질 수 있으므로 **인프라**, 거짓인 실패
 * (`TRUNCATED`·`EMPTY_RESULT`)는 같은 입력에 같은 결과가 오므로 **모델 품질**이다.
 *
 * ## 게이트 ⓪ 측정치
 *
 * 이 레인이 존재하는 이유는 `docs/kotlin-redevelopment-backlog.md` §1.2 게이트 ⓪ 이고, 그 게이트가
 * 못 박은 측정 항목은 셋이다 — **출력 팽창비 · 스타일 규칙 통과율 · 절단 발생률.** 셋을 길이
 * 구간별로 낸다([LONG_DOCUMENT_CHARS]). 절단은 품질 실패 목록 안에 두되 **비율은 따로 낸다**:
 * 「품질 실패 6건」 안에 절단 5건이 숨으면 상한 문제를 못 본다.
 *
 * 분포는 평균이 아니라 **중앙값·p90·최대**로 낸다. 상한을 정하는 데 필요한 것은 꼬리다.
 */
internal class LaneReport(
    private val description: String,
    private val journal: LaneJournal,
    /**
     * 이 회차가 실제로 쓴 단일 호출 출력 토큰 상한. 값의 출처는 하나다 — 제품 조립과 같은
     * 해석인 `props.maxOutputTokens`(`easydoc.llm.max-output-tokens`)에서 와야 하고,
     * [LaneReport] 는 여기서 새 기본값을 만들지 않는다. 기본 인자를 두지 않은 것도 같은
     * 이유다 — 호출부가 실제로 쓴 값을 빠뜨리면 컴파일이 막아야 한다.
     */
    private val maxOutputTokens: Int,
) {
    private val quality = mutableListOf<String>()
    private val infrastructure = mutableListOf<String>()
    private val measurements = mutableListOf<LaneMeasurement>()
    private val durationsMillis = mutableListOf<Long>()
    private val transcriptSkipped = mutableListOf<String>()

    /** 문서 한 건을 다 돈 뒤의 측정값과 소요. 변환이 실패한 문서도 센다 — 실패에 쓴 시간도 시간이다. */
    fun recordDocument(
        measurement: LaneMeasurement,
        elapsed: Duration,
    ) {
        measurements += measurement
        durationsMillis += elapsed.toMillis()
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

    /**
     * 이 문서는 [LaneTranscript] 가 켜져 있었지만 변환이 실패해 남길 본문이 없었다.
     *
     * 문서 id 만 담는다 — 본문이 없다는 사실 자체가 값이고, 실었다면 담을 본문도 없다.
     * [LaneTranscript] 가 꺼져 있을 때는 호출부가 이 메서드를 부르지 않는다 — 그래서
     * [render] 에도 이 노브를 켜지 않은 실행에서는 아무 줄이 늘지 않는다.
     */
    fun recordTranscriptSkipped(documentId: String) {
        transcriptSkipped += documentId
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
            appendLine(outcomeLine())
            appendLine(qualityLine())
            appendTranscriptSkippedLine()
            appendGateSection()
            appendDocumentSection()
            appendLine(callLine())
            appendLine(durationLine())
            appendLine("인프라 오류 분포 — ${distributionLine()}")
            appendSection("품질 실패(절단·사실 누락·judge 판정)", quality)
            appendSection("인프라 실패(provider 오류)", infrastructure)
        }.trimEnd()

    // ── 요약 줄 ──────────────────────────────────────────────────────────────────

    private fun outcomeLine(): String {
        val truncated = measurements.count { it.truncated }
        val otherFailures = measurements.count { it.convertedChars == null && !it.truncated }
        return "문서 ${measurements.size}건 · 변환 성공 ${converted(measurements).size} · " +
            "절단으로 실패 $truncated (${rate(truncated, measurements.size)}) · 그 밖의 변환 실패 $otherFailures"
    }

    private fun qualityLine(): String {
        val scored = converted(measurements)
        val passed = scored.count { it.stylePassed == true }
        return "스타일 규칙 통과 $passed/${scored.size} (${rate(passed, scored.size)}) · " +
            "품질 실패 ${quality.size}건 · 인프라 실패 ${infrastructure.size}건"
    }

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
        return "문서 소요 — 합계 ${seconds(sorted.sum())} · 중앙값 ${seconds(quantile(sorted, MEDIAN))} · " +
            "최대 ${seconds(sorted.last())}"
    }

    /**
     * [LaneTranscript] 가 켜져 있었는데 본문을 못 남긴 문서를 알린다. 켜지 않은 실행에서는
     * [recordTranscriptSkipped] 가 한 번도 불리지 않으므로 이 줄 자체가 안 생긴다
     * ([recordTranscriptSkipped] KDoc).
     */
    private fun StringBuilder.appendTranscriptSkippedLine() {
        if (transcriptSkipped.isNotEmpty()) {
            appendLine(
                "변환문 보존 — 건너뜀 ${transcriptSkipped.size}건(변환 실패, 남길 본문 없음): " +
                    transcriptSkipped.joinToString(", "),
            )
        }
    }

    private fun distributionLine(): String =
        journal
            .distribution()
            .entries
            .joinToString(" · ") { (label, count) -> "$label ×$count" }
            .ifEmpty { "없음" }

    // ── 게이트 ⓪ 블록 ────────────────────────────────────────────────────────────

    private fun StringBuilder.appendGateSection() {
        appendLine(
            "게이트 ⓪ 측정 (backlog §1.2) — 팽창비=변환 글자 수/원문 글자 수(공백 포함), " +
                "출력 토큰=문서당 합계(변환 1회+보정 1회), 세 값은 중앙값/p90/최대",
        )
        val (long, short) = measurements.partition { it.sourceChars > LONG_DOCUMENT_CHARS }
        appendLine(bucketLine("  ${LONG_DOCUMENT_CHARS}자 이하", short))
        appendLine(bucketLine("  ${LONG_DOCUMENT_CHARS}자 초과", long))
        appendLine(bucketLine("  전체", measurements))
        appendLine(truncationLine())
        appendLine(silentTruncationLine())
        if (journal.truncatedJudgeCalls > 0) {
            appendLine(
                "  ⚠ judge 호출 절단 ${journal.truncatedJudgeCalls}회 — 잘린 응답을 판정으로 읽었으므로 " +
                    "그 문서의 judge 결과를 신뢰할 수 없다",
            )
        }
        appendLine(
            "  단일 호출 최대 출력 토큰 ${journal.largestConversionCallOutputTokens} / " +
                "상한 $maxOutputTokens (easydoc.llm.max-output-tokens, 변환+보정 호출 기준)",
        )
    }

    // ── 문서별 측정 ───────────────────────────────────────────────────────────────

    /**
     * 문서 한 건 한 줄. 구간 집계([bucketLine])는 분포만 보여 주고 개별 문서 값은 어디에도
     * 실리지 않았다 — 장문 단독 값을 확인하려면 이 줄이 필요하다. 원문 글자 수 내림차순으로
     * 낸다: 이 리포트를 읽는 목적이 장문 확인이다.
     *
     * 변환 실패 문서는 [LaneMeasurement.convertedChars]·[LaneMeasurement.expansion]·
     * [LaneMeasurement.stylePassed] 가 전부 `null` 이다 — 없는 값을 1.0 이나 0 으로 채우지
     * 않고 "-" 로 낸다([LaneMeasurement] KDoc과 같은 이유).
     */
    private fun StringBuilder.appendDocumentSection() {
        appendLine("문서별 측정 (원문 글자 수 내림차순)")
        measurements
            .sortedByDescending { it.sourceChars }
            .forEach { appendLine("  ${documentLine(it)}") }
    }

    private fun documentLine(measurement: LaneMeasurement): String =
        "${measurement.documentId} — 원문 ${measurement.sourceChars} · " +
            "변환 ${measurement.convertedChars?.toString() ?: "-"} · " +
            "팽창비 ${measurement.expansion?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "-"} · " +
            "출력 토큰 ${measurement.outputTokens} · " +
            "절단 호출 ${measurement.truncatedCalls} · " +
            "스타일 ${styleLabel(measurement.stylePassed)}"

    private fun styleLabel(passed: Boolean?): String =
        when (passed) {
            true -> "통과"
            false -> "미통과"
            null -> "-"
        }

    /**
     * 절단을 **두 단위로** 나란히 낸다. 문서 단위는 「사용자에게 결과가 나가지 않은 건수」이고,
     * 호출 단위는 「상한에 닿은 빈도」다. 게이트 ⓪ 이 묻는 것은 뒤쪽이다.
     */
    private fun truncationLine(): String {
        val documents = measurements.count { it.truncated }
        val calls = truncatedCalls()
        return "  절단 — 문서 단위 $documents/${measurements.size} (${rate(documents, measurements.size)}, 변환 실패) · " +
            "호출 단위 $calls/${journal.conversionCalls} (${rate(calls, journal.conversionCalls)}, 변환+보정)"
    }

    /**
     * 호출 단위 절단은 **문서별 기록의 합**으로 낸다. 저널의 전체 카운터
     * ([LaneJournal.truncatedConversionCalls])를 여기서 따로 읽으면 구간 줄과 합계 줄이 서로 다른
     * 출처를 갖게 되고, 둘이 어긋나는 순간 어느 쪽이 참인지 말할 수 없다. 분모(호출 수)만 저널에서
     * 온다 — 문서별 기록은 호출이 몇 번이었는지 모른다.
     */
    private fun truncatedCalls(): Int = measurements.sumOf { it.truncatedCalls }

    /**
     * 두 값의 차이가 곧 **「잘렸는데 조용히 넘어간」 횟수**다. 보정 호출이 절단되면 유스케이스가
     * 원본 초안을 채택하므로 변환은 성공으로 끝난다 — 상한에 닿았는데 실패로 보이지 않는 갈래다.
     */
    private fun silentTruncationLine(): String {
        val silent = truncatedCalls() - measurements.count { it.truncated }
        return if (silent > 0) {
            "  └ 차이 ${silent}회 = 보정 호출이 잘렸지만 원본 초안이 채택돼 성공으로 끝난 횟수 " +
                "(상한에 닿았으나 실패로 보이지 않는다)"
        } else {
            "  └ 차이 없음 — 상한에 닿은 호출이 모두 문서 실패로 나타났다(조용히 넘어간 절단 없음)"
        }
    }

    private fun bucketLine(
        label: String,
        rows: List<LaneMeasurement>,
    ): String {
        if (rows.isEmpty()) {
            return "$label — 표본 없음"
        }
        val scored = converted(rows)
        val truncated = rows.count { it.truncated }
        val passed = scored.count { it.stylePassed == true }
        return "$label — 문서 ${rows.size} · 변환 성공 ${scored.size} · " +
            "절단 문서 $truncated (${rate(truncated, rows.size)}) / 호출 ${rows.sumOf { it.truncatedCalls }} · " +
            "스타일 통과 $passed/${scored.size} (${rate(passed, scored.size)}) · " +
            "팽창비 ${ratioTail(scored.mapNotNull { it.expansion })} · " +
            "출력 토큰 ${tokenTail(rows.map { it.outputTokens })}"
    }

    // ── 공용 ─────────────────────────────────────────────────────────────────────

    private fun converted(rows: List<LaneMeasurement>): List<LaneMeasurement> =
        rows.filter { it.convertedChars != null }

    private fun StringBuilder.appendSection(
        title: String,
        lines: List<String>,
    ) {
        appendLine("$title: ${lines.size}건")
        lines.forEach { appendLine("  - $it") }
    }

    private fun rate(
        count: Int,
        total: Int,
    ): String = if (total == 0) "-" else String.format(Locale.ROOT, "%.1f%%", PERCENT * count / total)

    private fun ratioTail(values: List<Double>): String =
        if (values.isEmpty()) {
            "-"
        } else {
            values.sorted().let { sorted ->
                listOf(quantile(sorted, MEDIAN), quantile(sorted, P90), sorted.last())
                    .joinToString("/") { String.format(Locale.ROOT, "%.2f", it) }
            }
        }

    private fun tokenTail(values: List<Int>): String =
        if (values.isEmpty()) {
            "-"
        } else {
            values.sorted().let { sorted ->
                "${quantile(sorted, MEDIAN)}/${quantile(sorted, P90)}/${sorted.last()}"
            }
        }

    private fun seconds(millis: Long): String = String.format(Locale.ROOT, "%.1f초", millis / MILLIS_PER_SECOND)

    private companion object {
        /**
         * 길이 구간의 경계.
         *
         * 2,000자인 이유는 비교 대상이 있기 때문이다 — master-plan §3.2 가 남긴
         * 「2,000자 초과 스타일 통과율 0.11」이 그 축에서 잰 값이고, 그 숫자는 gpt-4.1 기준이라
         * 현재 모델로 **같은 축에서** 다시 재야 갈음할 수 있다. 파일럿 안내의 「2,000자 내외
         * 권장」도 이 경계를 쓴다.
         */
        const val LONG_DOCUMENT_CHARS: Int = 2_000

        const val MEDIAN: Double = 0.5
        const val P90: Double = 0.9
        const val PERCENT: Double = 100.0
        const val MILLIS_PER_SECOND: Double = 1000.0

        /** nearest-rank 분위수. 표본이 56건이라 보간할 값어치가 없고, 보간하면 없는 값이 생긴다. */
        fun <T : Comparable<T>> quantile(
            sorted: List<T>,
            fraction: Double,
        ): T = sorted[ceil(fraction * sorted.size).toInt().coerceIn(1, sorted.size) - 1]
    }
}
