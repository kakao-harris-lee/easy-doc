package kr.easydoc.infrastructure.telegram

import kr.easydoc.application.notification.OperatorNotificationPort
import kr.easydoc.core.security.Secret
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.convert.DurationUnit
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration
import java.time.temporal.ChronoUnit

@ConfigurationProperties("easydoc.telegram")
data class TelegramProperties(
    val enabled: Boolean = false,
    val botToken: Secret = Secret.EMPTY,
    val chatId: String = "",
    @param:DurationUnit(ChronoUnit.SECONDS)
    val connectTimeout: Duration = Duration.ofSeconds(DEFAULT_CONNECT_TIMEOUT_SECONDS),
    @param:DurationUnit(ChronoUnit.SECONDS)
    val readTimeout: Duration = Duration.ofSeconds(DEFAULT_READ_TIMEOUT_SECONDS),
) {
    init {
        require(!enabled || !botToken.isBlank()) { "easydoc.telegram.bot-token 은 활성화 시 필수입니다" }
        require(!enabled || chatId.isNotBlank()) { "easydoc.telegram.chat-id 는 활성화 시 필수입니다" }
        require(!connectTimeout.isZero && !connectTimeout.isNegative) {
            "easydoc.telegram.connect-timeout 은 0보다 커야 합니다"
        }
        require(!readTimeout.isZero && !readTimeout.isNegative) {
            "easydoc.telegram.read-timeout 은 0보다 커야 합니다"
        }
    }

    override fun toString(): String =
        "TelegramProperties(enabled=$enabled, botToken=$botToken, chatId=[REDACTED], " +
            "connectTimeout=$connectTimeout, readTimeout=$readTimeout)"

    private companion object {
        const val DEFAULT_CONNECT_TIMEOUT_SECONDS = 2L
        const val DEFAULT_READ_TIMEOUT_SECONDS = 5L
    }
}

/**
 * Telegram 채널만 조립한다. 운영 알림 자체의 조립은 채널을 모르는
 * [kr.easydoc.infrastructure.notification.NotificationConfiguration] 몫이다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TelegramProperties::class)
class TelegramConfiguration {
    /** 꺼져 있으면 포트 자체가 없다 — 알림 빈은 그대로 있고 아무 데도 보내지 않는다. */
    @Bean
    @ConditionalOnProperty(prefix = "easydoc.telegram", name = ["enabled"], havingValue = "true")
    fun telegramOperatorNotificationAdapter(properties: TelegramProperties): OperatorNotificationPort =
        telegramOperatorNotificationAdapter(properties, DEFAULT_BASE_URL)

    // 테스트만 로컬 스텁 baseUrl 을 전달한다.
    fun telegramOperatorNotificationAdapter(
        properties: TelegramProperties,
        baseUrl: String,
    ): OperatorNotificationPort =
        TelegramOperatorNotificationAdapter(telegramRestClient(properties, baseUrl), properties)

    // RestClient 는 이 채널의 내부 사정이다. @Bean 으로 노출하면 타입으로 주입되는 공용 빈이
    // 되어 다른 곳의 RestClient 와 충돌한다.
    fun telegramRestClient(
        properties: TelegramProperties,
        baseUrl: String,
    ): RestClient {
        val httpClient = HttpClient.newBuilder().connectTimeout(properties.connectTimeout).build()
        val requests = JdkClientHttpRequestFactory(httpClient).apply { setReadTimeout(properties.readTimeout) }
        return RestClient
            .builder()
            .baseUrl(baseUrl)
            .requestFactory(requests)
            .build()
    }

    companion object {
        internal const val DEFAULT_BASE_URL = "https://api.telegram.org"
    }
}
