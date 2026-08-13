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
 * 생성자를 private 으로 막고 이 파일의 [maskText] 만 만들 수 있게 해 두면, LLM 호출부가
 * `MaskedText` 를 요구하는 것만으로 우회 경로가 **컴파일되지 않는다**. 검토자가 놓쳐도,
 * 테스트가 없어도 병합될 수 없다. 보안 불변식은 실패 시점을 앞으로 당길수록 값이 있다.
 *
 * LLM provider 인터페이스는 아직 없다(다음 조각). 그때 원문 `String` 오버로드를 만들지
 * 않는 것이 이 타입이 존재하는 이유 전부다.
 */
@JvmInline
value class MaskedText private constructor(val value: String) {
    internal companion object {
        /** 마스킹 파이프라인 전용 생성 통로. `internal` 이라 core 모듈 밖에서는 부를 수 없다. */
        fun wrap(masked: String): MaskedText = MaskedText(masked)
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
 */
data class MaskingResult(
    val maskedText: MaskedText,
    val items: List<MaskedItem>,
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
// 깨져 내보내기가 잘못된 원문을 꽂는다. 대신 [maskText] **내부**에서 탐색용 뷰를 만들어
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
 * 우선순위 패턴 순서로 개인정보를 찾아 자리표시자로 치환한다.
 *
 * 원본: `app/privacy/masking.py::mask_text`.
 *
 * 탐색은 보이지 않는 문자를 뺀 뷰에서도 함께 하지만([candidateSpans]), 잘라내는 것은
 * 언제나 원문이다. 따라서 [MaskedItem.original] 에는 낀 문자가 그대로 들어가고,
 * 자리표시자를 되돌리면 입력이 정확히 복원된다.
 */
fun maskText(text: String): MaskingResult {
    val (view, offsets) = searchView(text)

    val spans = mutableListOf<Triple<Int, Int, MaskCategory>>()
    for ((category, pattern) in PATTERNS) {
        for ((start, end) in candidateSpans(pattern, text, view, offsets)) {
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
                original = Secret(text.substring(start, end)),
            )
        masked.append(text, cursor, start).append(placeholder)
        cursor = end
    }
    masked.append(text, cursor, text.length)

    return MaskingResult(maskedText = MaskedText.wrap(masked.toString()), items = items)
}
