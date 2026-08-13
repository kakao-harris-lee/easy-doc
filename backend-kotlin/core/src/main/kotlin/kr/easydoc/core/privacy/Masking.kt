package kr.easydoc.core.privacy

import kr.easydoc.core.security.Secret
import kr.easydoc.core.text.unicodeRegex

/**
 * 마스킹 대상 개인정보 분류 — 주민등록번호(외국인등록번호 포함)·카드번호 **2종**.
 *
 * 원본: `app/privacy/masking.py::MaskCategory`.
 *
 * [label] 은 표기가 아니라 **복원 키**다. 값이 자리표시자에 그대로 박히고
 * (`[[주민등록번호1]]`), 계약(`contracts/easy-doc-v1.yaml::MaskedItemResponse`)이 이
 * 한국어 문자열을 enum 으로 못박았다 — 영문 코드로 바꾸면 계약 위반이다.
 */
enum class MaskCategory(val label: String) {
    RRN("주민등록번호"),
    CARD("카드번호"),
}

/**
 * 마스킹 파이프라인을 통과한 텍스트.
 *
 * ## 왜 String 이 아닌 별도 타입인가
 *
 * `CLAUDE.md` 아키텍처 규칙 2(보안 불변식): 사용자 문서 텍스트는 마스킹을 통과한
 * 뒤에만 LLM 으로 전달될 수 있다. 이 불변식을 주석이나 런타임 검사로 두면 마스킹을
 * 건너뛰는 새 경로가 생겼을 때 **운영에서 처음 터진다** — 그런 경로는 대개 테스트가
 * 없는 경로다.
 *
 * ## 강제의 실제 범위 (선언과 도달을 일치시킨 기록)
 *
 * 생성자가 `private` 이라 **이 클래스 안에서만** 인스턴스를 만들 수 있고, 클래스가 여는
 * 유일한 통로가 [Companion.mask] 다. 그 함수는 임의 문자열을 감싸지 않고 반드시
 * [maskParts] 를 통과시킨다 — 즉 "감싸기만 하는 통로"가 **존재하지 않는다.**
 *
 * 이전 판은 `internal fun wrap(masked: String)` 이었다. 주석은 "이 파일의 maskText 만
 * 만들 수 있다"고 적혀 있었지만 `internal` 은 **모듈 전체**라서 core 안 아무 파일에서나
 * `MaskedText.wrap(임의_문자열)` 이 컴파일됐다 — 선언한 범위(파일)와 실제 도달 범위
 * (모듈)가 달랐다. Kotlin 에는 "파일 private 생성자"가 없으므로 파일로 좁히는 대신
 * **통로 자체를 없애** 그보다 좁게 만들었다. [Companion.mask] 가 `internal` 인 것은
 * 남은 넓이지만, 그 함수로는 마스킹되지 않은 문자열을 만들 수 없으므로 위험이 아니다.
 *
 * 강제가 미치지 **않는** 범위도 적어 둔다.
 * - **Kotlin 호출자에 한정된다.** `@JvmInline value class` 는 JVM 에서 `String` 으로
 *   지워지므로, Java 호출자는 `MaskedText` 를 받는 함수에 생 `String` 을 넘길 수 있다.
 *   현재 저장소는 전부 Kotlin 이라 실질 위험은 없지만, Java 모듈이 생기면 이 문장이
 *   먼저 깨진다.
 * - 리플렉션·바이트코드 조작은 어떤 가시성으로도 막지 못한다.
 *
 * LLM provider 인터페이스는 아직 없다(다음 조각). 그때 원문 `String` 오버로드를 만들지
 * 않는 것이 이 타입이 존재하는 이유 전부다.
 */
@JvmInline
value class MaskedText private constructor(val value: String) {
    companion object {
        /**
         * [MaskedText] 를 만드는 **유일한** 경로. 임의 문자열을 감쌀 수 없다 —
         * 반드시 마스킹을 수행하고 그 결과만 감싼다.
         *
         * 공개 진입점은 [maskText] 이고 이 함수는 그 구현이다. `internal` 로 열려 있어도
         * 부작용이 없는 이유는 위 KDoc 참고.
         */
        internal fun mask(text: String): MaskingResult {
            val (masked, items) = maskParts(text)
            return MaskingResult(maskedText = MaskedText(masked), items = items)
        }
    }
}

/**
 * 마스킹된 개별 항목 (검수 화면 표시용).
 *
 * [original] 은 [Secret] 으로 감싼다. 데이터 클래스의 기본 `toString()` 이 모든 필드를
 * 그대로 찍기 때문에, 이 감싸기가 없으면 로그 한 줄이 곧 개인정보 유출이다.
 */
data class MaskedItem(
    val category: MaskCategory,
    val placeholder: String,
    val original: Secret,
)

/**
 * 마스킹 결과.
 *
 * [items] 에 원문 개인정보가 담기므로 **API 응답으로 직접 내보내지 않는다.** 외부로
 * 나가는 것은 [maskedText] 뿐이며, 원문-자리표시자 대응은 검수 화면 표시용으로만 쓴다.
 * 담기는 값이 고유식별정보·카드번호로 좁아졌으므로 반출 금지의 강도는 오히려 높다.
 *
 * **복원 성질**: `restorePlaceholders(maskedText.value, items).text` 는 [maskText] 의
 * 입력과 정확히 같다. 이 성질은 [restorePlaceholders] 로만 성립한다 — 자리표시자를
 * 손으로 `replace` 하면 입력에 원래 있던 자리표시자 모양까지 개인정보로 바뀐다.
 */
data class MaskingResult(
    val maskedText: MaskedText,
    val items: List<MaskedItem>,
)

/**
 * [restorePlaceholders] 의 결과.
 *
 * 값 하나(복원된 본문)만 돌려주지 않는 이유: 복원 대상은 **LLM 이 다시 쓴 글**이라
 * 우리가 넣은 자리표시자가 그대로 있다는 보장이 없다. 어긋난 방식이 세 가지이고
 * 각각 처리가 다르므로, 호출부가 판단할 수 있게 사실을 분리해 돌려준다.
 *
 * @property text 복원된 본문.
 * @property missing 우리가 만들었는데 본문에서 **사라진** 자리표시자. 그 자리의 정보가
 *   통째로 빠졌다는 뜻이다(계약의 `missing_placeholders` 와 같은 개념).
 * @property ambiguous 본문에 **두 번 이상** 나타난 우리 자리표시자. 마스킹은 각 자리표시자를
 *   딱 한 번만 넣으므로, 복수 출현은 LLM 이나 검수자가 복제했다는 뜻이다. 어느 쪽이
 *   우리 자리인지 알 수 없으므로 **한 곳도 복원하지 않는다**(사유는 [restorePlaceholders]).
 * @property foreign 자리표시자 모양이지만 우리가 만들지 않은 토큰. 그대로 두었다.
 */
data class PlaceholderRestoration(
    val text: String,
    val missing: List<String>,
    val ambiguous: List<String>,
    val foreign: List<String>,
)

// ── 범주에서 뺀 것: 전화번호·이메일·계좌번호 (2026-08-12, master-plan 3.2) ──────────
//
// **이 셋은 문서에 섞여 들어오면 마스킹 없이 그대로 LLM(국외 포함)으로 전송된다.**
// 나중에 채워 넣으려고 비워 둔 자리가 아니라, 명시적으로 감수하기로 한 대가다.
//
// 축소 근거: 주 용도가 공공기관 안내문 같은 공용 배포 문서의 변환이라 실제 개인정보
// 유입 확률이 낮다고 보고, 런타임 교체 국면에서 포팅·검증 비용을 줄여 개발 속도를 택했다.
// 다만 "확률이 낮다"는 것은 확률에 대한 판단이지 유입 가능성이 없다는 뜻이 아니다.
//
// "누락보다 과잉 마스킹이 안전하다"는 옛 원칙은 남은 2종에만 적용된다. 뺀 3종을 다시
// 잡도록 패턴을 넓히면 그것은 개선이 아니라 **정책 위반**이다.
//
// 재확대 조건(master-plan 3.2): 파일럿·운영에서 이 셋의 실제 유입이 확인되거나
// B2G 계약·CSAP 심사에서 범주가 문제되면 즉시 재확대한다. 문서만 넓게 적고 구현을
// 두는 방식은 금지다.
// ──────────────────────────────────────────────────────────────────────────────────

/**
 * 우선순위 순서 — 먼저 매칭된 구간이 이후 패턴보다 우선한다.
 *
 * 원본: `app/privacy/masking.py::_PATTERNS`.
 *
 * RRN 성별코드는 `[1-8]`: 5~8 은 외국인등록번호(고유식별정보)다. 구분자 없는 표기도 덮는다.
 * RRN 이 CARD 보다 앞이다 — 13자리와 16자리는 lookaround 로 갈리지만, 겹치는 표기가
 * 생기면 더 민감한 고유식별정보 쪽으로 판정되는 편이 안전하다.
 */
private val PATTERNS: List<Pair<MaskCategory, Regex>> =
    listOf(
        MaskCategory.RRN to unicodeRegex("""(?<!\d)\d{6}[ \t]*-?[ \t]*[1-8]\d{6}(?!\d)"""),
        MaskCategory.CARD to unicodeRegex("""(?<!\d)\d{4}[- ]?\d{4}[- ]?\d{4}[- ]?\d{4}(?!\d)"""),
    )

// ── 자리표시자 충돌: 입력에 이미 자리표시자 모양이 있을 때 ──────────────────────────
//
// 입력 `앞 [[주민등록번호1]] 뒤 900101-1234567 끝` 을 그대로 마스킹하면 결과에 같은
// 자리표시자가 **둘** 생긴다. 되돌리면 원문에 원래 있던 글자까지 주민등록번호로 바뀐다 —
// "자리표시자를 되돌리면 입력이 정확히 복원된다"가 거짓이 된다. Python 원본에도 있는
// 결함이지만 기준은 Python 출력이 아니라 요구사항이므로(master-plan 6.2) 여기서 고친다.
//
// 고른 방법: **마스킹 전에 입력의 자리표시자 모양을 탈출(escape)시킨다.** 그러면 마스킹이
// 끝난 본문에 자리표시자 형태로 남는 것은 우리가 넣은 것뿐이라 충돌이 만들어질 수 없다
// ("검사를 얹는 것보다 만들 수 없게 하는 쪽").
//
// 택하지 않은 두 갈래와 이유:
//
// 1. **자리표시자를 입력에 존재할 수 없는 형태로 바꾼다** (난수 접미사 등) — 계약이 형태를
//    못박아 닫혀 있다. `easy-doc-v1.yaml::MaskedItemResponse.placeholder` 와
//    `ConversionResponse.missing_placeholders` 가 둘 다
//    `^\[\[(주민등록번호|카드번호)[0-9]+\]\]$` 이고, React 검수 화면이 이 문자열을 그대로
//    렌더링한다. 계약을 바꾸는 것은 contract-keeper 의 일이고, 구현이 계약을 못 맞추면
//    고칠 것은 계약이 아니라 구현이다. 탈출은 **이 갈래가 노리던 성질(충돌 불가능)을
//    계약 형태를 건드리지 않고** 얻는다.
// 2. **충돌을 탐지해 다른 번호를 쓴다** — 계약 설명("n 은 범주별 1부터의 일련번호")과
//    parity 게이트 `check_placeholder_scheme`(번호는 등장 순서로 1,2,3…) 양쪽에 걸린다.
//    게다가 LLM 이 만들어 내는 경로는 닫지 못한다.
//
// 탈출 표기는 `[[` 뒤에 `!` 를 하나 넣는 것이다(`[[주민등록번호1]]` → `[[!주민등록번호1]]`).
// 이미 `!` 가 붙은 모양도 한 겹 더 씌워 되돌릴 수 있게 한다(`[[!X]]` → `[[!!X]]`) — 탈출
// 문자를 탈출하지 않으면 입력에 `[[!주민등록번호1]]` 이 있을 때 복원이 어긋난다.
// 탈출된 토큰은 계약 패턴과 겹치지 않으므로 자리표시자로 오인되지 않는다.
//
// 대가: 이런 입력에서는 마스킹 결과가 입력과 한 글자 달라지고(그 `!`), LLM 과 검수 화면에
// 그대로 보인다. [restorePlaceholders] 가 내보내기 시점에 되돌린다.
// ──────────────────────────────────────────────────────────────────────────────────

/**
 * 계약이 못박은 자리표시자 형태(`^\[\[(주민등록번호|카드번호)[0-9]+\]\]$`)를 범주 enum 에서 만든다.
 *
 * 문자열을 손으로 두 벌 적지 않는 이유: 범주가 바뀌면(재확대 조건, master-plan 3.2)
 * 탈출 대상과 자리표시자가 **같이** 움직여야 한다. 한쪽만 늘면 그 순간 충돌이 되살아난다.
 */
private val CATEGORY_ALTERNATION: String =
    MaskCategory.entries.joinToString(separator = "|") { Regex.escape(it.label) }

/**
 * 자리표시자 토큰. `unicodeRegex` 를 쓰지 않는다 — 축약 클래스가 없고 번호 자리는 계약이
 * `[0-9]` 로 못박았다. 전각 숫자(`１`)는 우리가 만드는 자리표시자와 문자열이 다르므로
 * 충돌 대상이 아니다.
 */
private val PLACEHOLDER: Regex = Regex("""\[\[(?:$CATEGORY_ALTERNATION)[0-9]+]]""")

/** 자리표시자 모양 + 이미 탈출된 모양. 탈출은 이 둘 모두에 한 겹을 더한다. */
private val PLACEHOLDER_LOOKALIKE: Regex = Regex("""\[\[!*(?:$CATEGORY_ALTERNATION)[0-9]+]]""")

/** 탈출된 모양만. 복원 끝에서 한 겹을 벗긴다. */
private val ESCAPED_LOOKALIKE: Regex = Regex("""\[\[!+(?:$CATEGORY_ALTERNATION)[0-9]+]]""")

/**
 * 입력에 있던 자리표시자 모양에 탈출 한 겹을 씌운다.
 *
 * 마스킹 판정에는 영향이 없다 — `!` 는 `[[` 와 한글 라벨 사이에만 들어가는데, 주민등록번호·
 * 카드번호 패턴은 숫자와 `-`·공백만 잇기 때문에 어떤 매치도 그 자리를 가로지르지 않는다.
 * 즉 탈출은 매치를 새로 만들지도, 깨지도 않는다.
 */
private fun escapeLookalikes(text: String): String =
    PLACEHOLDER_LOOKALIKE.replace(text) { match -> "[[!" + match.value.removePrefix("[[") }

/** [escapeLookalikes] 의 역. 한 겹만 벗긴다. */
private fun unescapeLookalikes(text: String): String =
    ESCAPED_LOOKALIKE.replace(text) { match -> "[[" + match.value.removePrefix("[[!") }

// ── 보이지 않는 문자로 인한 마스킹 회피 차단 ────────────────────────────────────────
//
// 숫자 사이에 폭 없는 문자나 제어문자가 끼면 위 정규식이 그대로 뚫린다
// (`docs/migration/_workspace/02_privacy-gate_control-char-verdict.md`).
// 악의적 회피가 아니라 **사고성 유입**이 주 경로다 — 실제 정부 문서 코퍼스에서
// 소프트하이픈(U+00AD)·NUL 이 하이픈 자리를 대신하고 있는 사례가 실측됐고, PDF 추출과
// JSON 붙여넣기 경로에는 이를 걸러 주는 것이 아무것도 없다. 피해자는 문서에 등장하는
// 제3자 시민이고, 누락은 조용해서 담당자도 알아채지 못한다.
//
// **파이프라인 앞단에 정규화 단계를 넣지 않는다.** 입력 자체를 정규화해 넘기면 복원이
// 깨져 내보내기가 잘못된 원문을 꽂는다. 대신 [maskParts] **내부**에서 탐색용 뷰를 만들어
// 거기서 찾고, 자르기는 원문 좌표로 한다 — 파이프라인 순서를 건드리지 않으므로
// 마스킹 선행 불변식도 그대로다.
//
// 대상은 "제어문자"가 아니라 **숫자 사이에서 보이지 않는 것 전체**다. 탭·개행·일반 공백은
// 뺀다 — 정규식이 `[ \t]` 로 이미 정식 처리하고, 개행까지 접으면 서로 다른 줄의 숫자열이
// 붙어 진짜 과잉 마스킹이 된다.
// ──────────────────────────────────────────────────────────────────────────────────

/** 원본: `app/privacy/masking.py::_INVISIBLE_RANGES`. */
private val INVISIBLE_RANGES: List<IntRange> =
    listOf(
        0x0000..0x0008, // C0 (탭 U+0009·개행 U+000A 제외)
        0x000B..0x000C,
        0x000E..0x001F, // 캐리지리턴 U+000D 제외
        0x007F..0x007F, // DEL
        0x00AD..0x00AD, // 소프트하이픈 — 실문서에서 실제로 검출된 것
        0x200B..0x200F, // 폭 없는 공백·비연결자·방향 표시
        0x202A..0x202E, // 방향 재정의
        0x2060..0x2060, // word joiner
        0xFEFF..0xFEFF, // BOM / zero-width no-break space
    )

private val INVISIBLE: Set<Char> =
    INVISIBLE_RANGES
        .flatMap { range -> range.map { it.toChar() } }
        .toSet()

private val INVISIBLE_RE: Regex =
    Regex(
        INVISIBLE_RANGES.joinToString(
            separator = "",
            prefix = "[",
            postfix = "]",
        ) { "\\u%04x-\\u%04x".format(it.first, it.last) },
    )

/**
 * 보이지 않는 문자를 뺀 탐색용 뷰와 `뷰 인덱스 → 원문 인덱스` 대응표를 만든다.
 *
 * 뺄 것이 없으면 대응표를 만들지 않고 `null` 을 돌려준다 — 흔한 경우의 비용을 없애고,
 * 호출부가 "뷰 경로를 탈 필요가 있는가"를 그 값으로 판정한다.
 */
private fun searchView(text: String): Pair<String, IntArray?> {
    val view = INVISIBLE_RE.replace(text, "")
    if (view.length == text.length) return text to null

    val offsets = IntArray(view.length)
    var cursor = 0
    text.forEachIndexed { index, char ->
        if (char !in INVISIBLE) {
            offsets[cursor] = index
            cursor++
        }
    }
    return view to offsets
}

/**
 * 원문 직접 매칭 + 뷰 매칭(원문 좌표로 환원)의 **합집합**을 돌려준다.
 *
 * 합집합인 이유: 뷰만 쓰면 지금 잡히는 것을 놓칠 수 있다. 예컨대 `1<ZWSP>900101-1234567` 은
 * 원문에서 `(?<!\d)` 가 성립해 매칭되지만, 뷰에서는 앞 숫자가 붙어 버려 성립하지 않는다.
 * 합집합은 현행 적중을 보존한 채 회피 경로만 더한다.
 *
 * 스팬은 시작 오름차순, 같은 시작이면 **긴 것 우선**으로 준다 — 낀 문자를 포함한 넓은
 * 쪽이 이겨야 원문에 조각이 남지 않는다.
 *
 * @return `(시작, 끝 배타)` 쌍의 목록.
 */
private fun candidateSpans(
    pattern: Regex,
    text: String,
    view: String,
    offsets: IntArray?,
): List<Pair<Int, Int>> {
    val spans = LinkedHashSet<Pair<Int, Int>>()
    pattern.findAll(text).forEach { spans += it.range.first to it.range.last + 1 }

    if (offsets != null) {
        pattern.findAll(view).forEach { match ->
            // 끝 좌표는 마지막으로 **매칭된 문자**의 원문 인덱스 + 1 이다. offsets[end] 를
            // 쓰면 매치 뒤에 붙은 보이지 않는 문자까지 삼켜 경계가 과잉 잠식된다.
            spans += offsets[match.range.first] to offsets[match.range.last] + 1
        }
    }
    return spans.sortedWith(compareBy({ it.first }, { -it.second }))
}

/**
 * 마스킹의 실제 구현. 문자열만 다루고 [MaskedText] 를 만들지 않는다 —
 * 만드는 것은 [MaskedText.Companion.mask] 하나뿐이다(생성 통로를 하나로 묶는 이유는
 * [MaskedText] KDoc).
 *
 * 원본: `app/privacy/masking.py::mask_text`.
 *
 * 탐색은 보이지 않는 문자를 뺀 뷰에서도 함께 하지만([candidateSpans]), 잘라내는 것은
 * 언제나 원문이다. 따라서 [MaskedItem.original] 에는 낀 문자가 그대로 들어간다.
 *
 * @return `(마스킹된 본문, 항목 목록)`.
 */
private fun maskParts(text: String): Pair<String, List<MaskedItem>> {
    // 입력에 있던 자리표시자 모양을 먼저 치운다. 이 뒤로 본문에 자리표시자 형태로 남는
    // 것은 우리가 넣은 것뿐이므로 같은 토큰이 둘이 되는 상황 자체가 만들어지지 않는다.
    val source = escapeLookalikes(text)
    val (view, offsets) = searchView(source)

    val spans = mutableListOf<Triple<Int, Int, MaskCategory>>()
    for ((category, pattern) in PATTERNS) {
        for ((start, end) in candidateSpans(pattern, source, view, offsets)) {
            // 이미 다른 패턴이 차지한 구간이면 건너뛴다.
            if (spans.any { (taken, takenEnd, _) -> start < takenEnd && taken < end }) continue
            spans += Triple(start, end, category)
        }
    }
    spans.sortWith(compareBy({ it.first }, { it.second }))

    val counters = mutableMapOf<MaskCategory, Int>()
    val items = mutableListOf<MaskedItem>()
    val masked = StringBuilder()
    var cursor = 0

    for ((start, end, category) in spans) {
        val ordinal = (counters[category] ?: 0) + 1
        counters[category] = ordinal
        val placeholder = "[[${category.label}$ordinal]]"

        items +=
            MaskedItem(
                category = category,
                placeholder = placeholder,
                original = Secret(source.substring(start, end)),
            )
        masked.append(source, cursor, start).append(placeholder)
        cursor = end
    }
    masked.append(source, cursor, source.length)

    return masked.toString() to items.toList()
}

/**
 * 우선순위 패턴 순서로 개인정보를 찾아 자리표시자로 치환한다.
 *
 * 원본: `app/privacy/masking.py::mask_text`.
 *
 * [restorePlaceholders] 로 되돌리면 입력이 **정확히** 복원된다. 자리표시자 모양이 입력에
 * 이미 있었다면 탈출 표기로 바뀌어 나가고(`[[주민등록번호1]]` → `[[!주민등록번호1]]`),
 * 복원 때 되돌려진다 — 사유는 위 "자리표시자 충돌" 절.
 */
fun maskText(text: String): MaskingResult = MaskedText.mask(text)

/**
 * 자리표시자를 원문 값으로 되돌린다 (**내보내기 전용**).
 *
 * 원본: `app/easyread/export.py::restore_placeholders`. 원본은 내보내기 모듈에 있지만
 * 이쪽으로 옮겼다. **근거**: 이 함수가 지키는 성질(정확 복원)을 만든 것은 마스킹이고,
 * 성립 조건도 마스킹 쪽에 있다 — 자리표시자 형태, 탈출 표기, "각 자리표시자는 딱 한 번만
 * 넣는다"가 전부 이 파일의 결정이다. 두 곳에 나눠 두면 한쪽만 바뀌어 성질이 조용히
 * 깨진다. 실제로 Python 은 나뉘어 있었고, 마스킹이 보장하지 않는 것(입력에 이미 있던
 * 자리표시자)을 내보내기가 보장한다고 가정해 이 결함이 생겼다. 내보내기 조각(Phase 4)은
 * 이 함수를 부른다.
 *
 * **호출 경로 규칙은 원본 그대로다** — 복원본을 만드는 경로는 내보내기 하나뿐이다.
 * 조회 응답·목록·로그에서 부르지 않는다.
 *
 * ## 우리가 만들지 않은 자리표시자를 어떻게 다루는가
 *
 * 복원 대상은 **LLM 이 다시 쓴 글**이다. 자리표시자 모양은 모델이 얼마든지 만들어 낼 수
 * 있고, 그 자리에 진짜 주민등록번호를 꽂으면 마스킹의 목적이 정면으로 뒤집힌다. 그래서
 * 개수로 판정한다 — 마스킹은 각 자리표시자를 **정확히 한 번** 넣으므로, 개수가 1이 아닌
 * 것은 우리가 만든 본문이 아니다.
 *
 * - **정확히 1회** → 복원한다. (모델이 문장을 다시 써서 자리가 옮겨간 것은 정상 경로다.)
 * - **0회** ([PlaceholderRestoration.missing]) → 복원할 것이 없다. 그 자리의 정보가 통째로
 *   빠진 것이므로 호출부가 검수를 요구한다(계약의 409 경로).
 * - **2회 이상** ([PlaceholderRestoration.ambiguous]) → **한 곳도 복원하지 않는다.** 어느
 *   것이 우리 자리인지 판정할 근거가 없는데 전부 채우면 개인정보를 모델이 고른 자리에
 *   심는 것이고, 하나만 채우면 그 하나를 고른 근거가 없다. 남는 것은 라벨뿐이라
 *   개인정보가 아니다(계약: `missing_placeholders` 는 라벨이라 개인정보가 아니다).
 * - **우리 목록에 없는 자리표시자** ([PlaceholderRestoration.foreign]) → 그대로 둔다.
 *   우리가 만들지 않은 표기를 지워 본문을 조용히 훼손하지 않는다(원본과 같은 판단).
 *
 * 마지막에 탈출 표기를 한 겹 벗긴다. 치환 뒤에 한 번 더 훑는 이유는 마스킹이 탈출된
 * 토큰 **안쪽**의 숫자를 가릴 수 있기 때문이다(`[[!주민등록번호1234567890123]]`).
 * 그 경우 자리표시자를 되돌려야 비로소 탈출된 토큰이 온전한 모양이 된다.
 *
 * @param text 자리표시자가 남아 있는 변환 결과(또는 검수 수정본).
 * @param items [maskText] 가 낸 항목 목록.
 */
fun restorePlaceholders(
    text: String,
    items: List<MaskedItem>,
): PlaceholderRestoration {
    val originals = items.associate { it.placeholder to it.original }
    val found = PLACEHOLDER.findAll(text).map { it.value }.toList()
    val occurrences = found.groupingBy { it }.eachCount()

    val restored =
        PLACEHOLDER.replace(text) { match ->
            val original = originals[match.value]
            if (original != null && occurrences[match.value] == 1) original.reveal() else match.value
        }

    return PlaceholderRestoration(
        text = unescapeLookalikes(restored),
        missing = items.map { it.placeholder }.filter { occurrences[it] == null },
        ambiguous = items.map { it.placeholder }.filter { (occurrences[it] ?: 0) > 1 },
        foreign = found.filterNot { it in originals }.distinct(),
    )
}
