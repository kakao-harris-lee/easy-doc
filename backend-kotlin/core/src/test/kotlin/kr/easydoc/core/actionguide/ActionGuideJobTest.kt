package kr.easydoc.core.actionguide

import kr.easydoc.core.exceptions.InvalidInputException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ActionGuideJobTest {
    @Test
    fun `외부 상태는 활성 여부와 wire name을 보존한다`() {
        assertThat(ActionGuideJobStatus.QUEUED.active).isTrue()
        assertThat(ActionGuideJobStatus.RUNNING.active).isTrue()
        assertThat(ActionGuideJobStatus.SUCCEEDED.active).isFalse()
        assertThat(ActionGuideJobStatus.ofWireName("superseded")).isEqualTo(ActionGuideJobStatus.SUPERSEDED)
    }

    @Test
    fun `실패 코드는 내부 예외 이름을 노출하지 않는다`() {
        assertThat(ActionGuideJobFailureCode.entries.map { it.wireName })
            .containsExactly("generation_failed", "result_invalid", "outcome_unknown")
        assertThatThrownBy { ActionGuideJobFailureCode.ofWireName("LlmProviderException") }
            .isInstanceOf(InvalidInputException::class.java)
    }
}
