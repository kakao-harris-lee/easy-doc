package kr.easydoc.application.subscription

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PaymentEnvironmentsTest {
    @Test
    fun `실결제 환경 이름은 허용 집합 안에 있다`() {
        assertThat(PaymentEnvironments.ALLOWED).contains(PaymentEnvironments.TOSS_LIVE)
        assertThat(PaymentEnvironments.ALLOWED).containsExactlyInAnyOrder(
            PaymentEnvironments.STUB,
            PaymentEnvironments.TOSS_TEST,
            PaymentEnvironments.TOSS_LIVE,
        )
    }

    @Test
    fun `저장된 식별자 문자열은 바뀌지 않는다`() {
        assertThat(PaymentEnvironments.STUB).isEqualTo("stub")
        assertThat(PaymentEnvironments.TOSS_TEST).isEqualTo("toss_test")
        assertThat(PaymentEnvironments.TOSS_LIVE).isEqualTo("toss_live")
    }
}
