package kr.easydoc.api

import kr.easydoc.api.config.EasyDocProperties
import kr.easydoc.application.subscription.PaymentEnvironments
import kr.easydoc.core.dictionary.DictionaryContextPolicy
import kr.easydoc.core.security.Secret
import kr.easydoc.infrastructure.app.AppProperties
import kr.easydoc.infrastructure.auth.AuthProperties
import kr.easydoc.infrastructure.auth.PhoneVerificationProperties
import kr.easydoc.infrastructure.billing.BillingProperties
import kr.easydoc.infrastructure.credit.CreditsProperties
import kr.easydoc.infrastructure.crypto.EncryptionProperties
import kr.easydoc.infrastructure.dictionary.DictionaryLookupProperties
import kr.easydoc.infrastructure.dictionary.DictionaryProperties
import kr.easydoc.infrastructure.document.FeedbackProperties
import kr.easydoc.infrastructure.document.KeyRotationProperties
import kr.easydoc.infrastructure.document.RetentionProperties
import kr.easydoc.infrastructure.llm.LlmProperties
import kr.easydoc.infrastructure.llm.OPENAI_PROVIDER_NAME
import kr.easydoc.infrastructure.mail.MailProperties
import kr.easydoc.infrastructure.sms.SmsProperties
import kr.easydoc.infrastructure.subscription.PaymentProperties
import kr.easydoc.infrastructure.usage.UsageProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource
import java.math.BigDecimal
import java.time.Duration

/** 설정 바인딩이 실제로 값을 싣는지 — 2026-08-19 실측으로 드러난 결함의 회귀 고정판. */
class ConfigurationPropertiesBindingTest {
    @Test
    @DisplayName("설정 클래스 전부가 기본값과 **다른** 값을 실제로 바인딩한다")
    fun `설정이 기본값과 다른 값을 싣는다`() {
        val auth =
            bindProperties(
                "easydoc.auth",
                AuthProperties::class.java,
                mapOf(
                    "easydoc.auth.jwt-secret" to BINDING_SECRET_VALUE,
                    "easydoc.auth.jwt-expire-minutes" to "15",
                    "easydoc.auth.argon2.iterations" to "7",
                ),
            )
        assertThat(auth.jwtSecret.reveal()).isEqualTo(BINDING_SECRET_VALUE)
        assertThat(auth.jwtExpireMinutes).isEqualTo(15)

        assertThat(auth.argon2.iterations).isEqualTo(7)

        val easyDoc =
            bindProperties(
                "easydoc",
                EasyDocProperties::class.java,
                mapOf("easydoc.cors-origins[0]" to "https://example.test"),
            )
        assertThat(easyDoc.corsOrigins).containsExactly("https://example.test")

        val encryption =
            bindProperties(
                "easydoc.encryption",
                EncryptionProperties::class.java,
                mapOf(
                    "easydoc.encryption.write-key-version" to "3",
                    "easydoc.encryption.keys[0].version" to "3",
                    "easydoc.encryption.keys[0].value" to BINDING_SECRET_VALUE,
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
        ).isEqualTo(BINDING_SECRET_VALUE)
    }

    @Test
    @DisplayName("LLM 설정이 기본값과 다른 값을 싣는다 — 출력 토큰 상한·모델별 단가 포함")
    fun `llm 설정이 기본값과 다른 값을 싣는다`() {
        val llm =
            bindProperties(
                "easydoc.llm",
                LlmProperties::class.java,
                mapOf(
                    "easydoc.llm.provider" to "anthropic",
                    "easydoc.llm.effort" to "high",
                    "easydoc.llm.open-ai-api-key" to BINDING_SECRET_VALUE,
                    "easydoc.llm.pricing.input-usd-per-million-tokens" to "2.00",
                    "easydoc.llm.pricing.output-usd-per-million-tokens" to "8.00",
                    "easydoc.llm.pricing.models.claude-sonnet-5.input-usd-per-million-tokens" to "2.00",
                    "easydoc.llm.pricing.models.claude-sonnet-5.output-usd-per-million-tokens" to "10.00",
                    "easydoc.llm.max-output-tokens" to "5000",
                ),
            )
        assertThat(llm.provider).isEqualTo("anthropic")
        assertThat(llm.effort).isEqualTo("high")
        assertThat(llm.openAiApiKey.reveal()).isEqualTo(BINDING_SECRET_VALUE)
        assertThat(llm.pricing.inputUsdPerMillionTokens).isEqualByComparingTo(BigDecimal("2.00"))
        assertThat(llm.pricing.outputUsdPerMillionTokens).isEqualByComparingTo(BigDecimal("8.00"))
        assertThat(llm.pricing.models).containsKey("claude-sonnet-5")
        val sonnetPricing = llm.pricing.models.getValue("claude-sonnet-5")
        assertThat(sonnetPricing.inputUsdPerMillionTokens).isEqualByComparingTo(BigDecimal("2.00"))
        assertThat(sonnetPricing.outputUsdPerMillionTokens).isEqualByComparingTo(BigDecimal("10.00"))
        assertThat(llm.maxOutputTokens).isEqualTo(5000)
    }

    @Test
    @DisplayName(
        "EASYDOC_LLM_PROVIDER 를 설정하지 않은 배포는 openai 로 간다 — 게시된 개인정보처리방침 §5 가 " +
            "OpenAI 를 수탁자로 공개한다. 기본값을 다른 사업자로 바꾸려면 방침 §5 를 먼저 개정한다",
    )
    fun `provider 기본값은 openai 다`() {
        // 테스트의 property override 가 가려 주지 않도록 application.yml 자체를 직접 읽는다
        // (IllustrationSuggestionApiConfigTest 와 같은 이유). 바인딩만 재면 yml 이 Kotlin 기본값을
        // 다른 사업자로 덮어써도 이 시험은 통과한다 — 그 덮어쓰기가 정확히 위험한 쪽이다.
        // 여기서 고정하는 것은 api 모듈의 application.yml 한 벌이다. worker 는 이 시험의 사정권 밖이고,
        // api·worker 두 yml 의 기본값이 서로 같은지는 GoldenLlmLaneTest 가 소스 루트에서 두 파일을
        // 함께 읽어 고정한다.
        assertThat(declaredInApiYml(LLM_PROVIDER_KEY))
            .describedAs("환경변수 없는 배포가 사용자 문서를 어느 사업자로 보내는지 정하는 한 줄이다")
            .containsExactly("\${$LLM_PROVIDER_ENV:$OPENAI_PROVIDER_NAME}")

        // yml 기본값과 Kotlin 기본값이 갈라지면 두 경로가 서로 다른 사업자를 고른다.
        val llm =
            bindProperties(
                "easydoc.llm",
                LlmProperties::class.java,
                mapOf("easydoc.llm.effort" to "medium"),
            )
        assertThat(llm.provider).isEqualTo(OPENAI_PROVIDER_NAME)
    }

    @Test
    @DisplayName(
        "LLM 읽기 타임아웃은 초 단위로 바인딩된다 — 리뷰 blocker(2026-09-09), 접미사 없는 숫자를 " +
            "밀리초로 해석하던 결함의 회귀 고정판",
    )
    fun `read-timeout 은 초 단위로 바인딩된다`() {
        val explicit =
            bindProperties(
                "easydoc.llm",
                LlmProperties::class.java,
                mapOf("easydoc.llm.read-timeout" to "600"),
            )
        assertThat(explicit.readTimeout).isEqualTo(Duration.ofSeconds(600))

        val default =
            bindProperties(
                "easydoc.llm",
                LlmProperties::class.java,
                mapOf("easydoc.llm.provider" to "openai"),
            )
        assertThat(default.readTimeout).isEqualTo(Duration.ofSeconds(120))
    }

    @Test
    @DisplayName("모델 id 자체에 점이 있으면(gpt-4.1) 대괄호 표기로 묶어야 한다 — 평평한 키에서 점은 중첩 구분자다")
    fun `점이 있는 모델 id는 대괄호로 묶는다`() {
        val llm =
            bindProperties(
                "easydoc.llm",
                LlmProperties::class.java,
                mapOf(
                    // `easydoc.llm.pricing.models.gpt-4.1...` 처럼 그냥 이으면 바인더가
                    // `models` 아래 `gpt-4`.`1` 두 단계로 쪼갠다 — 이 평평한 맵 키 형태
                    // (`MapConfigurationPropertySource`, 커맨드라인 인자·환경변수와 같은
                    // 성격)에서만 문제다. YAML 파일의 중첩 매핑(`models:\n  gpt-4.1:`)은
                    // 키가 문자열 그대로 실려 이 문제가 없다 — `application.yml` 주석 참고.
                    "easydoc.llm.pricing.models[gpt-4.1].input-usd-per-million-tokens" to "2.00",
                    "easydoc.llm.pricing.models[gpt-4.1].output-usd-per-million-tokens" to "8.00",
                ),
            )

        assertThat(llm.pricing.models).containsKey("gpt-4.1")
        val gpt41Pricing = llm.pricing.models.getValue("gpt-4.1")
        assertThat(gpt41Pricing.inputUsdPerMillionTokens).isEqualByComparingTo(BigDecimal("2.00"))
        assertThat(gpt41Pricing.outputUsdPerMillionTokens).isEqualByComparingTo(BigDecimal("8.00"))
    }

    @Test
    @DisplayName("보존 파기 설정이 기본값과 다른 값을 싣는다")
    fun `보존 설정이 기본값과 다른 값을 싣는다`() {
        val retention =
            bindProperties(
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
            bindProperties(
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
            bindProperties(
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
            bindProperties(
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
            bindProperties(
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
            bindProperties(
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
                    "easydoc.mail.smtp.password" to BINDING_SECRET_VALUE,
                ),
            )
        assertThat(mail.provider).isEqualTo("smtp")
        assertThat(mail.fromAddress).isEqualTo("pilot@easydoc.kr")
        assertThat(mail.timeoutMs).isEqualTo(9999L)
        assertThat(mail.smtp.host).isEqualTo("smtp.daum.net")
        assertThat(mail.smtp.port).isEqualTo(465)
        assertThat(mail.smtp.ssl).isTrue()
        assertThat(mail.smtp.username).isEqualTo("pilot")
        assertThat(mail.smtp.password.reveal()).isEqualTo(BINDING_SECRET_VALUE)
    }

    @Test
    @DisplayName("사용량 집계 설정이 기본값과 다른 값을 싣는다 — 시간대(U2)")
    fun `사용량 설정이 기본값과 다른 값을 싣는다`() {
        val usage =
            bindProperties(
                "easydoc.usage",
                UsageProperties::class.java,
                mapOf("easydoc.usage.zone" to "UTC"),
            )
        assertThat(usage.zone).isEqualTo("UTC")
        assertThat(usage.zoneId()).isEqualTo(java.time.ZoneId.of("UTC"))
    }

    @Test
    @DisplayName("크레딧 집행 설정이 기본값과 다른 값을 싣는다 — 스위치와 가입 부여")
    fun `크레딧 설정이 기본값과 다른 값을 싣는다`() {
        val credits =
            bindProperties(
                "easydoc.credits",
                CreditsProperties::class.java,
                mapOf(
                    "easydoc.credits.enforced" to "true",
                    "easydoc.credits.signup-grant" to "50",
                ),
            )
        assertThat(credits.enforced).isTrue()
        assertThat(credits.signupGrant).isEqualTo(50)
    }

    @Test
    @DisplayName("가입 부여 pepper 는 EASYDOC_CREDITS_SIGNUP_GRANT_PEPPER 와 같은 이름으로 Secret 에 실린다")
    fun `가입 부여 pepper 가 Secret 에 실린다`() {
        val credits =
            bindProperties(
                "easydoc.credits",
                CreditsProperties::class.java,
                mapOf("easydoc.credits.signup-grant-pepper" to BINDING_SECRET_VALUE),
            )
        assertThat(credits.signupGrantPepper.reveal()).isEqualTo(BINDING_SECRET_VALUE)
    }

    @Test
    @DisplayName("청구 운영 설정이 기본값과 다른 값을 싣는다 — 운영자 알림 주소")
    fun `청구 설정이 기본값과 다른 값을 싣는다`() {
        val billing =
            bindProperties(
                "easydoc.billing",
                BillingProperties::class.java,
                mapOf("easydoc.billing.operator-email" to "billing@easydoc.kr"),
            )
        assertThat(billing.operatorEmail).isEqualTo("billing@easydoc.kr")
    }

    @Test
    @DisplayName("휴대폰 인증 설정이 기본값과 다른 값을 싣는다 — 체험 크레딧과 지문 pepper 포함")
    fun `휴대폰 인증 설정이 기본값과 다른 값을 싣는다`() {
        val phoneVerification =
            bindProperties(
                "easydoc.phone-verification",
                PhoneVerificationProperties::class.java,
                mapOf(
                    "easydoc.phone-verification.code-ttl-minutes" to "9",
                    "easydoc.phone-verification.resend-cooldown-seconds" to "77",
                    "easydoc.phone-verification.max-attempts" to "3",
                    "easydoc.phone-verification.trial-credits" to "10",
                    "easydoc.phone-verification.fingerprint-pepper" to BINDING_SECRET_VALUE,
                ),
            )
        assertThat(phoneVerification.codeTtlMinutes).isEqualTo(9)
        assertThat(phoneVerification.resendCooldownSeconds).isEqualTo(77)
        assertThat(phoneVerification.maxAttempts).isEqualTo(3)
        assertThat(phoneVerification.trialCredits).isEqualTo(10)
        assertThat(phoneVerification.fingerprintPepper.reveal()).isEqualTo(BINDING_SECRET_VALUE)
    }

    @Test
    @DisplayName("SMS 발송 설정이 기본값과 다른 값을 싣는다 — provider 선택 포함")
    fun `SMS 설정이 기본값과 다른 값을 싣는다`() {
        val sms =
            bindProperties(
                "easydoc.sms",
                SmsProperties::class.java,
                mapOf(
                    "easydoc.sms.provider" to "sens",
                    "easydoc.sms.service-id" to "ncp-service-id",
                    "easydoc.sms.access-key" to "ncp-access-key",
                    "easydoc.sms.secret-key" to BINDING_SECRET_VALUE,
                    "easydoc.sms.from" to "01000000000",
                    "easydoc.sms.timeout-ms" to "9999",
                ),
            )
        assertThat(sms.provider).isEqualTo("sens")
        assertThat(sms.serviceId).isEqualTo("ncp-service-id")
        assertThat(sms.accessKey).isEqualTo("ncp-access-key")
        assertThat(sms.secretKey.reveal()).isEqualTo(BINDING_SECRET_VALUE)
        assertThat(sms.from).isEqualTo("01000000000")
        assertThat(sms.timeoutMs).isEqualTo(9999L)
    }

    @Test
    @DisplayName("공개 기준 URL 설정이 기본값과 다른 값을 싣는다")
    fun `공개 URL 설정이 기본값과 다른 값을 싣는다`() {
        val app =
            bindProperties(
                "easydoc.app",
                AppProperties::class.java,
                mapOf("easydoc.app.public-base-url" to "https://easydoc.kr"),
            )
        assertThat(app.publicBaseUrl).isEqualTo("https://easydoc.kr")
    }

    @Test
    fun `결제 제공자와 구매·갱신 스위치가 바인딩된다`() {
        val payment =
            bindProperties(
                "easydoc.payment",
                PaymentProperties::class.java,
                mapOf(
                    "easydoc.payment.provider" to PaymentEnvironments.TOSS_LIVE,
                    "easydoc.payment.purchase-enabled" to "true",
                    "easydoc.payment.auto-charge-enabled" to "true",
                    "easydoc.payment.mock-enabled" to "false",
                ),
            )
        assertThat(payment.provider).isEqualTo(PaymentEnvironments.TOSS_LIVE)
        assertThat(payment.purchaseEnabled).isTrue()
        assertThat(payment.autoChargeEnabled).isTrue()
        assertThat(payment.mockEnabled).isFalse()
    }

    /** 프로필 문서까지 포함해 api `application.yml` 이 선언한 값을 그대로 읽는다. */
    private fun declaredInApiYml(key: String): List<String> =
        YamlPropertySourceLoader()
            .load(API_CONFIG_RESOURCE, ClassPathResource(API_CONFIG_RESOURCE))
            .mapNotNull { it.getProperty(key)?.toString() }

    private companion object {
        const val API_CONFIG_RESOURCE = "application.yml"
        const val LLM_PROVIDER_KEY = "easydoc.llm.provider"
        const val LLM_PROVIDER_ENV = "EASYDOC_LLM_PROVIDER"
    }
}
