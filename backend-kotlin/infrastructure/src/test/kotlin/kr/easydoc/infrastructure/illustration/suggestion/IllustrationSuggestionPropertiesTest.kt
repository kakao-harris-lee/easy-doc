package kr.easydoc.infrastructure.illustration.suggestion

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.BindException
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import java.math.BigDecimal

/**
 * `easydoc.illustration-suggestions.*` 의 바인딩과 기동 자기점검(명세 §3·§6).
 *
 * 값을 코드 기본값으로만 확인하면 「설정 키가 오타여서 언제나 기본값」인 상태가 초록으로 남는다 —
 * 실제 `Binder` 로 키를 먹여 확인한다.
 */
class IllustrationSuggestionPropertiesTest {
    @Test
    @DisplayName("기본값은 전부 꺼짐이고 이용량 단가는 미설정(null)이다 — 0이 아니다")
    fun `기본값은 꺼짐이다`() {
        val properties = bind(emptyMap())

        assertThat(properties.enabled).isFalse()
        assertThat(properties.workerEnabled).isFalse()
        assertThat(properties.creditsPer100Chars).isNull()
        assertThat(properties.leaseDurationSeconds).isEqualTo(IllustrationSuggestionProperties.DEFAULT_LEASE_SECONDS)
        assertThat(properties.pollIntervalMs).isEqualTo(IllustrationSuggestionProperties.DEFAULT_POLL_INTERVAL_MS)
        assertThat(properties.maxLeaseAttempts).isEqualTo(IllustrationSuggestionProperties.DEFAULT_MAX_LEASE_ATTEMPTS)
        assertThat(properties.maxProviderAttemptsPerConversion)
            .isEqualTo(IllustrationSuggestionProperties.DEFAULT_MAX_PROVIDER_ATTEMPTS)
        assertThat(properties.maxOutputTokens).isEqualTo(IllustrationSuggestionProperties.DEFAULT_MAX_OUTPUT_TOKENS)
    }

    @Test
    @DisplayName("설정 키가 전부 바인딩된다 — 하나라도 오타면 기본값이 남아 실패한다")
    fun `설정 키가 바인딩된다`() {
        val properties =
            bind(
                mapOf(
                    "easydoc.illustration-suggestions.enabled" to "true",
                    "easydoc.illustration-suggestions.worker-enabled" to "true",
                    "easydoc.illustration-suggestions.owner" to "worker-7",
                    "easydoc.illustration-suggestions.lease-duration-seconds" to "90",
                    "easydoc.illustration-suggestions.poll-interval-ms" to "250",
                    "easydoc.illustration-suggestions.max-lease-attempts" to "7",
                    "easydoc.illustration-suggestions.max-provider-attempts-per-conversion" to "2",
                    "easydoc.illustration-suggestions.credits-per-100-chars" to "0.3",
                    "easydoc.illustration-suggestions.max-output-tokens" to "4096",
                ),
            )

        assertThat(properties.enabled).isTrue()
        assertThat(properties.workerEnabled).isTrue()
        assertThat(properties.owner).isEqualTo("worker-7")
        assertThat(properties.leaseDurationSeconds).isEqualTo(90)
        assertThat(properties.pollIntervalMs).isEqualTo(250)
        assertThat(properties.maxLeaseAttempts).isEqualTo(7)
        assertThat(properties.maxProviderAttemptsPerConversion).isEqualTo(2)
        assertThat(properties.creditsPer100Chars).isEqualByComparingTo(BigDecimal("0.3"))
        assertThat(properties.maxOutputTokens).isEqualTo(4_096)
    }

    @Test
    @DisplayName("빈 문자열 단가는 미설정으로 바인딩된다 — 환경변수 기본값 자리가 0으로 둔갑하지 않는다")
    fun `빈 단가는 미설정이다`() {
        assertThat(bind(mapOf("easydoc.illustration-suggestions.credits-per-100-chars" to "")).creditsPer100Chars)
            .isNull()
    }

    @Test
    @DisplayName("0은 설정된 값이다 — fake 모드의 무과금이며 미설정과 다르다")
    fun `단가 0은 설정된 값이다`() {
        assertThat(bind(mapOf("easydoc.illustration-suggestions.credits-per-100-chars" to "0")).creditsPer100Chars)
            .isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    @DisplayName("0.1 단위가 아닌 단가·음수 단가는 기동에서 거절한다")
    fun `잘못된 단가는 거절한다`() {
        listOf("0.05", "-0.1").forEach { rate ->
            assertThatThrownBy { bind(mapOf("easydoc.illustration-suggestions.credits-per-100-chars" to rate)) }
                .describedAs("단가 %s 를 받아들였다", rate)
                .isInstanceOf(BindException::class.java)
        }
    }

    @Test
    @DisplayName("상한과 주기의 범위를 기동에서 확인한다 — 자릿수 오타가 상한을 없애지 못한다")
    fun `범위를 벗어난 값은 거절한다`() {
        listOf(
            "easydoc.illustration-suggestions.max-lease-attempts" to "0",
            "easydoc.illustration-suggestions.max-lease-attempts" to "101",
            "easydoc.illustration-suggestions.max-provider-attempts-per-conversion" to "0",
            "easydoc.illustration-suggestions.lease-duration-seconds" to "0",
            "easydoc.illustration-suggestions.poll-interval-ms" to "0",
            "easydoc.illustration-suggestions.max-output-tokens" to "0",
        ).forEach { (key, value) ->
            assertThatThrownBy { bind(mapOf(key to value)) }
                .describedAs("%s=%s 를 받아들였다", key, value)
                .isInstanceOf(BindException::class.java)
        }
    }

    private fun bind(values: Map<String, String>): IllustrationSuggestionProperties =
        Binder(MapConfigurationPropertySource(values))
            .bindOrCreate("easydoc.illustration-suggestions", IllustrationSuggestionProperties::class.java)
}
