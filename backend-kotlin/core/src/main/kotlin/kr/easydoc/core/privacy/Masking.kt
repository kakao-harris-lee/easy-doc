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
 *
 * `toString()` 재정의 사유는 아래 「value class 와 toString」 절.
 */
@JvmInline
value class MaskedText private constructor(val value: String) {
    /** 길이만 남긴다. 사유는 아래 「value class 와 toString」 절. */
    override fun toString(): String = "MaskedText(${value.length}자)"

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

// ── value class 와 toString (privacy-gate 판정 5 / §4-bis, 2026-08-14) ─────────────
//
// 이 파일의 `@JvmInline value class` 셋([MaskedText]·[ModelDraft]·[ReviewedBody])은 전부
// **문서 본문**을 감싼다. 셋 다 `toString()` 을 재정의해 **길이만** 남긴다.
//
// **왜 이 규율이 여기까지 오지 못했었나.** 일반 class·data class 에는 이미 같은 규율이
// 적용돼 있었다 — `LlmPrompt` 는 `data class` 를 포기하면서까지 KDoc 한 절로 사유를 적었고
// (`LlmPrompt.kt` 「data class 가 아닌 이유」), `LlmCompletion`·`Secret`·[PlaceholderRestoration]
// 도 각각 재정의를 갖고 있다. **그런데 value class 셋에는 한 번도 적용되지 않았다.**
// 결함이 한 건이 아니라 **종류**인 이유가 이것이다 — 다음에 본문을 감싸는 래퍼를 만드는
// 사람도 같은 자리를 빠뜨린다. 그래서 사유를 타입 하나가 아니라 이 절에 둔다.
//
// **본문은 개인정보와 별개로 금지 대상이다.** `CLAUDE.md` 보안 규칙은 *"로그에 문서 본문·
// 개인정보를 절대 남기지 않는다. 로깅은 문서 ID·길이·처리 상태까지만"* 이라고 **둘**을 열거하고
// 뒷문장을 허용목록으로 못박았다. 개인정보가 한 글자도 없어도 본문은 금지다.
//
// **"마스킹했으니 안전하다"는 성립하지 않는다.** 가려지는 것은 주민등록번호·카드번호 2종뿐이고
// 전화번호·이메일·계좌번호는 그대로 남는다. 그 셋은 **LLM 전송을 감수한 것이지 로그 적재를
// 감수한 것이 아니다** — 범주 축소는 전송 경계의 결정이었고 로그 경계를 건드리지 않았다.
//
// **실측(privacy-gate)**: 재정의는 문자열 보간 · 명시 호출 · `Any` 인자(로거) · 컬렉션
// 네 경로에서 모두 듣는다. 박싱되는 로거 경로에서도 듣는다.
//
// **못 막는 것**: `.value` 를 직접 꺼내 넘기는 줄. 공개 프로퍼티라 타입으로는 닫히지 않는다 —
// 그쪽 절반은 `scan_privacy_invariants.py` 의 `LOG-BODY` 규칙이 식별자 이름으로 잡는다
// (그래서 그 `BODY_NAMES` 에 `draft`·`modelDraft`·`reviewed` 를 함께 넣었다).
// ──────────────────────────────────────────────────────────────────────────────────

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
 * **복원 성질**: 사람 검수를 거친 본문이라면 [restoreForExport] 가 [maskText] 의 입력을
 * 한 글자도 다르지 않게 되돌린다. 이 성질은 [restoreForExport] 로만 성립한다 —
 * 자리표시자를 손으로 `replace` 하면 입력에 원래 있던 자리표시자 모양까지 개인정보로 바뀐다.
 * 검수를 거치지 않은 본문에서는 이 성질이 **일부러** 성립하지 않는다(사유는
 * [restoreForExport] 앞의 "검수를 거치지 않은 본문" 절).
 */
data class MaskingResult(
    val maskedText: MaskedText,
    val items: List<MaskedItem>,
) {
    /**
     * 길이·건수만 남긴다. 사유는 아래 「value class 와 toString」 절과 **같지만**,
     * 이 타입만은 이유가 하나 더 있다.
     *
     * 두 필드는 각각 이미 안전하다 — [MaskedText.toString] 은 길이만 남기고
     * [MaskedItem.original] 은 [Secret] 이다. 그래서 재정의가 없어도 지금 당장은 평문이
     * 새지 않는다. **그러나 그것은 전이(轉移) 안전이지 이 타입의 성질이 아니다** —
     * 여기에 본문 필드를 하나 더하거나 [MaskedItem] 에 평문 필드를 더하는 순간 조용히 샌다.
     * 형제 타입 다섯이 전부 명시적으로 가려진 상태에서 이 하나만 **남의 안전에 얹혀 있었다**
     * (privacy-gate 판정 §4-quinquies).
     */
    override fun toString(): String = "MaskingResult(maskedText=${maskedText.value.length}자, items=${items.size})"
}

/**
 * [restoreForExport] 의 결과.
 *
 * 값 하나(복원된 본문)만 돌려주지 않는 이유: 복원 대상은 **LLM 이 다시 쓴 글**이라
 * 우리가 넣은 자리표시자가 그대로 있다는 보장이 없다. 어긋난 방식이 네 가지이고
 * 각각 처리가 다르므로, 호출부가 판단할 수 있게 사실을 분리해 돌려준다.
 *
 * @property text 최종 본문. 검수본이 없으면 복원하지 않은 초안이다.
 * @property missing 우리가 만들었는데 본문에서 **사라진** 자리표시자. 그 자리의 정보가
 *   통째로 빠졌다는 뜻이다(계약의 `missing_placeholders` 와 같은 개념).
 * @property ambiguous 본문에 **두 번 이상** 나타난 우리 자리표시자. 마스킹은 각 자리표시자를
 *   딱 한 번만 넣으므로, 복수 출현은 LLM 이나 검수자가 복제했다는 뜻이다. 어느 쪽이
 *   우리 자리인지 알 수 없으므로 **한 곳도 복원하지 않는다**(사유는 [restoreForExport]).
 * @property foreign 자리표시자 모양이지만 우리가 만들지 않은 토큰. 그대로 두었다.
 * @property withheld **검수본이 없어서** 복원을 보류한 자리표시자. 검수를 거쳤다면 값이
 *   들어갔을 자리다(개수 판정을 통과한 것들). 검수본이 있으면 항상 빈 목록이다.
 *   비어 있지 않다는 것은 "내보낼 문서에 `[[주민등록번호1]]` 이 글자 그대로 남는다"는
 *   뜻이므로, 그대로 내보낼지 막을지는 application 이 정한다(사유는 [restoreForExport]).
 */
data class PlaceholderRestoration(
    val text: String,
    val missing: List<String>,
    val ambiguous: List<String>,
    val foreign: List<String>,
    val withheld: List<String>,
) {
    /**
     * 길이·건수만 남긴다. [text] 는 **자리표시자가 진짜 주민등록번호로 되돌아간 최종 본문**이라,
     * 이 저장소에서 평문 개인정보가 담기는 값 중 가장 위험한 축에 든다.
     *
     * `data class` 의 기본 `toString()` 은 모든 필드를 그대로 찍으므로, 로거 인자로 한 번
     * 실리는 순간 복원된 원문이 로그 수집기로 나간다(CLAUDE.md 보안 규칙: 로깅은 문서 ID·
     * 길이·처리 상태까지만). 형제 타입인 `LlmPrompt`·`LlmCompletion` 이 같은 이유로 이미
     * 같은 처리를 받고 있었고 이 타입만 빠져 있었다 — 교차 리뷰 X-6.
     *
     * 라벨 목록도 개수만 남긴다. 계약상 라벨 자체는 개인정보가 아니지만
     * (`missing_placeholders` 는 라벨뿐), 여기서 얻을 것은 "몇 건인가"이고 어느 라벨인지는
     * 호출부가 값으로 다루면 된다.
     */
    override fun toString(): String =
        "PlaceholderRestoration(text=${text.length}자, missing=${missing.size}, " +
            "ambiguous=${ambiguous.size}, foreign=${foreign.size}, withheld=${withheld.size})"
}

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

// ── 표기 변형: 유니코드 인식 패턴 안에 ASCII 전용 리터럴을 남기지 않는다 ────────────
//
// 판정: `07_privacy-gate_masking-verdicts.md` §1 (2026-08-13).
//
// `unicodeRegex` 는 `\d` 를 전각·아라비아-인도 숫자까지 넓히지만, **패턴 안에 손으로 적은
// ASCII 리터럴은 그 플래그가 건드리지 않는다.** privacy-gate 실측이 그 빈자리를 두 종류로
// 갈랐고, 둘 다 "가려야 할 고유식별정보·카드번호가 그대로 외부 모델로 나가는" 방향이다.
//
//   종류 A · ASCII 전용 숫자 클래스 — RRN 성별코드가 `[1-8]` 이었다. 앞 6자리가 전각이면
//       `\d` 가 잡아 주는데 이 한 자리에서 매치가 끊겨 `９００１０１-１２３４５６７` 가
//       통째로 통과했다. 카드번호에는 숫자 클래스가 `\d` 뿐이라 이 종류가 없다.
//   종류 B · ASCII 전용 구분자 리터럴 — RRN 의 `[ \t]`·`-` 와 CARD 의 `[- ]`.
//       **두 패턴 모두에 있었다.** 전각 하이픈(U+FF0D)·마이너스(U+2212)·엔 대시(U+2013)·
//       NBSP(U+00A0)·전각 공백(U+3000)이 전부 뚫었다.
//
// `searchView` 는 이 둘을 막지 못한다 — 그것이 지우는 것은 **보이지 않는** 문자이고,
// 전각 숫자·전각 하이픈·NBSP·전각 공백은 **보이는** 문자다.
//
// **마스킹 전 정규화로 고치지 않는다.** 입력을 접어서 넘기면 `MaskedItem.original` 이 접힌
// 값이 되고 `restoreForExport` 가 사용자 본문을 다른 글자로 되돌린다
// (`02_privacy-gate_control-char-verdict.md` §5.1). 고친 것은 **패턴뿐**이라 파이프라인
// 단계가 늘지 않았고, 잘라 내는 것은 여전히 원문이다 — 복원 성질과 마스킹 선행 불변식이
// 둘 다 그대로다.

/**
 * 구분자로 인정하는 하이픈류.
 *
 * 하이픈과 공백을 한 상수로 합치지 않고 둘로 나눈 이유는 두 범주가 **쓰는 자리가 다르기**
 * 때문이다 — RRN 은 `공백* 하이픈? 공백*` 으로 세 자리에 나눠 쓰고, CARD 는 둘을 합친
 * 한 자리를 쓴다. 그래도 **원천은 이 두 상수 하나씩**이라 다음에 문자를 더할 때 한쪽만
 * 늘어나는 일이 생기지 않는다(`CATEGORY_ALTERNATION` 이 같은 이유로 이미 그렇게 돼 있다).
 */
private const val HYPHEN_CHARS =
    // U+002D 하이픈마이너스 · U+2010 하이픈 · U+2013 엔 대시 · U+2014 엠 대시 ·
    // U+2212 마이너스 · U+FF0D 전각 하이픈. 문자 리터럴을 직접 적지 않고 코드포인트로
    // 적는다 — 전각·대시류는 소스에서 눈으로 구별되지 않아, 리터럴로 적으면 다음 사람이
    // 무엇이 빠졌는지 셀 수 없다. `\uXXXX` 는 정규식 엔진이 해석한다.
    """\u002D\u2010\u2013\u2014\u2212\uFF0D"""

/**
 * 구분자로 인정하는 공백류.
 *
 * **`\s` 를 쓰지 않는다.** `\s` 에는 개행·캐리지리턴이 들어 있어, 서로 다른 줄의 숫자열이
 * 이어 붙어 **진짜 과잉 마스킹**이 된다(parity fixture `masking-keeps-newline-split-digits`
 * 가 그 성질을 못박는다). 탭·개행을 뺀 것과 같은 이유다
 * (`02_privacy-gate_control-char-verdict.md` §5.2).
 *
 * ## TAB 은 여백이 아니라 **열 경계**다 (privacy-gate 판정 §4-septies.7)
 *
 * 이 KDoc 은 처음부터 "탭을 뺐다"고 적고 있었는데 **상수에는 `\u0009` 가 들어 있었다.**
 * Python 원본의 `[ \t]` 에서 그대로 딸려 온 것이고 SEP 축에서 심사된 적이 없다.
 *
 * 왜 여백이 아닌가: [SEP] 의 개수 상한은 **폭의 대리 지표**다 — "자리당 한 칸까지는 구분,
 * 둘 이상은 정렬". 그런데 TAB 은 정의상 **다음 탭 스톱까지 밀어내는** 문자라, 공백으로는
 * 2개 이상 있어야 하는 일을 **1개로 한다.** 대리 지표가 TAB 에서만 성립하지 않는다.
 * §4-ter.2 의 표에서 TAB 이 속하는 행은 "개수로 가르는" 행이 아니라 **개행·CR·VT·FF 와 같은
 * "종류로 가르는" 행**이다 — 새 기준을 만든 것이 아니라 이미 갈라 둔 자리에 빠진 하나를 넣는다.
 *
 * **실측된 과잉 3건**(판정 §4-septies.7): `900101<TAB>1234567` 두 열이 한 주민등록번호가
 * 되고, `1234<TAB>5678<TAB>9012<TAB>3456` **표 4열이 통째로 카드번호**가 되며, 금액 4열도
 * 같다. 붙여넣기 경로(`DocumentTextRequest`)로 표를 복사하면 클립보드 TSV 가 그대로 TAB 이다.
 *
 * **대가 — 이 수정은 누락 방향이다.** `900101<TAB>-<TAB>1234567` 은 이제 가려지지 않는다.
 * 이것은 §4-ter.2 가 개수 상한을 두면서 **이미 감수한 누락과 같은 종류**다
 * (`900101<공백 2개>1234567` 도 가려지지 않는다). TAB 만 예외로 두면 **어느 공백 문자로
 * 조판됐는지에 따라 결과가 갈리고**, 그것이 §4-ter 가 닫은 "문자 종류로 갈린다"의 재발이다.
 * 실측 근거: 원문 추출본 1,971,493자에서 숫자-TAB-숫자는 **0건**이다.
 */
private const val SPACE_CHARS =
    // U+0020 공백 · U+00A0 NBSP · U+2007 FIGURE SPACE · U+202F NARROW NBSP · U+3000 전각 공백.
    // **U+0009 TAB 은 없다** — 위 KDoc 「TAB 은 여백이 아니라 열 경계다」. 되돌리지 말 것.
    """\u0020\u00A0\u2007\u202F\u3000"""

private const val SPACE_CLASS = "[$SPACE_CHARS]"

private const val HYPHEN_CLASS = "[$HYPHEN_CHARS]"

/**
 * **구분자 문법. RRN 과 CARD 가 이 하나를 공유한다.**
 *
 * ```
 * SEP := (?: SPACE? HYPHEN SPACE? | SPACE? )
 * ```
 *
 * 읽는 법: "하이픈이 있으면 양옆에 공백을 **한 개씩까지** 허용하고, 하이픈이 없으면 공백은
 * **한 개까지**." 최대 길이 **3문자로 유한**하다.
 *
 * ## 상한을 문자 수가 아니라 문법으로 고른 이유 (privacy-gate 판정 §4-ter.2)
 *
 * 정당한 주민등록번호·카드번호 표기에서 구분자는 **자릿수 그룹을 가르는 기호 하나**이고,
 * 그 기호 주변의 공백은 **조판 여백 한 칸**이다. 같은 자리에 공백이 둘 이상 오는 표기는
 * "구분"이 아니라 **정렬**이다 — 표 열 맞춤으로 떨어져 있는 접수번호 6자리와 관리번호
 * 7자리가 하나의 주민등록번호로 결합되던 것이 그것이다(과잉 마스킹 = STY-03 팩트 소실).
 *
 * **판정 기준은 문자 종류가 아니라 개수다.** NBSP·U+3000 을 집합에서 빼는 방식은 택하지
 * 않았다 — 그 문자로 적힌 **진짜** 주민등록번호(`900101<NBSP>1234567`)를 다시 놓친다.
 *
 * ## 이전 판이 왜 틀렸나
 *
 * 이전 판은 RRN 이 `SPACE* HYPHEN? SPACE*`, CARD 가 `[하이픈∪공백]?` 이었다. 두 벌로
 * 적혀 있었고 **둘 다 틀렸다** — RRN 은 반복 상한이 없어 표 정렬을 삼켰고(과잉), CARD 는
 * 구분자가 한 문자뿐이라 `1234 - 5678 - 9012 - 3456` 을 놓쳤다(누락). 한 문법이 두 결함을
 * 동시에 닫는다. 상수를 하나로 묶은 것은 다음 확장에서 한쪽만 늘어나지 않게 하기 위해서다.
 *
 * ## 판정 1 의 "`\s` 금지"와의 관계
 *
 * 모순이 아니라 **그 지시를 완성한 것**이다. 그때는 문자 집합만 열거하고 반복 상한을
 * 지정하지 않았는데, 그 누락이 과잉 결합을 만들었다. 여기서 집합은 그대로 두고 반복만 묶는다.
 * 개행·CR·VT·FF 는 여전히 집합 밖이다 — 줄·문단·페이지 경계이기 때문이다.
 */
private const val SEP = "(?:$SPACE_CLASS?$HYPHEN_CLASS$SPACE_CLASS?|$SPACE_CLASS?)"

/** RRN 성별코드로 인정하는 값. 5~8 은 외국인등록번호(고유식별정보)다. */
private val RRN_GENDER_CODES = 1..8

/** [Character.digit] 의 진법. 성별코드는 십진 한 자리다. */
private const val DECIMAL_RADIX = 10

/**
 * 마스킹 대상 패턴 하나.
 *
 * [accept] 를 정규식 밖에 두는 이유: 성별코드 판정을 문자 클래스로 적으면 다시 표기
 * 열거가 되어 종류 A 가 되살아난다. 대신 자리는 `\d` 로 잡고 **매치된 문자의 십진값**을
 * [Character.digit] 으로 본다 — 전각뿐 아니라 **모든 유니코드 십진 숫자 체계**가 한 번에
 * 덮이고, 열거 누락이 재발하지 않는다.
 */
private class MaskPattern(
    val category: MaskCategory,
    val regex: Regex,
    /** 매치를 채택할지 판정한다. 거부한 매치는 구간을 **점유하지 않는다**. */
    val accept: (MatchResult) -> Boolean = { true },
)

/**
 * 성별코드 자리의 값이 1~8 인지 본다. 값 판정이라 표기 체계와 무관하다.
 *
 * 거부된 매치는 [maskParts] 의 `spans` 에 들어가지 않으므로 구간을 점유하지 않는다 —
 * 뒤이은 CARD 패턴이 같은 자리를 판정할 기회를 잃지 않는다.
 */
private fun acceptsRrnGenderCode(match: MatchResult): Boolean {
    val genderCode = match.groupValues[1]
    // **코드포인트로 센다.** 이전 판은 `singleOrNull()` 로 UTF-16 `Char` 하나인지 봤는데,
    // `\d`(UNICODE_CHARACTER_CLASS)가 인정하는 십진 숫자 중 **보충 평면의 310개는 전부
    // 2문자(서로게이트 쌍)**라 그 전부를 거부했다. 정규식은 잡았는데 가드가 되돌려서
    // 마스킹이 통째로 빠지는 결함이었다 — privacy-gate 판정 §4-ter.1.
    //
    // 결함의 종류는 커버리지가 아니라 **정합성**이다: 패턴은 코드포인트로 세고 가드는
    // `Char` 로 세어, 둘이 "숫자 한 자"의 정의를 다르게 갖고 있었다. 그래서 고친 것은
    // "U+1D7CF 를 잡는 것"이 아니라 **계수 단위를 패턴과 일치시킨 것**이다.
    //
    // `Character.digit(Char, Int)` 오버로드를 쓰지 않는다 — **그 오버로드의 존재가 이
    // 결함의 원인이다.** `codePointAt` 이 `Int` 를 주므로 `(Int, Int)` 오버로드가 잡힌다.
    if (genderCode.codePointCount(0, genderCode.length) != 1) return false
    return Character.digit(genderCode.codePointAt(0), DECIMAL_RADIX) in RRN_GENDER_CODES
}

/**
 * 우선순위 순서 — 먼저 매칭된 구간이 이후 패턴보다 우선한다.
 *
 * 원본: `app/privacy/masking.py::_PATTERNS`. 표기 변형 대응은 원본에 없는 확장이다
 * (privacy-gate 판정 §1.4 — Python 도 같은 결함을 갖고 있으나 기준은 Python 출력이 아니다).
 *
 * RRN 이 CARD 보다 앞이다 — 13자리와 16자리는 lookaround 로 갈리지만, 겹치는 표기가
 * 생기면 더 민감한 고유식별정보 쪽으로 판정되는 편이 안전하다.
 */
private val PATTERNS: List<MaskPattern> =
    listOf(
        MaskPattern(
            category = MaskCategory.RRN,
            regex = unicodeRegex("""(?<!\d)\d{6}$SEP(\d)\d{6}(?!\d)"""),
            accept = ::acceptsRrnGenderCode,
        ),
        MaskPattern(
            category = MaskCategory.CARD,
            regex = unicodeRegex("""(?<!\d)\d{4}$SEP\d{4}$SEP\d{4}$SEP\d{4}(?!\d)"""),
        ),
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
// 그대로 보인다. [restoreForExport] 가 내보내기 시점에 되돌린다(검수 여부와 무관하다 —
// 탈출을 벗기는 것은 개인정보 복원이 아니라 우리가 바꿔 놓은 사용자 본문의 복구다).
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
 * 카드번호 패턴이 잇는 것은 **숫자와 [HYPHEN_CHARS]·[SPACE_CHARS] 뿐**이라 어떤 매치도
 * 그 자리를 가로지르지 않는다. 즉 탈출은 매치를 새로 만들지도, 깨지도 않는다.
 *
 * 구분자 집합을 넓힐 때(privacy-gate 판정 §1.4 종류 B) 이 문장이 계속 성립하려면 조건이
 * 하나다 — **집합에 `!`·`[`·`]`·한글을 넣지 않는 것.** 현재 두 상수는 하이픈류와 공백류
 * 뿐이라 성립한다. 넣게 되면 이 KDoc 이 아니라 탈출 표기부터 다시 설계해야 한다.
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
        // C0 중 **줄·페이지 경계 문자 넷을 모두 뺀다** — 탭 U+0009 · LF U+000A ·
        // VT U+000B(수직 탭) · FF U+000C(폼피드) · CR U+000D.
        //
        // VT·FF 를 뺀 것이 privacy-gate 판정 §4-ter.3 이다. 이전 판은 `0x000B..0x000C` 를
        // 넣어 두어 `900101<VT>1234567` 이 탐색 뷰에서 13자리로 **결합**됐다 — 서로 다른
        // 줄·페이지의 숫자열이 하나의 주민등록번호로 마스킹되는 과잉이다. LF·CR 을 뺀 근거
        // (`02_privacy-gate_control-char-verdict.md` §5.2)가 VT·FF 에도 그대로 적용되는데
        // 그때 열거에서 빠졌다. **열거로 범위를 정한 것의 전형적 실패다.**
        //
        // 여기 구멍이 난 것처럼 보인다고 되메우지 말 것 — 의도된 구멍이다.
        0x0000..0x0008,
        0x000E..0x001F,
        0x007F..0x007F, // DEL
        0x00AD..0x00AD, // 소프트하이픈 — 실문서에서 실제로 검출된 것
        0x200B..0x200F, // 폭 없는 공백·비연결자·방향 표시
        0x202A..0x202E, // 방향 재정의
        0x2060..0x2060, // word joiner
        0xFEFF..0xFEFF, // BOM / zero-width no-break space
    )

private val INVISIBLE: Set<Char> =
    INVISIBLE_RANGES
        // `toChar()` 는 코드포인트를 UTF-16 한 자로 자른다 — BMP 밖 값이 들어오면 **조용히
        // 다른 문자가 된다.** `acceptsRrnGenderCode` 가 앓던 것과 같은 종류(코드포인트를
        // Char 로 세는 것)라, 범위가 넓어지는 날 같은 방식으로 무너지지 않게 못박는다.
        // 여기 담기는 것은 전부 BMP 이고, 보충 평면 문자를 넣어야 한다면 이 자료구조부터
        // 코드포인트 집합으로 바꿔야 한다.
        .onEach { range ->
            check(range.last <= Char.MAX_VALUE.code) {
                "INVISIBLE_RANGES 에 BMP 밖 코드포인트가 들어왔다: $range — Char 집합으로는 담을 수 없다."
            }
        }.flatMap { range -> range.map { it.toChar() } }
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
 * ## 합집합인 이유 — 두 축은 역할이 달라 판정 규칙도 다르다
 *
 * > **분리축([SEP], 구분자)은 "두 숫자 그룹이 한 값인가"라는 적극 판정이다** —
 * > 하나의 일관된 읽기에서 폭이 여백 한 칸 이내여야 한다.
 * >
 * > **경계축(`(?<!\d)`·`(?!\d)` lookaround)은 "가려서는 안 된다"는 거부권이다** —
 * > 거부는 원문 읽기와 접힌 읽기가 **둘 다** 거부할 때만 성립한다.
 *
 * 합집합은 이 규칙의 구현이다. 적극 판정에는 **합의 하나**를 요구하고(어느 읽기에서든
 * 개수 상한을 넘으면 그 읽기는 매치를 내지 않는다), 거부권에는 **만장일치**를 요구한다.
 *
 * 그래서 §4-ter 의 개수 원칙과 모순이 없다 — 개수는 **분리축**의 규칙이고, 접기가 건드리는
 * 것은 경계축이다. 실측으로도 그렇다: 보이지 않는 문자를 몇 개를 끼워도 **가시 간격의 개수
 * 판정은 흔들리지 않는다**(`900101<SP><ZWSP><SP>1234567` 은 가시 2칸이라 가려지지 않는다).
 * 개수 기준이 재는 것은 **여백의 폭**이고, 폭 0인 문자는 몇 개가 와도 폭을 만들지 않는다.
 *
 * ## 합집합의 여분이 무엇인지 (열거로 확정된 한 종류)
 *
 * privacy-gate 가 1,120조합을 열거해 확인했다(판정 §4-septies.3): 합집합과 뷰 전용이
 * 갈리는 조합이 **90건이고 전부 한 종류**다.
 *
 * > **폭 0인 문자 한 개가 "긴 숫자열의 일부"라는 거부 근거를 무효화한다.**
 * > `1<ZWSP>900101-1234567` · `900101-1234567<ZWSP>8` · `1<ZWSP>1234-5678-9012-3456` 은
 * > 전부 가려지고, **그 문자만 뺀** `1900101-1234567` · `900101-12345678` ·
 * > `11234-5678-9012-3456` 은 전부 가려지지 않는다.
 *
 * 그 여분을 남기는 쪽을 택한 근거는 정책이다 — `master-plan.md` 의 *"누락보다 과잉 마스킹이
 * 안전하다"* 가 남은 2종(주민등록번호·카드번호)에 그대로 적용되고, 두 실패가 비대칭이다:
 * 누락은 **조용하고 되돌릴 수 없다**(가려지지 않은 고유식별정보가 국외 모델로 나간다),
 * 과잉은 **검수 화면에 보인다**(계약 `MaskedItemResponse.original` 이 필수 필드라 담당자가
 * 자리표시자와 원문을 나란히 보고 되돌릴 수 있다).
 *
 * **실문서 비용은 실측 0이다** — 2,665,995자에서 합집합과 뷰 전용의 마스킹 건수가 7로 같고
 * 갈린 파일이 0건이다. 다만 기제는 합성 열거로 실재하고 붙여넣기 경로
 * (`DocumentTextRequest`)는 추출기를 거치지 않아 웹 복사본의 U+200B 가 그대로 들어온다.
 * 그래서 "발생 0"을 근거로 방치하지 않고 **경계축 양성·음성을 짝으로 고정**한다
 * (`MaskingTest` 「탐색 뷰 접기 경계」).
 *
 * ## 이전 KDoc 이 왜 근거가 되지 못했나 (같은 실수를 되풀이하지 않기 위해 남긴다)
 *
 * 이전 판은 *"합집합은 **현행 적중**을 보존한 채 회피 경로만 더한다"* 였다. 두 군데가 틀렸다.
 * ⑴ 여기서 보존한 "현행"은 뷰가 없던 **원문 전용 구현**인데, 그것은 제어문자 판정이
 * **교체 대상으로 지목한** 구현이다 — 교체 대상을 기준으로 삼았다. 그 판정의 처방은
 * *"패턴은 뷰에서 돌린다"* 였고 **합집합은 그 처방에 없다.** ⑵ *"회피 경로만 더한다"* 는
 * 거짓이다 — 열거 90/90 이 보인 대로 원문 경로의 고유 기여는 **전건이 경계 거부의 무효화**이고,
 * 회피 차단은 뷰 경로가 전부 하고 있다. 그리고 그 예시로 든 `1<ZWSP>900101-1234567` 은
 * 이 설계가 **막는** 것이 아니라 **만드는** 것이었다.
 *
 * **동작은 그대로다. 바뀐 것은 근거뿐이다.**
 *
 * 스팬은 시작 오름차순, 같은 시작이면 **긴 것 우선**으로 준다 — 낀 문자를 포함한 넓은
 * 쪽이 이겨야 원문에 조각이 남지 않는다.
 *
 * [MaskPattern.accept] 가 거부한 매치는 **여기서 걸러진다.** 판정을 매치 단계에서 하는
 * 이유는 성별코드처럼 "몇 번째 문자인가"가 필요한 판정을 스팬(시작·끝)만으로는 할 수
 * 없기 때문이다 — 매치 그룹은 원문 경로와 뷰 경로 어느 쪽에서도 같은 문자를 가리킨다.
 *
 * @return `(시작, 끝 배타)` 쌍의 목록.
 */
private fun candidateSpans(
    pattern: MaskPattern,
    text: String,
    view: String,
    offsets: IntArray?,
): List<Pair<Int, Int>> {
    val spans = LinkedHashSet<Pair<Int, Int>>()
    pattern.regex
        .findAll(text)
        .filter(pattern.accept)
        .forEach { spans += it.range.first to it.range.last + 1 }

    if (offsets != null) {
        pattern.regex
            .findAll(view)
            .filter(pattern.accept)
            .forEach { match ->
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
    for (pattern in PATTERNS) {
        for ((start, end) in candidateSpans(pattern, source, view, offsets)) {
            // 이미 다른 패턴이 차지한 구간이면 건너뛴다. 판정에서 거부된 매치는 애초에
            // candidateSpans 가 걸러 여기 오지 않으므로 구간을 점유하지 않는다 —
            // 성별코드가 9·0 인 13자리 숫자열이 뒤 패턴의 판정 기회를 뺏지 않는다.
            if (spans.any { (taken, takenEnd, _) -> start < takenEnd && taken < end }) continue
            spans += Triple(start, end, pattern.category)
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
 * 사람 검수를 거친 본문이라면 [restoreForExport] 가 입력을 **정확히** 되돌린다. 자리표시자
 * 모양이 입력에 이미 있었다면 탈출 표기로 바뀌어 나가고(`[[주민등록번호1]]` →
 * `[[!주민등록번호1]]`), 복원 때 되돌려진다 — 사유는 위 "자리표시자 충돌" 절.
 */
fun maskText(text: String): MaskingResult = MaskedText.mask(text)

// ── 검수를 거치지 않은 본문에는 개인정보를 꽂지 않는다 ──────────────────────────────
//
// 아래 [restoreForExport] 의 개수 판정(정확히 1회)이 잡는 것은 **복제와 유실뿐**이다.
// 모델이 우리 자리표시자를 지우고 **다른 자리에 하나** 만들면 개수는 여전히 1이라 그
// 자리에 복원된다. 본문만 놓고 보면 이것과 "모델이 문장을 다시 써서 자리가 옮겨간
// 정상 경로"를 구분할 수단이 없다. 구분에 실패한 대가는 시민의 주민등록번호가 엉뚱한
// 사람 자리에 박힌 채 배포되는 것이다.
//
// 위치를 확증할 수 있는 것은 **분할 화면을 본 사람**뿐이다. HITL 은 이 제품의 설계된
// 통제이지 변명이 아니다(master-plan 3.3). 그런데 Python 은 그 통제를 우회한다 —
// `app/services/documents.py::export_conversion` 이 `edited_text ?? easy_text` 로 본문을
// 고른 뒤 **무조건** 복원해서, 검수를 한 번도 거치지 않은 모델 초안에도 개인정보를 꽂는다.
// 409 차단은 `missing_placeholders` 가 비어 있지 않을 때만 걸리는데, 단발 위조는 개수가
// 1이라 그 목록이 비어 있다. 즉 "검수 없이 개인정보가 잘못된 위치에 복원되는" 경로가
// 실제로 열려 있다. Kotlin 에는 아직 호출자가 없으므로 지금이 막을 자리다 — 호출자가
// 생긴 뒤에 막으면 이미 그 형태로 짜여 있다.
//
// 그래서 두 가지를 한다.
//
// 1. **본문 선택을 호출부에서 뺏는다.** `easy_text` 와 `edited_text` 를 서로 다른 타입으로
//    받아 core 가 고른다. 호출부는 "이 본문이 사람 검수를 거쳤는가"를 말하지 않으면
//    컴파일되지 않고, 두 값을 바꿔 넣으면 타입이 어긋난다. 런타임 검사나 주석으로 두지
//    않는 이유는 [MaskedText] 와 같다 — 다음 사람은 주석을 읽지 않는다.
// 2. **검수본이 없으면 복원하지 않는다.** 자리표시자를 글자 그대로 남긴 채 내보낸다.
//
// 2번의 근거는 두 실패의 비대칭이다. 복원하지 않았을 때의 최악은 `[[주민등록번호1]]` 이
// 박힌 문서다 — 눈에 보이고, 되돌릴 수 있고, 그 자체는 개인정보가 아니다(계약: 라벨뿐).
// 복원했을 때의 최악은 엉뚱한 자리의 진짜 주민등록번호다 — 보이지 않고, 배포되면 되돌릴
// 수 없다. 게다가 이 규칙이 무는 것은 [MaskedItem] 이 실제로 잡힌 문서뿐이다. 주 용도인
// 공용 안내문은 대개 `items` 가 비어 있어 한 글자도 달라지지 않는다 — 규칙이 비용을
// 물리는 대상이 정확히 실수 비용이 가장 큰 문서와 겹친다.
//
// 택하지 않은 갈래: **자리표시자들의 상대 순서가 마스킹 때와 같은지 보고 복원한다.**
// 순서는 *위치*가 아니라 *차례*에 대한 증거다. 쉬운 글 변환은 문장을 쪼개고 묶으므로
// 정상 재작성에서도 차례가 흔들려 오탐이 나고, 반대로 차례를 지킨 채 자리만 옮긴 위조는
// 그대로 통과한다. 무엇보다 **자리표시자가 하나뿐이면 순서로는 아무것도 잡지 못하는데**,
// 지적된 것이 바로 그 경우다. 검증하지 못한 것을 검증했다고 믿게 만드는 장치라 넣지 않는다.
//
// **이 통제가 못 잡는 것** (닫은 척하지 않는다):
// - **읽지 않고 저장만 한 검수본.** `edited_text` 가 있다는 것은 "사람이 본문을 제출했다"이지
//   "자리표시자 위치를 확인했다"가 아니다. core 는 둘을 구분할 수단이 없다. 남는 위험은
//   검수 화면(HITL)과 application 이 진다.
// - **검수본 안의 단발 위조.** 사람이 넘긴 본문은 그대로 복원한다 — 사람 눈이 마지막 방어다.
// - **application 이 초안을 `edited_text` 에 미리 채우면 이 통제는 통째로 무너진다.**
//   검수 화면을 열 때 자동 저장하는 식의 구현이 그렇다. `edited_text` 는 사람이 실제로
//   제출하기 전까지 null 이어야 한다 — Phase 4 문서 API 가 지켜야 할 요구사항이다.
// - **Java 호출자·리플렉션.** `value class` 는 JVM 에서 `String` 으로 지워진다([MaskedText]
//   와 같은 한계).
//
// **계약에 대해**: 검수 없는 내보내기를 409 로 막을지는 여기서 정하지 않는다. 계약이 정한
// 409 조건은 둘뿐이고("아직 완료되지 않음", "검수본이 없는데 `missing_placeholders` 가 비어
// 있지 않음"), 그 조건을 재해석하거나 새 실패 모드를 만드는 것은 contract-keeper 의 일이다.
// core 가 보장하는 것은 하나다 — **검수를 거치지 않은 본문에는 개인정보가 들어가지 않는다.**
// 그 사실을 [PlaceholderRestoration.withheld] 로 알리고, 막을지 내보낼지는 application 이 정한다.
// ──────────────────────────────────────────────────────────────────────────────────

// ── provenance 래퍼 사용 규약 (privacy-gate 판정 X-5 조건 1, 2026-08-13) ────────────
//
// 두 타입의 생성자는 **public 이고 앞으로도 public 이다.** 좁힐 수 없다는 것이 판정이다:
// Kotlin `internal` 은 Gradle 모듈 경계인데, `ReviewedBody` 를 만들어야 하는 계층이 바로
// 그 밖(`api` 의 HTTP 요청 어댑터)이다. 이름 있는 팩터리(`fromHumanSubmission(...)`)로
// 옮기는 것도 방어가 아니다 — 손으로 `ReviewedBody(모델응답)` 을 쓸 수 있는 사람은
// `ReviewedBody.fromHumanSubmission(모델응답)` 도 쓸 수 있다. **한 칸 옮기는 것은 방어가
// 아니라 이동이다**(`00_progress.md` 가 같은 형태를 이미 그렇게 판정했다).
//
// 그래서 수용하되 **조용할 수는 없게** 만든다. 규칙은 아래에 적고, 새 생성 지점이 조용히
// 늘지 않는 것은 `ProvenanceCreationSitesTest` 의 허용목록이 지킨다 — 생성 지점을 늘리려면
// 허용목록에 줄을 더해야 하고, 그 diff 는 리뷰에 올라간다.
//
// **[ReviewedBody] 를 만들어도 되는 곳 — 한 곳뿐이다.**
//   HTTP 요청 경계에서 사람이 제출한 `edited_text` 필드를 읽는 어댑터. 그 필드가 요청에
//   **실제로 실려 왔을 때만** 만든다.
//
// **[ReviewedBody] 를 만들면 안 되는 값·자리.**
//   `easy_text`(초안) · LLM 응답 · 후처리 산출물 · 워커 재처리 경로 ·
//   내보내기 경로에서 "본문을 고르는" 코드. `core`·`infrastructure` 에서는 만들지 않는다.
//
// **[ModelDraft] 로 감쌀 수 있는 값.**
//   `LlmCompletion.text` 와 그 후처리 결과, 또는 저장된 `easy_text` 컬럼.
//   **사용자 업로드 원문으로 만들지 않는다** — 그것이 `LlmPrompt.forRepair` 를 통해
//   마스킹 없이 provider 로 나가는 경로다.
//
// **`edited_text` 는 사람이 실제로 제출하기 전까지 `null` 이어야 한다.**
//   검수 화면을 열 때 초안을 자동 저장하는 구현은 이 통제를 통째로 무너뜨린다.
//   요구사항 대장 INV-01-a (`00_requirements-inventory.md` §1) 로 올려 두었다 —
//   여기 주석으로만 있으면 Phase 4 게이트에서 세어지지 않는다.
// ──────────────────────────────────────────────────────────────────────────────────

/**
 * 검수를 거치지 않은 모델 초안 (`easy_text`).
 *
 * [ReviewedBody] 와 **다른 타입**인 것이 요점이다. 둘 다 생 `String` 이면
 * `restoreForExport(edited, easy, items)` 처럼 뒤집어 넣어도 컴파일되고, 그 순간 검수하지
 * 않은 초안이 검수본 자리에 들어가 이 통제가 막으려는 일이 그대로 일어난다.
 *
 * **감쌀 수 있는 값은 위 「provenance 래퍼 사용 규약」이 열거한 것뿐이다.** 사용자 업로드
 * 원문을 여기 감싸면 `LlmPrompt.forRepair` 를 통해 마스킹 없이 provider 로 나간다.
 *
 * `toString()` 재정의 사유는 「value class 와 toString」 절. 이 타입에서 특히 중요한 이유가
 * 하나 더 있다 — 생성자가 공개라 규약이 깨지면 **마스킹 전 원문**이 들어올 수 있고, 그때
 * 무방비 `toString()` 은 그것을 로그로 내보내는 증폭기가 된다.
 */
@JvmInline
value class ModelDraft(val value: String) {
    /** 길이만 남긴다. 사유는 「value class 와 toString」 절. */
    override fun toString(): String = "ModelDraft(${value.length}자)"
}

/**
 * 사람이 검수 화면에서 **제출한** 본문 (`edited_text`). 제출 전에는 `null` 이다.
 *
 * 이 타입으로 감싸는 행위가 곧 "사람 검수를 거쳤다"는 선언이다. 초안을 여기 감싸 넣으면
 * 통제가 무너진다 — 위 절의 "못 잡는 것" 참고.
 *
 * **만들 수 있는 곳은 HTTP 요청 경계의 `edited_text` 어댑터 하나뿐이다**(위
 * 「provenance 래퍼 사용 규약」). 변환 유스케이스·워커·내보내기에서는 만들지 않는다 —
 * 그 자리에서 이 타입이 필요해 보인다면 "사람이 제출했다"는 사실을 어디선가 잃어버린
 * 것이므로, 감싸지 말고 그 사실을 인자로 받아 올려야 한다.
 *
 * `toString()` 재정의 사유는 「value class 와 toString」 절.
 */
@JvmInline
value class ReviewedBody(val value: String) {
    /** 길이만 남긴다. 사유는 「value class 와 toString」 절. */
    override fun toString(): String = "ReviewedBody(${value.length}자)"
}

/**
 * 내보낼 최종 본문을 고르고, **사람 검수를 거친 경우에만** 자리표시자를 원문으로
 * 되돌린다 (**내보내기 전용**).
 *
 * 원본: `app/easyread/export.py::restore_placeholders` + `app/services/documents.py::
 * export_conversion` 의 본문 선택(`edited_text ?? easy_text`). 두 곳에 나뉘어 있던 것을
 * 하나로 합쳤다. **근거**: 나뉘어 있었기 때문에 "검수본이 없으면 초안을 쓴다"(services)와
 * "자리표시자를 무조건 되돌린다"(export)가 서로를 모른 채 결합해, 검수 없이 개인정보를
 * 복원하는 경로가 생겼다. 정확 복원이라는 성질을 만든 것도 마스킹 쪽이다 — 자리표시자
 * 형태, 탈출 표기, "각 자리표시자는 딱 한 번만 넣는다"가 전부 이 파일의 결정이다.
 * 내보내기 조각(Phase 4)은 이 함수를 부른다.
 *
 * **호출 경로 규칙은 원본 그대로다** — 복원본을 만드는 경로는 내보내기 하나뿐이다.
 * 조회 응답·목록·로그에서 부르지 않는다.
 *
 * ## 판정 규칙
 *
 * 본문은 `reviewed ?? draft` 다(계약의 본문 선택 규칙과 같다).
 *
 * **[reviewed] 가 `null` 이면 한 곳도 복원하지 않는다.** 복원했을 자리표시자는
 * [PlaceholderRestoration.withheld] 로 알린다. 사유는 이 함수 앞의 "검수를 거치지 않은
 * 본문" 절.
 *
 * 검수본이 있으면 자리표시자별 등장 횟수로 판정한다. 복원 대상은 **LLM 이 다시 쓴 글**이라
 * 자리표시자 모양은 모델이 얼마든지 만들어 낼 수 있고, 그 자리에 진짜 주민등록번호를 꽂으면
 * 마스킹의 목적이 정면으로 뒤집힌다. 마스킹은 각 자리표시자를 **정확히 한 번** 넣으므로,
 * 개수가 1이 아닌 것은 우리가 만든 본문 그대로가 아니다.
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
 * [PlaceholderRestoration] 의 `missing`·`ambiguous`·`foreign` 은 **검수 여부와 무관하게**
 * 계산한다 — 호출부가 본문 상태를 보고할 수 있어야 하고, 그 값들은 라벨이라 개인정보가 아니다.
 *
 * 마지막에 탈출 표기를 한 겹 벗기는 것도 **검수 여부와 무관하다.** 탈출은 우리가 사용자
 * 본문을 바꿔 놓은 것이고 벗기는 것은 그 복구일 뿐이라, 개인정보가 새로 들어가지 않는다.
 * 치환 뒤에 한 번 더 훑는 이유는 마스킹이 탈출된 토큰 **안쪽**의 숫자를 가릴 수 있기
 * 때문이다(`[[!주민등록번호1234567890123]]`). 그 경우 자리표시자를 되돌려야 비로소 탈출된
 * 토큰이 온전한 모양이 된다.
 *
 * @param draft 변환 결과 초안(`easy_text`). 완료된 변환에는 항상 있다.
 * @param reviewed 사람이 제출한 검수본(`edited_text`). 제출 전이면 `null`.
 * @param items [maskText] 가 낸 항목 목록.
 */
fun restoreForExport(
    draft: ModelDraft,
    reviewed: ReviewedBody?,
    items: List<MaskedItem>,
): PlaceholderRestoration {
    val body = reviewed?.value ?: draft.value
    val originals = items.associate { it.placeholder to it.original }
    val found = PLACEHOLDER.findAll(body).map { it.value }.toList()
    val occurrences = found.groupingBy { it }.eachCount()

    val restored =
        if (reviewed == null) {
            body
        } else {
            PLACEHOLDER.replace(body) { match ->
                val original = originals[match.value]
                if (original != null && occurrences[match.value] == 1) original.reveal() else match.value
            }
        }

    return PlaceholderRestoration(
        text = unescapeLookalikes(restored),
        missing = items.map { it.placeholder }.filter { occurrences[it] == null },
        ambiguous = items.map { it.placeholder }.filter { (occurrences[it] ?: 0) > 1 },
        foreign = found.filterNot { it in originals }.distinct(),
        withheld =
            if (reviewed == null) {
                items.map { it.placeholder }.filter { occurrences[it] == 1 }
            } else {
                emptyList()
            },
    )
}
