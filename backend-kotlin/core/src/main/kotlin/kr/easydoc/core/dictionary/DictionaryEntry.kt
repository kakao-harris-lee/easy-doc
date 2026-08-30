package kr.easydoc.core.dictionary

import kr.easydoc.core.privacy.CONTENT_MASK
import kr.easydoc.core.privacy.UserContent

// easy-dictionary 색인의 도메인 타입 — 정본은 `dictionary/DESIGN.md` §3.2·§4.3 이다.
//
// **이 패키지는 JSON 을 모른다.** core 본 소스에는 JSON 라이브러리가 없고(core/build.gradle.kts
// 주석이 그 경계를 정한다), `easy_dict.index.json` 을 읽어 아래 타입으로 옮기는 일은
// infrastructure 어댑터 몫이다. 그래서 여기에는 색인 파일의 축약 키(`t`/`e`/`d`/`s`/`r`/`p`/
// `g`/`c`/`x`)가 없다 — 축약은 wire 표현이고 도메인 이름은 풀어 쓴다. 어댑터가 두 이름을
// 잇는 자리에서 쓰라고 [ReplaceStrategy.ofWire]·[RiskLevel.ofWire] 만 공개한다.

/**
 * 치환 전략 (§3.2 `replace_strategy`). **문자열이 아니라 값이다** — 프롬프트 구역을 가르는
 * 기준이라 오타 하나가 "절대 바꾸지 마세요" 항목을 "바꿔 쓰세요"로 보내는 사고가 된다.
 */
enum class ReplaceStrategy(val wire: String) {
    /** 원어를 지우고 쉬운 말로 바꾼다 — "지워도 안전하다"고 검수된 항목. */
    SUBSTITUTE("substitute"),

    /** 원어는 남기고 뜻을 덧붙인다. */
    GLOSS("gloss"),

    /** 법령명·제도명처럼 손대면 안 되는 항목. */
    KEEP("keep"),
    ;

    companion object {
        /** 색인 파일의 `s` 값을 전략으로 옮긴다. 모르는 값은 조용히 넘기지 않고 즉시 거절한다. */
        fun ofWire(wire: String): ReplaceStrategy =
            entries.firstOrNull { it.wire == wire }
                ?: throw IllegalArgumentException("알 수 없는 치환 전략: $wire")
    }
}

/**
 * 위험도 (§3.2 `risk_level`).
 *
 * [weight] 는 프롬프트 예산이 모자랄 때의 **잘림 순서**다(§7.2). 이 숫자를 바꾸면 어떤 용어가
 * 먼저 사라지는지가 바뀌므로, 순서 자체를 여기 한 곳에 둔다.
 */
enum class RiskLevel(
    val wire: String,
    val weight: Int,
) {
    /** 오변환 피해가 크다 — 전략과 무관하게 최대 상세도로 싣는다. */
    HIGH("high", 2),

    /** 주의는 필요하지만 치명적이지는 않다. */
    LOW("low", 1),

    /** 검수에서 위험 신호가 없었다. */
    NONE("none", 0),
    ;

    companion object {
        /** 색인 파일의 `r` 값을 위험도로 옮긴다. */
        fun ofWire(wire: String): RiskLevel =
            entries.firstOrNull { it.wire == wire }
                ?: throw IllegalArgumentException("알 수 없는 위험도: $wire")
    }
}

/**
 * 변환 전후 예문 한 쌍 (§3.1 `examples`).
 *
 * 프롬프트에서 예문은 **지시문보다 강한 신호**다(§7.2.2). 그래서 [isGolden](사람 검수 완료)이
 * 정렬 우선순위를 갖는다 — 검수되지 않은 예문이 few-shot 자리를 먼저 차지하면 지시문이 진다.
 */
data class DictionaryExample(
    val before: String,
    val after: String,
    val isGolden: Boolean,
)

/**
 * 사전 엔트리 한 건 (§3.2).
 *
 * `entry_id` 는 여기 없다 — 색인이 `id -> 엔트리` 맵으로 들고 있고([DictionaryIndex.of]),
 * 매칭 결과가 [DictionaryMatch.entryId] 로 되돌려 준다. 엔트리가 자기 id 를 또 들면 두 곳이
 * 어긋날 수 있는 자리가 하나 생긴다.
 *
 * [definition]·[caution]·[tags]·[examples] 만 기본값을 갖는다 — 색인에서 실제로 비어 있을 수
 * 있는 필드들이다. 나머지는 어댑터가 반드시 채우게 둔다.
 */
data class DictionaryEntry(
    val term: String,
    val easyTerm: String,
    val strategy: ReplaceStrategy,
    val risk: RiskLevel,
    val priority: Int,
    val definition: String? = null,
    val caution: String? = null,
    val tags: List<String> = emptyList(),
    val examples: List<DictionaryExample> = emptyList(),
)

/**
 * 텍스트 한 구간에서 발견된 용어 매칭 (§6.5).
 *
 * [surface] 는 **문서에 실제로 쓰인 형태**이고 `entry.term` 은 표제어 원형이다. 둘이 다르면
 * 활용형·띄어쓰기 변형으로 걸린 것이다([isInflected]).
 *
 * [start]·[end] 는 UTF-16 코드 단위 인덱스라 `text.substring(start, end)` 가 그대로 [surface]
 * 다. 참조 구현(파이썬)은 코드 포인트로 세지만, 경계 판정이 보는 문자(한글 음절·로마자·숫자·
 * 공백·문장부호)가 전부 BMP 라 판정 결과는 두 단위에서 같다 — 서로게이트 반쪽은 한글 음절도
 * 로마자도 아니어서 파이썬이 보는 보충 평면 문자 하나와 같은 판정을 받는다.
 *
 * ## `@UserContent` 를 붙이는 이유
 *
 * [surface] 는 **사용자 문서에서 잘라낸 조각**인데, 이름이 `SensitiveToStringReachTest` 의
 * 민감 이름 토큰(`text`·`body`·`content`…) 중 무엇에도 걸리지 않는다. 그 게이트는 이름
 * 휴리스틱이라, 어노테이션이 없으면 이 타입은 「통과」가 아니라 **아예 검사 대상이 아니다**.
 * `UserContent` KDoc 이 "필드 **이름**만 봐서는 드러나지 않는 자리에 붙인다"고 정한 것이
 * 정확히 이 자리이고, [RepairPrompt] 도 같은 사유로 붙어 있다.
 *
 * 오늘 유출 경로는 없다 — core 에는 로거가 없고, 이 타입은 `kr.easydoc.core.dictionary` 와
 * 주입 어댑터 밖으로 나가지 않으며, 어댑터는 개수만 로그에 남긴다. 그러나 그 안전은 **아무도
 * 매치를 로깅하지 않아서**일 뿐이고 다음 사람이 한 줄 더하면 사라진다. 게이트가 그 한 줄을
 * 잡게 하는 것이 이 어노테이션의 값어치다.
 */
@UserContent
data class DictionaryMatch(
    val start: Int,
    val end: Int,
    val surface: String,
    val entryId: Int,
    val entry: DictionaryEntry,
) {
    /**
     * 표제어 원형이 아니라 변형형으로 걸렸는가.
     *
     * 이번 릴리스의 프롬프트 컨텍스트는 이 값을 쓰지 않는다 — §6.6 이 "LLM 이 활용을 처리하므로
     * 표제어 원형으로 표기한다"로 정해 뒀기 때문이다. 치환 엔진(`annotate`)을 이식할 때
     * 활용형에 원형을 끼워 넣어 비문을 만드는 사고를 막는 신호가 이 값이다.
     */
    val isInflected: Boolean get() = surface != entry.term

    /**
     * **[surface] 만 가린다.** [start]·[end]·[entryId] 는 위치와 식별자라 문서 내용이 아니고,
     * 진단에 실제로 쓸모가 있다 — 전부 지우면 「어느 위치의 어떤 엔트리였나」를 잃는다.
     * [entry] 는 [entryId] 로 언제든 되짚을 수 있으므로 찍지 않는다.
     */
    override fun toString(): String = "DictionaryMatch(start=$start, end=$end, surface=$CONTENT_MASK, entryId=$entryId)"
}
