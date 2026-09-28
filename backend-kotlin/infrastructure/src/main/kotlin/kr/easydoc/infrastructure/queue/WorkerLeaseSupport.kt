package kr.easydoc.infrastructure.queue

import java.net.InetAddress
import java.time.Duration

/** Worker 기능들이 공유하는 owner 해석과 lease 입력 검증만 모은다. */
internal object WorkerLeaseSupport {
    const val OWNER_MAX_LENGTH: Int = 64
    const val MAX_ALLOWED_LEASE_ATTEMPTS: Int = 100

    fun resolve(
        configuredOwner: String,
        defaultOwner: String,
        leaseDurationSeconds: Long,
        maxLeaseAttempts: Int,
        hostName: () -> String = ::localHostName,
    ): WorkerLeaseSettings {
        val duration = Duration.ofSeconds(leaseDurationSeconds)
        require(!duration.isZero && !duration.isNegative) { "리스 수명이 양수가 아닙니다" }
        require(maxLeaseAttempts in 1..MAX_ALLOWED_LEASE_ATTEMPTS) {
            "리스 재획득 상한은 1 이상 $MAX_ALLOWED_LEASE_ATTEMPTS 이하여야 합니다"
        }
        val owner =
            configuredOwner
                .ifBlank { hostName().ifBlank { defaultOwner } }
                .take(OWNER_MAX_LENGTH)
        return WorkerLeaseSettings(owner, duration, maxLeaseAttempts)
    }

    private fun localHostName(): String =
        runCatching { InetAddress.getLocalHost().hostName }
            .getOrElse { "" }
}

internal data class WorkerLeaseSettings(
    val owner: String,
    val leaseDuration: Duration,
    val maxLeaseAttempts: Int,
)
