package kr.easydoc.infrastructure.quality

import kr.easydoc.application.conversion.ConversionFailureKind
import kr.easydoc.core.easyread.StyleRuleKind
import kr.easydoc.core.llm.LlmCompletion
import kr.easydoc.core.llm.LlmFinishReason
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * 집계가 ⑴ 「모델이 못한 것」과 「인프라가 흔들린 것」을 가르고 ⑵ 게이트 ⓪ 이 요구한 셋
 * (출력 팽창비·스타일 규칙 통과율·절단 발생률)을 **길이 구간별로** 내는지 본다.
 */
class LaneReportTest {
    private val journal = LaneJournal()

    // 64000 은 2026-08-31 1차 측정이 실제로 쓴 상한이다 — DEFAULT_MAX_TOKENS(16000)와 다른 값을
    // 골라야 「상한 줄이 주입값을 찍는다」를 실제로 시험한다(우연히 같은 값이면 통과해도 증명이 안 된다).
    private val report = LaneReport("provider=anthropic settings=stub", journal, maxOutputTokens = 64_000)

    @Test
    @DisplayName("provider 오류는 인프라로, 절단·빈 결과는 모델 품질로 센다")
    fun `실패를 두 갈래로 센다`() {
        report.recordConversionFailure("g-001", ConversionFailureKind.PROVIDER_ERROR, fault("HTTP 429"), retries = 2)
        report.recordConversionFailure("g-002", ConversionFailureKind.TRUNCATED, fault = null, retries = 0)
        report.recordQualityFailure("g-003", "사실 누락 2")

        val rendered = report.render()

        assertThat(report.failures()).hasSize(3)
        assertThat(rendered).contains("품질 실패(절단·사실 누락·judge 판정): 2건")
        assertThat(rendered).contains("인프라 실패(provider 오류): 1건")
        assertThat(rendered).contains("g-001: 변환 실패 PROVIDER_ERROR — 원인 HTTP 429, 재시도 2회")
    }

    @Test
    @DisplayName("팽창비·스타일 통과율·절단률을 2,000자 경계로 갈라 낸다")
    fun `게이트 측정치를 길이 구간별로 낸다`() {
        // 호출 6회(문서 4건 중 둘이 보정까지 갔다) — 호출 단위 절단률의 분모다.
        repeat(6) { journal.recordCall(completion(outputTokens = 2_000)) }
        // 단문 둘: 하나는 스타일 통과(팽창비 1.20), 하나는 실패(1.50).
        record(id = "g-001", chars = CharCounts(1_000, 1_200), outputTokens = 2_000, style = StyleSample(true))
        record(id = "g-002", chars = CharCounts(1_000, 1_500), outputTokens = 2_400, style = StyleSample(false))
        // 장문 둘: 하나는 통과(1.80)했지만 **보정 호출이 잘렸고**, 하나는 변환 자체가 절단됐다.
        recordRepairTruncated(
            id = "g-003",
            chars = CharCounts(3_000, 5_400),
            outputTokens = 9_000,
            truncatedCalls = 1,
        )
        recordTruncated(id = "g-004", sourceChars = 3_500, outputTokens = 16_000)

        val rendered = report.render()

        assertThat(rendered).contains("문서 4건 · 변환 성공 3 · 절단으로 실패 1 (25.0%) · 그 밖의 변환 실패 0")
        assertThat(rendered).contains("스타일 규칙 통과 2/3 (66.7%)")
        // 단문 구간: 절단 0건, 스타일 1/2, 팽창비 중앙 1.20 · p90 1.50 · 최대 1.50.
        assertThat(rendered).contains(
            "2000자 이하 — 문서 2 · 변환 성공 2 · 절단 문서 0 (0.0%) / 호출 0 · 스타일 통과 1/2 (50.0%) · " +
                "팽창비 1.20/1.50/1.50 · 출력 토큰 2000/2400/2400",
        )
        // 장문 구간: 절단률이 단문과 갈려 보여야 한다 — 이 갈림이 게이트 판정의 축이다.
        // 장문 구간이 절단을 문서 1건·호출 2회로 갈라 낸다 — 조용히 넘어간 보정 절단이 여기 보인다.
        assertThat(rendered).contains(
            "2000자 초과 — 문서 2 · 변환 성공 1 · 절단 문서 1 (50.0%) / 호출 2 · 스타일 통과 1/1 (100.0%) · " +
                "팽창비 1.80/1.80/1.80 · 출력 토큰 9000/16000/16000",
        )
        assertThat(rendered).contains("전체 — 문서 4 · 변환 성공 3 · 절단 문서 1 (25.0%) / 호출 2")
        // 합계 줄과 구간 줄이 같은 출처(문서별 기록)에서 나온다 — 둘이 어긋나면 어느 쪽도 못 믿는다.
        assertThat(rendered).contains("절단 — 문서 단위 1/4 (25.0%, 변환 실패) · 호출 단위 2/6 (33.3%, 변환+보정)")
        assertThat(rendered).contains("차이 1회 = 보정 호출이 잘렸지만 원본 초안이 채택돼 성공으로 끝난 횟수")
    }

    @Test
    @DisplayName("보정 절단은 호출 단위에만 잡히고 문서 단위에는 잡히지 않는다 — 그 차이를 보고한다")
    fun `조용히 넘어간 절단을 차이로 낸다`() {
        // 변환 호출 1 + 보정 호출 1, 그중 보정만 절단. 유스케이스는 원본 초안을 채택해 성공으로 끝낸다.
        journal.beginDocument("g-001")
        journal.recordCall(completion(outputTokens = 4_000))
        journal.recordCall(completion(outputTokens = 16_000, truncated = true))
        recordRepairTruncated(
            id = "g-001",
            chars = CharCounts(3_000, 3_600),
            outputTokens = 4_000,
            // 저널이 센 값을 그대로 싣는다 — 이 배선이 끊기면 구간 집계가 조용히 0 이 된다.
            truncatedCalls = journal.truncatedCallsFor("g-001"),
        )

        val rendered = report.render()

        assertThat(journal.truncatedConversionCalls).isEqualTo(1)
        assertThat(rendered).contains("절단 — 문서 단위 0/1 (0.0%, 변환 실패) · 호출 단위 1/2 (50.0%, 변환+보정)")
        assertThat(rendered).contains("차이 1회 = 보정 호출이 잘렸지만 원본 초안이 채택돼 성공으로 끝난 횟수")
    }

    @Test
    @DisplayName("조용히 넘어간 절단이 없으면 두 값이 같다고 명시한다")
    fun `차이가 없으면 없다고 적는다`() {
        journal.beginDocument("g-001")
        journal.recordCall(completion(outputTokens = 4_000))
        record(id = "g-001", chars = CharCounts(900, 1_000), outputTokens = 4_000, style = StyleSample(passed = true))

        assertThat(report.render()).contains("차이 없음 — 상한에 닿은 호출이 모두 문서 실패로 나타났다")
    }

    @Test
    @DisplayName("judge 응답이 잘리면 판정을 믿을 수 없다고 경고한다 — 게이트 숫자와 섞지 않는다")
    fun `judge 절단은 경고로만 낸다`() {
        journal.beginDocument("g-001")
        journal.recordCall(completion(outputTokens = 4_000))
        journal.beginJudge("g-001")
        journal.recordCall(completion(outputTokens = 16_000, truncated = true))

        val rendered = report.render()

        assertThat(journal.truncatedConversionCalls).isZero()
        assertThat(journal.truncatedJudgeCalls).isEqualTo(1)
        assertThat(rendered).contains("⚠ judge 호출 절단 1회")
        assertThat(rendered).contains("호출 단위 0/1")
    }

    @Test
    @DisplayName("단일 호출 최대 출력 토큰을 상한과 나란히 낸다 — judge 호출은 섞지 않는다")
    fun `단일 호출 최대 출력 토큰을 낸다`() {
        journal.beginDocument("g-001")
        journal.recordCall(completion(outputTokens = 7_400))
        journal.beginJudge("g-001")
        journal.recordCall(completion(outputTokens = 3))

        assertThat(journal.largestConversionCallOutputTokens).isEqualTo(7_400)
        assertThat(report.render())
            .contains("단일 호출 최대 출력 토큰 7400 / 상한 64000 (easydoc.llm.max-output-tokens, 변환+보정 호출 기준)")
    }

    @Test
    @DisplayName("문서별 측정값을 원문 글자 수 내림차순으로 낸다 — 실패 문서는 -로 낸다")
    fun `문서별 값을 원문 글자 수 내림차순으로 낸다`() {
        record(
            id = "g-001",
            chars = CharCounts(1_000, 1_200),
            outputTokens = 2_000,
            style = StyleSample(passed = true, sentenceCount = 20, issueCounts = emptyMap()),
        )
        record(
            id = "g-002",
            chars = CharCounts(1_000, 1_500),
            outputTokens = 2_400,
            style =
                StyleSample(
                    passed = false,
                    sentenceCount = 15,
                    issueCounts = mapOf(StyleRuleKind.LENGTH to 2, StyleRuleKind.COMMA to 1),
                ),
        )
        // 성공했지만 보정 호출이 잘린 문서 — expansion·stylePassed 는 값이 있어야 한다.
        recordRepairTruncated(
            id = "g-003",
            chars = CharCounts(3_000, 5_400),
            outputTokens = 9_000,
            truncatedCalls = 1,
            style =
                StyleSample(
                    passed = true,
                    sentenceCount = 40,
                    issueCounts = mapOf(StyleRuleKind.DIFFICULT_WORD to 4),
                ),
        )
        // 변환 자체가 실패한 문서 — convertedChars/expansion/stylePassed 가 전부 null 이라 "-" 로 낸다.
        recordTruncated(id = "g-004", sourceChars = 3_500, outputTokens = 16_000)

        val documentLines = report.render().lines().filter { it.contains(" — 원문 ") }

        assertThat(documentLines).containsExactly(
            "  g-004 — 원문 3500 · 변환 - · 팽창비 - · 출력 토큰 16000 · 절단 호출 1 · 스타일 - · 문장 0 · 위반 -",
            "  g-003 — 원문 3000 · 변환 5400 · 팽창비 1.80 · 출력 토큰 9000 · 절단 호출 1 · 스타일 통과 · 문장 40 · " +
                "위반 4건(밀도 0.100 · LENGTH=0 COMMA=0 DOUBLE_PASSIVE=0 DIFFICULT_WORD=4 GLOSS_COLLISION=0)",
            "  g-001 — 원문 1000 · 변환 1200 · 팽창비 1.20 · 출력 토큰 2000 · 절단 호출 0 · 스타일 통과 · 문장 20 · " +
                "위반 0건(밀도 0.000 · LENGTH=0 COMMA=0 DOUBLE_PASSIVE=0 DIFFICULT_WORD=0 GLOSS_COLLISION=0)",
            "  g-002 — 원문 1000 · 변환 1500 · 팽창비 1.50 · 출력 토큰 2400 · 절단 호출 0 · 스타일 미통과 · 문장 15 · " +
                "위반 3건(밀도 0.200 · LENGTH=2 COMMA=1 DOUBLE_PASSIVE=0 DIFFICULT_WORD=0 GLOSS_COLLISION=0)",
        )
    }

    @Test
    @DisplayName("위반 밀도 = 위반 수/문장 수 — 위반이 없으면 0.000, 변환 실패나 문장 0건이면 잴 수 없다")
    fun `위반 밀도를 계산한다`() {
        val noIssues =
            LaneMeasurement(
                documentId = "g-001",
                sourceChars = 100,
                convertedChars = 120,
                outputTokens = 50,
                truncated = false,
                truncatedCalls = 0,
                stylePassed = true,
                sentenceCount = 10,
                styleIssueCounts = emptyMap(),
            )
        assertThat(noIssues.styleIssueCount).isEqualTo(0)
        assertThat(noIssues.styleIssueDensity).isEqualTo(0.0)

        val someIssues =
            LaneMeasurement(
                documentId = "g-002",
                sourceChars = 100,
                convertedChars = 120,
                outputTokens = 50,
                truncated = false,
                truncatedCalls = 0,
                stylePassed = false,
                sentenceCount = 8,
                styleIssueCounts = mapOf(StyleRuleKind.LENGTH to 3, StyleRuleKind.COMMA to 1),
            )
        assertThat(someIssues.styleIssueCount).isEqualTo(4)
        assertThat(someIssues.styleIssueDensity).isEqualTo(0.5)

        val failed =
            LaneMeasurement(
                documentId = "g-003",
                sourceChars = 100,
                convertedChars = null,
                outputTokens = 50,
                truncated = true,
                truncatedCalls = 1,
                stylePassed = null,
                sentenceCount = 0,
                styleIssueCounts = emptyMap(),
            )
        assertThat(failed.styleIssueDensity).isNull()

        val noSentences =
            LaneMeasurement(
                documentId = "g-004",
                sourceChars = 10,
                convertedChars = 0,
                outputTokens = 5,
                truncated = false,
                truncatedCalls = 0,
                stylePassed = true,
                sentenceCount = 0,
                styleIssueCounts = emptyMap(),
            )
        assertThat(noSentences.styleIssueDensity).isNull()
    }

    @Test
    @DisplayName("문서별 줄에 규칙별 위반 수를 낸다 — DIFFICULT_WORD 과다 발화를 다른 규칙과 섞지 않고 읽을 수 있다")
    fun `규칙별 위반 수를 낸다`() {
        record(
            id = "g-001",
            chars = CharCounts(1_000, 1_200),
            outputTokens = 2_000,
            style =
                StyleSample(
                    passed = false,
                    sentenceCount = 10,
                    issueCounts = mapOf(StyleRuleKind.DIFFICULT_WORD to 5, StyleRuleKind.LENGTH to 1),
                ),
        )

        assertThat(report.render()).contains("LENGTH=1 COMMA=0 DOUBLE_PASSIVE=0 DIFFICULT_WORD=5 GLOSS_COLLISION=0")
    }

    @Test
    @DisplayName("게이트 ⓪ 요약에 문서 간 위반 밀도 중앙값을 낸다")
    fun `밀도 중앙값을 요약에 낸다`() {
        record(
            id = "g-001",
            chars = CharCounts(1_000, 1_200),
            outputTokens = 2_000,
            style = StyleSample(passed = true, sentenceCount = 10, issueCounts = emptyMap()),
        )
        record(
            id = "g-002",
            chars = CharCounts(1_000, 1_200),
            outputTokens = 2_000,
            style = StyleSample(passed = false, sentenceCount = 10, issueCounts = mapOf(StyleRuleKind.LENGTH to 2)),
        )
        record(
            id = "g-003",
            chars = CharCounts(1_000, 1_200),
            outputTokens = 2_000,
            style = StyleSample(passed = false, sentenceCount = 10, issueCounts = mapOf(StyleRuleKind.LENGTH to 4)),
        )

        // 밀도 0.000 / 0.200 / 0.400 의 중앙값(nearest-rank, 3건) = 0.200.
        assertThat(report.render()).contains("스타일 위반 밀도 중앙값 0.200 (위반 수/문장 수, 표본 3건)")
    }

    @Test
    @DisplayName("문서를 한 번만 돌리면 반복 집계 섹션이 아예 없다 — runs=1 과 같다")
    fun `한 번만 돌리면 반복 집계가 없다`() {
        record(id = "g-001", chars = CharCounts(1_000, 1_200), outputTokens = 2_000, style = StyleSample(passed = true))

        assertThat(report.render()).doesNotContain("문서별 반복 집계")
    }

    @Test
    @DisplayName("같은 문서를 여러 번 돌리면 팽창비·밀도의 중앙값/최소/최대와 절단 호출 합계를 문서별로 낸다")
    fun `반복 실행을 문서별로 집계한다`() {
        // 세 번 다 같은 문서 g-001 — 팽창비 1.00/1.20/1.40, 밀도 0.100/0.200/0.300, 절단 호출 0/1/0.
        report.recordDocument(
            LaneMeasurement(
                documentId = "g-001",
                sourceChars = 1_000,
                convertedChars = 1_000,
                outputTokens = 500,
                truncated = false,
                truncatedCalls = 0,
                stylePassed = true,
                sentenceCount = 10,
                styleIssueCounts = mapOf(StyleRuleKind.LENGTH to 1),
            ),
            ELAPSED,
        )
        report.recordDocument(
            LaneMeasurement(
                documentId = "g-001",
                sourceChars = 1_000,
                convertedChars = 1_200,
                outputTokens = 500,
                truncated = false,
                truncatedCalls = 1,
                stylePassed = false,
                sentenceCount = 10,
                styleIssueCounts = mapOf(StyleRuleKind.LENGTH to 2),
            ),
            ELAPSED,
        )
        report.recordDocument(
            LaneMeasurement(
                documentId = "g-001",
                sourceChars = 1_000,
                convertedChars = 1_400,
                outputTokens = 500,
                truncated = false,
                truncatedCalls = 0,
                stylePassed = false,
                sentenceCount = 10,
                styleIssueCounts = mapOf(StyleRuleKind.LENGTH to 3),
            ),
            ELAPSED,
        )

        val rendered = report.render()

        assertThat(rendered).contains("문서별 반복 집계 (같은 문서를 여러 번 돌린 결과 — 중앙값/최소/최대)")
        assertThat(rendered).contains(
            "g-001 — 반복 3회 · 팽창비 1.20/1.00/1.40 · 밀도 0.200/0.100/0.300 · 절단 호출 합계 1",
        )
    }

    @Test
    @DisplayName("표본이 없는 구간은 0 이 아니라 「표본 없음」으로 낸다")
    fun `빈 구간을 0 으로 채우지 않는다`() {
        record(id = "g-001", chars = CharCounts(500, 600), outputTokens = 900, style = StyleSample(passed = true))

        val rendered = report.render()

        assertThat(rendered).contains("2000자 초과 — 표본 없음")
        assertThat(rendered).contains("2000자 이하 — 문서 1")
    }

    @Test
    @DisplayName("요약에 무엇으로 쟀는지와 인프라 흔들림이 함께 남는다")
    fun `요약이 측정 조건과 흔들림을 남긴다`() {
        journal.beginDocument("g-001")
        journal.recordFault(LaneFault("HTTP 429", status = 429, transient = true), retried = true)
        record(id = "g-001", chars = CharCounts(800, 900), outputTokens = 1_100, style = StyleSample(passed = true))

        val rendered = report.render()

        assertThat(rendered).contains("provider=anthropic settings=stub")
        assertThat(rendered).contains("재시도 1회/예산 ${journal.budget}")
        assertThat(rendered).contains("인프라 오류 분포 — HTTP 429 ×1")
        assertThat(rendered).contains("3.0초")
    }

    @Test
    @DisplayName("실패가 없으면 보고할 것도 없다")
    fun `실패가 없으면 비어 있다`() {
        assertThat(report.failures()).isEmpty()
        assertThat(report.render()).contains("인프라 오류 분포 — 없음")
    }

    @Test
    @DisplayName("변환문 보존이 건너뛴 문서를 id 로만 남긴다 — 본문은 실리지 않는다")
    fun `변환문 보존 건너뜀을 id 로만 남긴다`() {
        report.recordTranscriptSkipped("g-001")
        report.recordTranscriptSkipped("g-002")

        val rendered = report.render()

        assertThat(rendered).contains("변환문 보존 — 건너뜀 2건(변환 실패, 남길 본문 없음): g-001, g-002")
    }

    @Test
    @DisplayName("건너뛴 문서가 없으면 그 줄이 아예 없다 — 노브를 켜지 않은 실행과 같게 보인다")
    fun `건너뜀이 없으면 줄이 없다`() {
        assertThat(report.render()).doesNotContain("변환문 보존")
    }

    /** [record]·[recordRepairTruncated] 가 공유하는 원문·변환 글자 수 묶음(detekt `LongParameterList` 회피). */
    private data class CharCounts(
        val source: Int,
        val converted: Int,
    )

    /**
     * [record]·[recordRepairTruncated] 가 공유하는 스타일 측정값 묶음. [CharCounts] 와 같은
     * 이유로 하나의 파라미터로 묶는다 — [LaneMeasurement] 자체는 data class 라 detekt
     * `LongParameterList` 에서 빠지지만([config/detekt/detekt.yml] 이 손대지 않은 기본값
     * `ignoreDataClasses`), 이 파일의 조립 함수는 아니다.
     */
    private data class StyleSample(
        val passed: Boolean,
        val sentenceCount: Int = DEFAULT_SENTENCE_COUNT,
        val issueCounts: Map<StyleRuleKind, Int> = emptyMap(),
    )

    /** 변환에 성공한 문서. 절단된 호출은 없다. */
    private fun record(
        id: String,
        chars: CharCounts,
        outputTokens: Int,
        style: StyleSample,
    ) {
        report.recordDocument(
            LaneMeasurement(
                documentId = id,
                sourceChars = chars.source,
                convertedChars = chars.converted,
                outputTokens = outputTokens,
                truncated = false,
                truncatedCalls = 0,
                stylePassed = style.passed,
                sentenceCount = style.sentenceCount,
                styleIssueCounts = style.issueCounts,
            ),
            ELAPSED,
        )
    }

    /**
     * **변환은 성공했지만 보정 호출이 잘린** 문서. 이 레인이 새로 잡으려는 갈래가 이것이다 —
     * 문서 단위로는 성공이라 [LaneMeasurement.truncated] 는 거짓이고, 절단은 호출 수에만 남는다.
     */
    private fun recordRepairTruncated(
        id: String,
        chars: CharCounts,
        outputTokens: Int,
        truncatedCalls: Int,
        style: StyleSample = StyleSample(passed = true),
    ) {
        report.recordDocument(
            LaneMeasurement(
                documentId = id,
                sourceChars = chars.source,
                convertedChars = chars.converted,
                outputTokens = outputTokens,
                truncated = false,
                truncatedCalls = truncatedCalls,
                stylePassed = style.passed,
                sentenceCount = style.sentenceCount,
                styleIssueCounts = style.issueCounts,
            ),
            ELAPSED,
        )
    }

    private fun recordTruncated(
        id: String,
        sourceChars: Int,
        outputTokens: Int,
    ) {
        report.recordDocument(
            LaneMeasurement(
                documentId = id,
                sourceChars = sourceChars,
                convertedChars = null,
                outputTokens = outputTokens,
                truncated = true,
                truncatedCalls = 1,
                stylePassed = null,
                sentenceCount = 0,
                styleIssueCounts = emptyMap(),
            ),
            ELAPSED,
        )
    }

    private fun completion(
        outputTokens: Int,
        truncated: Boolean = false,
    ): LlmCompletion =
        LlmCompletion(
            text = "결과",
            provider = "anthropic",
            model = "claude-sonnet-5",
            inputTokens = 10,
            outputTokens = outputTokens,
            finishReason = if (truncated) LlmFinishReason.MAX_TOKENS else LlmFinishReason.END_TURN,
        )

    private fun fault(label: String): LaneFault = LaneFault(label, status = null, transient = true)

    private companion object {
        val ELAPSED: Duration = Duration.ofSeconds(3)

        /** 밀도를 다루지 않는 테스트가 굳이 문장 수를 정하지 않아도 되게 두는 기본값. */
        const val DEFAULT_SENTENCE_COUNT: Int = 10
    }
}
