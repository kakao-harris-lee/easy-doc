package kr.easydoc.api

import kr.easydoc.api.config.EasyDocProperties
import kr.easydoc.core.dictionary.DictionaryContextPolicy
import kr.easydoc.core.security.Secret
import kr.easydoc.infrastructure.app.AppProperties
import kr.easydoc.infrastructure.auth.AuthProperties
import kr.easydoc.infrastructure.auth.GoogleOAuthProperties
import kr.easydoc.infrastructure.auth.KakaoOAuthProperties
import kr.easydoc.infrastructure.auth.NaverOAuthProperties
import kr.easydoc.infrastructure.auth.OAuthProperties
import kr.easydoc.infrastructure.crypto.EncryptionProperties
import kr.easydoc.infrastructure.dictionary.DictionaryLookupProperties
import kr.easydoc.infrastructure.dictionary.DictionaryProperties
import kr.easydoc.infrastructure.document.FeedbackProperties
import kr.easydoc.infrastructure.document.KeyRotationProperties
import kr.easydoc.infrastructure.document.RetentionProperties
import kr.easydoc.infrastructure.llm.LlmProperties
import kr.easydoc.infrastructure.mail.MailProperties
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
    @DisplayName("소셜 로그인 설정이 기본값과 다른 값을 싣는다 — backlog §1.4 P0-1")
    fun `소셜 로그인 설정이 기본값과 다른 값을 싣는다`() {
        val oauth =
            bind(
                "easydoc.oauth",
                OAuthProperties::class.java,
                mapOf("easydoc.oauth.state-ttl-minutes" to "3"),
            )
        assertThat(oauth.stateTtlMinutes).isEqualTo(3)

        val google =
            bind(
                "easydoc.oauth.google",
                GoogleOAuthProperties::class.java,
                mapOf(
                    "easydoc.oauth.google.client-id" to "test-client-id",
                    "easydoc.oauth.google.client-secret" to SECRET_VALUE,
                    "easydoc.oauth.google.redirect-uris[0]" to "https://example.test/auth/google/callback",
                    "easydoc.oauth.google.timeout-ms" to "5000",
                ),
            )
        assertThat(google.clientId).isEqualTo("test-client-id")
        assertThat(google.clientSecret.reveal()).isEqualTo(SECRET_VALUE)
        assertThat(google.redirectUris).containsExactly("https://example.test/auth/google/callback")
        assertThat(google.timeoutMs).isEqualTo(5000L)
    }

    @Test
    @DisplayName(
        "redirect-uris 를 설정하지 않으면 로그인·연결 콜백 기본값 둘 다 실린다 " +
            "(리뷰 후속 조치 — link/callback 을 빠뜨리면 oauthLinkStart 가 기본 구성에서 422 다)",
    )
    fun `구글 redirect_uri 기본값이 로그인과 연결 콜백을 모두 담는다`() {
        val google =
            bind(
                "easydoc.oauth.google",
                GoogleOAuthProperties::class.java,
                mapOf(
                    "easydoc.oauth.google.client-id" to "test-client-id",
                    "easydoc.oauth.google.client-secret" to SECRET_VALUE,
                ),
            )
        assertThat(google.redirectUris)
            .containsExactly(
                GoogleOAuthProperties.DEFAULT_LOGIN_REDIRECT_URI,
                GoogleOAuthProperties.DEFAULT_LINK_REDIRECT_URI,
            )
    }

    @Test
    @DisplayName("카카오 소셜 로그인 설정이 기본값과 다른 값을 싣는다 — backlog §1.4, 계약 2.13.0")
    fun `카카오 설정이 기본값과 다른 값을 싣는다`() {
        val kakao =
            bind(
                "easydoc.oauth.kakao",
                KakaoOAuthProperties::class.java,
                mapOf(
                    "easydoc.oauth.kakao.client-id" to "test-kakao-client-id",
                    "easydoc.oauth.kakao.client-secret" to SECRET_VALUE,
                    "easydoc.oauth.kakao.redirect-uris[0]" to "https://example.test/auth/kakao/callback",
                    "easydoc.oauth.kakao.timeout-ms" to "6000",
                ),
            )
        assertThat(kakao.clientId).isEqualTo("test-kakao-client-id")
        assertThat(kakao.clientSecret.reveal()).isEqualTo(SECRET_VALUE)
        assertThat(kakao.redirectUris).containsExactly("https://example.test/auth/kakao/callback")
        assertThat(kakao.timeoutMs).isEqualTo(6000L)
    }

    @Test
    @DisplayName("카카오 redirect-uris 를 설정하지 않으면 로그인·연결 콜백 기본값 둘 다 실린다 — 구글과 같은 방침")
    fun `카카오 redirect_uri 기본값이 로그인과 연결 콜백을 모두 담는다`() {
        val kakao =
            bind(
                "easydoc.oauth.kakao",
                KakaoOAuthProperties::class.java,
                mapOf(
                    "easydoc.oauth.kakao.client-id" to "test-kakao-client-id",
                    "easydoc.oauth.kakao.client-secret" to SECRET_VALUE,
                ),
            )
        assertThat(kakao.redirectUris)
            .containsExactly(
                KakaoOAuthProperties.DEFAULT_LOGIN_REDIRECT_URI,
                KakaoOAuthProperties.DEFAULT_LINK_REDIRECT_URI,
            )
    }

    @Test
    @DisplayName("네이버 소셜 로그인 설정이 기본값과 다른 값을 싣는다 — backlog §1.4, 계약 2.15.0")
    fun `네이버 설정이 기본값과 다른 값을 싣는다`() {
        val naver =
            bind(
                "easydoc.oauth.naver",
                NaverOAuthProperties::class.java,
                mapOf(
                    "easydoc.oauth.naver.client-id" to "test-naver-client-id",
                    "easydoc.oauth.naver.client-secret" to SECRET_VALUE,
                    "easydoc.oauth.naver.redirect-uris[0]" to "https://example.test/auth/naver/callback",
                    "easydoc.oauth.naver.timeout-ms" to "7000",
                ),
            )
        assertThat(naver.clientId).isEqualTo("test-naver-client-id")
        assertThat(naver.clientSecret.reveal()).isEqualTo(SECRET_VALUE)
        assertThat(naver.redirectUris).containsExactly("https://example.test/auth/naver/callback")
        assertThat(naver.timeoutMs).isEqualTo(7000L)
    }

    @Test
    @DisplayName("네이버 redirect-uris 를 설정하지 않으면 로그인·연결 콜백 기본값 둘 다 실린다 — 구글·카카오와 같은 방침")
    fun `네이버 redirect_uri 기본값이 로그인과 연결 콜백을 모두 담는다`() {
        val naver =
            bind(
                "easydoc.oauth.naver",
                NaverOAuthProperties::class.java,
                mapOf(
                    "easydoc.oauth.naver.client-id" to "test-naver-client-id",
                    "easydoc.oauth.naver.client-secret" to SECRET_VALUE,
                ),
            )
        assertThat(naver.redirectUris)
            .containsExactly(
                NaverOAuthProperties.DEFAULT_LOGIN_REDIRECT_URI,
                NaverOAuthProperties.DEFAULT_LINK_REDIRECT_URI,
            )
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
    @DisplayName("키 회전 설정이 기본값과 다른 값을 싣는다 — 배치 크기")
    fun `키 회전 설정이 기본값과 다른 값을 싣는다`() {
        val rotation =
            bind(
                "easydoc.encryption.rotation",
                KeyRotationProperties::class.java,
                mapOf("easydoc.encryption.rotation.batch-size" to "77"),
            )
        assertThat(rotation.batchSize).isEqualTo(77)
    }

    @Test
    @DisplayName("피드백 설정이 기본값과 다른 값을 싣는다 — 편집 거리 셀 예산과 의견 보존 일수")
    fun `피드백 설정이 기본값과 다른 값을 싣는다`() {
        val feedback =
            bind(
                "easydoc.feedback",
                FeedbackProperties::class.java,
                mapOf(
                    "easydoc.feedback.edit-distance-cell-budget" to "12345",
                    "easydoc.feedback.comment-retention-days" to "7",
                ),
            )
        assertThat(feedback.editDistanceCellBudget).isEqualTo(12345L)
        assertThat(feedback.editDistanceBudget().cells).isEqualTo(12345L)
        assertThat(feedback.commentRetentionDays).isEqualTo(7)
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

    @Test
    @DisplayName("사전 조회 설정이 기본값과 다른 값을 싣는다 — 남용 한도와 사전 단위 표기(P0-5 조각 4)")
    fun `사전 조회 설정이 기본값과 다른 값을 싣는다`() {
        val lookup =
            bind(
                "easydoc.dictionary.lookup",
                DictionaryLookupProperties::class.java,
                mapOf(
                    "easydoc.dictionary.lookup.enabled" to "true",
                    "easydoc.dictionary.lookup.rate-limit-per-minute" to "5",
                    "easydoc.dictionary.lookup.dictionary-name" to "테스트 사전",
                    "easydoc.dictionary.lookup.dictionary-license" to "테스트 라이선스",
                ),
            )
        assertThat(lookup.enabled).isTrue()
        assertThat(lookup.rateLimitPerMinute).isEqualTo(5)
        assertThat(lookup.dictionaryName).isEqualTo("테스트 사전")
        assertThat(lookup.dictionaryLicense).isEqualTo("테스트 라이선스")
    }

    @Test
    @DisplayName("메일 발송 설정이 기본값과 다른 값을 싣는다 — smtp 하위 설정 포함")
    fun `메일 설정이 기본값과 다른 값을 싣는다`() {
        val mail =
            bind(
                "easydoc.mail",
                MailProperties::class.java,
                mapOf(
                    "easydoc.mail.provider" to "smtp",
                    "easydoc.mail.from-address" to "pilot@easydoc.kr",
                    "easydoc.mail.timeout-ms" to "9999",
                    "easydoc.mail.smtp.host" to "smtp.daum.net",
                    "easydoc.mail.smtp.port" to "465",
                    "easydoc.mail.smtp.ssl" to "true",
                    "easydoc.mail.smtp.username" to "pilot",
                    "easydoc.mail.smtp.password" to SECRET_VALUE,
                ),
            )
        assertThat(mail.provider).isEqualTo("smtp")
        assertThat(mail.fromAddress).isEqualTo("pilot@easydoc.kr")
        assertThat(mail.timeoutMs).isEqualTo(9999L)
        assertThat(mail.smtp.host).isEqualTo("smtp.daum.net")
        assertThat(mail.smtp.port).isEqualTo(465)
        assertThat(mail.smtp.ssl).isTrue()
        assertThat(mail.smtp.username).isEqualTo("pilot")
        assertThat(mail.smtp.password.reveal()).isEqualTo(SECRET_VALUE)
    }

    @Test
    @DisplayName("공개 기준 URL 설정이 기본값과 다른 값을 싣는다")
    fun `공개 URL 설정이 기본값과 다른 값을 싣는다`() {
        val app =
            bind(
                "easydoc.app",
                AppProperties::class.java,
                mapOf("easydoc.app.public-base-url" to "https://easydoc.kr"),
            )
        assertThat(app.publicBaseUrl).isEqualTo("https://easydoc.kr")
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
