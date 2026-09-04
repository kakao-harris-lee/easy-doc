package kr.easydoc.api

import kr.easydoc.api.config.EasyDocProperties
import kr.easydoc.core.dictionary.DictionaryContextPolicy
import kr.easydoc.core.security.Secret
import kr.easydoc.infrastructure.auth.AuthProperties
import kr.easydoc.infrastructure.crypto.EncryptionProperties
import kr.easydoc.infrastructure.dictionary.DictionaryProperties
import kr.easydoc.infrastructure.document.FeedbackProperties
import kr.easydoc.infrastructure.document.RetentionProperties
import kr.easydoc.infrastructure.llm.LlmProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.BindResult
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySource
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.core.convert.ConversionService
import org.springframework.core.convert.support.DefaultConversionService
import java.math.BigDecimal

/** 설정 바인딩이 실제로 값을 싣는지 — 2026-08-19 실측으로 드러난 결함의 회귀 고정판. */
class ConfigurationPropertiesBindingTest {
    @Test
    @DisplayName("설정 클래스 전부가 기본값과 **다른** 값을 실제로 바인딩한다")
    fun `설정이 기본값과 다른 값을 싣는다`() {
        val auth =
            bind(
                "easydoc.auth",
                AuthProperties::class.java,
                mapOf(
                    "easydoc.auth.jwt-secret" to SECRET_VALUE,
                    "easydoc.auth.jwt-expire-minutes" to "15",
                    "easydoc.auth.argon2.iterations" to "7",
                ),
            )
        assertThat(auth.jwtSecret.reveal()).isEqualTo(SECRET_VALUE)
        assertThat(auth.jwtExpireMinutes).isEqualTo(15)

        assertThat(auth.argon2.iterations).isEqualTo(7)

        val easyDoc =
            bind(
                "easydoc",
                EasyDocProperties::class.java,
                mapOf("easydoc.cors-origins[0]" to "https://example.test"),
            )
        assertThat(easyDoc.corsOrigins).containsExactly("https://example.test")

        val encryption =
            bind(
                "easydoc.encryption",
                EncryptionProperties::class.java,
                mapOf(
                    "easydoc.encryption.write-key-version" to "3",
                    "easydoc.encryption.keys[0].version" to "3",
                    "easydoc.encryption.keys[0].value" to SECRET_VALUE,
                ),
            )
        assertThat(encryption.writeKeyVersion).isEqualTo(3)
        assertThat(encryption.keys).hasSize(1)
        assertThat(encryption.keys.first().version).isEqualTo(3)
        assertThat(
            encryption.keys
                .first()
                .value
                .reveal(),
        ).isEqualTo(SECRET_VALUE)
    }

    @Test
    @DisplayName("LLM 설정이 기본값과 다른 값을 싣는다 — 출력 토큰 상한 포함")
    fun `llm 설정이 기본값과 다른 값을 싣는다`() {
        val llm =
            bind(
                "easydoc.llm",
                LlmProperties::class.java,
                mapOf(
                    "easydoc.llm.provider" to "anthropic",
                    "easydoc.llm.effort" to "high",
                    "easydoc.llm.open-ai-api-key" to SECRET_VALUE,
                    "easydoc.llm.pricing.input-usd-per-million-tokens" to "2.00",
                    "easydoc.llm.pricing.output-usd-per-million-tokens" to "8.00",
                    "easydoc.llm.max-output-tokens" to "5000",
                ),
            )
        assertThat(llm.provider).isEqualTo("anthropic")
        assertThat(llm.effort).isEqualTo("high")
        assertThat(llm.openAiApiKey.reveal()).isEqualTo(SECRET_VALUE)
        assertThat(llm.pricing.inputUsdPerMillionTokens).isEqualByComparingTo(BigDecimal("2.00"))
        assertThat(llm.pricing.outputUsdPerMillionTokens).isEqualByComparingTo(BigDecimal("8.00"))
        assertThat(llm.maxOutputTokens).isEqualTo(5000)
    }

    @Test
    @DisplayName("보존 파기 설정이 기본값과 다른 값을 싣는다")
    fun `보존 설정이 기본값과 다른 값을 싣는다`() {
        val retention =
            bind(
                "easydoc.retention",
                RetentionProperties::class.java,
                mapOf(
                    "easydoc.retention.enabled" to "false",
                    "easydoc.retention.dry-run" to "true",
                    "easydoc.retention.batch-size" to "7",
                ),
            )
        assertThat(retention.enabled).isFalse()
        assertThat(retention.dryRun).isTrue()
        assertThat(retention.batchSize).isEqualTo(7)
    }

    @Test
    @DisplayName("피드백 설정이 기본값과 다른 값을 싣는다 — 편집 거리 셀 예산")
    fun `피드백 설정이 기본값과 다른 값을 싣는다`() {
        val feedback =
            bind(
                "easydoc.feedback",
                FeedbackProperties::class.java,
                mapOf("easydoc.feedback.edit-distance-cell-budget" to "12345"),
            )
        assertThat(feedback.editDistanceCellBudget).isEqualTo(12345L)
        assertThat(feedback.editDistanceBudget().cells).isEqualTo(12345L)
    }

    @Test
    @DisplayName("사전 주입 설정이 기본값과 다른 값을 싣는다 — 플래그와 예산 다섯이 전부 운영 손잡이다")
    fun `사전 설정이 기본값과 다른 값을 싣는다`() {
        val dictionary =
            bind(
                "easydoc.dictionary",
                DictionaryProperties::class.java,
                mapOf(
                    "easydoc.dictionary.enabled" to "false",
                    "easydoc.dictionary.max-terms" to "12",
                    "easydoc.dictionary.max-chars" to "1500",
                    "easydoc.dictionary.max-chars-ratio" to "0.5",
                    "easydoc.dictionary.min-substitute" to "2",
                    "easydoc.dictionary.max-examples" to "1",
                ),
            )
        assertThat(dictionary.enabled).isFalse()
        assertThat(dictionary.policy())
            .isEqualTo(
                DictionaryContextPolicy(
                    maxTerms = 12,
                    maxChars = 1500,
                    maxCharsRatio = 0.5,
                    minSubstitute = 2,
                    maxExamples = 1,
                ),
            )
    }

    private fun <T : Any> bind(
        prefix: String,
        type: Class<T>,
        values: Map<String, String>,
    ): T {
        val conversion = DefaultConversionService()

        conversion.addConverter(String::class.java, Secret::class.java) { Secret(it) }
        val sources: List<ConfigurationPropertySource> = listOf(MapConfigurationPropertySource(values))
        val binder = Binder(sources, null, conversion as ConversionService)
        val result: BindResult<T> = binder.bind(prefix, Bindable.of(type))
        check(result.isBound) { "$prefix 바인딩이 아무 값도 싣지 못했다" }
        return result.get()
    }

    private companion object {
        /** 기본값(빈 값)과 다르기만 하면 된다. 실제 키가 아니다. */
        const val SECRET_VALUE = "binding-test-only-value-0123456789"
    }
}
