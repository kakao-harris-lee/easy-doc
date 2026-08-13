package kr.easydoc.core.easyread

import kr.easydoc.core.text.trimText
import kr.easydoc.core.text.unicodeRegex

// LLM 변환 응답 후처리 — 모델이 덧붙인 껍데기를 벗기고 본문만 남긴다.
//
// 원본: app/easyread/postprocess.py
//
// ## 제거 조건을 좁게 잡는 이유 (비대칭)
//
// 프롬프트로 금지해도(OUTPUT_INSTRUCTION) 모델이 코드 펜스·머리말을 붙이는 경우가 있어
// 방어적으로 제거한다. 다만 **본문을 잘못 지우는 쪽이 껍데기를 남기는 쪽보다 위험하다.**
//
// - 껍데기가 남으면: 검수자 눈에 즉시 보이고, 지우면 끝난다.
// - 본문을 지우면: 무엇이 지워졌는지 원문과 대조하지 않으면 알 수 없고, 지워진 것이
//   대상 조건이나 신청 마감일이면 시민이 잘못된 안내를 받는다.
//
// 그래서 머리말 판정 신호를 '변환 결과'·'바꾼 결과'·'쉬운 글'·끝 콜론으로 좁힌다.
// '결과' 부분 문자열만 보면 "다음은 심사 결과입니다." 같은 정상 본문 첫 문장까지 지운다.
// 꼬리말은 아예 제거하지 않는다 — 과잉 제거 위험이 커서 HITL 검수에 맡긴다.

/** 앞쪽 마크다운 코드 펜스. 여는 쪽은 ```text·```markdown 등 언어 태그를 허용한다. */
private val FENCE_OPEN = Regex("""\A```[^\n]*\n?""")

/**
 * 뒤쪽 마크다운 코드 펜스.
 *
 * 끝 앵커로 `\z`(입력의 절대 끝)를 쓴다. Java 의 `\Z` 는 **마지막 줄바꿈 앞**에서도
 * 성립하므로 Python `re` 의 `\Z` 와 뜻이 다르다 — 그대로 옮기면 뒤에 줄바꿈이 남은
 * 입력에서 펜스가 아닌 자리를 지울 수 있다.
 */
private val FENCE_CLOSE = Regex("""\n?```[ \t]*\z""")

/** 머리말 판정 1단계: 첫 줄이 '다음은'·'아래는'으로 시작하는가. */
private val PREAMBLE_START = Regex("""^(?:다음은|아래는)""")

/**
 * 머리말 판정 2단계: 변환 결과를 가리키는 신호가 있는가.
 *
 * 두 단계를 모두 만족해야 지운다. 한쪽만으로는 정상 본문을 지운다 — "다음은 심사
 * 결과입니다."는 1단계만, "쉬운 글로 바꿔 드립니다."는 2단계만 만족한다.
 */
private val PREAMBLE_SIGNAL = unicodeRegex(""":\s*${'$'}|변환\s*결과|바꾼\s*결과|쉬운\s*글""")

/** 앞뒤 마크다운 코드 펜스를 **한 겹만** 벗긴다. */
private fun stripFences(text: String): String = FENCE_CLOSE.replace(FENCE_OPEN.replace(text, ""), "").trimText()

/**
 * 첫 줄이 변환 결과를 소개하는 머리말이면 **그 한 줄만** 제거한다.
 *
 * 줄바꿈이 없으면 머리말 뒤에 본문이 없다는 뜻이므로 전부 날리지 않고 원문을 유지한다.
 * 이것이 없으면 한 줄짜리 응답이 통째로 사라져 빈 변환 결과가 저장된다.
 */
private fun stripPreamble(text: String): String {
    val separator = text.indexOf('\n')
    if (separator < 0) return text
    val head = text.substring(0, separator).trimText()
    // 두 신호를 **모두** 만족해야 지운다. 한쪽만으로는 정상 본문을 지운다.
    val isPreamble = PREAMBLE_START.containsMatchIn(head) && PREAMBLE_SIGNAL.containsMatchIn(head)
    return if (isPreamble) text.substring(separator + 1).trimText() else text
}

/**
 * 공백·코드 펜스·머리말을 제거한 본문을 돌려준다.
 *
 * 원본: `app/easyread/postprocess.py::postprocess`.
 *
 * 두 번 도는 이유는 펜스와 머리말의 순서가 뒤바뀐 경우를 흡수하기 위해서다
 * (` ```\n다음은 변환 결과입니다:\n본문 ` 과 `다음은 변환 결과입니다:\n```\n본문` 이 둘 다 나온다).
 * **대가**: 머리말처럼 보이는 줄이 연달아 둘이면 둘 다 사라진다. 두 번째 줄이 진짜 본문일
 * 가능성은 신호를 좁혀 놓아 낮지만 0은 아니다 — 이 성질은 `PostprocessTest` 가 고정해 둔다.
 */
fun postprocess(raw: String): String {
    var text = raw.trimText()
    repeat(PASSES) {
        text = stripPreamble(stripFences(text))
    }
    return text
}

/** 펜스↔머리말 순서가 뒤바뀐 경우까지 흡수하는 데 필요한 최소 횟수. */
private const val PASSES = 2
