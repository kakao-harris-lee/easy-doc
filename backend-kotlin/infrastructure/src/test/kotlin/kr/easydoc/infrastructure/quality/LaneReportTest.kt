package kr.easydoc.infrastructure.quality

import kr.easydoc.application.conversion.ConversionFailureKind
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
    private val report = LaneReport("provider=anthropic settings=stub", journal)

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
        // 단문 둘: 하나는 스타일 통과(팽창비 1.20), 하나는 실패(1.50).
        record(id = "g-001", sourceChars = 1_000, convertedChars = 1_200, outputTokens = 2_000, stylePassed = true)
        record(id = "g-002", sourceChars = 1_000, convertedChars = 1_500, outputTokens = 2_400, stylePassed = false)
        // 장문 둘: 하나는 통과(1.80), 하나는 절단.
        record(id = "g-003", sourceChars = 3_000, convertedChars = 5_400, outputTokens = 9_000, stylePassed = true)
        recordTruncated(id = "g-004", sourceChars = 3_500, outputTokens = 16_000)

        val rendered = report.render()

        assertThat(rendered).contains("문서 4건 · 변환 성공 3 · 절단 1 (25.0%) · 그 밖의 변환 실패 0")
        assertThat(rendered).contains("스타일 규칙 통과 2/3 (66.7%)")
        // 단문 구간: 절단 0건, 스타일 1/2, 팽창비 중앙 1.20 · p90 1.50 · 최대 1.50.
        assertThat(rendered).contains(
            "2000자 이하 — 문서 2 · 변환 성공 2 · 절단 0 (0.0%) · 스타일 통과 1/2 (50.0%) · " +
                "팽창비 1.20/1.50/1.50 · 출력 토큰 2000/2400/2400",
        )
        // 장문 구간: 절단률이 단문과 갈려 보여야 한다 — 이 갈림이 게이트 판정의 축이다.
        assertThat(rendered).contains(
            "2000자 초과 — 문서 2 · 변환 성공 1 · 절단 1 (50.0%) · 스타일 통과 1/1 (100.0%) · " +
                "팽창비 1.80/1.80/1.80 · 출력 토큰 9000/16000/16000",
        )
        assertThat(rendered).contains("전체 — 문서 4 · 변환 성공 3 · 절단 1 (25.0%)")
    }

    @Test
    @DisplayName("단일 호출 최대 출력 토큰을 상한과 나란히 낸다 — judge 호출은 섞지 않는다")
    fun `단일 호출 최대 출력 토큰을 낸다`() {
        journal.beginDocument("g-001")
        journal.recordCall(completion(outputTokens = 7_400))
        journal.beginJudge("g-001")
        journal.recordCall(completion(outputTokens = 3))

        assertThat(journal.largestConversionCallOutputTokens).isEqualTo(7_400)
        assertThat(report.render()).contains("단일 호출 최대 출력 토큰 7400 / 상한 16000 (DEFAULT_MAX_TOKENS)")
    }

    @Test
    @DisplayName("표본이 없는 구간은 0 이 아니라 「표본 없음」으로 낸다")
    fun `빈 구간을 0 으로 채우지 않는다`() {
        record(id = "g-001", sourceChars = 500, convertedChars = 600, outputTokens = 900, stylePassed = true)

        val rendered = report.render()

        assertThat(rendered).contains("2000자 초과 — 표본 없음")
        assertThat(rendered).contains("2000자 이하 — 문서 1")
    }

    @Test
    @DisplayName("요약에 무엇으로 쟀는지와 인프라 흔들림이 함께 남는다")
    fun `요약이 측정 조건과 흔들림을 남긴다`() {
        journal.beginDocument("g-001")
        journal.recordFault(LaneFault("HTTP 429", status = 429, transient = true), retried = true)
        record(id = "g-001", sourceChars = 800, convertedChars = 900, outputTokens = 1_100, stylePassed = true)

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

    private fun record(
        id: String,
        sourceChars: Int,
        convertedChars: Int,
        outputTokens: Int,
        stylePassed: Boolean,
    ) {
        report.recordDocument(
            LaneMeasurement(
                documentId = id,
                sourceChars = sourceChars,
                convertedChars = convertedChars,
                outputTokens = outputTokens,
                truncated = false,
                stylePassed = stylePassed,
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
                stylePassed = null,
            ),
            ELAPSED,
        )
    }

    private fun completion(outputTokens: Int): LlmCompletion =
        LlmCompletion(
            text = "결과",
            provider = "anthropic",
            model = "claude-sonnet-5",
            inputTokens = 10,
            outputTokens = outputTokens,
            finishReason = LlmFinishReason.END_TURN,
        )

    private fun fault(label: String): LaneFault = LaneFault(label, status = null, transient = true)

    private companion object {
        val ELAPSED: Duration = Duration.ofSeconds(3)
    }
}
