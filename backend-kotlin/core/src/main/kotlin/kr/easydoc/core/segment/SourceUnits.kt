package kr.easydoc.core.segment

import kr.easydoc.core.privacy.MaskedText
import kr.easydoc.core.privacy.MaskingResult

// P0-4 문단 대응 — 계획 §2 결정 1 「구조 모델: 저장하지 않고 유도한다」.
//
// 원본 단위 = 저장된 추출 원문(`infrastructure/ingest/ExtractedTextBuilder`)을 `\n` 으로 쪼갠 줄.
// 쉬운 글 단위 = `edited_text ?? easy_text` 를 `\n` 으로 쪼갠 줄. 새 표도, 새 암호문 열도 만들지
// 않는다 — 이미 저장된 두 문자열의 순수 함수다.

/**
 * 텍스트를 줄 단위로 쪼갠다. [joinUnits] 과 **무손실 왕복**이다:
 * `joinUnits(splitUnits(x)) == x`.
 *
 * Kotlin `String.split` 이 빈 문자열도 원소로 담아 조각 수를 그대로 보존하므로(`""` →
 * `[""]`, `"a\n"` → `["a", ""]`), 첫·끝 빈 줄을 걷어내는 별도 처리를 하지 않는다 — 걷어내면
 * `\n` 개수가 달라져 왕복이 깨진다.
 */
fun splitUnits(text: String): List<String> = text.split("\n")

/** [splitUnits] 의 역. 저장되는 문자열은 화면을 단위 목록으로 바꿔도 한 글자도 달라지지 않는다. */
fun joinUnits(units: List<String>): String = units.joinToString("\n")

/**
 * [masking] 을 줄 단위로 쪼갠 뒤 [index] 번째 줄만 담은 새 [MaskingResult] 를 만든다 —
 * 재변환 전용(계획 §4 결정 3, `docs/plans/2026-09-04-p0-4-paragraph-mapping-reconversion.md`).
 * [MaskedText.unitOf] 가 `internal`(모듈 범위)인 이유는 그 함수 KDoc — 이 함수가 `core`
 * 밖(`application`)으로 여는 공개 통로다([kr.easydoc.core.privacy.maskText] 가
 * [MaskedText.Companion.mask] 를 여는 것과 같은 자리).
 *
 * **다시 마스킹하지 않는다.** [masking] 은 이미 `maskText` 를 거친 값이고, 그 부분 문자열도
 * 여전히 마스킹된 문자열이다 — 다시 마스킹하면 `escapeLookalikes` 가 이미 있는 자리표시자를
 * 탈출 처리해 자리표시자 형태가 깨진다.
 *
 * [MaskingResult.items] 는 그 줄에 **실제로 나타나는** 자리표시자만 남긴다 — 문서 전체의
 * 자리표시자 목록을 그대로 넘기면 재변환 결과의 「자리표시자 유실」 판정이 이 단위에 없는
 * 자리표시자까지 유실로 세게 된다.
 */
fun maskedUnitOf(
    masking: MaskingResult,
    index: Int,
): MaskingResult {
    val unit = MaskedText.unitOf(masking.maskedText, index)
    return MaskingResult(
        maskedText = unit,
        items = masking.items.filter { it.placeholder in unit.value },
    )
}
