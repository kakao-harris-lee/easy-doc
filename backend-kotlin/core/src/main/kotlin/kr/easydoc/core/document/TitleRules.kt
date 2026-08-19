package kr.easydoc.core.document

import kr.easydoc.core.text.stripControlChars

// 문서 제목을 정하는 규칙.
//
// 원본: `app/services/documents.py::_resolve_title`·`_shorten_derived_title`.
//
// **파일 이름을 쓰지 않는다.** 파일 이름 자체가 개인정보일 수 있고(`홍길동_주민등록등본.pdf`),
// 같은 이유로 저장도 로깅도 하지 않는다(계약 `DocumentTextRequest.title` 이 같은 규칙을 적었다).

/** 제목을 유도할 수 없을 때(첫 줄이 없거나 공백뿐일 때) 쓰는 이름. */
const val FALLBACK_TITLE: String = "제목 없음"

/**
 * 본문에서 **유도한** 제목의 목표 길이. 어절 경계는 이 안에서만 찾는다.
 *
 * [MAX_TITLE_LENGTH] 와 다른 기준이다 — 저쪽은 저장할 수 있는 최대치이고, 이쪽은 목록에서
 * 한 줄로 읽히는 길이다. 본문 첫 줄은 문장 하나가 통째로 들어오는 일이 흔해서, 상한만
 * 믿으면 목록이 제목으로 도배된다.
 */
const val AUTO_TITLE_TARGET_LENGTH: Int = 30

/** 유도한 제목이 잘렸음을 알리는 말줄임표. 한 글자다(`...` 세 글자가 아니다). */
const val TITLE_ELLIPSIS: String = "…"

/**
 * 제목을 정한다. 사용자가 준 것이 있으면 그것을, 없으면 본문 첫 줄에서 유도한다.
 *
 * ## 두 입력을 다르게 다루는 이유
 *
 * - **사용자가 적어 준 제목**은 [MAX_TITLE_LENGTH] 만 지키고 손대지 않는다. 직접 붙인
 *   이름을 말없이 짧게 줄이면 그 사람이 담은 뜻이 사라진다.
 * - **본문 첫 줄**은 우리가 임의로 고른 값이라 [AUTO_TITLE_TARGET_LENGTH] 로 줄인다.
 *   문장 하나가 통째로 목록을 채우는 편이 더 나쁘다.
 *
 * 상한을 넘는 제목을 **거절하지 않고 자르는** 것도 계약이 정한 것이다
 * (`x-input-limits.max_title_length`) — 목록에 보일 이름일 뿐이라, 긴 첫 줄을 가진 문서
 * 업로드를 통째로 실패시킬 이유가 없다.
 *
 * 제어문자를 자르기 **전에** 걷어낸다. 순서를 뒤집으면 잘린 길이가 보이는 글자 수와
 * 어긋나고, 남은 제어문자가 내보내기(XML) 시점에 터진다.
 *
 * @param given 사용자가 준 제목. 없거나 공백뿐이면 유도로 넘어간다.
 * @param body 본문. 첫 번째 **내용 있는** 줄을 제목의 바탕으로 쓴다.
 */
fun resolveTitle(
    given: String?,
    body: String,
): String {
    val trimmed = given.orEmpty().trim()
    if (trimmed.isNotEmpty()) {
        // 제어문자만으로 이루어진 제목은 걷어내면 빈 문자열이 된다 — 그때는 유도가 아니라
        // 대체 제목이다(원본과 같다). 사용자가 무언가를 적었다는 사실을 본문으로 덮지 않는다.
        return takeCodePoints(stripControlChars(trimmed), MAX_TITLE_LENGTH).ifEmpty { FALLBACK_TITLE }
    }

    // 제어문자를 지우면 앞뒤에 공백이 드러날 수 있다(`"\u0000 안내"` → `" 안내"`).
    // 어절 경계를 세기 전에 다시 다듬는다.
    val firstLine =
        body
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
    val derived = stripControlChars(firstLine).trim()
    return if (derived.isEmpty()) FALLBACK_TITLE else shortenDerivedTitle(derived)
}

/**
 * 유도한 제목을 목표 길이 안으로 줄이고 말줄임표를 붙인다.
 *
 * 어절(공백) 경계를 먼저 찾는 이유는 한국어에서 어절 중간이 잘리면 남은 조각이 다른 말로
 * 읽히기 때문이다. 목표 길이 안에 경계가 없으면(붙여 쓴 제목·URL) 그대로 하드컷 한다 —
 * 한 줄을 통째로 남기는 것보다 낫다.
 *
 * 앞뒤 공백이 없는 문자열을 전제로 하므로 [resolveTitle] 에서만 부른다.
 */
private fun shortenDerivedTitle(title: String): String {
    if (charCountOf(title) <= AUTO_TITLE_TARGET_LENGTH) return title

    // 목표 길이 **바로 다음 글자까지** 본다 — 어절이 정확히 목표 길이에서 끝나면 그 경계를
    // 살릴 수 있다(한 어절을 통째로 잃지 않는다).
    val window = takeCodePoints(title, AUTO_TITLE_TARGET_LENGTH + 1).trimEnd()
    val boundary = window.indexOfLast { it.isWhitespace() }
    val head =
        if (boundary > 0) {
            window.substring(0, boundary).trimEnd()
        } else {
            takeCodePoints(window, AUTO_TITLE_TARGET_LENGTH)
        }
    return head + TITLE_ELLIPSIS
}
