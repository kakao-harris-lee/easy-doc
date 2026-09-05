package kr.easydoc.infrastructure.document

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 재변환(`POST /conversions/{conversion_id}/units/{source_unit_index}/reconvert`) 설정.
 * 바인딩 접두사는 `easydoc.reconversion`(P0-4 S4, 계획 §0 게이트 1).
 *
 * [callBudget] 은 **문서 1건당** 재변환 LLM 호출 예산이다 — 요청 수가 아니라 실제 호출 수로
 * 예약·정산한다(`ReconvertUnitService`, `JdbcConversionRepository.reserveReconversionCalls`).
 * 기본값 20은 사용자가 정한 비용 정책값이다(계획 §0 게이트 1, 2026-09-05 확정) — 운영 중
 * 조정될 수 있는 값이라 코드 상수가 아니라 구성값이다(CLAUDE.md 「상수와 구성 관리」).
 */
@ConfigurationProperties(prefix = "easydoc.reconversion")
data class ReconversionProperties(val callBudget: Int = DEFAULT_CALL_BUDGET) {
    companion object {
        const val DEFAULT_CALL_BUDGET: Int = 20
    }
}
