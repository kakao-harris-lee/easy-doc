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

/** 뒤쪽 마크다운 코드 펜스. */
private val FENCE_CLOSE = Regex("""\n?```[ \t]*\z""")

/** 머리말 판정 1단계: 첫 줄이 '다음은'·'아래는'으로 시작하는가. */
private val PREAMBLE_START = Regex("""^(?:다음은|아래는)""")

/** 머리말 판정 2단계: 변환 결과를 가리키는 신호가 있는가. */
private val PREAMBLE_SIGNAL = unicodeRegex(""":\s*${'$'}|변환\s*결과|바꾼\s*결과|쉬운\s*글""")

/** 앞뒤 마크다운 코드 펜스를 **한 겹만** 벗긴다. */
private fun stripFences(text: String): String = FENCE_CLOSE.replace(FENCE_OPEN.replace(text, ""), "").trimText()

/** 첫 줄이 변환 결과를 소개하는 머리말이면 **그 한 줄만** 제거한다. */
private fun stripPreamble(text: String): String {
    val separator = text.indexOf('\n')
    if (separator < 0) return text
    val head = text.substring(0, separator).trimText()
    // 두 신호를 **모두** 만족해야 지운다. 한쪽만으로는 정상 본문을 지운다.
    val isPreamble = PREAMBLE_START.containsMatchIn(head) && PREAMBLE_SIGNAL.containsMatchIn(head)
    return if (isPreamble) text.substring(separator + 1).trimText() else text
}

// 동작 검증: `PostprocessTest`.

/** 공백·코드 펜스·머리말을 제거한 본문을 돌려준다. */
fun postprocess(raw: String): String {
    var text = raw.trimText()
    repeat(PASSES) {
        text = stripPreamble(stripFences(text))
    }
    return text
}

/** 펜스↔머리말 순서가 뒤바뀐 경우까지 흡수하는 데 필요한 최소 횟수. */
private const val PASSES = 2
