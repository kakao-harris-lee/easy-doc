package kr.easydoc.core.document

import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.easyread.ExportFormat
import kr.easydoc.core.segment.SegmentMap
import java.time.Instant
import java.util.UUID

/**
 * 변환 한 건의 **조회 결과** — 계약 `ConversionResponse` 의 17필드에 1:1 대응한다.
 *
 * **형식 셋([sourceFormat]·[exportFormat]·[formatPreservation])은 결과 필드가 아니다.**
 * 문서 메타에서 오므로 완료 전에도 그대로 실린다 — 그래서 [carriesResult] 가 세지 않는다.
 */
data class ConversionView(
    val id: UUID,
    val documentId: UUID,
    val status: ConversionStatus,
    /** 이 변환이 딸린 문서의 원본 형식. 계약 `SourceFormat`. */
    val sourceFormat: SourceFormat,
    /**
     * 내려받을 때 **써야 하는** 형식 — [sourceFormat] 이 정한다([ExportFormat.ofSource]).
     * 현재 모든 원본에 값이 있고, PDF는 레이아웃·스타일을 버린 `TXT`다.
     */
    val exportFormat: ExportFormat?,
    /**
     * [exportFormat] 이 `null` 이고 이 원본에 사용자가 고를 수 있는 형식이 있을 때만
     * 비어 있지 않다([ExportFormat.choicesFor]). 현재는 모든 원본에 기본값이 있어 빈 목록이다.
     */
    val exportFormatChoices: List<ExportFormat>,
    /** 서식 유지 판정. `null` 은 「유지 불가」가 아니라 **서버가 아직 판정하지 않았다**. */
    val formatPreservation: FormatPreservation?,
    val easyText: PlainBody?,
    val editedText: PlainBody?,
    val reviewedAt: Instant?,
    /**
     * 파일럿 피드백을 마지막으로 제출한 시각. 낸 적이 없으면 `null`.
     *
     * **[reviewedAt] 과 다른 사실이다** — 「수정본을 저장했다」와 「의견을 냈다」는 서로를
     * 함의하지 않는다. 피드백 저장이 [reviewedAt] 을 대신 찍지 않는 사유는 계약
     * `ConversionResponse.feedback_submitted_at` 의 설명이 정본이다.
     */
    val feedbackSubmittedAt: Instant?,
    /**
     * 원문-쉬운 글 문단 대응표. **저장하지 않고 매 조회마다 유도한다**
     * (`core/segment/SegmentAlignment.kt` — 계획 §2 결정 2). 완료 전이거나 두 본문 중 하나를
     * 읽을 수 없으면(원문 행이 만료·삭제로 사라진 경합 포함) `null` — 「대응을 확인하지 못했다」와
     * 다른 사유이므로 별도 필드를 두지 않고 이 필드 하나가 겸한다(계약 `segment_map` 설명).
     */
    val segmentMap: SegmentMap?,
    val model: String?,
    val providerName: String?,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val failureCode: String?,
    /** 저장된 유효 본문의 서버 버전. 완료 전 0, 첫 완료 1이며 본문이 실제로 바뀔 때만 증가한다. */
    val contentRevision: Long = if (status.exposesResult) 1 else 0,
    /** 서버 설정과 구현 범위에서 유도한 검수 기능 노출 정보. */
    val reviewCapabilities: ReviewCapabilities = ReviewCapabilities.NONE,
) {
    /**
     * 계약 `ConversionResponse.description` 의 **「결과 필드」 열** 중 하나라도 값을 들었는가.
     * 완료 전에 나가는 것은 `id`·`document_id`·`status`·`failure_code` 와 **형식 셋** 일곱이다.
     *
     * [feedbackSubmittedAt] 도 그 열에 든다 — 피드백은 완료된 변환에만 낼 수 있으므로
     * (`ConversionFeedbackService.save` 의 409), 완료 전에 값이 서면 그것은 결함이다.
     */
    val carriesResult: Boolean
        get() =
            listOf(
                easyText,
                editedText,
                reviewedAt,
                feedbackSubmittedAt,
                model,
                providerName,
                inputTokens,
                outputTokens,
                segmentMap,
            ).any { it != null }

    /**
     * 로그 허용목록 그대로 — 식별자·상태·실패 코드뿐이다.
     * 형식은 문서 메타이지 사용자 콘텐츠가 아니라 남긴다(`Document.toString` 과 같은 판단).
     */
    override fun toString(): String =
        "ConversionView($id, ${status.wireName}, ${sourceFormat.wireName}, failure=$failureCode, " +
            "segmentMap=$segmentMap)"
}

data class ReviewCapabilities(
    val reviewSupport: Boolean,
    val actionGuide: Boolean,
    val tableRelations: Boolean,
    val reviewHistory: Boolean,
    val explanations: Boolean,
    val illustrations: Boolean,
    /**
     * R7 ER-17 문맥 기반 그림 제안. 토글이 켜져 있고 **이용량 단가가 설정돼 있을 때만** true 다
     * (명세 §3·§6) — 단가가 없으면 접수가 503 이라 기능을 노출하는 것이 거짓말이 된다.
     */
    val illustrationSuggestions: Boolean,
) {
    companion object {
        val NONE = ReviewCapabilities(false, false, false, false, false, false, false)
    }
}
