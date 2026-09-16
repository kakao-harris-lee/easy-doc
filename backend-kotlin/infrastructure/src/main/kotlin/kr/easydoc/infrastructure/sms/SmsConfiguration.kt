package kr.easydoc.infrastructure.sms

import kr.easydoc.application.auth.PhoneVerificationSmsSender
import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.privacy.CONTENT_MASK
import kr.easydoc.core.security.Secret
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
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
                PhoneVerificationSmsSender { _, _, _ -> Unit }
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
