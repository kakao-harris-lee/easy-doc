package kr.easydoc.infrastructure.notification

import kr.easydoc.application.auth.UserRepository
import kr.easydoc.application.notification.OperatorNotificationPort
import kr.easydoc.application.notification.OperatorNotificationService
import kr.easydoc.application.notification.OperatorNotifications
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 운영 알림의 조립 지점. 채널이 Telegram 인지 다른 무엇인지는 알지 못한다 —
 * 보내는 쪽 포트가 있으면 서비스와 비동기 데코레이터로 감싸고, 없으면 무동작 구현을 준다.
 *
 * 포트가 없을 때 빈 자체를 빼지 않는 이유는 소비자 때문이다. AuthService·SocialLoginService·
 * DeleteAccountService·TossBillingService 는 채널 설정과 무관하게 이 빈을 주입받는다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OperatorNotificationProperties::class)
class NotificationConfiguration {
    @Bean
    fun operatorNotifications(
        properties: OperatorNotificationProperties,
        users: UserRepository,
        ports: ObjectProvider<OperatorNotificationPort>,
    ): OperatorNotifications {
        // 채널이 없으면 사용자 조회도 메시지 생성도 하지 않는다.
        val port = ports.getIfAvailable() ?: return OperatorNotifications { }
        return AsyncOperatorNotifications(
            OperatorNotificationService(users, port),
            properties.queueCapacity,
            properties.shutdownTimeout,
        )
    }
}
