package kr.easydoc.infrastructure.subscription

import kr.easydoc.application.subscription.BillingOrder
import kr.easydoc.application.subscription.BillingSession
import kr.easydoc.application.subscription.TossPayment
import kr.easydoc.core.exceptions.DecryptionFailedException
import kr.easydoc.core.security.Secret
import kr.easydoc.infrastructure.PostgresTestSupport
import kr.easydoc.infrastructure.crypto.AesGcmContentCipher
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

class TossStoreTest {
    @Test
    fun `billing envelopes rotate both fields without changing leases and reject swapped ciphertext`() {
        val db = PostgresTestSupport.createEmptyDatabase("toss_rotation")
        Flyway
            .configure()
            .dataSource(db.jdbcUrl, db.username, db.password)
            .load()
            .migrate()
        val jdbc = JdbcClient.create(DriverManagerDataSource(db.jdbcUrl, db.username, db.password))
        val owner = UUID.randomUUID()
        val workspace = UUID.randomUUID()
        db.execute("INSERT INTO users(id,email,password_hash) VALUES ('$owner','rotation@example.test','test')")
        db.execute("INSERT INTO workspaces(id,user_id,name) VALUES ('$workspace','$owner','rotation')")
        val keys = mapOf(1 to key(1), 2 to key(2))
        val old = JdbcTossBillingStore(jdbc, AesGcmContentCipher(keys, 1))
        val session =
            BillingSession(
                workspace,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "start",
                Instant.now(),
                Secret("synthetic-auth-secret"),
                Secret("synthetic-billing-secret"),
                "active",
            )
        old.saveSession(session)
        val order = BillingOrder(UUID.randomUUID(), workspace, "start", 1000, Instant.now(), Instant.now())
        old.saveOrder(
            order.copy(
                payment =
                    TossPayment(
                        Secret("synthetic-payment-secret"),
                        order.id,
                        "DONE",
                        1000,
                        1000,
                        Secret("https://example.test/private-receipt"),
                    ),
            ),
        )
        assertThat(old.claim(order.id, Instant.now())).isNotNull()
        val rotated = JdbcTossBillingStore(jdbc, AesGcmContentCipher(keys, 2))
        rotated.rotateSecrets()
        val newOnly = JdbcTossBillingStore(jdbc, AesGcmContentCipher(mapOf(2 to keys.getValue(2)), 2))
        assertThat(newOnly.session(workspace)?.billingKey).isEqualTo(session.billingKey)
        assertThat(newOnly.order(order.id)?.payment?.key).isEqualTo(Secret("synthetic-payment-secret"))
        assertThat(db.queryInt("SELECT count(*) FROM toss_billing_orders WHERE lease_until IS NOT NULL")).isEqualTo(1)
        assertThat(db.queryInt("SELECT count(*) FROM toss_billing_sessions WHERE key_version=2")).isEqualTo(1)
        assertThat(db.queryInt("SELECT count(*) FROM toss_billing_orders WHERE key_version=2")).isEqualTo(1)
        db.execute(
            """UPDATE toss_billing_orders SET payload_encrypted=(SELECT payload_encrypted FROM toss_billing_sessions),
                encryption_scheme=encryption_scheme, key_version=key_version""",
        )
        assertThatThrownBy { newOnly.order(order.id) }.isInstanceOf(DecryptionFailedException::class.java)
    }

    @Test
    fun `authorization candidates filter expired sessions before the batch limit`() {
        val db = PostgresTestSupport.createEmptyDatabase("toss_authorization_candidates")
        Flyway
            .configure()
            .dataSource(db.jdbcUrl, db.username, db.password)
            .load()
            .migrate()
        val jdbc = JdbcClient.create(DriverManagerDataSource(db.jdbcUrl, db.username, db.password))
        val store = JdbcTossBillingStore(jdbc, AesGcmContentCipher(mapOf(1 to key(1)), 1))
        val now = Instant.parse("2026-09-28T00:00:00Z")
        val retryWindow = Duration.ofDays(14)
        val cutoff = now.minus(retryWindow)
        val expired =
            (0 until 50).map { index ->
                saveIssuingSession(db, store, now.minus(retryWindow).minusSeconds(index + 1L), index)
            }
        val boundary = saveIssuingSession(db, store, cutoff, 50)
        val live = saveIssuingSession(db, store, cutoff.plusSeconds(1), 51)

        val candidates = store.authorizationCandidates(cutoff)

        assertThat(candidates).containsExactly(live)
        assertThat(candidates).doesNotContainAnyElementsOf(expired + boundary)
        assertThat(store.session(expired.first())?.state).isEqualTo("issuing")
        assertThat(store.session(expired.first())?.authKey).isEqualTo(Secret("auth-0"))
    }

    private fun saveIssuingSession(
        db: kr.easydoc.infrastructure.DatabaseHandle,
        store: JdbcTossBillingStore,
        expiresAt: Instant,
        index: Int,
    ): UUID {
        val owner = UUID.randomUUID()
        val workspace = UUID.randomUUID()
        db.execute("INSERT INTO users(id,email,password_hash) VALUES ('$owner','auth-$index@example.test','test')")
        db.execute("INSERT INTO workspaces(id,user_id,name) VALUES ('$workspace','$owner','auth-$index')")
        store.saveSession(
            BillingSession(
                workspace,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "start",
                expiresAt,
                Secret("auth-$index"),
                state = "issuing",
            ),
        )
        return workspace
    }

    private fun key(value: Byte) = Secret(Base64.getEncoder().encodeToString(ByteArray(32) { value }))
}
