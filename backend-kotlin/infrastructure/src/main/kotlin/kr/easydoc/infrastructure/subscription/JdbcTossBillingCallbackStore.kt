package kr.easydoc.infrastructure.subscription

import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.subscription.TossBillingCallbackArtifact
import kr.easydoc.application.subscription.TossBillingCallbackStore
import kr.easydoc.core.crypto.EncryptedContent
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** JDBC adapter for short-lived server-side billing callback hand-offs. */
class JdbcTossBillingCallbackStore(
    private val jdbc: JdbcClient,
    private val cipher: ContentCipher,
) : TossBillingCallbackStore {
    private val codec = BillingCallbackPayloadCodec(cipher)

    override fun save(artifact: TossBillingCallbackArtifact) {
        val encrypted = codec.encode(artifact.id, artifact.payload)
        jdbc
            .sql(
                """
                INSERT INTO toss_billing_callbacks
                  (id,workspace_id,session_id,customer,environment,purpose,state_hash,resume_hash,
                   state_expires_at,auth_expires_at,resume_expires_at,completed_at,payload_encrypted,
                   processing_until,encryption_scheme,key_version,updated_at)
                VALUES (:id,:workspace,:session,:customer,:environment,:purpose,:stateHash,:resumeHash,
                        :stateExpires,:authExpires,:resumeExpires,:completed,:payload,:processingUntil,
                        :scheme,:version,now())
                ON CONFLICT (id) DO UPDATE SET
                  resume_hash=EXCLUDED.resume_hash,state_expires_at=EXCLUDED.state_expires_at,
                  auth_expires_at=EXCLUDED.auth_expires_at,resume_expires_at=EXCLUDED.resume_expires_at,
                  completed_at=EXCLUDED.completed_at,processing_until=EXCLUDED.processing_until,
                  payload_encrypted=EXCLUDED.payload_encrypted,
                  encryption_scheme=EXCLUDED.encryption_scheme,key_version=EXCLUDED.key_version,
                  updated_at=now()
                """.trimIndent(),
            ).param("id", artifact.id)
            .param("workspace", artifact.workspaceId)
            .param("session", artifact.sessionId)
            .param("customer", artifact.customerId)
            .param("environment", artifact.environment)
            .param("purpose", artifact.purpose)
            .param("stateHash", artifact.stateHash)
            .param("resumeHash", artifact.resumeHash)
            .param("stateExpires", artifact.stateExpiresAt.atOffset(ZoneOffset.UTC))
            .param("authExpires", artifact.authExpiresAt?.atOffset(ZoneOffset.UTC))
            .param("resumeExpires", artifact.resumeExpiresAt?.atOffset(ZoneOffset.UTC))
            .param("completed", artifact.completedAt?.atOffset(ZoneOffset.UTC))
            .param("payload", encrypted.bytes)
            .param("processingUntil", artifact.processingUntil?.atOffset(ZoneOffset.UTC))
            .param("scheme", encrypted.scheme)
            .param("version", encrypted.keyVersion)
            .update()
    }

    override fun findByState(stateHash: String): TossBillingCallbackArtifact? = find("state_hash", stateHash)

    override fun findByResume(resumeHash: String): TossBillingCallbackArtifact? = find("resume_hash", resumeHash)

    override fun sessionMatches(artifact: TossBillingCallbackArtifact): Boolean =
        jdbc
            .sql(
                """
                SELECT EXISTS(
                    SELECT 1 FROM toss_billing_sessions
                    WHERE workspace_id=:workspace AND id=:session AND customer=:customer AND environment=:environment
                )
                """.trimIndent(),
            ).param("workspace", artifact.workspaceId)
            .param("session", artifact.sessionId)
            .param("customer", artifact.customerId)
            .param("environment", artifact.environment)
            .query(Boolean::class.java)
            .single()

    @Suppress("LongMethod") // Locked expiry cleanup deletes metadata and scrubs complete encrypted envelopes.
    override fun purge(now: Instant): Int {
        val instant = now.atOffset(ZoneOffset.UTC)
        var changed =
            jdbc
                .sql(
                    """
                    DELETE FROM toss_billing_callbacks
                    WHERE (resume_hash IS NULL AND state_expires_at<=:now)
                       OR (resume_hash IS NOT NULL AND resume_expires_at<=:now)
                    """.trimIndent(),
                ).param("now", instant)
                .update()

        // Credentials must leave the encrypted envelope at the ten-minute deadline. This is an
        // optimistic compare-and-swap: capture/resume wins if it changed the row after the
        // candidate was read, and the next cleanup tick retries the old ciphertext safely.
        val expired =
            jdbc
                .sql(
                    """
                    SELECT id,payload_encrypted,encryption_scheme,key_version
                    FROM toss_billing_callbacks
                    WHERE auth_expires_at<=:now
                    """.trimIndent(),
                ).param("now", instant)
                .query { rs, _ ->
                    val id = rs.getObject("id", UUID::class.java)
                    Triple(
                        id,
                        EncryptedContent(
                            rs.getBytes("payload_encrypted"),
                            rs.getString("encryption_scheme"),
                            rs.getInt("key_version"),
                        ),
                        codec.decode(
                            id,
                            EncryptedContent(
                                rs.getBytes("payload_encrypted"),
                                rs.getString("encryption_scheme"),
                                rs.getInt("key_version"),
                            ),
                        ),
                    )
                }.list()
        expired.forEach { (id, old, payload) ->
            val scrubbed = codec.encode(id, payload.copy(authKey = null))
            changed +=
                jdbc
                    .sql(
                        """
                        UPDATE toss_billing_callbacks
                        SET auth_expires_at=NULL,payload_encrypted=:payload,
                            encryption_scheme=:scheme,key_version=:version,updated_at=now()
                        WHERE id=:id AND auth_expires_at<=:now
                          AND payload_encrypted=:old
                        """.trimIndent(),
                    ).param("payload", scrubbed.bytes)
                    .param("scheme", scrubbed.scheme)
                    .param("version", scrubbed.keyVersion)
                    .param("id", id)
                    .param("now", instant)
                    .param("old", old.bytes)
                    .update()
        }
        return changed
    }

    /** Rotate callback payloads with compare-and-swap so a concurrent capture cannot be lost. */
    fun rotateSecrets() {
        val rows =
            jdbc
                .sql(
                    "SELECT id,payload_encrypted,encryption_scheme,key_version FROM toss_billing_callbacks " +
                        "WHERE key_version<>:version",
                ).param("version", cipher.writeKeyVersion)
                .query { rs, _ ->
                    rs.getObject("id", UUID::class.java) to
                        EncryptedContent(
                            rs.getBytes("payload_encrypted"),
                            rs.getString("encryption_scheme"),
                            rs.getInt("key_version"),
                        )
                }.list()
        rows.forEach { (id, old) ->
            val updated = codec.rotate(id, old)
            val changed =
                jdbc
                    .sql(
                        """
                        UPDATE toss_billing_callbacks SET payload_encrypted=:payload,
                            encryption_scheme=:scheme,key_version=:version,updated_at=now()
                        WHERE id=:id AND payload_encrypted=:old
                        """.trimIndent(),
                    ).param("payload", updated.bytes)
                    .param("scheme", updated.scheme)
                    .param("version", updated.keyVersion)
                    .param("id", id)
                    .param("old", old.bytes)
                    .update()
            check(changed == 1) { "Billing callback envelope changed concurrently; retry rotation" }
        }
    }

    private fun find(
        column: String,
        value: String,
    ): TossBillingCallbackArtifact? =
        jdbc
            .sql(
                "SELECT * FROM toss_billing_callbacks WHERE $column=:value FOR UPDATE",
            ).param("value", value)
            .query { rs, _ -> map(rs) }
            .optional()
            .orElse(null)

    private fun map(rs: java.sql.ResultSet): TossBillingCallbackArtifact {
        val id = rs.getObject("id", UUID::class.java)
        return TossBillingCallbackArtifact(
            id = id,
            workspaceId = rs.getObject("workspace_id", UUID::class.java),
            sessionId = rs.getObject("session_id", UUID::class.java),
            customerId = rs.getObject("customer", UUID::class.java),
            environment = rs.getString("environment"),
            purpose = rs.getString("purpose"),
            stateHash = rs.getString("state_hash"),
            resumeHash = rs.getString("resume_hash"),
            stateExpiresAt = instant(rs, "state_expires_at"),
            authExpiresAt = rs.getObject("auth_expires_at", OffsetDateTime::class.java)?.toInstant(),
            resumeExpiresAt = rs.getObject("resume_expires_at", OffsetDateTime::class.java)?.toInstant(),
            processingUntil = rs.getObject("processing_until", OffsetDateTime::class.java)?.toInstant(),
            completedAt = rs.getObject("completed_at", OffsetDateTime::class.java)?.toInstant(),
            payload =
                codec.decode(
                    id,
                    EncryptedContent(
                        rs.getBytes("payload_encrypted"),
                        rs.getString("encryption_scheme"),
                        rs.getInt("key_version"),
                    ),
                ),
        )
    }

    private fun instant(
        rs: java.sql.ResultSet,
        name: String,
    ): Instant = rs.getObject(name, OffsetDateTime::class.java).toInstant()
}
