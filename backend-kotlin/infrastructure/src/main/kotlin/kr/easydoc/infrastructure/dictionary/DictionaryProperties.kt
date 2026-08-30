package kr.easydoc.infrastructure.dictionary

import kr.easydoc.core.dictionary.DictionaryContextPolicy
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 사전 컨텍스트 주입 설정. 바인딩 접두사는 `easydoc.dictionary`.
 *
 * 예산 값 다섯은 운영 중 조정될 수 있으므로 코드에 박지 않는다(CLAUDE.md 「상수와 구성 관리」).
 * 기본값은 [DictionaryContextPolicy] 의 실측 권장값을 **그대로 가져온다** — 숫자를 여기 옮겨
 * 적으면 core 기본값과 갈릴 자리가 하나 생기고, 참조 픽스처를 뽑은 파라미터와도 갈린다.
 *
 * [enabled] 의 기본값이 **켜짐**인 것은 정책이다: 사전 쪽에서 흡수 단어를 덜어내는 릴리스와
 * 주입이 함께 켜져야 「어느 목록도 그 낱말을 다루지 않는」 무지침 구간이 생기지 않는다
 * (`dictionary/docs/consumer-overlap-policy.md` §4).
 */
@ConfigurationProperties(prefix = "easydoc.dictionary")
data class DictionaryProperties(
    val enabled: Boolean = true,
    val maxTerms: Int = DEFAULTS.maxTerms,
    val maxChars: Int? = DEFAULTS.maxChars,
    val maxCharsRatio: Double? = DEFAULTS.maxCharsRatio,
    val minSubstitute: Int = DEFAULTS.minSubstitute,
    val maxExamples: Int = DEFAULTS.maxExamples,
) {
    fun policy(): DictionaryContextPolicy =
        DictionaryContextPolicy(
            maxTerms = maxTerms,
            maxChars = maxChars,
            maxCharsRatio = maxCharsRatio,
            minSubstitute = minSubstitute,
            maxExamples = maxExamples,
        )

    companion object {
        private val DEFAULTS = DictionaryContextPolicy()
    }
}
