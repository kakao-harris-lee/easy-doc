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

        // 종료 대기는 전송 한 번보다 길어야 "남은 큐를 비운다"는 약속이 지켜진다. Telegram 채널의
        // 기본값은 connect 2s + read 5s = 7s(easydoc.telegram.connect-timeout/read-timeout)이므로
        // 그보다 여유 있는 10s 를 기본으로 둔다. 채널 timeout 을 올리면 이 값도 함께 올린다.
        const val DEFAULT_SHUTDOWN_TIMEOUT_SECONDS = 10L
    }
}
