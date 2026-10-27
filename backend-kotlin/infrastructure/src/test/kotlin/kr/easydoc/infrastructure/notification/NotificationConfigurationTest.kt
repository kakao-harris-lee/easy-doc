package kr.easydoc.infrastructure.notification

import kr.easydoc.application.auth.UserRepository
import kr.easydoc.application.notification.OperatorNotificationEvent
import kr.easydoc.application.notification.OperatorNotificationPort
import kr.easydoc.application.notification.OperatorNotifications
import kr.easydoc.core.user.PasswordHash
import kr.easydoc.core.user.StoredUser
import kr.easydoc.core.user.User
import kr.easydoc.infrastructure.db.SecretConverter
import kr.easydoc.infrastructure.telegram.TelegramConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient
import java.time.Instant
import java.util.UUID

class NotificationConfigurationTest {
    private val context =
        ApplicationContextRunner().withUserConfiguration(
            NotificationConfiguration::class.java,
            TelegramConfiguration::class.java,
            StubUsersConfiguration::class.java,
            // 운영 컨텍스트에서 설정 문자열을 Secret 으로 바인딩하는 제품 변환기.
            SecretConverter::class.java,
        )

    @Test
    fun `Telegram 이 꺼져 있어도 운영 알림 빈이 있고 아무 데도 보내지 않는다`() {
        context.run { loaded ->
            assertThat(loaded.getBeanNamesForType(OperatorNotificationPort::class.java)).isEmpty()

            val notifications = loaded.getBean(OperatorNotifications::class.java)
            notifications.notify(signUp())

            assertThat(notifications).isNotInstanceOf(AsyncOperatorNotifications::class.java)
            assertThat(loaded.getBean(CountingUserRepository::class.java).findByIdCount).isZero()
        }
    }

    @Test
    fun `Telegram 을 켜면 비동기 데코레이터로 조립하고 RestClient 는 노출하지 않는다`() {
        context
            .withPropertyValues(
                "easydoc.telegram.enabled=true",
                "easydoc.telegram.bot-token=configuration-test-only-value",
                "easydoc.telegram.chat-id=-100999",
            ).run { loaded ->
                assertThat(loaded.getBeanNamesForType(OperatorNotificationPort::class.java)).hasSize(1)
                assertThat(loaded.getBean(OperatorNotifications::class.java))
                    .isInstanceOf(AsyncOperatorNotifications::class.java)
                assertThat(loaded.getBeanNamesForType(RestClient::class.java)).isEmpty()
            }
    }

    private fun signUp(): OperatorNotificationEvent =
        OperatorNotificationEvent.MemberSignedUp(UUID.randomUUID(), Instant.EPOCH, "member@example.test", "email")

    @Configuration(proxyBeanMethods = false)
    class StubUsersConfiguration {
        @Bean
        fun users(): CountingUserRepository = CountingUserRepository()
    }

    class CountingUserRepository : UserRepository {
        var findByIdCount = 0
            private set

        override fun findById(id: UUID): User? {
            findByIdCount++
            return null
        }

        override fun findByEmail(email: String): StoredUser? = error("not used")

        override fun exists(id: UUID): Boolean = error("not used")

        override fun lockForUpdate(id: UUID): User? = error("not used")

        override fun create(
            email: String,
            passwordHash: PasswordHash,
        ): User = error("not used")

        override fun createWithoutPassword(
            email: String,
            emailVerified: Boolean,
        ): User = error("not used")

        override fun updatePasswordHash(
            userId: UUID,
            passwordHash: PasswordHash,
        ) = error("not used")

        override fun markEmailVerified(userId: UUID): Boolean = error("not used")
    }
}
