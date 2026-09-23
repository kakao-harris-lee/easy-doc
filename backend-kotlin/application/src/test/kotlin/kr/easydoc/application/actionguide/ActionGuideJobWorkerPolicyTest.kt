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
    fun `상한 1은 첫 리스만 허용하는 유효한 구성이다`() {
        assertThat(ActionGuideJobWorkerPolicy(OWNER_NAME, LEASE, 1).maxLeaseAttempts).isEqualTo(1)
    }

    private companion object {
        const val OWNER_NAME = "worker-1"
        val LEASE: Duration = Duration.ofSeconds(30)
    }
}
