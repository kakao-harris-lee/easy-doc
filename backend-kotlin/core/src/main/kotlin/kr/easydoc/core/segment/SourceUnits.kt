package kr.easydoc.core.segment

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
