package kr.easydoc.infrastructure.telegram

import kr.easydoc.application.auth.UserRepository
import kr.easydoc.application.notification.OperatorNotificationPort
import kr.easydoc.application.notification.OperatorNotificationService
import kr.easydoc.application.notification.OperatorNotifications
import kr.easydoc.core.security.Secret
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

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TelegramProperties::class)
class TelegramConfiguration {
    @Bean
    fun telegramRestClient(properties: TelegramProperties): RestClient =
        telegramRestClient(properties, DEFAULT_BASE_URL)

    // 테스트만 로컬 스텁 baseUrl을 전달한다. Spring @Bean으로 노출하지 않는다.
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

    @Bean
    fun telegramOperatorNotificationAdapter(
        client: RestClient,
        properties: TelegramProperties,
    ): OperatorNotificationPort = TelegramOperatorNotificationAdapter(client, properties)

    @Bean
    fun operatorNotifications(
        properties: TelegramProperties,
        users: UserRepository,
        port: OperatorNotificationPort,
    ): OperatorNotifications = OperatorNotificationService(properties.enabled, users, port)

    companion object {
        internal const val DEFAULT_BASE_URL = "https://api.telegram.org"
    }
}
