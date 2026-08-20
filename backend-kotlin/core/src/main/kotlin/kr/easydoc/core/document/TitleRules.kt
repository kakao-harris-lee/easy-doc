package kr.easydoc.core.document

import kr.easydoc.core.text.stripControlChars

// 문서 제목을 정하는 규칙.
//
// ## 제목은 **본문에서 만들지 않는다** (2026-08-20 사용자 결정 · 게이트 27 Critical ①)
//
// 이전 판은 제목이 없으면 **본문 첫 줄**을 30자로 잘라 제목으로 썼다(원본
// `app/services/documents.py::_resolve_title`·`_shorten_derived_title` 포팅). 그 갈래는
// 방어 둘을 동시에 우회한다.
//
// - `documents.title` 은 **평문 컬럼**이다. 원문은 AEAD 로 봉하는데 그 조각이 옆 칸에
//   평문으로 남는다 — DB·백업·덤프·향후 검색 표면 전부에 걸린다.
// - 업로드 시점에는 **마스킹이 돌지 않는다**(마스킹 선행 불변식의 대상은 LLM 전송이고
//   그것은 워커의 일이다). 그래서 첫 줄에 주민등록번호가 있으면 그대로 평문 저장된다.
//
// 그래서 제목의 바탕은 **사용자가 이름으로 준 값**뿐이다 — 적어 준 제목, 없으면 파일 이름.
// 둘 다 없으면 [FALLBACK_TITLE] 이다. 본문은 어떤 경우에도 제목이 되지 않는다.
//
// ## 파일 이름을 쓰는 것은 **되돌린 결정**이다 — 남는 위험을 여기 적어 둔다
//
// 이 파일의 이전 판과 계약(`DocumentTextRequest.title`)과
// `migration-safety-gate` SKILL.md 는 *"파일명은 제목으로 쓰지 않는다 — 파일명 자체가
// 개인정보일 수 있다"* 고 적었다. 2026-08-20 사용자 결정이 그 갈래를 뒤집었고, 근거는
// **본문과 파일 이름의 성격이 다르다**는 것이다 — 본문은 사용자가 맡긴 내용이고 파일
// 이름은 사용자가 스스로 붙여 건넨 이름이라 적어 준 제목과 같은 부류다.
//
// **남는 위험**: `홍길동_주민등록등본.docx` 같은 이름은 여전히 평문으로 저장된다. 그
// 위험을 줄이려고 이 파일이 하는 일은 [baseNameOf] 의 셋뿐이고(경로 제거·확장자 제거·
// 제어문자 제거), 이름 안의 개인정보 자체는 걸러내지 않는다. 계약 문면과 게이트 문장의
// 개정은 각각 `contract-keeper`·`privacy-gate` 몫이다.

/** 제목을 정할 근거가 하나도 없을 때 쓰는 이름. */
const val FALLBACK_TITLE: String = "제목 없음"

/**
 * 제목을 정한다. **사용자가 이름으로 준 값만** 쓴다 — 본문은 쓰지 않는다.
 *
 * 우선순위는 ⑴ 적어 준 제목 ⑵ 업로드 파일 이름 ⑶ [FALLBACK_TITLE] 이다.
 *
 * ## 두 입력을 같게 다룬다
 *
 * 둘 다 사용자가 고른 이름이므로 규칙이 하나다 — 앞뒤 공백을 털고, 제어문자를 걷어내고,
 * [MAX_TITLE_LENGTH] 에서 **자른다(거절하지 않는다)**. 상한 초과를 거절하지 않는 것은
 * 계약이 정한 것이다(`x-input-limits.max_title_length`) — 목록에 보일 이름일 뿐이라
 * 업로드를 통째로 실패시킬 이유가 없다.
 *
 * 제어문자를 자르기 **전에** 걷어낸다. 순서를 뒤집으면 잘린 길이가 보이는 글자 수와
 * 어긋나고, 남은 제어문자가 내보내기(XML) 시점에 터진다.
 *
 * ## 적어 준 제목이 제어문자뿐이면 파일 이름으로 넘어가지 않는다
 *
 * 사용자가 **무언가를 적었다는 사실**을 다른 값으로 덮지 않는다(이전 판이 본문에 대해
 * 내린 판단과 같다). 그때는 곧바로 [FALLBACK_TITLE] 이다.
 *
 * @param given 사용자가 적어 준 제목. 없거나 공백뿐이면 파일 이름으로 넘어간다.
 * @param uploadFilename 업로드 파일 이름. 붙여넣기 모드에서는 `null` 이다.
 */
fun resolveTitle(
    given: String?,
    uploadFilename: String?,
): String {
    // 「사용자가 적어 준 것이 있으면 그것, 없으면 파일 이름」을 **한 자리에서** 고른다.
    // 갈래마다 돌려주면 "어느 입력이 이겼는가"가 흩어진다.
    val trimmed = given.orEmpty().trim()
    val chosen = if (trimmed.isNotEmpty()) trimmed else baseNameOf(uploadFilename)
    return chosen?.let { sanitizeName(it) } ?: FALLBACK_TITLE
}

/**
 * 이름 하나를 저장할 수 있는 모양으로 다듬는다. 남는 것이 없으면 `null`.
 *
 * 앞뒤 공백은 **한 번 더** 턴다 — 제어문자를 지우면 가려져 있던 공백이 드러난다
 * (`"\u0000 안내"` → `" 안내"`).
 */
private fun sanitizeName(raw: String): String? =
    takeCodePoints(stripControlChars(raw), MAX_TITLE_LENGTH).trim().ifEmpty { null }

/**
 * 파일 이름에서 제목의 바탕이 될 조각을 꺼낸다 — **경로와 확장자를 뗀다.**
 *
 * 세 판단과 사유(파일 이름도 사용자 입력이라 정의역을 정해 두어야 한다):
 *
 * 1. **마지막 경로 조각만 쓴다.** multipart 클라이언트에 따라 `C:\Users\hong\문서\x.docx`
 *    처럼 전체 경로가 실려 온다(RFC 7578 은 `filename` 을 신뢰하지 말라고 적는다).
 *    경로를 그대로 저장하면 사용자의 로컬 디렉터리 구조가 평문으로 남고, 그 경로에는
 *    보통 계정 이름이 들어 있다. `/` 와 `\` 를 **둘 다** 자른다 — 서버 OS 가 아니라
 *    **보낸 쪽** OS 가 구분자를 정하기 때문이다.
 * 2. **확장자를 뗀다.** 형식은 이미 `documents.source_format` 이 든다. 목록에 보일 이름에
 *    같은 사실을 두 번 적을 이유가 없다. 점이 **맨 앞**이면 떼지 않는다 — 그때는 확장자가
 *    아니라 이름 전체다(`.gitignore` 형태). 점이 여럿이면 **마지막 하나만** 뗀다
 *    (`보고서.최종.docx` → `보고서.최종`).
 * 3. **뗀 결과가 비면 `null`** 이다. `".docx"`·`"/"`·`"   "` 같은 입력에서 빈 제목을 만들지
 *    않고 호출자가 [FALLBACK_TITLE] 로 가게 한다.
 *
 * 제어문자 제거와 길이 자르기는 [sanitizeName] 이 적어 준 제목과 **같은 규칙으로** 한다.
 */
private fun baseNameOf(uploadFilename: String?): String? {
    val segment =
        uploadFilename
            .orEmpty()
            .trim()
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
    if (segment.isEmpty()) return null
    val lastDot = segment.lastIndexOf('.')
    val stem = if (lastDot > 0) segment.substring(0, lastDot) else segment
    return stem.trim().ifEmpty { null }
}
