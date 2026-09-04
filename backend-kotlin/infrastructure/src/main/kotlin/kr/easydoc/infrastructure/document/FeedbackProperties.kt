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
 *
 * [commentRetentionDays] 는 `conversion_feedback` 자유 의견(`comment_encrypted`·
 * `encryption_scheme`·`key_version`)의 보존 일수다 — worker 의 보존 파기 배치
 * (`PurgeFeedbackComments`, `RetentionPurgeScheduler`)가 이 값보다 오래된 의견 세 열을
 * `NULL`로 만든다. **척도 숫자(배포 의향·품질 만족도·소요 시간·수정률 지표)는 영구히
 * 남는다** — `conversion_feedback` 은 FK 가 없어 문서 30일 파기 사슬 밖이고
 * (`V2__conversion_feedback.sql`), 지우는 이유는 그 표에 남는 개인정보(자유 의견에 섞여
 * 들어오는 문서 본문 조각)이지 판정 근거로 쓰는 척도가 아니다
 * (`docs/kotlin-redevelopment-backlog.md` §1.1 「`conversion_feedback`의 삭제 경로」 판단 ⑵).
 * 기본값 30일은 master-plan §3.2 의 "기본 보존 30일 후 자동 삭제"를 그대로 따른다.
 *
 * 나이 판정은 `updated_at` 이 아니라 `submitted_at` 을 쓴다 — 계약이 `submitted_at` 을
 * 「의견을 마지막으로 **저장한**(재제출한) 시각」으로 정의하는 반면, `updated_at` 은 키
 * 회전(`EnvelopeRotation.rotateFeedback`)이 내용은 그대로 두고 봉투만 다시 봉할 때도
 * 함께 밀린다(`JdbcConversionFeedbackRepository.REWRITE_COMMENT_SQL`). 회전 주기가
 * 보존 일수보다 짧으면 `updated_at` 기준은 「정기 회전이 도는 한 절대 지워지지 않는 의견」을
 * 만든다 — 회전은 내용을 다시 **쓴** 사건이 아니라 봉투를 바꾼 사건이므로 삭제 시계가
 * 거기에 반응하면 안 된다.
 */
@ConfigurationProperties(prefix = "easydoc.feedback")
data class FeedbackProperties(
    val editDistanceCellBudget: Long = DEFAULT_EDIT_DISTANCE_CELL_BUDGET,
    val commentRetentionDays: Int = DEFAULT_COMMENT_RETENTION_DAYS,
) {
    fun editDistanceBudget(): EditDistanceBudget = EditDistanceBudget(editDistanceCellBudget)

    companion object {
        const val DEFAULT_EDIT_DISTANCE_CELL_BUDGET: Long = 200_000_000L
        const val DEFAULT_COMMENT_RETENTION_DAYS: Int = 30
    }
}
