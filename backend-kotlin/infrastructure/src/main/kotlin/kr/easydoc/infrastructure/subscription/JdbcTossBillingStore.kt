package kr.easydoc.infrastructure.subscription

import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.subscription.BillingOrder
import kr.easydoc.application.subscription.BillingSession
import kr.easydoc.application.subscription.TossBillingStore
import kr.easydoc.application.subscription.TossPayment
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.security.Secret
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Suppress("TooManyFunctions") // Implements the durable billing store and encrypted payload codecs.
class JdbcTossBillingStore(
    private val jdbc: JdbcClient,
    private val cipher: ContentCipher,
) : TossBillingStore {
    private val json = ObjectMapper()

    override fun session(workspace: UUID): BillingSession? =
        jdbc
            .sql("SELECT * FROM toss_billing_sessions WHERE workspace_id=:workspace")
            .param("workspace", workspace)
            .query { rs, _ ->
                val id = rs.getObject("id", UUID::class.java)
                val data = payload(rs, id, EncryptedField.BILLING_SESSION)
                BillingSession(
                    rs.getObject("workspace_id", UUID::class.java),
                    id,
                    rs.getObject("customer", UUID::class.java),
                    rs.getString("plan_id"),
                    instant(rs, "expires_at"),
                    secret(data, "auth"),
                    secret(data, "billing"),
                    rs.getString("state"),
                    data.path("fail").asBoolean(false),
                )
            }.optional()
            .orElse(null)

    override fun saveSession(session: BillingSession) {
        val encrypted =
            seal(
                session.id,
                EncryptedField.BILLING_SESSION,
                mapOf(
                    "auth" to session.authKey?.reveal(),
                    "billing" to session.billingKey?.reveal(),
                    "fail" to session.simulateFailure,
                ),
            )
        jdbc
            .sql(
                """
                INSERT INTO toss_billing_sessions
                  (workspace_id,id,customer,plan_id,state,expires_at,payload_encrypted,encryption_scheme,key_version)
                VALUES (:workspace,:id,:customer,:plan,:state,:expires,:payload,:scheme,:version)
                ON CONFLICT (workspace_id) DO UPDATE SET id=EXCLUDED.id, customer=EXCLUDED.customer,
                  plan_id=EXCLUDED.plan_id,
                  state=EXCLUDED.state, expires_at=EXCLUDED.expires_at, payload_encrypted=EXCLUDED.payload_encrypted,
                  encryption_scheme=EXCLUDED.encryption_scheme, key_version=EXCLUDED.key_version
                """.trimIndent(),
            ).param("workspace", session.workspaceId)
            .param("id", session.id)
            .param("customer", session.customer)
            .param(
                "plan",
                session.planId,
            ).param("state", session.state)
            .param("expires", session.expiresAt.atOffset(ZoneOffset.UTC))
            .param(
                "payload",
                encrypted.bytes,
            ).param("scheme", encrypted.scheme)
            .param("version", encrypted.keyVersion)
            .update()
    }

    override fun order(id: UUID): BillingOrder? =
        jdbc
            .sql("SELECT * FROM toss_billing_orders WHERE id=:id")
            .param("id", id)
            .query { rs, _ ->
                val payload = payload(rs, id, EncryptedField.BILLING_ORDER)
                val payment =
                    secret(payload, "key")?.let {
                        TossPayment(
                            it,
                            UUID.fromString(payload.path("order").asString()),
                            payload.path("status").asString(),
                            payload.path("amount").intValue(),
                            payload.path("remaining").intValue(),
                            secret(payload, "receipt") ?: Secret.EMPTY,
                        )
                    }
                BillingOrder(
                    id,
                    rs.getObject("workspace_id", UUID::class.java),
                    rs.getString("plan_id"),
                    rs.getInt("amount"),
                    instant(rs, "created_at"),
                    instant(rs, "cycle_ends_at"),
                    rs.getString("kind"),
                    rs.getObject("original_id", UUID::class.java),
                    rs.getInt("previous_remaining"),
                    rs.getString("status"),
                    payment,
                    rs.getBoolean("simulate_failure"),
                )
            }.optional()
            .orElse(null)

    override fun saveOrder(order: BillingOrder) {
        val p = order.payment
        val encrypted =
            seal(
                order.id,
                EncryptedField.BILLING_ORDER,
                mapOf(
                    "key" to p?.key?.reveal(),
                    "order" to p?.orderId?.toString(),
                    "status" to p?.status,
                    "amount" to p?.amount,
                    "remaining" to p?.remainingAmount,
                    "receipt" to p?.receipt?.reveal(),
                ),
            )
        jdbc
            .sql(
                """
                INSERT INTO toss_billing_orders
                  (id,workspace_id,plan_id,amount,created_at,cycle_ends_at,kind,original_id,
                  previous_remaining,status,simulate_failure,payload_encrypted,encryption_scheme,key_version)
                VALUES (:id,:workspace,:plan,:amount,:created,:end,:kind,:original,:previous,:status,:fail,:payload,
                  :scheme,:version)
                ON CONFLICT (id) DO UPDATE SET status=EXCLUDED.status,payload_encrypted=EXCLUDED.payload_encrypted,
                  encryption_scheme=EXCLUDED.encryption_scheme,key_version=EXCLUDED.key_version,lease_until=NULL,
                    sync_requested=false
                """.trimIndent(),
            ).param(
                "id",
                order.id,
            ).param("workspace", order.workspaceId)
            .param("plan", order.planId)
            .param("amount", order.amount)
            .param(
                "created",
                order.createdAt.atOffset(ZoneOffset.UTC),
            ).param("end", order.cycleEndsAt.atOffset(ZoneOffset.UTC))
            .param("kind", order.kind)
            .param("original", order.originalId)
            .param("previous", order.previousRemaining)
            .param("status", order.status)
            .param("fail", order.simulateFailure)
            .param("payload", encrypted.bytes)
            .param("scheme", encrypted.scheme)
            .param("version", encrypted.keyVersion)
            .update()
    }

    override fun pending(workspace: UUID): Boolean =
        jdbc
            .sql(
                "SELECT count(*) FROM toss_billing_orders WHERE workspace_id=:workspace AND status IN " +
                    "('pending','processing','manual_review')",
            ).param("workspace", workspace)
            .query(Int::class.java)
            .single() > 0

    override fun claim(
        id: UUID,
        now: Instant,
    ): BillingOrder? {
        val changed =
            jdbc
                .sql(
                    """
                    UPDATE toss_billing_orders SET lease_until=:lease
                    WHERE id=:id AND (lease_until IS NULL OR lease_until <= :now)
                    """.trimIndent(),
                ).param(
                    "id",
                    id,
                ).param(
                    "now",
                    now.atOffset(ZoneOffset.UTC),
                ).param("lease", now.plusSeconds(LEASE_SECONDS).atOffset(ZoneOffset.UTC))
                .update()
        return if (changed == 1) order(id) else null
    }

    override fun release(
        id: UUID,
        nextAttempt: Instant,
    ) {
        jdbc
            .sql(
                "UPDATE toss_billing_orders SET lease_until=NULL,next_attempt_at=:next WHERE id=:id",
            ).param("id", id)
            .param("next", nextAttempt.atOffset(ZoneOffset.UTC))
            .update()
    }

    override fun candidates(now: Instant): List<UUID> =
        jdbc
            .sql(
                """
                SELECT id FROM toss_billing_orders WHERE ((status IN ('pending','processing') AND
                  next_attempt_at<=:now) OR sync_requested)
                  AND (lease_until IS NULL OR lease_until<=:now) ORDER BY next_attempt_at LIMIT 50
                """.trimIndent(),
            ).param("now", now.atOffset(ZoneOffset.UTC))
            .query(UUID::class.java)
            .list()
            .filterNotNull()

    override fun requestSync(id: UUID) {
        jdbc
            .sql(
                "UPDATE toss_billing_orders SET sync_requested=true WHERE id=:id AND kind='charge'",
            ).param("id", id)
            .update()
    }

    override fun savePayment(
        order: BillingOrder,
        payment: TossPayment,
    ) {
        val status =
            when {
                payment.remainingAmount == 0 -> "refunded"
                payment.remainingAmount < payment.amount -> "partially_refunded"
                else -> "paid"
            }
        jdbc
            .sql(
                """
                INSERT INTO subscription_payments
                  (workspace_id,id,plan_id,amount,status,created_at,simulated_failure,provider,refunded_amount)
                VALUES (:workspace,:id,:plan,:amount,:status,:created,:fail,'toss_test',:refunded)
                ON CONFLICT (workspace_id,id) DO UPDATE SET
                  status=EXCLUDED.status,refunded_amount=EXCLUDED.refunded_amount
                """.trimIndent(),
            ).param(
                "workspace",
                order.workspaceId,
            ).param("id", order.id)
            .param("plan", order.planId)
            .param("amount", payment.amount)
            .param(
                "status",
                status,
            ).param("created", order.createdAt.atOffset(ZoneOffset.UTC))
            .param("fail", order.simulateFailure)
            .param("refunded", payment.amount - payment.remainingAmount)
            .update()
    }

    override fun cleanupCandidates(): List<UUID> =
        jdbc
            .sql(
                "SELECT workspace_id FROM toss_billing_sessions WHERE state='revoking' LIMIT 50",
            ).query(UUID::class.java)
            .list()
            .filterNotNull()

    override fun authorizationCandidates(): List<UUID> =
        jdbc
            .sql(
                "SELECT workspace_id FROM toss_billing_sessions WHERE state='issuing' ORDER BY expires_at LIMIT 50",
            ).query(UUID::class.java)
            .list()
            .filterNotNull()

    /** Update only the envelope, and only if the ciphertext read is still current. Never resets a payment lease. */
    fun rotateSecrets() {
        listOf(EncryptedField.BILLING_SESSION, EncryptedField.BILLING_ORDER).forEach { field ->
            val table = field.wireName.substringBefore('.')
            val rows =
                jdbc
                    .sql(
                        "SELECT id,payload_encrypted,encryption_scheme,key_version FROM $table WHERE " +
                            "key_version<>:version",
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
                val updated = cipher.encrypt(cipher.decrypt(old, id, field), id, field)
                val count =
                    jdbc
                        .sql(
                            "UPDATE $table SET " +
                                "payload_encrypted=:payload,encryption_scheme=:scheme," +
                                "key_version=:version " +
                                "WHERE id=:id AND payload_encrypted=:old",
                        ).param(
                            "payload",
                            updated.bytes,
                        ).param("scheme", updated.scheme)
                        .param("version", updated.keyVersion)
                        .param("id", id)
                        .param("old", old.bytes)
                        .update()
                check(count == 1) { "Billing key rotation encountered a concurrent update; retry rotation" }
            }
        }
    }

    private fun seal(
        id: UUID,
        field: EncryptedField,
        data: Map<String, Any?>,
    ) = cipher.encrypt(PlainBody(json.writeValueAsString(data)), id, field)

    private fun payload(
        rs: ResultSet,
        id: UUID,
        field: EncryptedField,
    ): JsonNode =
        json.readTree(
            cipher
                .decrypt(
                    EncryptedContent(
                        rs.getBytes("payload_encrypted"),
                        rs.getString("encryption_scheme"),
                        rs.getInt("key_version"),
                    ),
                    id,
                    field,
                ).value,
        )

    private fun secret(
        node: JsonNode,
        key: String,
    ): Secret? =
        node
            .get(key)
            ?.takeUnless {
                it.isNull
            }?.asString()
            ?.let(::Secret)

    private companion object {
        const val LEASE_SECONDS = 120L
    }

    private fun instant(
        rs: ResultSet,
        key: String,
    ): Instant = rs.getObject(key, OffsetDateTime::class.java).toInstant()
}
