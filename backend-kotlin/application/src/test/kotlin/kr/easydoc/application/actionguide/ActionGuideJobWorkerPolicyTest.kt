package kr.easydoc.application.actionguide

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

class ActionGuideJobWorkerPolicyTest {
    @Test
    fun `리스 재획득 상한이 1 미만이면 worker가 뜨지 않는다`() {
        assertThatThrownBy { ActionGuideJobWorkerPolicy(OWNER_NAME, LEASE, 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ActionGuideJobWorkerPolicy(OWNER_NAME, LEASE, -1) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `리스 재획득 상한이 허용 범위를 넘으면 worker가 뜨지 않는다`() {
        val tooLarge = ActionGuideJobWorkerPolicy.MAX_ALLOWED_LEASE_ATTEMPTS + 1
        assertThatThrownBy { ActionGuideJobWorkerPolicy(OWNER_NAME, LEASE, tooLarge) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `경계값 1과 허용 최대치는 유효한 구성이다`() {
        assertThat(ActionGuideJobWorkerPolicy(OWNER_NAME, LEASE, 1).maxLeaseAttempts).isEqualTo(1)
        val largest = ActionGuideJobWorkerPolicy.MAX_ALLOWED_LEASE_ATTEMPTS
        assertThat(ActionGuideJobWorkerPolicy(OWNER_NAME, LEASE, largest).maxLeaseAttempts).isEqualTo(largest)
    }

    private companion object {
        const val OWNER_NAME = "worker-1"
        val LEASE: Duration = Duration.ofSeconds(30)
    }
}
