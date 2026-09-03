package kr.easydoc.infrastructure.document

import kr.easydoc.core.text.EditDistanceBudget
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 파일럿 피드백 저장(`ConversionFeedbackService`) 설정. 바인딩 접두사는 `easydoc.feedback`.
 *
 * [editDistanceCellBudget] 은 `PUT /conversions/{id}/feedback` 의 동기 경로에서 도는
 * 편집 거리 계산(`core/text/EditDistance.kt` `editDistanceWithin`)의 **CPU 상한**이다 —
 * 요청 스레드에서 굴리는 O(n·m) Levenshtein 표의 최대 칸 수(`rows × columns`, 접두·접미를
 * 뗀 뒤 크기)를 막는다. 운영 중 조정될 수 있는 값이라 코드에 박지 않는다(CLAUDE.md
 * 「상수와 구성 관리」).
 *
 * 기본값 2억 셀의 근거: 종전 조건(검수본 4,000자 × 초안은 16,000토큰 출력 예산이 허용하던
 * 약 3만 자)의 최악 조합 약 1.2억 셀이 그대로 계산되던 것을 유지하면서, 단일 스레드에서
 * 대략 0.2~0.5초 안에 끝나는 크기다. 상한이 20,000자로 오른 지금도 접두·접미를 뗀 뒤의
 * 실제 수정 구간이 예산 이내면(예: 만 자 × 이만 자 이하) 계산된다 — 예산을 넘는 나머지는
 * 편집 거리가 `null`(측정 대상 아님)로 빠진다.
 */
@ConfigurationProperties(prefix = "easydoc.feedback")
data class FeedbackProperties(val editDistanceCellBudget: Long = DEFAULT_EDIT_DISTANCE_CELL_BUDGET) {
    fun editDistanceBudget(): EditDistanceBudget = EditDistanceBudget(editDistanceCellBudget)

    companion object {
        const val DEFAULT_EDIT_DISTANCE_CELL_BUDGET: Long = 200_000_000L
    }
}
