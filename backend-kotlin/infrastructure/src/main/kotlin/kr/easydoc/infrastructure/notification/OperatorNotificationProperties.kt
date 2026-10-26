package kr.easydoc.infrastructure.notification

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.convert.DurationUnit
import java.time.Duration
import java.time.temporal.ChronoUnit

/**
 * 운영 알림 전달 큐의 운영 조정값. 채널(Telegram 등)과 무관한 값만 둔다.
 *
 * 큐 용량은 "운영자가 놓쳐도 되는 알림의 상한"이다 — 가득 차면 호출자를 막는 대신 버린다.
 */
@ConfigurationProperties("easydoc.notification")
data class OperatorNotificationProperties(
    val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    @param:DurationUnit(ChronoUnit.SECONDS)
    val shutdownTimeout: Duration = Duration.ofSeconds(DEFAULT_SHUTDOWN_TIMEOUT_SECONDS),
) {
    init {
        require(queueCapacity > 0) { "easydoc.notification.queue-capacity 는 0보다 커야 합니다" }
        require(!shutdownTimeout.isZero && !shutdownTimeout.isNegative) {
            "easydoc.notification.shutdown-timeout 은 0보다 커야 합니다"
        }
    }

    private companion object {
        const val DEFAULT_QUEUE_CAPACITY = 100
        const val DEFAULT_SHUTDOWN_TIMEOUT_SECONDS = 5L
    }
}
