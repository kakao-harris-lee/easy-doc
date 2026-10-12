package kr.easydoc.infrastructure.subscription

import kr.easydoc.application.subscription.TossBillingCallbackArtifact
import kr.easydoc.application.subscription.TossBillingCallbackPayload
import kr.easydoc.core.security.Secret
import kr.easydoc.infrastructure.PostgresTestSupport
import kr.easydoc.infrastructure.crypto.AesGcmContentCipher
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Instant
import java.util.Base64
import java.util.UUID

class JdbcTossBillingCallbackStoreTest {
    @Test
    fun `purge scrubs expired auth and removes expired callback metadata`() {
        val db = database("toss_callback_purge")
        val jdbc = JdbcClient.create(DriverManagerDataSource(db.jdbcUrl, db.username, db.password))
        val store = JdbcTossBillingCallbackStore(jdbc, cipher(1))
        val workspace = workspace(db, "purge")
        val now = Instant.parse("2026-10-11T00:00:00Z")
        val retained = artifact(workspace, now, stateHash = "a".repeat(64), authExpiresAt = now.minusSeconds(1))
        val expired =
            artifact(
                workspace,
                now,
                stateHash = "b".repeat(64),
                stateExpiresAt = now.minusSeconds(1),
                resumeHash = null,
                resumeExpiresAt = null,
                payload = TossBillingCallbackPayload(),
            )
        store.save(retained)
        store.save(expired)

        val changed = store.purge(now)

        assertThat(changed).isEqualTo(2)
        val scrubbed = requireNotNull(store.findByState(retained.stateHash))
        assertThat(scrubbed.payload.authKey).isNull()
        assertThat(scrubbed.payload.resumeToken).isEqualTo(retained.payload.resumeToken)
        assertThat(scrubbed.authExpiresAt).isNull()
        assertThat(store.findByState(expired.stateHash)).isNull()
    }

    @Test
    fun `rotation re-encrypts callback envelope and preserves readable metadata`() {
        val db = database("toss_callback_rotation")
        val jdbc = JdbcClient.create(DriverManagerDataSource(db.jdbcUrl, db.username, db.password))
        val workspace = workspace(db, "rotation")
        val now = Instant.parse("2026-10-11T00:00:00Z")
        val old = JdbcTossBillingCallbackStore(jdbc, cipher(1))
        val artifact = artifact(workspace, now, stateHash = "c".repeat(64))
        old.save(artifact)

        JdbcTossBillingCallbackStore(jdbc, cipher(2)).rotateSecrets()

        val version =
            jdbc
                .sql("SELECT key_version FROM toss_billing_callbacks WHERE id=:id")
                .param("id", artifact.id)
                .query(Int::class.java)
                .single()
        val current = requireNotNull(JdbcTossBillingCallbackStore(jdbc, cipher(2)).findByState(artifact.stateHash))
        assertThat(version).isEqualTo(2)
        assertThat(current.payload.authKey?.reveal()).isEqualTo("auth-value")
        assertThat(current.payload.resumeToken).isEqualTo(artifact.payload.resumeToken)
    }

    private fun database(name: String): kr.easydoc.infrastructure.DatabaseHandle {
        val db = PostgresTestSupport.createEmptyDatabase(name)
        Flyway
            .configure()
            .dataSource(db.jdbcUrl, db.username, db.password)
            .load()
            .migrate()
        return db
    }

    private fun workspace(
        db: kr.easydoc.infrastructure.DatabaseHandle,
        suffix: String,
    ): UUID {
        val owner = UUID.randomUUID()
        val workspace = UUID.randomUUID()
        db.execute("INSERT INTO users(id,email,password_hash) VALUES ('$owner','$owner@$suffix.test','test')")
        db.execute("INSERT INTO workspaces(id,user_id,name) VALUES ('$workspace','$owner','$suffix')")
        return workspace
    }

    @Suppress("LongParameterList") // Independent expiry fields are explicit in cleanup fixtures.
    private fun artifact(
        workspace: UUID,
        now: Instant,
        stateHash: String,
        stateExpiresAt: Instant = now.plusSeconds(900),
        resumeHash: String? = "d".repeat(64),
        resumeExpiresAt: Instant? = now.plusSeconds(86_400),
        authExpiresAt: Instant? = now.plusSeconds(600),
        payload: TossBillingCallbackPayload =
            TossBillingCallbackPayload(Secret("auth-value"), "resume-value"),
    ) = TossBillingCallbackArtifact(
        id = UUID.randomUUID(),
        workspaceId = workspace,
        sessionId = UUID.randomUUID(),
        customerId = UUID.randomUUID(),
        environment = "toss_test",
        purpose = "purchase",
        stateHash = stateHash,
        resumeHash = resumeHash,
        stateExpiresAt = stateExpiresAt,
        authExpiresAt = authExpiresAt,
        resumeExpiresAt = resumeExpiresAt,
        processingUntil = null,
        completedAt = null,
        payload = payload,
    )

    private fun cipher(version: Int): AesGcmContentCipher =
        AesGcmContentCipher(
            mapOf(
                1 to key(1),
                2 to key(2),
            ),
            version,
        )

    private fun key(value: Byte): Secret = Secret(Base64.getEncoder().encodeToString(ByteArray(32) { value }))
}
