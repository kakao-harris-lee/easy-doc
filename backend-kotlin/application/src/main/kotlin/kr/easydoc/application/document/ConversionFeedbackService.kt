package kr.easydoc.application.document

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.document.ConversionStatus
import kr.easydoc.core.document.charCountOf
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.pilot.MinutesSpent
import kr.easydoc.core.pilot.PublishIntent
import kr.easydoc.core.pilot.QualityScore
import kr.easydoc.core.text.editDistanceOf
import kr.easydoc.core.text.stripControlChars
import java.time.Instant
import java.util.UUID

/**
 * 자유 의견의 길이 상한(**코드 포인트**) — 계약 `x-input-limits.max_feedback_comment_length`
 * 와 **같은 값이다.** 계약이 정본이고 여기는 그 값을 코드가 쓸 수 있게 옮겨 놓은 자리다
 * (`core/document/DocumentLimits.kt` 가 같은 규칙으로 계약 값을 옮겨 적는다).
 *
 * 길이는 **정규화 후**에 잰다 — 계약이 `maxLength` 대신 `x-service-constraint`
 * (`measured_on: normalized`)를 쓴 사유가 그것이다.
 */
const val MAX_FEEDBACK_COMMENT_LENGTH: Int = 500

/**
 * 검수 화면 하단 피드백 폼이 보낸 **원시 값** 넷 — 계약 `ConversionFeedbackRequest` 그대로다.
 *
 * 정규화도 범위 판정도 여기서 하지 않는다. [ConversionFeedbackService.save] 가 **안에서**
 * 한다 — `ConversionReviewService` 가 `ReviewedBody` 를 받아 안에서 정규화하는 것과 같은
 * 형태이고, 그래야 판정 **순서**를 서비스 테스트로 고정할 수 있다.
 */
data class FeedbackSubmission(
    val publishIntent: String?,
    val qualityScore: Int,
    val minutesSpent: Int,
    val comment: String?,
) {
    /**
     * 자유 의견은 사용자 콘텐츠다 — 길이만 남긴다. 나머지 셋은 척도값이지만 함께 찍으면
     * 「누가 몇 점을 주었나」가 로그에 남으므로 의향만 남긴다([StoredFeedback.toString] 과 같다).
     */
    override fun toString(): String = "FeedbackSubmission($publishIntent, 의견 ${comment?.length ?: 0}자)"
}

/**
 * 저장된 피드백 — 계약 `ConversionFeedbackResponse` 의 여섯 필드에 1:1 대응한다.
 *
 * **파생 지표(글자 수 둘·편집 거리)를 싣지 않는다.** 그것은 게이트 ① 판정용 집계 값이지
 * 사용자에게 보여 줄 값이 아니고, 검수본과의 차이를 화면에 올리면 「AI가 내 수정을
 * 채점한다」는 오해를 만든다. 파일럿이 재려는 것은 담당자의 **판단**이지 담당자의 점수가
 * 아니며, 채점받는다는 느낌은 그 판단 자체를 왜곡한다. 파생 지표의 집계는 DB 에서
 * 스크립트가 한다(`docs/pilot-runbook.md` 「집계」).
 */
data class ConversionFeedbackView(
    val conversionId: UUID,
    val publishIntent: PublishIntent,
    val qualityScore: QualityScore,
    val minutesSpent: MinutesSpent,
    val comment: PlainBody?,
    val submittedAt: Instant,
) {
    /** 로그 허용목록 그대로 — 식별자·의향과 **의견의 유무**뿐이다. */
    override fun toString(): String =
        "ConversionFeedbackView($conversionId, ${publishIntent.wireName}, 의견 ${if (comment == null) "없음" else "있음"})"
}

/**
 * 파일럿 피드백 저장 — 게이트 ①(master-plan §9, 절차는 `docs/pilot-runbook.md`
 * 「게이트 ① 판정」)의 **유일한 수기 입력**을 받는다.
 *
 * 수기 값 넷과 함께 수정률 지표 셋을 계산해 남긴다. 지표의 평문은 [ConversionQueryService]
 * 가 이미 여는 것을 **그대로 쓴다** — 복호화 경로를 새로 만들지 않는 것이 이 설계의 핵심이다.
 * 경로가 둘이 되면 결속 인자(`record`·`field`)를 맞추는 자리도 둘이 된다.
 *
 * 같은 변환에 다시 제출하면 덮어쓴다. 서비스는 매번 **같은 경로**를 지나고 멱등성은
 * [ConversionFeedbackRepository.upsert] 가 보장한다 — 「이미 냈는가」로 갈리는 갈래가 없다.
 */
class ConversionFeedbackService(
    private val feedback: ConversionFeedbackRepository,
    private val cipher: ContentCipher,
    private val query: ConversionQueryService,
    private val transaction: TransactionRunner,
) {
    /**
     * 저장하고 **저장된 값 그대로**를 돌려준다. 판정 순서는 값 검증 → 소유권(404) →
     * 상태(409) → 저장이다.
     *
     * **값 검증이 소유권보다 앞인 것이 요구사항이다.** 앞의 것들은 모든 식별자에 같은
     * 응답이라 자원의 존재를 누설하지 않는다 — 남의 식별자로 범위 밖 값을 보내도 404 가
     * 아니라 422 가 나가고, 그 차이로 자원의 존재를 물을 수 없다
     * (`ConversionReviewService.save` 의 같은 판단과 나란하다).
     */
    fun save(
        ownerId: UUID,
        conversionId: UUID,
        submitted: FeedbackSubmission,
    ): ConversionFeedbackView {
        val values = validate(submitted)

        // 404 는 이 조회가 던진다 — 없는 것과 남의 것을 구분하지 않는 술어가 여기 하나다.
        val result = query.read(ownerId, conversionId)
        if (result.status != ConversionStatus.DONE) throw ConflictException(CONVERSION_NOT_DONE_MESSAGE)

        val metrics = EditMetrics.of(draft = result.easyText, edited = result.editedText)
        val sealedComment =
            values.comment?.let { cipher.encrypt(it, conversionId, EncryptedField.CONVERSION_FEEDBACK_COMMENT) }

        val submittedAt =
            transaction.inTransaction {
                feedback.upsert(
                    ownerId = ownerId,
                    feedback =
                        StoredFeedback(
                            conversionId = conversionId,
                            publishIntent = values.publishIntent,
                            qualityScore = values.qualityScore,
                            minutesSpent = values.minutesSpent,
                            comment = sealedComment,
                            easyCharCount = metrics.easyCharCount,
                            editedCharCount = metrics.editedCharCount,
                            editDistance = metrics.editDistance,
                        ),
                )
            }

        return ConversionFeedbackView(
            conversionId = conversionId,
            publishIntent = values.publishIntent,
            qualityScore = values.qualityScore,
            minutesSpent = values.minutesSpent,
            comment = values.comment,
            submittedAt = submittedAt,
        )
    }

    /**
     * 원시 값 넷을 도메인 값으로 세운다. 범위의 정본은 `core/pilot/ConversionFeedback.kt` 이고
     * 여기는 그 생성자를 부르는 자리다 — 판정을 옮겨 적지 않는다.
     */
    private fun validate(submitted: FeedbackSubmission): ValidFeedback =
        ValidFeedback(
            publishIntent = PublishIntent.ofRequestValue(submitted.publishIntent),
            qualityScore = QualityScore(submitted.qualityScore),
            minutesSpent = MinutesSpent(submitted.minutesSpent),
            comment = normalizeComment(submitted.comment),
        )

    /**
     * 제어문자를 걷어낸 뒤 **그 결과로** 판정한다. 길이는 **코드 포인트**이고, 남는 것이
     * 공백뿐이면 `null` 로 접는다 — 「제어문자만 담긴 의견」이 빈 의견과 같은 취급을 받아야
     * 한다(검수본의 같은 판단과 나란하다. 그쪽은 필수 항목이라 422 이고 이쪽은 선택 항목이라
     * 「없음」이다).
     *
     * 저장 정의역은 [PlainBody] 가 끊는다 — 길이와 **다른 축**이고, 봉인해 저장하는 값이라
     * 검수본과 같은 조항이 걸린다(계약 `x-stored-text-domain`).
     */
    private fun normalizeComment(comment: String?): PlainBody? {
        val stripped = stripControlChars(comment ?: return null)
        if (charCountOf(stripped) > MAX_FEEDBACK_COMMENT_LENGTH) {
            throw InvalidInputException(FEEDBACK_COMMENT_TOO_LONG_MESSAGE)
        }
        return if (stripped.isBlank()) null else PlainBody(stripped)
    }
}

/** 판정을 통과한 수기 값 넷. 저장과 응답이 **같은 값**을 보게 묶어 둔다. */
private class ValidFeedback(
    val publishIntent: PublishIntent,
    val qualityScore: QualityScore,
    val minutesSpent: MinutesSpent,
    val comment: PlainBody?,
)

/**
 * 수정률 지표 셋 — **함께 만들어야 모순이 없다.**
 *
 * 생성자를 닫고 [of] 하나만 남긴 이유: 세 값은 서로 독립이 아니다. 편집 거리는 초안과
 * 검수본 **둘 다** 있어야 뜻이 있고, 검수본 글자 수는 검수본이 있어야 뜻이 있다. 셋을
 * 따로 계산해 넘기면 「검수본이 없는데 편집 거리는 0」 같은 행이 만들어지고, 집계는 그것을
 * 「하나도 고치지 않았다」로 읽는다 — 스키마가 `NULL` 을 허용한 사유가 정확히 그 구분이다
 * (`V2__conversion_feedback.sql` 「수정률 지표 셋」).
 */
private class EditMetrics private constructor(
    val easyCharCount: Int?,
    val editedCharCount: Int?,
    val editDistance: Int?,
) {
    companion object {
        fun of(
            draft: PlainBody?,
            edited: PlainBody?,
        ): EditMetrics =
            when {
                // 방어적 갈래다 — `done` 인 변환에는 초안이 있다. 그래도 셋을 함께 비우는
                // 것이 맞다: 초안이 분모이자 편집 거리의 한쪽 끝이라, 그것이 없으면 나머지
                // 둘은 「무엇에 견준 값인지」를 말하지 못한다.
                draft == null -> {
                    EditMetrics(null, null, null)
                }

                // 검수본이 없으면 「수정률 0%」가 아니라 「측정 대상 아님」이다 — 0 으로 채우지 않는다.
                edited == null -> {
                    EditMetrics(charCountOf(draft.value), null, null)
                }

                else -> {
                    EditMetrics(
                        easyCharCount = charCountOf(draft.value),
                        editedCharCount = charCountOf(edited.value),
                        editDistance = editDistanceOf(draft.value, edited.value),
                    )
                }
            }
    }
}
