package kr.easydoc.api.document

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import kr.easydoc.application.document.ConversionFeedbackView
import kr.easydoc.application.document.FeedbackSubmission
import kr.easydoc.core.privacy.CONTENT_MASK

/**
 * `PUT /conversions/{conversion_id}/feedback` 요청 본문. 계약 `ConversionFeedbackRequest`.
 *
 * **제약을 애너테이션으로 걸지 않는다** — 계약이 값 범위와 자유 의견 길이를 「서비스 층 ·
 * 문자열 `detail`」로 정했는데 Bean Validation 은 배열을 낸다([ConversionReviewRequest] 와
 * 같은 사유다).
 *
 * **`publish_intent` 를 enum 이 아니라 `String` 으로 받는다.** `PublishIntent` 로 바로
 * 바인딩하면 목록 밖 값이 Jackson 층에서 걸려 422 **배열** detail 로 나가는데, 계약의 422
 * 갈래는 「필수 필드 누락만 배열, 값이 틀린 것은 문자열」이다. 문자열로 받아
 * `PublishIntent.ofRequestValue` 에 넘기면 그 판정이 도메인 한 자리에 남고 응답 모양도
 * 계약과 같아진다.
 *
 * 반대로 세 필수 필드의 **타입은 널이 아니다** — 그래야 필드가 없을 때 Jackson 이 끊어
 * 계약이 요구하는 배열 detail 이 나간다. 널로 받아 서비스에서 판정하면 「누락」이 「값이
 * 틀림」과 같은 문자열 detail 로 뭉개진다.
 */
data class ConversionFeedbackRequest
    @JsonCreator
    constructor(
        @param:JsonProperty("publish_intent") val publishIntent: String,
        @param:JsonProperty("quality_score") val qualityScore: Int,
        @param:JsonProperty("minutes_spent") val minutesSpent: Int,
        /**
         * 자유 의견. **선택**이라 생략과 `null` 을 **둘 다** 받는다(계약 `comment` 설명).
         * 요청 바인딩의 기본 null 처분이 `Nulls.FAIL` 이므로([JsonRequestStrictnessConfig])
         * 그 자리를 여기서 되돌린다 — `DocumentTextRequest.title` 과 같은 형태다.
         */
        @param:JsonProperty("comment")
        @param:JsonSetter(nulls = Nulls.SET)
        val comment: String?,
    ) {
        /**
         * **자유 의견을 찍지 않는다.** 검수자가 문제를 설명하려고 문서 본문 조각을 그대로
         * 옮겨 적는 자리라 사용자 콘텐츠다(스키마가 이 열만 봉인하는 사유와 같다). 척도값
         * 셋도 함께 찍으면 「누가 몇 점을 주었나」가 로그에 남으므로 의향만 남긴다.
         */
        override fun toString(): String =
            "ConversionFeedbackRequest(publishIntent=$publishIntent, comment=$CONTENT_MASK ${comment?.length ?: 0}자)"

        /** 원시 값 넷을 유스케이스의 입력으로 옮긴다. **판정하지 않는다** — 그것은 서비스다. */
        fun toSubmission(): FeedbackSubmission =
            FeedbackSubmission(
                publishIntent = publishIntent,
                qualityScore = qualityScore,
                minutesSpent = minutesSpent,
                comment = comment,
            )
    }

/**
 * 저장된 피드백. 계약 `ConversionFeedbackResponse` — **여섯 필드가 전부다.**
 *
 * 생성자와 `copy()` 가 `private` 인 사유는 [ConversionResponse] 와 같다: 바이트를 만드는
 * 자리를 [of] 하나로 닫는다.
 */
@ConsistentCopyVisibility
data class ConversionFeedbackResponse private constructor(
    @get:JsonProperty("conversion_id") val conversionId: String,
    @get:JsonProperty("publish_intent") val publishIntent: String,
    @get:JsonProperty("quality_score") val qualityScore: Int,
    @get:JsonProperty("minutes_spent") val minutesSpent: Int,
    @get:JsonProperty("comment") val comment: String?,
    @get:JsonProperty("submitted_at") val submittedAt: String,
) {
    /** 요청 쪽과 같은 규율 — 자유 의견은 표식과 길이만, 척도값은 남기지 않는다. */
    override fun toString(): String =
        "ConversionFeedbackResponse(conversionId=$conversionId, publishIntent=$publishIntent, " +
            "comment=$CONTENT_MASK ${comment?.length ?: 0}자, submittedAt=$submittedAt)"

    companion object {
        /** `PlainBody` 를 벗기는 **유일한** 자리. 봉인을 푼 값은 소유자에게만 돌아간다. */
        fun of(view: ConversionFeedbackView): ConversionFeedbackResponse =
            ConversionFeedbackResponse(
                conversionId = view.conversionId.toString(),
                publishIntent = view.publishIntent.wireName,
                qualityScore = view.qualityScore.value,
                minutesSpent = view.minutesSpent.value,
                comment = view.comment?.value,
                submittedAt = view.submittedAt.toString(),
            )
    }
}
