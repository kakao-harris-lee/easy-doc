package kr.easydoc.application.subscription

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** 구매 가능 판정 — 검수 allowlist, 실결제 전환, 약관 시행 시각. */
class TossBillingLiveGateTest {
    @Test
    fun `closed purchases reject ordinary workspace before card registration`() {
        val f = TossBillingFixture(purchase = false, autoCharge = false)
        f.store.orders.clear()
        f.store.currentSession = null

        assertThatThrownBy { f.service.begin(f.owner, f.workspace, "start") }
            .isInstanceOf(kr.easydoc.core.exceptions.ConflictException::class.java)
            .hasMessage("신규 카드 등록이 중단되어 있습니다")
        assertThat(f.store.currentSession).isNull()
        assertThat(f.gateway.charges).isZero()
    }

    @Test
    fun `closed purchases allow only the review workspace to register a card`() {
        val f = TossBillingFixture(purchase = false, autoCharge = false, reviewWorkspace = true)
        f.store.orders.clear()
        f.store.currentSession = null

        val session = f.service.begin(f.owner, f.workspace, "start")

        assertThat(session.workspaceId).isEqualTo(f.workspace)
        assertThat(session.environment).isEqualTo(PaymentEnvironments.TOSS_TEST)
        assertThat(f.gateway.charges).isZero()
        assertThat(f.notifications.events).isEmpty()
    }

    @Test
    fun `review allowlist permits initial charge but does not enable automatic renewal`() {
        val f = TossBillingFixture(purchase = false, autoCharge = false, reviewWorkspace = true)
        f.service.process(f.order.id)
        assertThat(f.gateway.charges).isEqualTo(1)

        val renewal = TossBillingFixture(purchase = false, autoCharge = false, reviewWorkspace = true)
        renewal.store.orders[renewal.order.id] = renewal.order.copy(kind = "renewal")
        renewal.service.process(renewal.order.id)
        assertThat(renewal.gateway.charges).isZero()
    }

    @Test
    fun `production purchase opens Start overview registration and initial approval for ordinary workspace`() {
        val f =
            TossBillingFixture(environment = PaymentEnvironments.TOSS_LIVE, purchase = true, reviewWorkspace = false)
        f.store.orders.clear()
        f.store.currentSession = null

        val overview = f.overview()
        assertThat(overview.purchaseEnabled).isTrue()
        assertThat(overview.autoChargeEnabled).isTrue()
        assertThat(overview.plans.map { it.id }).containsExactly("start")
        assertThat(overview.plans.single().monthlyPrice).isEqualTo(99_000)
        assertThat(overview.plans.single().allowance).isEqualTo(50)

        val session =
            f.service.begin(
                f.owner,
                f.workspace,
                "start",
                consentVersion = "start-monthly-v1",
            )
        assertThat(session.workspaceId).isEqualTo(f.workspace)
        assertThat(session.environment).isEqualTo(PaymentEnvironments.TOSS_LIVE)

        val approval =
            TossBillingFixture(environment = PaymentEnvironments.TOSS_LIVE, purchase = true, reviewWorkspace = false)
        approval.service.process(approval.order.id)
        assertThat(approval.gateway.charges).isEqualTo(1)
    }

    @Test
    fun `production ignores review allowlist in both directions`() {
        val closed =
            TossBillingFixture(environment = PaymentEnvironments.TOSS_LIVE, purchase = false, reviewWorkspace = true)
        closed.store.orders.clear()
        closed.store.currentSession = null
        assertThat(closed.overview().purchaseEnabled).isFalse()
        assertThatThrownBy {
            closed.service.begin(
                closed.owner,
                closed.workspace,
                "start",
                consentVersion = "start-monthly-v1",
            )
        }.isInstanceOf(kr.easydoc.core.exceptions.ConflictException::class.java)

        val closedApproval =
            TossBillingFixture(environment = PaymentEnvironments.TOSS_LIVE, purchase = false, reviewWorkspace = true)
        closedApproval.service.process(closedApproval.order.id)
        assertThat(closedApproval.gateway.charges).isZero()

        val otherWorkspace =
            TossBillingFixture(environment = PaymentEnvironments.TOSS_LIVE, purchase = true, reviewWorkspace = false)
        assertThat(otherWorkspace.overview().purchaseEnabled).isTrue()
    }

    @Test
    fun `live purchases stay closed until the published terms take effect`() {
        val justBefore = LegalSnapshot.EFFECTIVE_AT.minusSeconds(1)

        val registration =
            TossBillingFixture(environment = PaymentEnvironments.TOSS_LIVE, purchase = true, now = justBefore)
        registration.store.orders.clear()
        registration.store.currentSession = null
        assertThat(registration.service.purchaseAllowed(registration.workspace)).isFalse()
        assertThat(registration.overview().purchaseEnabled).isFalse()
        assertThatThrownBy {
            registration.service.begin(
                registration.owner,
                registration.workspace,
                "start",
                consentVersion = "start-monthly-v1",
            )
        }.isInstanceOf(kr.easydoc.core.exceptions.ConflictException::class.java)

        val charge = TossBillingFixture(environment = PaymentEnvironments.TOSS_LIVE, purchase = true, now = justBefore)
        charge.service.process(charge.order.id)
        assertThat(charge.gateway.charges).isZero()

        val onEffective =
            TossBillingFixture(
                environment = PaymentEnvironments.TOSS_LIVE,
                purchase = true,
                now = LegalSnapshot.EFFECTIVE_AT,
            )
        assertThat(onEffective.service.purchaseAllowed(onEffective.workspace)).isTrue()
        onEffective.service.process(onEffective.order.id)
        assertThat(onEffective.gateway.charges).isEqualTo(1)
    }

    @Test
    fun `test billing purchase rules are untouched by the legal effective instant`() {
        val justBefore = LegalSnapshot.EFFECTIVE_AT.minusSeconds(1)

        val review = TossBillingFixture(purchase = false, autoCharge = false, reviewWorkspace = true, now = justBefore)
        assertThat(review.service.purchaseAllowed(review.workspace)).isTrue()
        review.service.process(review.order.id)
        assertThat(review.gateway.charges).isEqualTo(1)

        val open = TossBillingFixture(purchase = true, now = justBefore)
        assertThat(open.service.purchaseAllowed(open.workspace)).isTrue()
    }

    @Test
    fun `production keeps existing Start only plan validation`() {
        val f = TossBillingFixture(environment = PaymentEnvironments.TOSS_LIVE)
        f.store.orders.clear()
        f.store.currentSession = null
        assertThatThrownBy {
            f.service.begin(f.owner, f.workspace, "basic", consentVersion = "start-monthly-v1")
        }.isInstanceOf(kr.easydoc.core.exceptions.InvalidInputException::class.java)
            .hasMessage("알 수 없는 구독 플랜입니다")
    }
}
