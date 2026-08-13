package kr.easydoc.core.easyread

import kr.easydoc.core.text.isTextWhitespace
import kr.easydoc.core.text.unicodeRegex

// 쉬운 글 스타일 규칙 — 단일 정의(SSOT).
//
// 원본: app/easyread/style_rules.py
// 근거: 국립국어원 쉬운 글쓰기 지침, 보건복지부 가이드라인,
//       서울시 읽기쉬운자료개발센터('알다') 제작 원칙.
//
// **프롬프트 생성과 골든셋 평가가 반드시 이 파일의 상수·함수를 공유한다**
// (CLAUDE.md 아키텍처 규칙 4). 모델에게 지키라고 시킨 수치와 결과를 채점하는 수치가
// 갈라지면, 통과율이 모델 실력이 아니라 두 기준의 차이를 재게 된다.

/** 문장 최대 길이(자). 코드포인트 기준으로 센다. */
const val MAX_SENTENCE_CHARS = 50

/** 한 문장에 허용하는 쉼표 개수. 초과하면 '한 문장 한 정보' 위반으로 본다. */
const val MAX_COMMAS_PER_SENTENCE = 2

/**
 * 한 문장 한 정보 검사에 쓰는 쉼표(반각·전각·모점).
 *
 * 전각·모점까지 세는 이유: hwpx/pdf 추출본에 그대로 섞여 들어오고, 사람이 읽을 때는
 * 똑같이 문장을 끊는 구실을 한다. 반각만 세면 같은 문장이 입력 경로에 따라 다르게
 * 판정된다.
 */
internal val COMMA_CHARS: List<Char> = listOf(',', '，', '、')

/**
 * 이중 피동 등 피해야 할 서술 패턴.
 *
 * 원본: `app/easyread/style_rules.py::DOUBLE_PASSIVE_PATTERNS`.
 */
val DOUBLE_PASSIVE_PATTERNS: List<String> = listOf("되어지", "보여지", "쓰여지", "믿겨지", "잊혀지")

/**
 * 원칙 문구. **프롬프트 소스이기도 하다** — 검사 임계값을 문구에 보간해 모델이 지켜야
 * 할 수치와 채점 수치가 갈라지지 않게 한다(수치 자체는 위 상수가 SSOT).
 *
 * 원본: `app/easyread/style_rules.py::STYLE_PRINCIPLES`.
 */
val STYLE_PRINCIPLES: List<String> =
    listOf(
        "한 문장에는 정보를 하나만 담는다. 쉼표는 한 문장에 ${MAX_COMMAS_PER_SENTENCE}개까지만 쓴다.",
        "문장은 ${MAX_SENTENCE_CHARS}자를 넘기지 않는다.",
        "어려운 한자어·행정 용어는 쉬운 말로 바꾼다.",
        "능동태로 쓰고 이중 피동(예: '되어지다')을 쓰지 않는다.",
        "날짜·금액·연락처·신청 방법 등 중요한 정보는 빠뜨리지 않는다.",
        "존댓말로 부드럽게 설명한다.",
    )

/** 문장 분리 기준 — 마침표·물음표·느낌표 뒤의 공백, 또는 줄바꿈. */
private val SENTENCE_SPLIT = unicodeRegex("""(?<=[.!?])\s+|\n+""")

/**
 * 개조식 항목 마커("1.", "가.", "①)")는 문장이 아니라 번호다.
 * 분리 후 남는 마커 조각을 버려야 문장 수·평균 길이가 왜곡되지 않는다.
 *
 * 원본의 `^`·`$` 앵커는 빼고 [Regex.matches] 로 전체 일치를 요구한다 — 같은 판정이면서
 * Kotlin 문자열에서 `$` 를 이스케이프할 일이 없다(입력은 이미 양끝이 다듬어져 있다).
 */
private val LIST_MARKER = unicodeRegex("""(?:\d+|[가-힣]|[①-⑳])\s*[.)]""")

/**
 * 마침표·물음표·느낌표·줄바꿈 기준의 단순 문장 분리.
 *
 * 원본: `app/easyread/style_rules.py::split_sentences`.
 *
 * 개조식 항목 마커 조각은 문장으로 세지 않는다.
 */
fun splitSentences(text: String): List<String> =
    SENTENCE_SPLIT
        .split(text)
        .map { candidate -> candidate.trim { it.isTextWhitespace() } }
        .filter { it.isNotEmpty() && !LIST_MARKER.matches(it) }

/**
 * [word] 가 낱말 시작 위치에 한 번이라도 나타나는가.
 *
 * 원본: `app/easyread/style_rules.py::_appears_at_word_start`.
 *
 * 바로 앞 글자가 한글 음절이면 더 긴 낱말의 일부로 보고 건너뛴다("소득인정액"의 '정액',
 * "통장사본"의 '사본'). 뒤 글자는 보지 않는다 — 조사·어미가 붙은 "감면을"은 잡아야 할
 * 진짜 위반이다.
 *
 * 한국어에는 낱말 경계 표시가 없어, 완전한 형태소 분석 없이는 이것이 최선의 근사다.
 * 복합어 **끝**에 붙은 낱말("행정조치"의 '조치')은 놓치지만(과소 검출), 모델이 제도
 * 이름을 옳게 지켰는데 위반으로 세는 과잉 검출보다 게이트 신뢰에 낫다. 놓친 몫의
 * 이해도 판정은 골든셋 LLM judge 가 보완한다.
 */
private fun appearsAtWordStart(
    word: String,
    text: String,
): Boolean {
    var index = text.indexOf(word)
    while (index >= 0) {
        if (index == 0 || text[index - 1] !in '가'..'힣') return true
        index = text.indexOf(word, index + 1)
    }
    return false
}

/**
 * 치환 목록에 있는 어려운 표현 중 본문에 낱말로 남아 있는 것을 찾는다.
 *
 * 원본: `app/easyread/style_rules.py::find_difficult_words`.
 *
 * [PROMPT_ONLY_WORDS] 는 오탐이 많아 자동 채점 대상에서 제외한다.
 * 결과 순서는 [DIFFICULT_WORD_REPLACEMENTS] 의 선언 순서다 — 그 순서가 보정 프롬프트에
 * 그대로 실린다.
 */
fun findDifficultWords(text: String): List<String> =
    DIFFICULT_WORD_REPLACEMENTS.keys.filter { it !in PROMPT_ONLY_WORDS && appearsAtWordStart(it, text) }

/**
 * 규칙 위반 문장과 사유.
 *
 * [word] 는 **어려운 표현 잔존** 위반일 때만 채워지는 사전 키다. 보정 프롬프트가 그
 * 낱말의 뜻풀이를 함께 실으려면 사전 키가 필요한데, [reason] 문자열을 되파싱하면 사유
 * 문구를 손댈 때마다 조용히 깨진다 — 값으로 들고 다닌다.
 *
 * 치환 비문(뜻풀이 축자 삽입) 위반은 [word] 를 비운다. 처방이 사전값 조회가 아니라
 * 문장 재서술이라 보정 프롬프트가 사유 문구만 그대로 전달하면 된다.
 */
data class SentenceIssue(
    val sentence: String,
    val reason: String,
    val word: String? = null,
)

/** 규칙 기반 검사 결과. */
data class StyleCheckResult(
    val totalSentences: Int,
    val issues: List<SentenceIssue>,
) {
    val passed: Boolean get() = issues.isEmpty()
}

/**
 * 문장 길이·쉼표 수·이중 피동·어려운 표현·치환 비문을 검사한다.
 *
 * 원본: `app/easyread/style_rules.py::check_style`.
 */
fun checkStyle(text: String): StyleCheckResult {
    val sentences = splitSentences(text)
    val issues =
        buildList {
            for (sentence in sentences) {
                // 코드포인트로 센다. UTF-16 단위(`length`)로 세면 BMP 밖 문자가 두 자로
                // 잡혀 "50자"가 사용자가 세는 글자 수와 어긋난다.
                if (sentence.codePointCount(0, sentence.length) > MAX_SENTENCE_CHARS) {
                    this += SentenceIssue(sentence, "문장 길이 초과")
                }
                if (COMMA_CHARS.sumOf { comma -> sentence.count { it == comma } } > MAX_COMMAS_PER_SENTENCE) {
                    this += SentenceIssue(sentence, "쉼표 과다(한 문장 한 정보 위반 의심)")
                }
                for (pattern in DOUBLE_PASSIVE_PATTERNS) {
                    if (pattern in sentence) this += SentenceIssue(sentence, "이중 피동 표현($pattern)")
                }
                for (word in findDifficultWords(sentence)) {
                    this += SentenceIssue(sentence, "어려운 표현 잔존($word)", word)
                }
                for (gloss in findGlossCollisions(sentence)) {
                    // word 를 채우지 않는다 — 이 위반의 처방은 사전값 치환이 아니라 재서술이다.
                    // 사유 문구 자체가 보정 프롬프트의 지시가 된다.
                    this +=
                        SentenceIssue(
                            sentence,
                            "뜻풀이 축자 삽입($gloss) — 그 뜻이 통하게 문장을 자연스럽게 다시 쓸 것",
                        )
                }
            }
        }
    return StyleCheckResult(totalSentences = sentences.size, issues = issues)
}
