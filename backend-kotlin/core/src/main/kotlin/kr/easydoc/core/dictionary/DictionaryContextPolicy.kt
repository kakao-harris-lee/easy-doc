package kr.easydoc.core.dictionary

// 기본값은 `dictionary/docs/easy-doc-integration.md` §4 의 **실측 권장값**이다 —
// easy-doc A/B 56건에서 나온 숫자라 임의로 고른 상수가 아니다. core 는 이 값을 데이터 클래스
// 기본값으로만 들고, 운영 중 조정은 다음 조각의 `@ConfigurationProperties` 가 채워 넣는다
// (CLAUDE.md 「상수와 구성 관리」: 운영 중 바뀔 수 있는 값은 코드에 박지 않는다).

private const val DEFAULT_MAX_TERMS = 40
private const val DEFAULT_MAX_CHARS = 4000
private const val DEFAULT_MAX_CHARS_RATIO = 1.0
private const val DEFAULT_MIN_SUBSTITUTE = 5
private const val DEFAULT_MAX_EXAMPLES = 3

/**
 * 프롬프트 컨텍스트의 예산 정책 (§7.2).
 *
 * @property maxTerms 실을 고유 용어 수 상한. 넘으면 위험도 → priority 내림차순으로 자른다.
 * @property maxChars 렌더링 결과의 문자 수 상한(코드 포인트). `null` 이면 무제한.
 * @property maxCharsRatio 원문 길이에 비례하는 또 하나의 상한 후보. 지정되면
 *   `원문 문자 수 × 비율` 을 계산해 [maxChars] 와 **둘 중 작은 값**을 실제 상한으로 쓴다.
 *   실측에서 문서 38/56 이 컨텍스트가 원문보다 긴 상황이었고(최대 4.61배), 짧은 문서일수록
 *   고정 상한 하나로는 이 역전을 막을 수 없어서 필요하다. `null` 이면 비율 상한 없음.
 * @property minSubstitute `substitute` 예약석 수. `substitute` 는 대개 `risk='none'` 이라
 *   보호가 없으면 잘림에서 **언제나 가장 먼저 통째로** 사라진다(실측: 문서 051 에서 매칭된
 *   substitute 4건이 전부 잘려 "바꿔 쓰세요" 구역이 빈 채로 나갔다). 문서에서 매칭된
 *   `substitute` 중 priority 상위 `min(minSubstitute, 개수)` 건을 예약석으로 두고, [maxTerms]
 *   잘림과 [maxChars] 항목 제거 **둘 다**에서 예약석이 아닌 항목이 전부 제거된 뒤에야 건드린다.
 *   `0` 이면 예약 없음.
 * @property maxExamples "### 참고 예문" 에 실을 예문 수 상한. 예산이 모자라면 **항목보다 먼저**
 *   줄어든다 — 예문은 보조 자료라 핵심 지시문(세 구역)보다 덜 중요하다.
 */
data class DictionaryContextPolicy(
    val maxTerms: Int = DEFAULT_MAX_TERMS,
    val maxChars: Int? = DEFAULT_MAX_CHARS,
    val maxCharsRatio: Double? = DEFAULT_MAX_CHARS_RATIO,
    val minSubstitute: Int = DEFAULT_MIN_SUBSTITUTE,
    val maxExamples: Int = DEFAULT_MAX_EXAMPLES,
)
