package kr.easydoc.application.subscription

import kr.easydoc.core.exceptions.EmailNotVerifiedException
import kr.easydoc.core.exceptions.PhoneNotVerifiedException
import kr.easydoc.core.user.User
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** [requirePaymentEligible] — `SubscriptionService.checkout`·`TossBillingService.begin` 이 공유하는 게이트. */
class PaymentEligibilityTest {
    @Test
    @DisplayName("사용자가 없으면 이메일 미인증으로 취급한다")
    fun `null 사용자는 이메일 미인증 문구`() {
        assertThatThrownBy { requirePaymentEligible(null, PaymentAction.CHECKOUT) }
            .isInstanceOf(EmailNotVerifiedException::class.java)
            .hasMessage("이메일 인증 후 결제하세요")
    }

    @Test
    @DisplayName("이메일 미인증이면 EmailNotVerifiedException")
    fun `이메일 미인증`() {
        val user = user(emailVerified = false, phoneVerified = false)

        assertThatThrownBy { requirePaymentEligible(user, PaymentAction.CHECKOUT) }
            .isInstanceOf(EmailNotVerifiedException::class.java)
            .hasMessage("이메일 인증 후 결제하세요")
    }

    @Test
    @DisplayName("이메일은 인증됐지만 휴대폰이 미인증이면 PhoneNotVerifiedException")
    fun `휴대폰 미인증`() {
        val user = user(emailVerified = true, phoneVerified = false)

        assertThatThrownBy { requirePaymentEligible(user, PaymentAction.CHECKOUT) }
            .isInstanceOf(PhoneNotVerifiedException::class.java)
            .hasMessage("휴대폰 인증 후 결제하세요")
    }

    @Test
    @DisplayName("둘 다 인증됐으면 통과한다")
    fun `둘 다 인증`() {
        val user = user(emailVerified = true, phoneVerified = true)

        requirePaymentEligible(user, PaymentAction.CHECKOUT)
    }

    @Test
    @DisplayName("카드 등록 행동은 문구 뒷부분만 다르다")
    fun `카드 등록 문구`() {
        assertThatThrownBy { requirePaymentEligible(null, PaymentAction.CARD_REGISTRATION) }
            .isInstanceOf(EmailNotVerifiedException::class.java)
            .hasMessage("이메일 인증 후 카드를 등록하세요")

        assertThatThrownBy {
            requirePaymentEligible(user(emailVerified = true, phoneVerified = false), PaymentAction.CARD_REGISTRATION)
        }.isInstanceOf(PhoneNotVerifiedException::class.java)
            .hasMessage("휴대폰 인증 후 카드를 등록하세요")
    }

    private fun user(
        emailVerified: Boolean,
        phoneVerified: Boolean,
    ): User =
        User(
            id = UUID.randomUUID(),
            email = "user@example.com",
            createdAt = Instant.EPOCH,
            emailVerifiedAt = if (emailVerified) Instant.EPOCH else null,
            hasPassword = true,
            phoneVerifiedAt = if (phoneVerified) Instant.EPOCH else null,
        )
}
