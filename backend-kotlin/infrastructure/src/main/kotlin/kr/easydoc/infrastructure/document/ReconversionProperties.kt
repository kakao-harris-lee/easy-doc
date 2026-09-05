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
 *
 * [concurrency] 는 [callBudget] 과 축이 다르다 — [callBudget] 은 문서 1건당 영구 호출 상한이고,
 * 이쪽은 **이 프로세스가 지금 동시에 진행할 수 있는 재변환 LLM 호출 수**의 상한이다(제공자
 * 과부하를 막는 bulkhead, `ReconvertUnitService.reconversionGate`). 필드명이 `concurrency`인
 * 이유는 전체 경로 `easydoc.reconversion.concurrency` 가 relaxed binding 으로 하이픈 없이
 * 그대로 `EASYDOC_RECONVERSION_CONCURRENCY` 환경변수에 대응해야 하기 때문이다(리뷰 요청값).
 * 기본값 4도 운영 중 조정될 수 있는 값이라 구성값이다.
 */
@ConfigurationProperties(prefix = "easydoc.reconversion")
data class ReconversionProperties(
    val callBudget: Int = DEFAULT_CALL_BUDGET,
    val concurrency: Int = DEFAULT_CONCURRENCY,
) {
    companion object {
        const val DEFAULT_CALL_BUDGET: Int = 20
        const val DEFAULT_CONCURRENCY: Int = 4
    }
}
