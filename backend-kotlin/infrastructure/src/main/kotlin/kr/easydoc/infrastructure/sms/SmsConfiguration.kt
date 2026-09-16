package kr.easydoc.infrastructure.sms

import kr.easydoc.application.auth.PhoneVerificationSmsOutbox
import kr.easydoc.application.auth.PhoneVerificationSmsSender
import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.privacy.CONTENT_MASK
import kr.easydoc.core.security.Secret
import kr.easydoc.infrastructure.mail.E2E_PROFILE
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.time.Duration

@ConfigurationProperties(prefix = "easydoc.sms")
data class SmsProperties(
    val provider: String = "fake",
    val serviceId: String = "",
    val accessKey: String = "",
    val secretKey: Secret = Secret.EMPTY,
    val from: String = "",
    val timeoutMs: Long = 5_000,
) {
    override fun toString(): String =
        "SmsProperties(provider=$provider, serviceId=$CONTENT_MASK, accessKey=$CONTENT_MASK, " +
            "secretKey=$secretKey, from=$CONTENT_MASK, timeoutMs=$timeoutMs)"
}

@Configuration(proxyBeanMethods = false)
class SmsConfiguration {
    @Bean
    fun phoneVerificationSmsSender(properties: SmsProperties): PhoneVerificationSmsSender =
        when (properties.provider.lowercase()) {
            "fake" -> {
                FakeSmsSender()
            }

            "sens" -> {
                requireSensConfigured(properties)
                SensSmsSender(
                    SensSmsSettings(
                        serviceId = properties.serviceId,
                        accessKey = properties.accessKey,
                        secretKey = properties.secretKey,
                        fromNumber = properties.from.filter(Char::isDigit),
                        timeout = Duration.ofMillis(properties.timeoutMs),
                    ),
                )
            }

            else -> {
                throw ConfigurationException("지원하지 않는 SMS provider 설정입니다 (가능: fake, sens)")
            }
        }

    /**
     * `e2e` profile 전용 — `E2eSmsOutboxController`(api)가 요청 직후 보낸 인증 코드를
     * 되읽는 협력자다. `phoneVerificationSmsSender` 가 실제로 [PhoneVerificationSmsOutbox]
     * 를 구현하지 않으면(=`fake` 가 아닌 provider 로 잘못 조립되면) 기동 시점에 막는다 —
     * 첫 조회 요청까지 오설정을 미루지 않는다(`MailConfiguration.mailInbox` 와 같은
     * fail-fast 원칙). `compose.e2e.yml` 이 `e2e` profile 을 켤 때는 `easydoc.sms.provider`
     * 를 따로 지정하지 않으므로 기본값 `fake` 그대로 남아, 운영 경로에서는 이 빈 자체가
     * 조립되지 않는다.
     */
    @Bean
    @Profile(E2E_PROFILE)
    fun phoneVerificationSmsOutbox(sender: PhoneVerificationSmsSender): PhoneVerificationSmsOutbox =
        sender as? PhoneVerificationSmsOutbox
            ?: throw ConfigurationException(
                "e2e profile 은 PhoneVerificationSmsOutbox 를 구현하는 SMS 발송기로만 조립돼야 합니다 " +
                    "— easydoc.sms.provider=fake 인지 확인하라",
            )

    private fun requireSensConfigured(properties: SmsProperties) {
        val missing =
            buildList {
                if (properties.serviceId.isBlank()) add("easydoc.sms.service-id")
                if (properties.accessKey.isBlank()) add("easydoc.sms.access-key")
                if (properties.secretKey.isBlank()) add("easydoc.sms.secret-key")
                if (properties.from.filter(Char::isDigit).isBlank()) add("easydoc.sms.from")
            }
        if (missing.isNotEmpty()) {
            throw ConfigurationException("easydoc.sms.provider=sens 는 다음 설정이 필요합니다: ${missing.joinToString()}")
        }
    }
}
