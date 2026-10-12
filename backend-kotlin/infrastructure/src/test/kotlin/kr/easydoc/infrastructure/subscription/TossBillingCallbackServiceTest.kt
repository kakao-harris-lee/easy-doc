package kr.easydoc.infrastructure.subscription

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.credit.CreditAccountRepository
import kr.easydoc.application.subscription.BillingSession
import kr.easydoc.application.subscription.TossBillingCallbackArtifact
import kr.easydoc.application.subscription.TossBillingCallbackService
import kr.easydoc.application.subscription.TossBillingCallbackStore
import kr.easydoc.application.subscription.TossBillingService
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.security.Secret
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class TossBillingCallbackServiceTest {
    @Test
    fun `provider completion runs after callback claim transaction has committed`() {
        val now = Instant.parse("2026-10-11T00:00:00Z")
        val owner = UUID.randomUUID()
        val workspace = UUID.randomUUID()
        val customer = UUID.randomUUID()
        val session =
            BillingSession(
                workspaceId = workspace,
                id = UUID.randomUUID(),
                customer = customer,
                planId = "start",
                expiresAt = now.plusSeconds(900),
            )
        val store = RecordingCallbackStore()
        val transactions = DepthTrackingTransactionRunner()
        val accounts = Mockito.mock(CreditAccountRepository::class.java)
        val toss = Mockito.mock(TossBillingService::class.java)
        `when`(accounts.ownerOf(workspace)).thenReturn(owner)
        `when`(toss.environment).thenReturn("toss_test")
        `when`(toss.state(workspace)).thenReturn("authorizing")
        doAnswer {
            assertThat(transactions.depth).isZero()
            null
        }.`when`(toss).complete(
            owner,
            workspace,
            session.id,
            customer,
            Secret("auth-value"),
            false,
        )
        val service = service(now, store, transactions, toss, accounts)

        val state = service.register(session)
        val resume = service.capture(state, "auth-value", customer.toString(), null)

        val result = service.resume(owner, resume)

        assertThat(result.workspaceId).isEqualTo(workspace)
        assertThat(store.row?.completedAt).isEqualTo(now)
        assertThat(store.row?.processingUntil).isNull()
    }

    @Test
    fun `resume hides a callback bearer from a different owner`() {
        val now = Instant.parse("2026-10-11T00:00:00Z")
        val owner = UUID.randomUUID()
        val stranger = UUID.randomUUID()
        val workspace = UUID.randomUUID()
        val store = RecordingCallbackStore()
        val transactions = DepthTrackingTransactionRunner()
        val accounts = Mockito.mock(CreditAccountRepository::class.java)
        val toss = Mockito.mock(TossBillingService::class.java)
        `when`(accounts.ownerOf(workspace)).thenReturn(owner)
        val service = service(now, store, transactions, toss, accounts)
        store.row =
            artifact(
                workspace = workspace,
                now = now,
                payload =
                    kr.easydoc.application.subscription.TossBillingCallbackPayload(
                        Secret("auth-value"),
                        "resume",
                    ),
            )

        assertThatThrownBy { service.resume(stranger, "A".repeat(43)) }
            .isInstanceOf(NotFoundException::class.java)
        verifyNoInteractions(toss)
    }

    @Test
    fun `expired auth is scrubbed before the committed expired response`() {
        val now = Instant.parse("2026-10-11T00:00:00Z")
        val owner = UUID.randomUUID()
        val workspace = UUID.randomUUID()
        val store = RecordingCallbackStore()
        val transactions = DepthTrackingTransactionRunner()
        val accounts = Mockito.mock(CreditAccountRepository::class.java)
        val toss = Mockito.mock(TossBillingService::class.java)
        `when`(accounts.ownerOf(workspace)).thenReturn(owner)
        `when`(toss.state(workspace)).thenReturn("authorizing")
        val service = service(now, store, transactions, toss, accounts)
        store.row =
            artifact(
                workspace = workspace,
                now = now,
                authExpiresAt = now.minusSeconds(1),
                payload =
                    kr.easydoc.application.subscription.TossBillingCallbackPayload(
                        Secret("expired-auth"),
                        "resume",
                    ),
            )

        assertThatThrownBy { service.resume(owner, "A".repeat(43)) }
            .isInstanceOf(ConflictException::class.java)
        assertThat(store.row?.payload?.authKey).isNull()
        assertThat(store.row?.authExpiresAt).isNull()
        verify(toss).state(workspace)
        verifyNoMoreInteractions(toss)
    }

    @Test
    fun `busy lease and a late old completion cannot clear a newer lease`() {
        val now = Instant.parse("2026-10-11T00:00:00Z")
        val owner = UUID.randomUUID()
        val workspace = UUID.randomUUID()
        val store = RecordingCallbackStore()
        val transactions = DepthTrackingTransactionRunner()
        val accounts = Mockito.mock(CreditAccountRepository::class.java)
        val toss = Mockito.mock(TossBillingService::class.java)
        `when`(accounts.ownerOf(workspace)).thenReturn(owner)
        `when`(toss.state(workspace)).thenReturn("authorizing")
        val service = service(now, store, transactions, toss, accounts)
        val current =
            artifact(
                workspace = workspace,
                now = now,
                payload =
                    kr.easydoc.application.subscription.TossBillingCallbackPayload(
                        Secret("auth-value"),
                        "resume",
                    ),
            )
        store.row = current
        val busy = current.copy(processingUntil = now.plusSeconds(30))
        store.row = busy
        assertThatThrownBy { service.resume(owner, "A".repeat(43)) }
            .isInstanceOf(ConflictException::class.java)

        store.row = current
        doAnswer {
            store.row = requireNotNull(store.row).copy(processingUntil = now.plusSeconds(300))
            null
        }.`when`(toss).complete(
            owner,
            workspace,
            current.sessionId,
            current.customerId,
            Secret("auth-value"),
            false,
        )
        service.resume(owner, "A".repeat(43))

        assertThat(store.row?.processingUntil).isEqualTo(now.plusSeconds(300))
        assertThat(store.row?.completedAt).isNull()
    }

    private class DepthTrackingTransactionRunner : TransactionRunner {
        var depth = 0

        override fun <T> inTransaction(block: () -> T): T {
            depth += 1
            return try {
                block()
            } finally {
                depth -= 1
            }
        }
    }

    private class RecordingCallbackStore : TossBillingCallbackStore {
        var row: TossBillingCallbackArtifact? = null

        override fun save(artifact: TossBillingCallbackArtifact) {
            row = artifact
        }

        override fun findByState(stateHash: String): TossBillingCallbackArtifact? = row

        override fun findByResume(resumeHash: String): TossBillingCallbackArtifact? = row

        override fun sessionMatches(artifact: TossBillingCallbackArtifact): Boolean = true

        override fun purge(now: Instant): Int = 0
    }

    private fun service(
        now: Instant,
        store: RecordingCallbackStore,
        transactions: DepthTrackingTransactionRunner,
        toss: TossBillingService,
        accounts: CreditAccountRepository,
    ) = TossBillingCallbackService(
        store = store,
        toss = toss,
        accounts = accounts,
        transaction = transactions,
        clock = Clock.fixed(now, ZoneOffset.UTC),
        environment = "toss_test",
    )

    private fun artifact(
        workspace: UUID,
        now: Instant,
        authExpiresAt: Instant? = now.plusSeconds(600),
        processingUntil: Instant? = null,
        payload: kr.easydoc.application.subscription.TossBillingCallbackPayload =
            kr.easydoc.application.subscription
                .TossBillingCallbackPayload(),
    ) = TossBillingCallbackArtifact(
        id = UUID.randomUUID(),
        workspaceId = workspace,
        sessionId = UUID.randomUUID(),
        customerId = UUID.randomUUID(),
        environment = "toss_test",
        purpose = "purchase",
        stateHash = "a".repeat(64),
        resumeHash = "b".repeat(64),
        stateExpiresAt = now.plusSeconds(900),
        authExpiresAt = authExpiresAt,
        resumeExpiresAt = now.plusSeconds(86_400),
        processingUntil = processingUntil,
        completedAt = null,
        payload = payload,
    )
}
