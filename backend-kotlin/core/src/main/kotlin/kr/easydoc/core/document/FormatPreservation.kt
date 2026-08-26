package kr.easydoc.core.document

// 원본 서식 유지 상태 — 계약 `FormatPreservationStatus`·`FormatPreservation`.
//
// 값 집합의 정본은 계약이고 이 파일은 그 대응이다. **넓히지 마라** — 계약이 네 값
// (`available`·`partial`·`checking`·`failed`)을 뺀 사유가 그쪽 산문에 있고, 여기에
// 값을 먼저 더하면 구현이 계약에 없는 상태를 응답에 실을 수 있게 된다.
// 값 집합 대조: `ConversionFormatContractTest`.

/** 원본 서식 유지 상태. 오늘 서버가 정직하게 말할 수 있는 값은 **하나뿐이다.** */
enum class FormatPreservationStatus(val wireName: String) {
    /**
     * **유지할 원본 서식이 없다.** 붙여넣기라 파일이 아니었거나, 원본 파일 바이트가
     * 저장돼 있지 않아 되살릴 수 없는 문서다. 둘 다 **영구히 참**이라 구조 보존이
     * 구현된 뒤에도 이 판정은 뒤집히지 않는다.
     */
    NOT_APPLICABLE("not_applicable"),
}

/**
 * 서식 유지 판정 한 건. 계약 `FormatPreservation` — 두 필드가 전부다.
 *
 * **[details] 에 문서 본문·개인정보를 담지 않는다.** 담을 수 있는 것은 구조 요소의 종류와
 * 개수뿐이며, 그 규칙은 `missing_placeholders` 가 라벨만 싣는 것과 같은 판단이다.
 */
class FormatPreservation(
    val status: FormatPreservationStatus,
    val details: List<String>,
) {
    /** 상태와 **개수**만 남긴다 — 항목 문구는 사용자에게 보여 줄 값이지 로그에 남길 값이 아니다. */
    override fun toString(): String = "FormatPreservation(${status.wireName}, 항목 ${details.size}건)"
}

/**
 * 오늘 서버가 낼 수 있는 서식 유지 판정.
 *
 * 판정 기준이 [SourceFormat] 이 아니라 **원본 행의 유무**인 것이 이 함수의 요점이다.
 * `document_originals` 표가 서기 전에 올라온 DOCX·HWPX 는 형식은 파일인데 바이트가 이미
 * 사라졌고, 그 문서의 서식은 앞으로도 되살릴 수 없다. 형식으로만 갈랐다면 그 갈래가
 * 「아직 판정하지 않았다」로 남아 영원히 판정되지 않을 값을 기다리게 된다.
 *
 * 원본이 **있으면** `null` 이다 — 「유지 불가」가 아니라 **서버가 아직 판정하지 않았다.**
 * 원본 구조를 분석하는 구현이 없는 동안 다섯 상태 중 무엇을 골라도 추측이고, 계약
 * `format_preservation` 이 "낙관적으로 추측하지 않는다"를 그 자리에서 요구한다.
 */
fun formatPreservationOf(hasStoredOriginal: Boolean): FormatPreservation? =
    if (hasStoredOriginal) {
        null
    } else {
        FormatPreservation(FormatPreservationStatus.NOT_APPLICABLE, emptyList())
    }
