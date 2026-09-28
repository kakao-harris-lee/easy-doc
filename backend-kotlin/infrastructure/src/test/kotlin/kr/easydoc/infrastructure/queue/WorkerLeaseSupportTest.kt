package kr.easydoc.infrastructure.queue

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

class WorkerLeaseSupportTest {
    @Test
    fun `configured owner is kept and capped`() {
        val settings =
            WorkerLeaseSupport.resolve(
                configuredOwner = "x".repeat(WorkerLeaseSupport.OWNER_MAX_LENGTH + 1),
                defaultOwner = "fallback",
                leaseDurationSeconds = 120,
                maxLeaseAttempts = 5,
                hostName = { "host" },
            )

        assertThat(settings.owner).isEqualTo("x".repeat(WorkerLeaseSupport.OWNER_MAX_LENGTH))
        assertThat(settings.leaseDuration).isEqualTo(Duration.ofSeconds(120))
        assertThat(settings.maxLeaseAttempts).isEqualTo(5)
    }

    @Test
    fun `blank owner uses host and then feature fallback`() {
        val host =
            WorkerLeaseSupport.resolve(
                configuredOwner = "",
                defaultOwner = "fallback",
                leaseDurationSeconds = 1,
                maxLeaseAttempts = 1,
                hostName = { "host" },
            )
        val fallback =
            WorkerLeaseSupport.resolve(
                configuredOwner = " ",
                defaultOwner = "fallback",
                leaseDurationSeconds = 1,
                maxLeaseAttempts = 1,
                hostName = { "" },
            )

        assertThat(host.owner).isEqualTo("host")
        assertThat(fallback.owner).isEqualTo("fallback")
    }

    @Test
    fun `lease values use the shared boundaries`() {
        assertThatThrownBy {
            WorkerLeaseSupport.resolve("owner", "fallback", 0, 1, hostName = { "host" })
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            WorkerLeaseSupport.resolve(
                "owner",
                "fallback",
                1,
                WorkerLeaseSupport.MAX_ALLOWED_LEASE_ATTEMPTS + 1,
                hostName = { "host" },
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
