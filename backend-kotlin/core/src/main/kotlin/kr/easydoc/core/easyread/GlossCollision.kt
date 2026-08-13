package kr.easydoc.core.easyread

import kr.easydoc.core.text.unicodeRegex
import java.util.regex.Pattern

// # 치환 비문(뜻풀이 축자 삽입) 검출
//
// 원본: `app/easyread/style_rules.py` 의 치환 비문 절.
//
// 사전([DIFFICULT_WORD_REPLACEMENTS])의 오른쪽 값은 '그 자리에 끼워 넣을 치환어'가
// 아니라 **뜻풀이**다(프롬프트도 같은 정의를 쓴다 — "(뜻: ...)" 렌더링). 값이 문장에
// 축자로 끼워지면 "내어 줌 받아"·"뽑음 결과"·"사용 정해진 날짜" 같은 비문이 된다.
// 프롬프트 문구만으로는 확률적으로 재발하므로 기계 검출해 기존 보정 패스로 넘긴다
// (신규 LLM 호출 없음).
//
// ## 설계 원칙 두 가지
//
// 1. **반드시 사전 값 문자열에 앵커링한다.** "~음 받"처럼 형태소로 일반화하면
//    "도움 받으실"·"배움을 원하는"·"모음집" 같은 자연 표현이 오탐된다. 값 문자열은
//    "내어 줌"·"뽑음"처럼 실제 문장에 자연스럽게 등장할 일이 거의 없는 표기라
//    앵커로 쓸 수 있다.
// 2. **완벽한 비문 검출이 목표가 아니다.** 실측(2026-08-09 문서 020)에서 확인된 세
//    유형만 잡는다. 과소 검출은 골든셋 judge 가 보완하지만, 과잉 검출은 게이트 신뢰를
//    무너뜨리고 멀쩡한 문장에 보정을 유발한다.

private const val HANGUL_BASE = 0xAC00
private const val JONGSEONG_COUNT = 28
private const val JONGSEONG_MIEUM = 16

/**
 * 명사형(-ㅁ/-음) 값이지만 낱말로 굳어 자연스럽게 쓰이는 것 — 검출 대상에서 뺀다.
 *
 * 원본: `app/easyread/style_rules.py::LEXICALIZED_GLOSSES`.
 *
 * "알림 문자를 받으세요"·"돌봄 서비스"·"처음 하시는 분"·"이름 하나만"처럼 정상 표현이
 * 오탐되기 때문이다. **사전 값이 바뀌면 이 목록도 함께 갱신해야 한다.**
 */
val LEXICALIZED_GLOSSES: Set<String> =
    setOf(
        "이름",
        "밤",
        "지금",
        "처음",
        "바람",
        "알림",
        "널리 알림",
        "돌봄",
        "맞춤",
        "붙임",
        "따로 붙임",
        "빠짐",
        "같음",
        "지킴",
        // 사고·현상·문법 용어로 굳어 명사처럼 쓰이는 말: "걸림 없이", "깨짐 사고",
        // "겹침 없이", "떨어짐 주의", "무너짐 사고", "높임 표현", "줄임 표현".
        "걸림",
        "깨짐",
        "겹침",
        "떨어짐",
        "무너짐",
        "높임",
        "줄임",
    )

/**
 * 복합어 뒷자리에 자주 쓰이는 키("사용 기한"·"납부 기한"·"신청 기일").
 *
 * 원본: `app/easyread/style_rules.py::COMPOUND_TAIL_KEYS`.
 *
 * 값이 관형구라 앞 낱말과 조사 없이 이어지면 "사용 정해진 날짜"류 비문이 된다.
 */
val COMPOUND_TAIL_KEYS: Set<String> = setOf("기한", "기일", "정액")

/**
 * [COMPOUND_TAIL_KEYS] 가 복합어를 이룰 때 앞자리에 오는 낱말.
 *
 * 원본: `app/easyread/style_rules.py::COMPOUND_HEAD_NOUNS`.
 *
 * **열거가 이 패턴의 주 방어선이다.** "앞에 조사 없는 낱말이 오면 비문"으로 잡으면
 * "매달 정해진 금액"·"미리 정해진 날짜"·"학생에게 정해진 금액"처럼 정상 문장이 무더기로
 * 걸린다(부사·시간명사·다음절 조사). 실제 비문은 원문에 "사용 기한"·"납부 기일" 같은
 * 복합어가 있던 자리에서만 생기므로, 그 앞자리에 실제로 오는 낱말만 열거해 재현율을
 * 거의 잃지 않고 오탐을 없앤다.
 *
 * ## 선정 원칙
 *
 * 1. '기한'·'기일'·'정액' 앞에 **복합어로 붙는 동작성 한자어 명사**만 넣는다
 *    ("신고 기한"·"결제 기한"·"심사 기한"처럼 실제로 쓰이는 복합어여야 한다).
 * 2. **부사·시간명사는 절대 넣지 않는다.** "매달·미리·이미·올해·해마다"가 오탐의
 *    원인이었다 — 이 부류는 관형구를 자연스럽게 앞에서 꾸미므로 넣는 순간 정상 문장이
 *    비문으로 판정된다.
 * 3. 복합어를 이루지 않는 낱말은 재현율을 못 늘리면서 오탐 표면만 넓힌다. 확신이 없으면
 *    넣지 않는다("교육 기한"은 쓰이지 않아 뺐다).
 *
 * 사전 키에서 유도할 수 없다 — 이 낱말들은 대부분 사전에 없는 일반 명사다.
 */
val COMPOUND_HEAD_NOUNS: Set<String> =
    setOf(
        // 신청·접수 절차
        "신청",
        "접수",
        "제출",
        "등록",
        "가입",
        "신고",
        "모집",
        "예약",
        "심사",
        "심의",
        "처리",
        "보완",
        "회신",
        // 돈
        "납부",
        "납입",
        "결제",
        "입금",
        "지급",
        "지원",
        "청구",
        "환불",
        "상환",
        "환급",
        // 증서·자격·계약
        "발급",
        "교부",
        "갱신",
        "연장",
        "변경",
        "취소",
        "계약",
        "이행",
        "적용",
        // 물건·기간
        "사용",
        "이용",
        "보관",
        "반납",
        "반환",
        "판매",
    )

/** 값이 용언의 명사형(-ㅁ/-음)으로 끝나는가 — 마지막 음절의 종성이 ㅁ인지로 판정한다. */
private fun isNominalized(value: String): Boolean {
    val last = value.lastOrNull()
    return last != null &&
        last in '가'..'힣' &&
        (last.code - HANGUL_BASE) % JONGSEONG_COUNT == JONGSEONG_MIEUM
}

/**
 * 검출 대상 명사형 뜻풀이.
 *
 * 파생 규칙이 "종성 ㅁ − [LEXICALIZED_GLOSSES]"라 사전에 -ㅁ 값이 새로 들어오면 자동으로
 * 편입된다 — 그래서 `StyleRuleDataSnapshotTest` 가 이 집합을 스냅샷으로 고정한다
 * (사전 확장 시 반드시 제외 여부를 판단하게 만드는 장치).
 */
val NOMINAL_GLOSSES: Set<String> =
    DIFFICULT_WORD_REPLACEMENTS.values
        .filter { isNominalized(it) && it !in LEXICALIZED_GLOSSES }
        .toSet()

/**
 * 패턴 ②(체언 수식) 대상.
 *
 * 한 낱말짜리만 본다 — 여러 낱말짜리 값은 "해당하는 사람 중"처럼 체언이 뒤따르는 정상
 * 표현이 있다. 다른 뜻풀이의 꼬리인 값도 뺀다: '갱신 → 새로 고침'을 따른 결과가
 * '정정 → 고침'에 걸리면 사전이 자기모순이다.
 */
val MODIFIER_CHECKED_GLOSSES: Set<String> =
    NOMINAL_GLOSSES
        .filter { gloss ->
            !gloss.contains(" ") &&
                DIFFICULT_WORD_REPLACEMENTS.values.none { other -> other != gloss && other.endsWith(gloss) }
        }.toSet()

/**
 * 낱말 사이 공백. 줄바꿈은 제외한다 — 줄이 바뀌면 다른 문장·다른 항목이라
 * ("…신청을 받음\n보조기기 안내") 붙여 읽으면 오탐이 된다. 반대로 연속 공백·NBSP·
 * 전각 공백은 hwpx/pdf 추출본에 흔하고 후처리가 정규화하지 않으므로 모두 받아 준다.
 */
private const val INLINE_SPACE = """[^\S\r\n]"""

/**
 * 명사형 뜻풀이 바로 뒤에 오는 용언 — "내어 줌 받아"의 '받아'. 어미 글자까지 못 박아
 * "이름 하나"("하"+"나")처럼 용언이 아닌 낱말이 걸리지 않게 한다.
 */
private const val LIGHT_VERB_CHAIN = INLINE_SPACE + """*(?:받|하|되|시키)[아어여은는을며면고지도야으기게]"""

/**
 * 뜻풀이도 낱말 시작 위치에서만 센다([findDifficultWords] 와 같은 근사). 앞 글자가
 * 한글이면 더 긴 낱말의 일부다 — "줄바꿈 기준"의 '바꿈'이 걸리면 안 된다.
 */
private const val NOT_AFTER_HANGUL = """(?<![가-힣])"""

/** [COMPOUND_HEAD_NOUNS] 를 정렬해 이어 붙인 교대 패턴 조각. */
private val COMPOUND_HEAD_ALTERNATION: String =
    COMPOUND_HEAD_NOUNS.sorted().joinToString("|") { Pattern.quote(it) }

/**
 * 검출 패턴. **사전에서 유도한다** — 목록을 손으로 복제하면 사전과 기준이 갈라진다.
 *
 * 1. 명사형 뜻풀이 + 용언: "내어 줌 받아"
 * 2. 한 낱말짜리 명사형 뜻풀이 + 체언: "뽑음 결과"
 * 3. 복합어 앞자리 낱말 + 관형구 뜻풀이: "사용 정해진 날짜"
 *
 * 세 묶음의 **순서와 각 묶음 안의 정렬 순서가 [findGlossCollisions] 의 결과에 영향을
 * 준다** — 같은 길이의 매치가 겹칠 때 먼저 온 것이 남기 때문이다. 순서를 바꾸지 않는다.
 */
val GLOSS_COLLISION_PATTERNS: List<Pair<String, Regex>> =
    buildList {
        NOMINAL_GLOSSES.sorted().forEach { gloss ->
            this += gloss to unicodeRegex(NOT_AFTER_HANGUL + Pattern.quote(gloss) + LIGHT_VERB_CHAIN)
        }
        MODIFIER_CHECKED_GLOSSES.sorted().forEach { gloss ->
            this += gloss to unicodeRegex(NOT_AFTER_HANGUL + Pattern.quote(gloss) + INLINE_SPACE + "+[가-힣]")
        }
        COMPOUND_TAIL_KEYS.sorted().forEach { key ->
            val gloss = DIFFICULT_WORD_REPLACEMENTS.getValue(key)
            this += gloss to
                unicodeRegex(
                    NOT_AFTER_HANGUL +
                        "(?:" + COMPOUND_HEAD_ALTERNATION + ")" +
                        INLINE_SPACE + "+" + Pattern.quote(gloss),
                )
        }
    }

/**
 * 뜻풀이가 축자로 끼워져 비문이 된 자리의 뜻풀이 목록.
 *
 * 원본: `app/easyread/style_rules.py::find_gloss_collisions`.
 *
 * 한 자리가 여러 패턴에 걸리면("사용 정해진 날짜"의 '정해진 날'과 '정해진 날짜')
 * **가장 긴 매치 하나만** 남긴다 — 같은 결함을 여러 건으로 세면 보정 채택 판정(위반
 * 건수 비교)이 왜곡된다.
 */
fun findGlossCollisions(text: String): List<String> {
    val matches =
        GLOSS_COLLISION_PATTERNS.flatMap { (gloss, pattern) ->
            pattern.findAll(text).map { Triple(it.range.first, it.range.last + 1, gloss) }.toList()
        }

    val found = mutableListOf<String>()
    val covered = mutableListOf<Pair<Int, Int>>()
    // 긴 매치부터 본다(start - end = -길이) — 짧은 쪽이 안에 들어가면 버린다.
    for ((start, end, gloss) in matches.sortedBy { it.first - it.second }) {
        if (covered.any { (before, after) -> before <= start && end <= after }) continue
        covered += start to end
        found += gloss
    }
    return found
}
