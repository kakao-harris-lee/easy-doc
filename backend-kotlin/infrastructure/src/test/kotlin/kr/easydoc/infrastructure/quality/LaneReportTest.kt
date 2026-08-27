package kr.easydoc.infrastructure.quality

import kr.easydoc.application.conversion.ConversionFailureKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration

/** 집계가 「모델이 못한 것」과 「인프라가 흔들린 것」을 실제로 가르는지 본다. */
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
        assertThat(rendered).contains("품질 실패(사실 누락·judge 판정·모델 출력): 2건")
        assertThat(rendered).contains("인프라 실패(provider 오류): 1건")
        assertThat(rendered).contains("g-001: 변환 실패 PROVIDER_ERROR — 원인 HTTP 429, 재시도 2회")
    }

    @Test
    @DisplayName("요약에 무엇으로 쟀는지와 인프라 흔들림이 함께 남는다")
    fun `요약이 측정 조건과 흔들림을 남긴다`() {
        journal.beginDocument("g-001")
        journal.recordFault(LaneFault("HTTP 429", status = 429, transient = true), retried = true)
        report.recordDocument(Duration.ofSeconds(3))
        report.recordStyle(passed = true)

        val rendered = report.render()

        assertThat(rendered).contains("provider=anthropic settings=stub")
        assertThat(rendered).contains("재시도 1회/예산 ${journal.budget}")
        assertThat(rendered).contains("인프라 오류 분포 — HTTP 429 ×1")
        assertThat(rendered).contains("스타일 통과 1/1")
        assertThat(rendered).contains("3.0초")
    }

    @Test
    @DisplayName("실패가 없으면 보고할 것도 없다")
    fun `실패가 없으면 비어 있다`() {
        assertThat(report.failures()).isEmpty()
        assertThat(report.render()).contains("인프라 오류 분포 — 없음")
    }

    private fun fault(label: String): LaneFault = LaneFault(label, status = null, transient = true)
}
