package kr.easydoc.api.dictionary

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import kr.easydoc.application.dictionary.DictionaryAttribution
import kr.easydoc.core.dictionary.TermCandidate
import kr.easydoc.core.dictionary.TermMatchKind
import kr.easydoc.core.privacy.CONTENT_MASK

/**
 * `POST /dictionary/lookup` 요청 본문. 계약 `DictionaryLookupRequest`.
 *
 * **길이·빈 값 제약을 애너테이션으로 걸지 않는다** — 계약 `x-request-field-constraints`가
 * 이 필드를 다섯째 항목 옆에 명시적으로 여섯째로 올려 두었다: 정규화(제어문자 제거 +
 * 공백류 뭉개기 + 트림) **후** 길이를 재고, 위반 시 문자열 `detail`이어야 한다
 * (`WorkspaceNameRequest.name`과 같은 판단). `TermQuery.of` 가 그 정제·판정을 하므로
 * 여기서 `@field:Size` 를 걸면 원시 값 기준 스키마 배열 오류가 먼저 나가 계약과 어긋난다.
 */
data class DictionaryLookupRequest
    @JsonCreator
    constructor(
        @param:JsonProperty("text") val text: String,
    ) {
        /** [TermQuery] 와 같은 사유로 [text] 를 가린다 — 검수 담당자가 지목한 문서 조각이다. */
        override fun toString(): String = "DictionaryLookupRequest(text=$CONTENT_MASK, length=${text.length})"
    }

/** 변환 전후 예문 한 쌍. 계약 `DictionaryLookupExample`. */
data class DictionaryLookupExampleResponse(
    @get:JsonProperty("before") val before: String,
    @get:JsonProperty("after") val after: String,
)

/** 조회 후보 하나. 계약 `DictionaryLookupCandidate`. */
data class DictionaryLookupCandidateResponse(
    @get:JsonProperty("term") val term: String,
    @get:JsonProperty("easy_term") val easyTerm: String,
    @get:JsonProperty("strategy") val strategy: String,
    @get:JsonProperty("risk") val risk: String,
    @get:JsonProperty("definition") val definition: String?,
    @get:JsonProperty("caution") val caution: String?,
    @get:JsonProperty("tags") val tags: List<String>,
    @get:JsonProperty("examples") val examples: List<DictionaryLookupExampleResponse>,
    @get:JsonProperty("match_kind") val matchKind: String,
    @get:JsonProperty("applicable") val applicable: Boolean,
) {
    companion object {
        fun of(candidate: TermCandidate): DictionaryLookupCandidateResponse =
            DictionaryLookupCandidateResponse(
                term = candidate.term,
                easyTerm = candidate.easyTerm,
                strategy = candidate.strategy.wire,
                risk = candidate.risk.wire,
                definition = candidate.definition,
                caution = candidate.caution,
                tags = candidate.tags,
                examples = candidate.examples.map { DictionaryLookupExampleResponse(it.before, it.after) },
                matchKind = candidate.matchKind.wireName(),
                applicable = candidate.applicable,
            )

        /** 계약 `TermMatchKind` enum 값(`exact`|`inflected`|`compound_part`)으로 옮긴다. */
        private fun TermMatchKind.wireName(): String = name.lowercase()
    }
}

/** 사전 단위 표기. 계약 `DictionaryAttribution`. */
data class DictionaryAttributionResponse(
    @get:JsonProperty("name") val name: String,
    @get:JsonProperty("license") val license: String,
    @get:JsonProperty("schema_version") val schemaVersion: String,
) {
    /** [DictionaryAttribution] 과 같은 사유로 [name] 을 가린다. */
    override fun toString(): String =
        "DictionaryAttributionResponse(name=$CONTENT_MASK ${name.length}자, license=$license, " +
            "schemaVersion=$schemaVersion)"

    companion object {
        fun of(attribution: DictionaryAttribution): DictionaryAttributionResponse =
            DictionaryAttributionResponse(
                name = attribution.name,
                license = attribution.license,
                schemaVersion = attribution.schemaVersion,
            )
    }
}

/**
 * `POST /dictionary/lookup` 응답. 계약 `DictionaryLookupResponse` — `query`·`candidates`·
 * `dictionary` 셋뿐이다.
 */
@ConsistentCopyVisibility
data class DictionaryLookupResponse private constructor(
    @get:JsonProperty("query") val query: String,
    @get:JsonProperty("candidates") val candidates: List<DictionaryLookupCandidateResponse>,
    @get:JsonProperty("dictionary") val dictionary: DictionaryAttributionResponse,
) {
    /** [query] 는 검수 담당자가 지목한 문서 조각이라 [DictionaryLookupRequest] 와 같은 사유로 가린다. */
    override fun toString(): String =
        "DictionaryLookupResponse(query=$CONTENT_MASK ${query.length}자, candidates=${candidates.size}건)"

    companion object {
        fun of(
            query: String,
            candidates: List<TermCandidate>,
            attribution: DictionaryAttribution,
        ): DictionaryLookupResponse =
            DictionaryLookupResponse(
                query = query,
                candidates = candidates.map(DictionaryLookupCandidateResponse::of),
                dictionary = DictionaryAttributionResponse.of(attribution),
            )
    }
}
