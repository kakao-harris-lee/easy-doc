package kr.easydoc.infrastructure.quality

import kr.easydoc.application.conversion.ConversionFailureKind
import kr.easydoc.core.easyread.StyleRuleKind
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
    /**
     * `checkStyle` 이 나눈 문장 수(`StyleCheckResult.totalSentences`). [stylePassed] 를 낸
     * 바로 그 [kr.easydoc.core.quality.evaluateStyle] 호출에서 그대로 가져온다 — 이 레인이
     * 문장 분리기를 따로 두지 않는 이유가 여기 있다. 분리기를 따로 두면 통과 판정과 문장 수가
     * 서로 다른 분리 기준을 잴 위험이 생긴다(둘이 갈리면 위반 밀도가 어느 기준으로 잰 값인지
     * 말할 수 없다). 변환이 실패했으면 잴 본문이 없으므로 0.
     */
    val sentenceCount: Int,
    /**
     * 규칙별([StyleRuleKind]) 위반 문장 수. 마찬가지로 [sentenceCount] 를 낸 `checkStyle`
     * 호출의 `issues` 를 규칙별로 센 값이다. 변환이 실패했으면 빈 맵.
     */
    val styleIssueCounts: Map<StyleRuleKind, Int>,
) {
    /**
     * 출력 팽창비 = 변환 글자 수 / 원문 글자 수.
     *
     * master-plan §3.2 가 「상한값은 골든셋 LLM 평가로 **출력 팽창비를 측정한 뒤** 재조정한다」고
     * 정한 그 값이다. 변환이 실패했으면 `null` — 없는 값을 1.0 으로 채우면 분포가 거짓이 된다.
     */
    val expansion: Double? get() = convertedChars?.takeIf { sourceChars > 0 }?.let { it.toDouble() / sourceChars }

    /** [styleIssueCounts] 값의 합 — 문서 한 건의 스타일 위반 총수. */
    val styleIssueCount: Int get() = styleIssueCounts.values.sum()

    /**
     * 스타일 위반 밀도 = 위반 수 / 문장 수. 게이트 ⓪ 의 새 입력값 — 문서 단위 이분법(통과/미통과)
     * 대신 위반이 얼마나 조밀한지를 본다.
     *
     * 변환이 실패했거나([convertedChars] `null`) 문장이 하나도 없으면([sentenceCount] 0)
     * `null` — 0 으로 채우면 "위반이 없다"와 "잴 수 없다"가 구분되지 않는다([expansion] 과
     * 같은 판단).
     */
    val styleIssueDensity: Double?
        get() = convertedChars?.let { styleIssueCount.toDouble().takeIf { sentenceCount > 0 }?.div(sentenceCount) }
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
            appendRunAggregateSection()
            appendLine(callLine())
            appendLine(durationLine())
            appendLine("인프라 오류 분포 — ${distributionLine()}")
            appendSection("품질 실패(절단·사실 누락·judge 판정)", quality)
            appendSection("인프라 실패(provider 오류)", infrastructure)
        }.trimEnd()

    // ── 요약 줄 ──────────────────────────────────────────────────────────────────

    /**
     * **모든 헤드라인 수치의 단위는 문서다.** [measurements] 는 문서×회차 행이라 그대로 세면
     * runs=3 에서 문서 7건이 21건으로 보인다 — [documentSummaries] 가 그 사고를 막는 자리다.
     * runs=1 이면 그룹 크기가 항상 1이라 모든 축약이 원본 행과 값이 같으므로 오늘 렌더링과
     * 완전히 같다.
     *
     * 세 갈래(변환 성공·절단으로 실패·그 밖의 변환 실패)는 **서로 배타적이고 문서 수의 합과
     * 같아야 한다** — runs=1 이 그랬던 것과 같다. 그래서 「변환 성공」은
     * [DocumentSummary.allRunsConverted](모든 회차가 변환에 성공)를 쓴다 — [스타일 통과율의
     * 분모][qualityLine]가 쓰는 [DocumentSummary.hasConvertedRun](회차 중 하나라도 성공)과는
     * 다른 축약이다. 회차 하나가 절단된 문서도 스타일을 잴 회차는 있을 수 있지만(그래서
     * 통과율 분모에는 들어간다), 「변환 성공」이라 부를 수는 없다 — 절단으로 실패 쪽에 이미
     * 잡혔다.
     */
    private fun outcomeLine(): String {
        val summaries = documentSummaries()
        val truncated = summaries.count { it.truncated }
        val converted = summaries.count { it.allRunsConverted }
        val otherFailures = summaries.count { it.otherFailure }
        return "문서 ${summaries.size}건 · 변환 성공 $converted · " +
            "절단으로 실패 $truncated (${rate(truncated, summaries.size)}) · 그 밖의 변환 실패 $otherFailures"
    }

    /**
     * 통과율 분모는 [DocumentSummary.hasConvertedRun](회차 중 하나라도 성공)이다 —
     * [outcomeLine] 의 「변환 성공」([DocumentSummary.allRunsConverted], 모든 회차 성공)과
     * 다른 축약이다. 절단된 회차가 섞여도 성공한 회차의 스타일 판정은 실재하는 값이라, 그
     * 회차들을 근거로 통과율을 매길 수 있다 — [DocumentSummary.stylePassedAllRuns] 가 그 절단
     * 회차 때문에 결국 미통과로 떨어뜨린다(박한 쪽).
     */
    private fun qualityLine(): String {
        val summaries = documentSummaries()
        val scored = summaries.filter { it.hasConvertedRun }
        val passed = scored.count { it.stylePassedAllRuns }
        return "스타일 규칙 통과 $passed/${scored.size} (${rate(passed, scored.size)}) · " +
            "품질 실패 ${quality.size}건 · 인프라 실패 ${infrastructure.size}건"
    }

    private fun callLine(): String {
        val exhausted = if (journal.budgetExhausted) "(소진)" else ""
        return "LLM 호출 ${journal.calls}회 · 재시도 ${journal.retries}회/예산 ${journal.budget}$exhausted · " +
            "입력 ${journal.inputTokens} 토큰 · 출력 ${journal.outputTokens} 토큰"
    }

    /**
     * 합계는 이 레인이 실제로 쓴 총 시간이라 회차 축약 없이 [durationsMillis] 그대로 합한다 —
     * 「몇 초를 썼는가」는 문서 개념이 아니라 실측 총량이다. 중앙값·최대는 문서 단위다 —
     * 문서마다 회차 중앙값을 먼저 내고([DocumentSummary.medianElapsedMillis]), 그 위에서
     * 다시 중앙값·최대를 낸다. 그러지 않으면 runs=3 에서 한 문서의 세 회차가 분포에 세 번
     * 들어가 「전형적인 문서가 걸리는 시간」을 부풀린다.
     */
    private fun durationLine(): String {
        if (durationsMillis.isEmpty()) {
            return "문서 소요 — 없음"
        }
        val perDocument = documentSummaries().map { it.medianElapsedMillis }.sorted()
        return "문서 소요 — 합계 ${seconds(durationsMillis.sum())} · 중앙값 ${seconds(quantile(perDocument, MEDIAN))} · " +
            "최대 ${seconds(perDocument.last())}"
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
        val summaries = documentSummaries()
        val (long, short) = summaries.partition { it.sourceChars > LONG_DOCUMENT_CHARS }
        appendLine(bucketLine("  ${LONG_DOCUMENT_CHARS}자 이하", short))
        appendLine(bucketLine("  ${LONG_DOCUMENT_CHARS}자 초과", long))
        appendLine(bucketLine("  전체", summaries))
        appendLine(styleDensityLine())
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
            "스타일 ${styleLabel(measurement.stylePassed)} · " +
            "문장 ${measurement.sentenceCount} · " +
            "위반 ${styleIssueSummary(measurement)}"

    private fun styleLabel(passed: Boolean?): String =
        when (passed) {
            true -> "통과"
            false -> "미통과"
            null -> "-"
        }

    /**
     * 위반 총수·밀도·규칙별([StyleRuleKind]) 내역을 한 자리에 낸다. 규칙별로 나누는 이유는
     * DIFFICULT_WORD 과다 발화처럼 특정 규칙 하나가 밀도를 끌어올리는 경우를 다른 규칙과
     * 섞지 않고 읽기 위해서다. 변환이 실패한 문서는 잴 본문이 없으므로 "-"([styleLabel] 과
     * 같은 관례).
     */
    private fun styleIssueSummary(measurement: LaneMeasurement): String {
        if (measurement.convertedChars == null) return "-"
        val density = measurement.styleIssueDensity?.let { String.format(Locale.ROOT, "%.3f", it) } ?: "-"
        val breakdown = StyleRuleKind.entries.joinToString(" ") { "$it=${measurement.styleIssueCounts[it] ?: 0}" }
        return "${measurement.styleIssueCount}건(밀도 $density · $breakdown)"
    }

    /**
     * 게이트 ⓪ 요약에 남기는 문서 간 위반 밀도 중앙값. **표본은 문서 수다** — runs>1 이면
     * [DocumentSummary.medianDensity] 가 먼저 문서 하나의 회차들을 중앙값 하나로 접었으므로,
     * 여기서 다시 문서 수만큼만 센다(회차 수가 아니다). 개별 문서 값은 [appendDocumentSection]
     * 이 회차별로 낸다.
     */
    private fun styleDensityLine(): String {
        val densities = documentSummaries().mapNotNull { it.medianDensity }
        return if (densities.isEmpty()) {
            "  스타일 위반 밀도 — 표본 없음"
        } else {
            "  스타일 위반 밀도 중앙값 ${String.format(Locale.ROOT, "%.3f", quantile(densities.sorted(), MEDIAN))} " +
                "(위반 수/문장 수, 표본 ${densities.size}건)"
        }
    }

    /**
     * 같은 문서를 여러 번([EASYDOC_LANE_RUNS]) 돌렸을 때만 나온다 — 1회씩만 돈 문서는 반복
     * 집계랄 것이 없다. 매 실행에서 어떤 문서 그룹도 크기가 2 이상이 아니면(= runs 가 1이면)
     * 이 섹션 자체가 생기지 않는다([recordTranscriptSkipped] KDoc과 같은 관례).
     */
    private fun StringBuilder.appendRunAggregateSection() {
        val repeated = documentGroups().filter { it.size > 1 }
        if (repeated.isEmpty()) return
        appendLine("문서별 반복 집계 (같은 문서를 여러 번 돌린 결과 — 중앙값/최소/최대)")
        repeated.forEach { group ->
            appendLine(
                "  ${group.first().documentId} — 반복 ${group.size}회 · " +
                    "팽창비 ${medianMinMax(group.mapNotNull { it.expansion }, decimals = 2)} · " +
                    "밀도 ${medianMinMax(group.mapNotNull { it.styleIssueDensity }, decimals = 3)} · " +
                    "절단 호출 합계 ${group.sumOf { it.truncatedCalls }}",
            )
        }
    }

    private fun medianMinMax(
        values: List<Double>,
        decimals: Int,
    ): String =
        if (values.isEmpty()) {
            "-"
        } else {
            values.sorted().let { sorted ->
                listOf(quantile(sorted, MEDIAN), sorted.first(), sorted.last())
                    .joinToString("/") { String.format(Locale.ROOT, "%.${decimals}f", it) }
            }
        }

    /**
     * 절단을 **두 단위로** 나란히 낸다. 문서 단위는 「사용자에게 결과가 나가지 않은 건수」이고,
     * 호출 단위는 「상한에 닿은 빈도」다. 게이트 ⓪ 이 묻는 것은 뒤쪽이다.
     *
     * 문서 단위 분모·분자는 [documentSummaries] 를 쓴다 — 문서 하나가 회차 셋 중 하나만
     * 잘려도 그 문서는 「절단 문서」 한 건이지 세 건이 아니다([DocumentSummary.truncated]).
     * 호출 단위는 여전히 [truncatedCalls] 로 회차를 모두 더한 값이다 — 이쪽은 애초에 호출
     * 개념이라 회차 축약을 거치지 않는다.
     */
    private fun truncationLine(): String {
        val summaries = documentSummaries()
        val documents = summaries.count { it.truncated }
        val calls = truncatedCalls()
        return "  절단 — 문서 단위 $documents/${summaries.size} (${rate(documents, summaries.size)}, 변환 실패) · " +
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
     * 앞쪽([truncatedCalls])은 호출 단위, 뒤쪽([DocumentSummary.truncated] 문서 수)은 문서
     * 단위다 — [truncationLine] 이 나란히 내는 두 단위와 같은 짝이다.
     */
    private fun silentTruncationLine(): String {
        val silent = truncatedCalls() - documentSummaries().count { it.truncated }
        return if (silent > 0) {
            "  └ 차이 ${silent}회 = 보정 호출이 잘렸지만 원본 초안이 채택돼 성공으로 끝난 횟수 " +
                "(상한에 닿았으나 실패로 보이지 않는다)"
        } else {
            "  └ 차이 없음 — 상한에 닿은 호출이 모두 문서 실패로 나타났다(조용히 넘어간 절단 없음)"
        }
    }

    /**
     * 장문·단문 구간은 게이트 ⓪ B2/B3 판정을 그대로 읽는 자리다 — [outcomeLine] 과 같은
     * 이유로 「변환 성공」은 [DocumentSummary.allRunsConverted](엄격) 을 쓴다. 여기서도
     * 「변환 성공 1 · 절단 문서 1」처럼 합이 안 맞아 보이는 모순을 두면 B2/B3 를 읽는 자리에서
     * 바로 그 모순이 재현된다.
     *
     * 「스타일 통과」의 분모는 여전히 [DocumentSummary.hasConvertedRun](느슨, 회차 중 하나라도
     * 성공)이다 — 절단된 회차가 섞여도 성공한 회차의 스타일 판정은 실재하는 값이라 통과율을
     * 매길 근거가 있다([qualityLine] 과 같은 판단). 두 「변환 성공」이 서로 다른 분모라 줄에
     * 그대로 나란히 두면 헷갈리므로, 통과율 분모 옆에 **그 분모가 무엇인지** 적어 둔다
     * (`변환 회차 있는 문서`) — 숫자만 보고 같은 집합이라고 오해하지 않게.
     *
     * 팽창비·출력 토큰은 [DocumentSummary.medianExpansion]·[DocumentSummary.medianOutputTokens] —
     * 문서마다 회차 중앙값을 먼저 낸 값이다. 여기서 다시 [ratioTail]·[tokenTail] 로 **문서 간**
     * 중앙값/p90/최대를 낸다. 원본 회차 값을 그대로 모아 냈다면(runs=3) 한 문서의 세 회차가
     * 분포에 세 번 들어가 꼬리(p90·최대)가 실제보다 두꺼워 보인다.
     */
    private fun bucketLine(
        label: String,
        rows: List<DocumentSummary>,
    ): String {
        if (rows.isEmpty()) {
            return "$label — 표본 없음"
        }
        val scored = rows.filter { it.hasConvertedRun }
        val converted = rows.count { it.allRunsConverted }
        val truncated = rows.count { it.truncated }
        val passed = scored.count { it.stylePassedAllRuns }
        return "$label — 문서 ${rows.size} · 변환 성공 $converted · " +
            "절단 문서 $truncated (${rate(truncated, rows.size)}) / 호출 ${rows.sumOf { it.truncatedCallsTotal }} · " +
            "스타일 통과 $passed/${scored.size}(변환 회차 있는 문서) (${rate(passed, scored.size)}) · " +
            "팽창비 ${ratioTail(scored.mapNotNull { it.medianExpansion })} · " +
            "출력 토큰 ${tokenTail(rows.map { it.medianOutputTokens })}"
    }

    // ── 문서 단위 축약 ────────────────────────────────────────────────────────────

    /**
     * 문서 하나를 여러 번([EASYDOC_LANE_RUNS]) 돌렸을 때, **모든 헤드라인 통계가 읽는 문서
     * 단위 축약값**. [measurements] 는 문서×회차 행이라 그대로 세면 runs=3 에서 문서 7건이
     * 21건으로 보인다 — Codex 정지 시점 리뷰가 잡은 문제다. runs=1 이면 그룹 크기가 언제나
     * 1이라 아래 모든 축약이 그 한 행의 값과 같으므로, 오늘(runs 노브 이전) 렌더링과 완전히
     * 같다.
     *
     * ## 축약 규칙
     *
     * - [truncated] 는 회차 중 **하나라도** 절단으로 실패했으면 참이다 — 「일부만 잘려도 그
     *   문서는 절단 문서다」.
     * - [allRunsConverted]·[hasConvertedRun]·[otherFailure] 는 **쓰임이 다른 두 축약**이다.
     *     - [outcomeLine] 의 「변환 성공·절단으로 실패·그 밖의 변환 실패」 세 갈래는 배타적이고
     *       문서 수의 합과 같아야 한다(runs=1 이 그랬듯). 그래서 [truncated] 가 먼저 걸러내고,
     *       남은 문서 중 **모든** 회차가 변환에 성공했으면(그리고 그때만) [allRunsConverted]
     *       가 참이며 「변환 성공」이 된다. 나머지(절단도 아니고 전부 성공도 아닌, 즉 일부·전부
     *       가 그 밖의 이유로 실패한)는 [otherFailure] — 정확히 셋의 나머지다.
     *     - [qualityLine]·[bucketLine] 의 스타일 통과율 분모는 [hasConvertedRun](회차 중
     *       **하나라도** 성공)을 쓴다 — 절단된 회차가 섞여도 성공한 회차의 스타일 판정은
     *       실재하므로 통과율을 매길 근거가 있다. 그래서 [hasConvertedRun] 은 [truncated] 와
     *       배타적이지 않다(두 회차는 성공하고 한 회차는 잘린 문서가 흔한 예다) — 그 문서는
     *       [truncationLine] 에는 절단 문서로, 통과율 분모에는 성공 문서로 **둘 다** 잡힌다.
     *       [stylePassedAllRuns] 가(아래) 그 절단을 결국 미통과로 떨어뜨린다.
     * - [stylePassedAllRuns] 는 **문자 그대로 모든 회차**(절단된 회차 포함)가 통과해야 참이다.
     *   절단된 회차는 `stylePassed` 가 `null` 이라 `== true` 를 만족하지 못하므로, 회차 하나가
     *   잘리기만 해도 그 문서는 통과로 집계되지 않는다 — 관대한 정의(변환 성공 회차만 보고
     *   판단)는 절단이라는 불안정을 통과율 뒤에 숨긴다. **박한 쪽이 참이다.**
     * - [medianExpansion]·[medianDensity] 는 변환에 성공한 회차들의 값 중앙값이다(성공한
     *   회차가 없으면 `null`) — 절단된 회차가 섞인 문서라도 끝난 회차의 값은 실측값이므로 그
     *   값을 그대로 쓴다. [medianOutputTokens]·[medianElapsedMillis] 는 **모든** 회차의 값
     *   중앙값이다 — 출력 토큰·소요 시간은 절단된 회차도 실측값을 낸다.
     * - [truncatedCallsTotal] 은 이 문서의 모든 회차에 걸친 호출 단위 절단 합 — 절단
     *   발생률의 호출 쪽 분자는 여전히 호출 수 기준이라 문서 축약을 거치지 않는다.
     */
    private data class DocumentSummary(
        val documentId: String,
        val sourceChars: Int,
        val truncated: Boolean,
        val allRunsConverted: Boolean,
        val hasConvertedRun: Boolean,
        val otherFailure: Boolean,
        val stylePassedAllRuns: Boolean,
        val medianExpansion: Double?,
        val medianDensity: Double?,
        val medianOutputTokens: Int,
        val medianElapsedMillis: Long,
        val truncatedCallsTotal: Int,
    )

    /** [documentId] 별로 묶은, 소요 시간과 짝지은 반복 기록. 처음 나온 순서를 유지한다. */
    private fun runGroups(): List<List<Pair<LaneMeasurement, Long>>> =
        measurements
            .zip(durationsMillis)
            .groupBy { (measurement, _) -> measurement.documentId }
            .values
            .toList()

    /** [documentGroups] 는 [appendRunAggregateSection] 이 회차별 원본 값을 그대로 나열하는 데 쓴다. */
    private fun documentGroups(): List<List<LaneMeasurement>> = runGroups().map { rows -> rows.map { it.first } }

    /** [documentGroups] 와 같은 근원([runGroups])에서 문서 단위로 축약한 값. [DocumentSummary] KDoc. */
    private fun documentSummaries(): List<DocumentSummary> = runGroups().map(::summarize)

    private fun summarize(rows: List<Pair<LaneMeasurement, Long>>): DocumentSummary {
        val truncated = rows.any { (measurement, _) -> measurement.truncated }
        val allRunsConverted = !truncated && rows.all { (measurement, _) -> measurement.convertedChars != null }
        val hasConvertedRun = rows.any { (measurement, _) -> measurement.convertedChars != null }
        val convertedExpansions = rows.mapNotNull { (measurement, _) -> measurement.expansion }
        val convertedDensities = rows.mapNotNull { (measurement, _) -> measurement.styleIssueDensity }
        return DocumentSummary(
            documentId = rows.first().first.documentId,
            sourceChars = rows.first().first.sourceChars,
            truncated = truncated,
            allRunsConverted = allRunsConverted,
            hasConvertedRun = hasConvertedRun,
            // outcomeLine 세 갈래의 나머지다 — 절단도 아니고(!truncated) 전부 성공도 아닌
            // (!allRunsConverted) 문서. truncated 가 이미 배타적으로 걸러졌으므로 이 셋의
            // 합은 문서 수와 같다.
            otherFailure = !truncated && !allRunsConverted,
            stylePassedAllRuns = rows.all { (measurement, _) -> measurement.stylePassed == true },
            medianExpansion = medianOrNull(convertedExpansions),
            medianDensity = medianOrNull(convertedDensities),
            medianOutputTokens = quantile(rows.map { (measurement, _) -> measurement.outputTokens }.sorted(), MEDIAN),
            medianElapsedMillis = quantile(rows.map { (_, elapsedMillis) -> elapsedMillis }.sorted(), MEDIAN),
            truncatedCallsTotal = rows.sumOf { (measurement, _) -> measurement.truncatedCalls },
        )
    }

    private fun <T : Comparable<T>> medianOrNull(values: List<T>): T? =
        values.takeIf { it.isNotEmpty() }?.sorted()?.let { quantile(it, MEDIAN) }

    // ── 공용 ─────────────────────────────────────────────────────────────────────

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
