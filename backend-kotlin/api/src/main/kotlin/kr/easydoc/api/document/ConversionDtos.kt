package kr.easydoc.api.document

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import kr.easydoc.application.conversion.ReconvertUnitResult
import kr.easydoc.core.document.ConversionView
import kr.easydoc.core.document.FormatPreservation
import kr.easydoc.core.document.MaskedItemView
import kr.easydoc.core.privacy.CONTENT_MASK
import kr.easydoc.core.segment.SegmentMap
import kr.easydoc.core.segment.SegmentUnit

/** 마스킹 항목 한 건. 계약 `components/schemas/MaskedItemResponse` — 세 필드가 전부다. */
data class MaskedItemResponse(
    @get:JsonProperty("category") val category: String,
    @get:JsonProperty("placeholder") val placeholder: String,
    @get:JsonProperty("original") val original: String,
) {
    /**
     * **원값을 찍지 않는다.** 자리표시자는 라벨이라 남긴다 — 계약이
     * `missing_placeholders` 를 두고 *"라벨뿐이라 개인정보가 아니다"* 라고 적은 것과 같은 판단.
     */
    override fun toString(): String =
        "MaskedItemResponse(category=$category, placeholder=$placeholder, original=$CONTENT_MASK)"

    companion object {
        fun of(item: MaskedItemView): MaskedItemResponse =
            MaskedItemResponse(
                category = item.category.label,
                placeholder = item.placeholder,
                // 이 저장소에서 가린 값이 평문 문자열이 되는 **유일한** 호출이다.
                original = item.original.reveal(),
            )
    }
}

/**
 * PUT 요청 본문. 계약 `ConversionReviewRequest`. **제약을 애너테이션으로 걸지 않는다** —
 * 계약이 「정규화 후 · 서비스 층 · 문자열 `detail`」인데 Bean Validation 은 배열을 낸다.
 */
data class ConversionReviewRequest
    @JsonCreator
    constructor(
        @param:JsonProperty("edited_text") val editedText: String,
    ) {
        /** **수정본을 찍지 않는다.** */
        override fun toString(): String = "ConversionReviewRequest(editedText=$CONTENT_MASK ${editedText.length}자)"
    }

/**
 * 서식 유지 상태 한 건. 계약 `components/schemas/FormatPreservation` — 두 필드가 전부다.
 *
 * **`details` 는 사용자에게 그대로 보여 줄 문구 목록이지 본문이 아니다** — 담을 수 있는
 * 것은 구조 요소의 종류와 개수뿐이라 `toString` 이 가리지 않는다(`missing_placeholders`
 * 가 라벨을 그대로 남기는 것과 같은 판단).
 */
data class FormatPreservationResponse(
    @get:JsonProperty("status") val status: String,
    @get:JsonProperty("details") val details: List<String>,
) {
    companion object {
        fun of(preservation: FormatPreservation): FormatPreservationResponse =
            FormatPreservationResponse(
                status = preservation.status.wireName,
                details = preservation.details,
            )
    }
}

/**
 * 대응 단위 한 건. 계약 `components/schemas/SegmentMapUnit` — 세 필드가 전부다.
 *
 * 담는 것은 색인·신뢰도뿐이다 — 본문은 싣지 않는다(계획 §3 「본문은 싣지 않는다」).
 */
data class SegmentMapUnitResponse(
    @get:JsonProperty("easy_unit_index") val easyUnitIndex: Int,
    @get:JsonProperty("source_unit_indexes") val sourceUnitIndexes: List<Int>,
    @get:JsonProperty("confidence") val confidence: String,
) {
    companion object {
        fun of(unit: SegmentUnit): SegmentMapUnitResponse =
            SegmentMapUnitResponse(
                easyUnitIndex = unit.easyUnitIndex,
                sourceUnitIndexes = unit.sourceUnitIndexes,
                // 계약 `SegmentConfidence` 의 값은 영문 소문자다 — enum 이름을 그대로 낮춘다.
                confidence = unit.confidence.name.lowercase(),
            )
    }
}

/**
 * 원문-쉬운 글 문단 대응표. 계약 `components/schemas/SegmentMap` — 세 필드가 전부다.
 *
 * **저장하지 않고 매 조회마다 유도한다**(`core/segment/SegmentAlignment.kt`,
 * `docs/plans/2026-09-04-p0-4-paragraph-mapping-reconversion.md` §2 결정 2) — 클라이언트가
 * 검수본을 수정한 뒤에는 이 응답이 낡으므로, 화면은 편집 시점마다 자신이 가진 두 본문으로
 * 다시 계산해도 된다(같은 순수 함수를 프런트가 다시 부르는 것도 계약이 막지 않는다).
 */
data class SegmentMapResponse(
    @get:JsonProperty("source_unit_count") val sourceUnitCount: Int,
    @get:JsonProperty("easy_unit_count") val easyUnitCount: Int,
    @get:JsonProperty("units") val units: List<SegmentMapUnitResponse>,
) {
    companion object {
        fun of(map: SegmentMap): SegmentMapResponse =
            SegmentMapResponse(
                sourceUnitCount = map.sourceUnitCount,
                easyUnitCount = map.easyUnitCount,
                units = map.units.map(SegmentMapUnitResponse::of),
            )
    }
}

/**
 * `GET`·`PUT /conversions/{conversion_id}` 응답. 계약 `ConversionResponse` — **열아홉 필드가
 * 전부다.** 생성자와 `copy()` 가 `private` 인 것은 [of] 의 노출 판정을 우회하는 조립 지점이
 * 생기지 않게 한다.
 *
 * **형식 셋(`source_format`·`export_format`·`export_format_choices`·`format_preservation`)은
 * 결과 필드가 아니다** — 문서 메타에서 오므로 완료 전에도 실리고, 그래서 [of] 의 노출 단언이
 * 세지 않는다.
 */
@ConsistentCopyVisibility
data class ConversionResponse private constructor(
    @get:JsonProperty("id") val id: String,
    @get:JsonProperty("document_id") val documentId: String,
    @get:JsonProperty("status") val status: String,
    @get:JsonProperty("source_format") val sourceFormat: String,
    /** `null` 이면 서버가 하나로 정하지 않는다 — [exportFormatChoices] 를 보라. */
    @get:JsonProperty("export_format") val exportFormat: String?,
    /**
     * [exportFormat] 이 `null` 이고 사용자가 고를 수 있는 형식이 있을 때만 비어 있지
     * 않다. 그 밖에는 빈 배열이다(2.6.0 — `x-export-format-derivation.choices`).
     */
    @get:JsonProperty("export_format_choices") val exportFormatChoices: List<String>,
    /** `null` 은 「유지 불가」가 아니라 **서버가 아직 판정하지 않았다**. */
    @get:JsonProperty("format_preservation") val formatPreservation: FormatPreservationResponse?,
    @get:JsonProperty("easy_text") val easyText: String?,
    @get:JsonProperty("edited_text") val editedText: String?,
    @get:JsonProperty("reviewed_at") val reviewedAt: String?,
    /** 피드백을 마지막으로 제출한 시각. **`reviewed_at` 과 다른 사실이다**(계약 설명이 정본). */
    @get:JsonProperty("feedback_submitted_at") val feedbackSubmittedAt: String?,
    @get:JsonProperty("masked_items") val maskedItems: List<MaskedItemResponse>,
    @get:JsonProperty("missing_placeholders") val missingPlaceholders: List<String>,
    /** `null` 은 완료 전이거나 두 본문 중 하나를 읽을 수 없다는 뜻이다(계약 설명이 정본). */
    @get:JsonProperty("segment_map") val segmentMap: SegmentMapResponse?,
    @get:JsonProperty("model") val model: String?,
    @get:JsonProperty("provider_name") val providerName: String?,
    @get:JsonProperty("input_tokens") val inputTokens: Int?,
    @get:JsonProperty("output_tokens") val outputTokens: Int?,
    @get:JsonProperty("failure_code") val failureCode: String?,
) {
    /** 본문 둘은 표식과 길이만, 마스킹 항목은 **개수만** 남긴다. */
    override fun toString(): String =
        "ConversionResponse(id=$id, documentId=$documentId, status=$status, " +
            "sourceFormat=$sourceFormat, exportFormat=$exportFormat, exportFormatChoices=$exportFormatChoices, " +
            "formatPreservation=$formatPreservation, " +
            "easyText=$CONTENT_MASK ${easyText?.length ?: 0}자, editedText=$CONTENT_MASK ${editedText?.length ?: 0}자, " +
            "reviewedAt=$reviewedAt, feedbackSubmittedAt=$feedbackSubmittedAt, maskedItems=${maskedItems.size}건, " +
            "missingPlaceholders=$missingPlaceholders, segmentMap=$segmentMap, " +
            "model=$CONTENT_MASK, providerName=$CONTENT_MASK, " +
            "inputTokens=$inputTokens, outputTokens=$outputTokens, failureCode=$failureCode)"

    companion object {
        /**
         * 바이트를 만드는 **유일한** 자리라 노출 범위를 여기서 한 번 더 닫는다. 단언이 울리면
         * 조립 지점이 `exposesResult` 를 지나지 않은 것이고, 그때는 500 이 유출보다 낫다.
         */
        fun of(view: ConversionView): ConversionResponse {
            require(view.status.exposesResult || !view.carriesResult) {
                "완료 전 변환에 결과가 실렸다: ${view.status.wireName} masked=${view.maskedItems.size}"
            }
            return ConversionResponse(
                id = view.id.toString(),
                documentId = view.documentId.toString(),
                status = view.status.wireName,
                // 값 집합의 정본은 계약이다 — `enum` 이름이 아니라 `wireName` 만 나간다.
                sourceFormat = view.sourceFormat.wireName,
                exportFormat = view.exportFormat?.extension,
                exportFormatChoices = view.exportFormatChoices.map { it.extension },
                formatPreservation = view.formatPreservation?.let(FormatPreservationResponse::of),
                // `PlainBody` 를 벗기는 **유일한** 자리. DTO 가 그 타입을 들지 않는 사유는 클래스 KDoc.
                easyText = view.easyText?.value,
                editedText = view.editedText?.value,
                reviewedAt = view.reviewedAt?.toString(),
                feedbackSubmittedAt = view.feedbackSubmittedAt?.toString(),
                maskedItems = view.maskedItems.map(MaskedItemResponse::of),
                missingPlaceholders = view.missingPlaceholders,
                segmentMap = view.segmentMap?.let(SegmentMapResponse::of),
                model = view.model,
                providerName = view.providerName,
                inputTokens = view.inputTokens,
                outputTokens = view.outputTokens,
                failureCode = view.failureCode,
            )
        }
    }
}

/**
 * `POST .../reconvert` 요청 본문. 계약 `ReconvertUnitRequest`. `source_unit_index`는
 * 경로가 지므로 여기 없다.
 */
data class ReconvertUnitRequest
    @JsonCreator
    constructor(
        @param:JsonProperty("easy_unit_indexes") val easyUnitIndexes: List<Int>,
        @param:JsonProperty("easy_text_fingerprint") val easyTextFingerprint: String,
    ) {
        /** 지문은 해시값이라도 `DictionaryLookupRequest.text`와 같은 규약을 따른다. */
        override fun toString(): String =
            "ReconvertUnitRequest(easyUnitIndexes=$easyUnitIndexes, easyTextFingerprint=$CONTENT_MASK)"
    }

/**
 * `POST .../reconvert` 응답. 계약 `ReconvertUnitResponse` — **여섯 필드가 전부다.**
 * 후보뿐이고 변환 본문에는 아무것도 쓰이지 않는다.
 */
data class ReconvertUnitResponse(
    @get:JsonProperty("candidate_text") val candidateText: String,
    @get:JsonProperty("source_unit_index") val sourceUnitIndex: Int,
    @get:JsonProperty("easy_unit_indexes") val easyUnitIndexes: List<Int>,
    @get:JsonProperty("easy_text_fingerprint") val easyTextFingerprint: String,
    @get:JsonProperty("llm_calls_used") val llmCallsUsed: Int,
    @get:JsonProperty("remaining_call_budget") val remainingCallBudget: Int,
) {
    /** **후보 본문을 찍지 않는다** — 사용자 문서 내용이다. */
    override fun toString(): String =
        "ReconvertUnitResponse(candidateText=$CONTENT_MASK ${candidateText.length}자, " +
            "sourceUnitIndex=$sourceUnitIndex, easyUnitIndexes=$easyUnitIndexes, " +
            "llmCallsUsed=$llmCallsUsed, remainingCallBudget=$remainingCallBudget)"

    companion object {
        fun of(result: ReconvertUnitResult): ReconvertUnitResponse =
            ReconvertUnitResponse(
                candidateText = result.candidateText,
                sourceUnitIndex = result.sourceUnitIndex,
                easyUnitIndexes = result.easyUnitIndexes,
                easyTextFingerprint = result.easyTextFingerprint,
                llmCallsUsed = result.llmCallsUsed,
                remainingCallBudget = result.remainingCallBudget,
            )
    }
}
