package kr.easydoc.infrastructure.retention

import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.retention.ComplaintDecisionCommand
import kr.easydoc.application.retention.RefundDecisionCommand
import kr.easydoc.application.retention.RetainedRecordType
import kr.easydoc.application.retention.RetainedTransactionAdminRepository
import kr.easydoc.application.retention.RetainedTransactionArchiver
import kr.easydoc.application.retention.RetainedTransactionDecision
import kr.easydoc.application.retention.RetainedTransactionDetail
import kr.easydoc.application.retention.RetainedTransactionFilter
import kr.easydoc.application.retention.RetainedTransactionPurgeStore
import kr.easydoc.application.retention.RetainedTransactionSummary
import kr.easydoc.application.subscription.BillingOrder
import kr.easydoc.application.subscription.BillingSession
import kr.easydoc.application.subscription.TossPayment
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.NotFoundException
import org.springframework.jdbc.core.simple.JdbcClient
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Encrypted, account-independent legal transaction archive.
 *
 * The table intentionally has no FK to the account graph.  Every write here is made by the
 * caller's transaction, while reads and expiry deletion can continue after account deletion.
 */
@Suppress("TooManyFunctions", "LargeClass") // JDBC mapping keeps archive envelopes, decisions and expiry together.
class JdbcRetainedTransactionRepository(
    private val jdbc: JdbcClient,
    cipher: ContentCipher,
    private val clock: Clock = Clock.systemUTC(),
) : RetainedTransactionArchiver,
    RetainedTransactionAdminRepository,
    RetainedTransactionPurgeStore {
    private val codec = RetainedTransactionPayloadCodec(cipher)

    @Suppress("LongMethod") // All evidence shares the payment finalization transaction.
    override fun archivePayment(
        order: BillingOrder,
        payment: TossPayment,
        previousRefundedAmount: Int,
        customerEmail: String?,
        consent: BillingSession?,
    ) {
        if (order.environment != LIVE_ENVIRONMENT || order.kind !in CHARGE_KINDS) return
        val email = customerEmail ?: workspaceOwnerEmail(order.workspaceId)
        val occurredAt = payment.approvedAt
        val manualReview = occurredAt == null
        upsertRecord(
            id = order.id,
            type = RetainedRecordType.PAYMENT,
            sourceId = order.id,
            paymentId = order.id,
            workspaceId = order.workspaceId,
            environment = order.environment,
            occurredAt = occurredAt,
            retention = occurredAt ?: clock.instant(),
            manualReview = manualReview,
            payload =
                mapOf(
                    "customer_email" to email,
                    "order_id" to order.id.toString(),
                    "plan_id" to order.planId,
                    "amount" to payment.amount,
                    "status" to payment.status,
                    "remaining_amount" to payment.remainingAmount,
                    "approved_at" to occurredAt?.toString(),
                ),
        )
        upsertRecord(
            id = supplyId(order.id),
            type = RetainedRecordType.SUPPLY,
            sourceId = order.id,
            paymentId = order.id,
            workspaceId = order.workspaceId,
            environment = order.environment,
            occurredAt = occurredAt,
            retention = occurredAt ?: clock.instant(),
            manualReview = manualReview,
            payload =
                mapOf(
                    "customer_email" to email,
                    "order_id" to order.id.toString(),
                    "plan_id" to order.planId,
                    "amount" to payment.amount,
                    "cycle_ends_at" to order.cycleEndsAt.toString(),
                ),
        )
        val refunded = payment.amount - payment.remainingAmount
        if (refunded > previousRefundedAmount) {
            upsertRecord(
                id = refundId(order.id, refunded),
                type = RetainedRecordType.REFUND,
                sourceId = refundId(order.id, refunded),
                paymentId = order.id,
                workspaceId = order.workspaceId,
                environment = order.environment,
                // Toss does not provide a per-refund timestamp in the payment row. Do not infer it.
                occurredAt = payment.canceledAt,
                retention = payment.canceledAt ?: clock.instant(),
                manualReview = payment.canceledAt == null,
                payload =
                    mapOf(
                        "customer_email" to email,
                        "order_id" to order.id.toString(),
                        "amount" to (refunded - previousRefundedAmount),
                        "cumulative_refunded_amount" to refunded,
                        "remaining_amount" to payment.remainingAmount,
                        "canceled_at" to payment.canceledAt?.toString(),
                    ),
            )
        }
        archiveConsent(
            consent ?: liveConsent(order.workspaceId),
            order.workspaceId,
            order.id,
            email,
            snapshotEligible = true,
            retentionReference = occurredAt,
        )
    }

    @Suppress("LongMethod", "NestedBlockDepth") // Verify every evidence type before the caller cascades live rows.
    override fun archiveForUser(userId: UUID) {
        val workspaces =
            jdbc
                .sql("SELECT id FROM workspaces WHERE user_id=:user ORDER BY id")
                .param("user", userId)
                .query { rs, _ -> rs.getObject("id", UUID::class.java) }
                .list()
        val email = userEmail(userId)
        workspaces.forEach { workspace ->
            val orders = liveOrders(workspace)
            orders.forEach { row ->
                val paymentId = row.orderId
                val approvedAt = row.approvedAt
                val missingEvidence = approvedAt == null
                upsertRecord(
                    id = paymentId,
                    type = RetainedRecordType.PAYMENT,
                    sourceId = paymentId,
                    paymentId = paymentId,
                    workspaceId = workspace,
                    environment = LIVE_ENVIRONMENT,
                    occurredAt = approvedAt,
                    retention = approvedAt ?: clock.instant(),
                    manualReview = missingEvidence,
                    onlyIfMissing = true,
                    payload =
                        mapOf(
                            "customer_email" to email,
                            "order_id" to paymentId.toString(),
                            "plan_id" to row.planId,
                            "amount" to row.amount,
                            "status" to row.status,
                            "remaining_amount" to row.remainingAmount,
                            "approved_at" to approvedAt?.toString(),
                        ),
                )
                upsertRecord(
                    id = supplyId(paymentId),
                    type = RetainedRecordType.SUPPLY,
                    sourceId = paymentId,
                    paymentId = paymentId,
                    workspaceId = workspace,
                    environment = LIVE_ENVIRONMENT,
                    occurredAt = approvedAt,
                    retention = approvedAt ?: clock.instant(),
                    manualReview = missingEvidence,
                    onlyIfMissing = true,
                    payload =
                        mapOf(
                            "customer_email" to email,
                            "order_id" to paymentId.toString(),
                            "plan_id" to row.planId,
                            "amount" to row.amount,
                        ),
                )
                refundOrders(paymentId).forEach { refund ->
                    val cumulativeRefunded = row.amount - refund.previousRemaining + refund.amount
                    if (refund.amount <= 0 || cumulativeRefunded !in refund.amount..row.amount) {
                        throw ConflictException("환불 증빙 금액을 확인할 수 없어 탈퇴할 수 없습니다")
                    }
                    upsertRecord(
                        id = refundId(paymentId, cumulativeRefunded),
                        type = RetainedRecordType.REFUND,
                        sourceId = refundId(paymentId, cumulativeRefunded),
                        paymentId = paymentId,
                        workspaceId = workspace,
                        environment = LIVE_ENVIRONMENT,
                        occurredAt = refund.occurredAt,
                        retention = refund.occurredAt ?: clock.instant(),
                        manualReview = refund.occurredAt == null,
                        onlyIfMissing = true,
                        payload =
                            mapOf(
                                "customer_email" to email,
                                "order_id" to paymentId.toString(),
                                "amount" to refund.amount,
                                "cumulative_refunded_amount" to cumulativeRefunded,
                                "refund_order_id" to refund.id.toString(),
                                "occurred_at" to refund.occurredAt?.toString(),
                            ),
                    )
                }
                if (recordRow(paymentId)?.let { it.manualReview || it.occurredAt == null } != false) {
                    throw ConflictException("결제 승인 시각을 확인할 수 없어 탈퇴 기록을 보존할 수 없습니다")
                }
                val archivedRefunds =
                    jdbc
                        .sql(
                            """
                            SELECT * FROM retained_transaction_records WHERE payment_id=:payment AND
                            record_type='refund'
                            """.trimIndent(),
                        ).param("payment", paymentId)
                        .query { rs, _ -> record(rs) }
                        .list()
                val refundedTotal =
                    archivedRefunds.sumOf {
                        codec
                            .decodeRecord(
                                it.id,
                                it.payload,
                            ).path("amount")
                            .asInt()
                    }
                if (refundedTotal != row.amount - row.remainingAmount) {
                    throw ConflictException("환불 증빙을 확인할 수 없어 탈퇴할 수 없습니다")
                }
            }
            val latestApproval = orders.mapNotNull { recordRow(it.orderId)?.occurredAt }.maxOrNull()
            if (latestApproval != null) {
                liveConsent(workspace)?.let {
                    archiveConsent(
                        it,
                        workspace,
                        null,
                        email,
                        snapshotEligible = false,
                        retentionReference = latestApproval,
                    )
                }
            }
        }
        verifyComplete(userId)
    }

    override fun list(
        filter: RetainedTransactionFilter,
        page: Int,
        size: Int,
    ): Map<String, Any?> {
        val safePage = page.coerceAtLeast(0)
        val safeSize = size.coerceIn(1, MAX_PAGE_SIZE)
        val clauses = mutableListOf<String>()
        val params = linkedMapOf<String, Any?>()
        filter.type?.let {
            clauses += "record_type=:type"
            params["type"] = it.wireName
        }
        filter.from?.let {
            clauses += "occurred_at>=:from"
            params["from"] = it.atOffset(ZoneOffset.UTC)
        }
        filter.to?.let {
            clauses += "occurred_at<:to"
            params["to"] = it.atOffset(ZoneOffset.UTC)
        }
        val where = if (clauses.isEmpty()) "" else " WHERE ${clauses.joinToString(" AND ")}"
        val countQuery = jdbc.sql("SELECT count(*) FROM retained_transaction_records$where")
        params.forEach { (name, value) -> countQuery.param(name, value) }
        val total = countQuery.query(Long::class.java).single()
        val query =
            jdbc.sql(
                """
                SELECT id,record_type,occurred_at,retained_until,payment_id,environment,manual_review
                FROM retained_transaction_records$where
                ORDER BY occurred_at DESC NULLS LAST,created_at DESC,id DESC LIMIT :limit OFFSET :offset
                """.trimIndent(),
            )
        params.forEach { (name, value) -> query.param(name, value) }
        val items =
            query
                .param("limit", safeSize)
                .param("offset", safePage * safeSize)
                .query { rs, _ -> summary(rs) }
                .list()
        return mapOf(
            "items" to items.map(::summaryMap),
            "total" to total,
            "page" to safePage,
            "size" to safeSize,
        )
    }

    override fun find(id: UUID): RetainedTransactionDetail? =
        jdbc
            .sql("SELECT * FROM retained_transaction_records WHERE id=:id")
            .param("id", id)
            .query { rs, _ -> record(rs) }
            .optional()
            .orElse(null)
            ?.let { detail(it) }

    override fun refundDecision(
        id: UUID,
        actor: UUID,
        command: RefundDecisionCommand,
    ): RetainedTransactionDetail {
        val target = recordRow(id, forUpdate = true) ?: throw NotFoundException("보존 거래 기록을 찾을 수 없습니다")
        if (target.type !in setOf(RetainedRecordType.PAYMENT, RetainedRecordType.REFUND)) {
            throw ConflictException("환불 판단 대상이 아닙니다")
        }
        return saveDecision(
            target = target,
            actor = actor,
            operationId = command.operationId,
            type = "refund",
            status = command.status,
            amount = command.amount,
            recoveryCredits = command.recoveryCredits,
            reason = command.reason,
            stopRenewal = command.stopRenewal,
            receivedAt = command.receivedAt,
            retention = retentionUntil(command.receivedAt, TRANSACTION_RETENTION_YEARS),
        )
    }

    override fun complaintDecision(
        id: UUID,
        actor: UUID,
        command: ComplaintDecisionCommand,
    ): RetainedTransactionDetail {
        val target = recordRow(id, forUpdate = true) ?: throw NotFoundException("보존 거래 기록을 찾을 수 없습니다")
        return saveDecision(
            target = target,
            actor = actor,
            operationId = command.operationId,
            type = "complaint",
            status = command.status,
            amount = null,
            recoveryCredits = null,
            reason = command.reason,
            stopRenewal = null,
            receivedAt = command.receivedAt,
            retention = retentionUntil(clock.instant(), COMPLAINT_RETENTION_YEARS),
        )
    }

    override fun purge(
        expiredBefore: Instant,
        batchSize: Int,
        dryRun: Boolean,
    ): Int {
        val decisionIds =
            jdbc
                .sql(
                    """
                    SELECT operation_id FROM retained_transaction_decisions WHERE retained_until<:before ORDER
                    BY retained_until,operation_id LIMIT :limit FOR UPDATE
                    """.trimIndent(),
                ).param("before", expiredBefore.atOffset(ZoneOffset.UTC))
                .param("limit", batchSize)
                .query(UUID::class.java)
                .list()
        val recordIds =
            jdbc
                .sql(
                    """
                    SELECT r.id FROM retained_transaction_records r WHERE r.retained_until<:before AND r.manual_review=false
                    AND NOT EXISTS (SELECT 1 FROM retained_transaction_decisions d WHERE d.record_id=r.id AND d.retained_until>=:before)
                    ORDER BY r.retained_until,r.id LIMIT :limit FOR UPDATE
                    """.trimIndent(),
                ).param("before", expiredBefore.atOffset(ZoneOffset.UTC))
                .param("limit", batchSize)
                .query(UUID::class.java)
                .list()
        if (dryRun) return decisionIds.size + recordIds.size
        val deletedDecisions =
            if (decisionIds.isNotEmpty()) {
                jdbc
                    .sql(
                        """
                        DELETE FROM retained_transaction_decisions WHERE operation_id IN (:ids) AND
                        retained_until<:before
                        """.trimIndent(),
                    ).param("ids", decisionIds)
                    .param("before", expiredBefore.atOffset(ZoneOffset.UTC))
                    .update()
            } else {
                0
            }
        val deletedRecords =
            if (recordIds.isNotEmpty()) {
                jdbc
                    .sql(
                        """
                        DELETE FROM retained_transaction_records WHERE id IN (:ids) AND retained_until<:before AND manual_review=false
                        AND NOT EXISTS (SELECT 1 FROM retained_transaction_decisions d WHERE d.record_id=retained_transaction_records.id AND d.retained_until>=:before)
                        """.trimIndent(),
                    ).param("ids", recordIds)
                    .param("before", expiredBefore.atOffset(ZoneOffset.UTC))
                    .update()
            } else {
                0
            }
        return deletedDecisions + deletedRecords
    }

    fun rotateSecrets() {
        rotateRecords()
        rotateDecisions()
    }

    @Suppress("LongParameterList", "CyclomaticComplexMethod") // Compare every immutable idempotency field.
    private fun saveDecision(
        target: RecordRow,
        actor: UUID,
        operationId: UUID,
        type: String,
        status: String,
        amount: Int?,
        recoveryCredits: BigDecimal?,
        reason: String,
        stopRenewal: Boolean?,
        receivedAt: Instant,
        retention: Instant,
    ): RetainedTransactionDetail {
        val existing = decision(operationId)
        if (existing != null) {
            val payload = codec.decodeDecision(operationId, existing.payload)
            val same =
                payload.path("record_id").asString() == target.id.toString() &&
                    payload.path("type").asString() == type &&
                    payload.path("status").asString() == status &&
                    payload.path("amount").asInt(-1).let { (amount == null && it == -1) || amount == it } &&
                    creditsEqual(payload.path("recovery_credits").asString(), recoveryCredits) &&
                    payload.path("reason").asString() == reason &&
                    payload.path("stop_renewal").asBoolean(false) == (stopRenewal ?: false) &&
                    payload.path("received_at").asString() == receivedAt.toString()
            if (!same) throw ConflictException("같은 operation_id에 다른 판단을 기록할 수 없습니다")
            return detail(recordRow(existing.recordId) ?: target)
        }
        val payload =
            mapOf(
                "record_id" to target.id.toString(),
                "type" to type,
                "status" to status,
                "amount" to amount,
                "recovery_credits" to recoveryCredits?.toPlainString(),
                "reason" to reason,
                "stop_renewal" to stopRenewal,
                "received_at" to receivedAt.toString(),
            )
        val encrypted = codec.encodeDecision(operationId, payload)
        jdbc
            .sql(
                """
                INSERT INTO retained_transaction_decisions
                  (operation_id,record_id,decision_type,actor_user_id,status,amount,recovery_credits,stop_renewal,
                   received_at,retained_until,payload_encrypted,encryption_scheme,key_version)
                VALUES (:operation,:record,:type,:actor,:status,:amount,:credits,:stop,:received,:retained,:payload,:scheme,:version)
                """.trimIndent(),
            ).param("operation", operationId)
            .param("record", target.id)
            .param("type", type)
            .param("actor", actor)
            .param("status", status)
            .param("amount", amount)
            .param("credits", recoveryCredits)
            .param("stop", stopRenewal)
            .param(
                "received",
                receivedAt.atOffset(ZoneOffset.UTC),
            ).param("retained", retention.atOffset(ZoneOffset.UTC))
            .param("payload", encrypted.bytes)
            .param("scheme", encrypted.scheme)
            .param("version", encrypted.keyVersion)
            .update()
        return detail(target)
    }

    private fun verifyComplete(userId: UUID) {
        val incomplete =
            jdbc
                .sql(
                    """
                    SELECT count(*) FROM retained_transaction_records r
                    JOIN workspaces w ON w.id=r.workspace_id
                    WHERE w.user_id=:user AND r.manual_review=true
                    """.trimIndent(),
                ).param("user", userId)
                .query(Long::class.java)
                .single()
        if (incomplete > 0) throw ConflictException("결제 기록 증빙이 확인되지 않아 탈퇴할 수 없습니다")
        val missingConsent =
            jdbc
                .sql(
                    """
                    SELECT count(*) FROM workspaces w
                    WHERE w.user_id=:user
                      AND EXISTS (
                        SELECT 1 FROM toss_billing_orders o
                        WHERE o.workspace_id=w.id AND o.environment=:environment
                          AND o.kind IN ('charge','renewal') AND o.status='paid'
                      )
                      AND NOT EXISTS (
                        SELECT 1 FROM retained_transaction_records r
                        WHERE r.workspace_id=w.id AND r.record_type='consent'
                      )
                    """.trimIndent(),
                ).param("user", userId)
                .param("environment", LIVE_ENVIRONMENT)
                .query(Long::class.java)
                .single()
        if (missingConsent > 0) throw ConflictException("결제 동의 증빙이 확인되지 않아 탈퇴할 수 없습니다")
    }

    @Suppress("LongParameterList") // Explicit columns of the encrypted archive row.
    private fun upsertRecord(
        id: UUID,
        type: RetainedRecordType,
        sourceId: UUID,
        paymentId: UUID?,
        workspaceId: UUID?,
        environment: String?,
        occurredAt: Instant?,
        retention: Instant,
        manualReview: Boolean,
        payload: Map<String, Any?>,
        onlyIfMissing: Boolean = false,
    ) {
        // Historical backfills must never replace the evidence captured at provider confirmation.
        if (onlyIfMissing && recordRow(id) != null) return
        val encrypted = codec.encodeRecord(id, payload)
        jdbc
            .sql(
                """
                INSERT INTO retained_transaction_records
                  (id,record_type,source_id,payment_id,workspace_id,environment,occurred_at,retained_until,manual_review,
                   payload_encrypted,encryption_scheme,key_version)
                VALUES (:id,:type,:source,:payment,:workspace,:environment,:occurred,:retained,:manual,:payload,:scheme,:version)
                ON CONFLICT (record_type,source_id) DO UPDATE SET
                  payment_id=EXCLUDED.payment_id,workspace_id=EXCLUDED.workspace_id,environment=EXCLUDED.environment,
                  occurred_at=COALESCE(retained_transaction_records.occurred_at,EXCLUDED.occurred_at),
                  retained_until=GREATEST(retained_transaction_records.retained_until,EXCLUDED.retained_until),
                  manual_review=retained_transaction_records.manual_review OR EXCLUDED.manual_review,
                  payload_encrypted=EXCLUDED.payload_encrypted,encryption_scheme=EXCLUDED.encryption_scheme,
                  key_version=EXCLUDED.key_version
                """.trimIndent(),
            ).param("id", id)
            .param("type", type.wireName)
            .param("source", sourceId)
            .param("payment", paymentId)
            .param("workspace", workspaceId)
            .param("environment", environment)
            .param(
                "occurred",
                occurredAt?.atOffset(ZoneOffset.UTC),
            ).param("retained", retentionUntil(retention, TRANSACTION_RETENTION_YEARS).atOffset(ZoneOffset.UTC))
            .param("manual", manualReview)
            .param("payload", encrypted.bytes)
            .param("scheme", encrypted.scheme)
            .param("version", encrypted.keyVersion)
            .update()
    }

    @Suppress("LongParameterList", "CyclomaticComplexMethod") // Keep snapshot provenance and expiry checks together.
    private fun archiveConsent(
        session: BillingSession?,
        workspaceId: UUID,
        paymentId: UUID?,
        email: String?,
        snapshotEligible: Boolean,
        retentionReference: Instant? = null,
    ) {
        if (session == null || session.environment != LIVE_ENVIRONMENT || session.consentVersion == null) return
        val id = consentId(session.id)
        recordRow(id)?.takeIf { it.type == RetainedRecordType.CONSENT && !it.manualReview }?.let {
            extendRetention(it.id, retentionReference)
            return
        }
        verifiedConsent(workspaceId, session)?.let {
            extendRetention(it.id, retentionReference)
            return
        }
        val occurredAt = session.consentAt
        // start-monthly-v1 is the only version for which the current published snapshot is
        // explicitly supplied by successful checkout. Historical backfills retain only the
        // version/time and stay quarantined until evidence is reviewed.
        val current =
            snapshotEligible && session.consentVersion == CURRENT_CONSENT_VERSION &&
                occurredAt != null && !occurredAt.isBefore(Instant.parse("2026-10-06T00:00:00+09:00"))
        upsertRecord(
            id = id,
            type = RetainedRecordType.CONSENT,
            sourceId = session.id,
            paymentId = paymentId,
            workspaceId = workspaceId,
            environment = session.environment,
            occurredAt = occurredAt,
            retention = retentionReference ?: occurredAt ?: clock.instant(),
            manualReview = occurredAt == null || !current,
            payload =
                mapOf(
                    "customer_email" to email,
                    "consent_version" to session.consentVersion,
                    "consent_at" to occurredAt?.toString(),
                    "terms_snapshot" to if (current) legalResource("legal/terms-$LEGAL_SNAPSHOT_VERSION.md") else null,
                    "privacy_snapshot" to
                        if (current) legalResource("legal/privacy-$LEGAL_SNAPSHOT_VERSION.md") else null,
                ),
        )
    }

    private fun extendRetention(
        id: UUID,
        retentionReference: Instant?,
    ) {
        if (retentionReference == null) return
        jdbc
            .sql(
                """
                UPDATE retained_transaction_records SET
                retained_until=GREATEST(retained_until,:until) WHERE id=:id
                """.trimIndent(),
            ).param("id", id)
            .param(
                "until",
                retentionUntil(retentionReference, TRANSACTION_RETENTION_YEARS).atOffset(ZoneOffset.UTC),
            ).update()
    }

    private fun verifiedConsent(
        workspaceId: UUID,
        session: BillingSession,
    ): RecordRow? {
        val consentVersion = session.consentVersion ?: return null
        val consentAt = session.consentAt ?: return null
        return jdbc
            .sql(
                """
                SELECT * FROM retained_transaction_records
                WHERE record_type='consent' AND workspace_id=:workspace AND environment=:environment
                  AND manual_review=false
                ORDER BY created_at,id
                """.trimIndent(),
            ).param("workspace", workspaceId)
            .param("environment", LIVE_ENVIRONMENT)
            .query { rs, _ -> record(rs) }
            .list()
            .firstOrNull { row ->
                val payload = codec.decodeRecord(row.id, row.payload)
                payload.path("consent_version").asString() == consentVersion &&
                    payload.path("consent_at").asString() == consentAt.toString()
            }
    }

    private fun detail(row: RecordRow): RetainedTransactionDetail {
        val decisions =
            jdbc
                .sql(
                    """
                    SELECT * FROM retained_transaction_decisions WHERE record_id=:record ORDER BY
                    created_at,operation_id
                    """.trimIndent(),
                ).param("record", row.id)
                .query { rs, _ -> decisionRow(rs) }
                .list()
                .map { decision -> decision.toDomain(codec.decodeDecision(decision.operationId, decision.payload)) }
        return RetainedTransactionDetail(row.summary(), codec.decodeRecord(row.id, row.payload).toString(), decisions)
    }

    private fun decision(operationId: UUID): DecisionRow? =
        jdbc
            .sql("SELECT * FROM retained_transaction_decisions WHERE operation_id=:operation")
            .param("operation", operationId)
            .query { rs, _ -> decisionRow(rs) }
            .optional()
            .orElse(null)

    private fun recordRow(
        id: UUID,
        forUpdate: Boolean = false,
    ): RecordRow? =
        jdbc
            .sql("SELECT * FROM retained_transaction_records WHERE id=:id${if (forUpdate) " FOR UPDATE" else ""}")
            .param("id", id)
            .query { rs, _ -> record(rs) }
            .optional()
            .orElse(null)

    private fun record(rs: ResultSet): RecordRow =
        RecordRow(
            id = rs.getObject("id", UUID::class.java),
            type = RetainedRecordType.fromWire(rs.getString("record_type")),
            sourceId = rs.getObject("source_id", UUID::class.java),
            paymentId = rs.getObject("payment_id", UUID::class.java),
            workspaceId = rs.getObject("workspace_id", UUID::class.java),
            environment = rs.getString("environment"),
            occurredAt = rs.getObject("occurred_at", OffsetDateTime::class.java)?.toInstant(),
            retainedUntil = rs.getObject("retained_until", OffsetDateTime::class.java).toInstant(),
            manualReview = rs.getBoolean("manual_review"),
            payload =
                EncryptedContent(
                    rs.getBytes("payload_encrypted"),
                    rs.getString("encryption_scheme"),
                    rs.getInt("key_version"),
                ),
        )

    private fun summary(rs: ResultSet): RetainedTransactionSummary =
        RetainedTransactionSummary(
            id = rs.getObject("id", UUID::class.java),
            type = RetainedRecordType.fromWire(rs.getString("record_type")),
            occurredAt = rs.getObject("occurred_at", OffsetDateTime::class.java)?.toInstant(),
            retainedUntil = rs.getObject("retained_until", OffsetDateTime::class.java).toInstant(),
            paymentId = rs.getObject("payment_id", UUID::class.java),
            environment = rs.getString("environment"),
            manualReview = rs.getBoolean("manual_review"),
        )

    private fun summaryMap(item: RetainedTransactionSummary): Map<String, Any?> =
        mapOf(
            "id" to item.id,
            "type" to item.type.wireName,
            "occurred_at" to item.occurredAt,
            "retained_until" to item.retainedUntil,
            "payment_id" to item.paymentId,
            "environment" to item.environment,
            "manual_review" to item.manualReview,
        )

    private fun workspaceOwnerEmail(workspaceId: UUID): String? =
        jdbc
            .sql(
                """
                SELECT u.email FROM users u JOIN workspaces w ON w.user_id=u.id WHERE
                w.id=:workspace
                """.trimIndent(),
            ).param("workspace", workspaceId)
            .query(String::class.java)
            .optional()
            .orElse(null)

    private fun userEmail(userId: UUID): String? =
        jdbc
            .sql(
                "SELECT email FROM users WHERE id=:user",
            ).param("user", userId)
            .query(String::class.java)
            .optional()
            .orElse(null)

    private fun liveConsent(workspaceId: UUID): BillingSession? =
        jdbc
            .sql(
                """
                SELECT
                id,workspace_id,customer,plan_id,expires_at,state,environment,purpose,consent_version,consent_at
                FROM toss_billing_sessions WHERE workspace_id=:workspace AND environment=:environment
                """.trimIndent(),
            ).param("workspace", workspaceId)
            .param("environment", LIVE_ENVIRONMENT)
            .query { rs, _ ->
                BillingSession(
                    workspaceId = rs.getObject("workspace_id", UUID::class.java),
                    id = rs.getObject("id", UUID::class.java),
                    customer = rs.getObject("customer", UUID::class.java),
                    planId = rs.getString("plan_id"),
                    expiresAt = rs.getObject("expires_at", OffsetDateTime::class.java).toInstant(),
                    state = rs.getString("state"),
                    environment = rs.getString("environment"),
                    purpose = rs.getString("purpose"),
                    consentVersion = rs.getString("consent_version"),
                    consentAt = rs.getObject("consent_at", OffsetDateTime::class.java)?.toInstant(),
                )
            }.optional()
            .orElse(null)

    private fun liveOrders(workspaceId: UUID): List<OrderRow> =
        jdbc
            .sql(
                """
                SELECT o.id,o.plan_id,o.amount,o.status,o.approved_at,o.canceled_at,
                  COALESCE(p.refunded_amount,0) refunded_amount,
                  COALESCE(p.amount,o.amount) original_amount
                FROM toss_billing_orders o LEFT JOIN subscription_payments p ON p.id=o.id AND p.workspace_id=o.workspace_id
                WHERE o.workspace_id=:workspace AND o.environment=:environment AND o.kind IN ('charge','renewal') AND o.status='paid'
                ORDER BY o.id
                """.trimIndent(),
            ).param("workspace", workspaceId)
            .param("environment", LIVE_ENVIRONMENT)
            .query { rs, _ ->
                OrderRow(
                    rs.getObject("id", UUID::class.java),
                    rs.getString("plan_id"),
                    rs.getInt("amount"),
                    rs.getString("status"),
                    rs.getInt("original_amount") - rs.getInt("refunded_amount"),
                    rs.getObject("approved_at", OffsetDateTime::class.java)?.toInstant(),
                    rs.getObject("canceled_at", OffsetDateTime::class.java)?.toInstant(),
                )
            }.list()

    private fun refundOrders(originalId: UUID): List<RefundRow> =
        jdbc
            .sql(
                """
                SELECT id,amount,previous_remaining,canceled_at
                FROM toss_billing_orders WHERE original_id=:original AND kind='refund' AND status='paid' ORDER BY created_at,id
                """.trimIndent(),
            ).param("original", originalId)
            .query { rs, _ ->
                val amount = rs.getInt("amount")
                RefundRow(
                    rs.getObject("id", UUID::class.java),
                    amount,
                    rs.getInt("previous_remaining"),
                    rs.getObject("canceled_at", OffsetDateTime::class.java)?.toInstant(),
                )
            }.list()

    private fun rotateRecords() {
        jdbc
            .sql(
                """
                SELECT id,payload_encrypted,encryption_scheme,key_version FROM retained_transaction_records
                WHERE key_version<>:version
                """.trimIndent(),
            ).param("version", codecKeyVersion())
            .query { rs, _ ->
                RotationRow(
                    rs.getObject("id", UUID::class.java),
                    EncryptedContent(
                        rs.getBytes("payload_encrypted"),
                        rs.getString("encryption_scheme"),
                        rs.getInt("key_version"),
                    ),
                )
            }.list()
            .forEach { row ->
                val updated = codec.rotate(row.id, row.payload, EncryptedField.RETAINED_TRANSACTION)
                val count =
                    jdbc
                        .sql(
                            """
                            UPDATE retained_transaction_records SET payload_encrypted=:payload,encryption_scheme=:scheme,key_version=:version
                            WHERE id=:id AND payload_encrypted=:old
                            """.trimIndent(),
                        ).param(
                            "payload",
                            updated.bytes,
                        ).param("scheme", updated.scheme)
                        .param("version", updated.keyVersion)
                        .param("id", row.id)
                        .param("old", row.payload.bytes)
                        .update()
                check(count == 1) { "Retention envelope rotation encountered a concurrent update; retry rotation" }
            }
    }

    private fun rotateDecisions() {
        jdbc
            .sql(
                """
                SELECT operation_id,payload_encrypted,encryption_scheme,key_version FROM
                retained_transaction_decisions WHERE key_version<>:version
                """.trimIndent(),
            ).param("version", codecKeyVersion())
            .query { rs, _ ->
                RotationRow(
                    rs.getObject("operation_id", UUID::class.java),
                    EncryptedContent(
                        rs.getBytes("payload_encrypted"),
                        rs.getString("encryption_scheme"),
                        rs.getInt("key_version"),
                    ),
                )
            }.list()
            .forEach { row ->
                val updated = codec.rotate(row.id, row.payload, EncryptedField.RETAINED_DECISION)
                val count =
                    jdbc
                        .sql(
                            """
                            UPDATE retained_transaction_decisions SET payload_encrypted=:payload,encryption_scheme=:scheme,key_version=:version
                            WHERE operation_id=:id AND payload_encrypted=:old
                            """.trimIndent(),
                        ).param(
                            "payload",
                            updated.bytes,
                        ).param("scheme", updated.scheme)
                        .param("version", updated.keyVersion)
                        .param("id", row.id)
                        .param("old", row.payload.bytes)
                        .update()
                check(count == 1) { "Retention envelope rotation encountered a concurrent update; retry rotation" }
            }
    }

    private fun codecKeyVersion(): Int = codec.writeKeyVersion

    private fun legalResource(path: String): String? =
        javaClass.classLoader
            .getResourceAsStream(path)
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }

    private fun creditsEqual(
        stored: String,
        expected: BigDecimal?,
    ): Boolean {
        val actual = stored.toBigDecimalOrNull()
        return (actual == null && expected == null) ||
            (actual != null && expected != null && actual.compareTo(expected) == 0)
    }

    private fun retentionUntil(
        start: Instant,
        years: Long,
    ): Instant = start.atZone(ZoneOffset.UTC).plusYears(years).toInstant()

    private data class RecordRow(
        val id: UUID,
        val type: RetainedRecordType,
        val sourceId: UUID,
        val paymentId: UUID?,
        val workspaceId: UUID?,
        val environment: String?,
        val occurredAt: Instant?,
        val retainedUntil: Instant,
        val manualReview: Boolean,
        val payload: EncryptedContent,
    ) {
        fun summary() =
            RetainedTransactionSummary(id, type, occurredAt, retainedUntil, paymentId, environment, manualReview)
    }

    private data class DecisionRow(
        val operationId: UUID,
        val recordId: UUID,
        val type: String,
        val actor: UUID?,
        val status: String,
        val amount: Int?,
        val credits: BigDecimal?,
        val stopRenewal: Boolean?,
        val receivedAt: Instant,
        val retainedUntil: Instant,
        val createdAt: Instant,
        val updatedAt: Instant,
        val payload: EncryptedContent,
    ) {
        fun toDomain(node: tools.jackson.databind.JsonNode) =
            RetainedTransactionDecision(
                operationId,
                type,
                actor,
                status,
                amount,
                credits,
                node.path("reason").asString(),
                stopRenewal,
                receivedAt,
                createdAt,
                updatedAt,
            )
    }

    private data class RotationRow(
        val id: UUID,
        val payload: EncryptedContent,
    )

    private data class OrderRow(
        val orderId: UUID,
        val planId: String,
        val amount: Int,
        val status: String,
        val remainingAmount: Int,
        val approvedAt: Instant?,
        val canceledAt: Instant?,
    )

    private data class RefundRow(
        val id: UUID,
        val amount: Int,
        val previousRemaining: Int,
        val occurredAt: Instant?,
    )

    private fun decisionRow(rs: ResultSet) =
        DecisionRow(
            rs.getObject("operation_id", UUID::class.java),
            rs.getObject("record_id", UUID::class.java),
            rs.getString("decision_type"),
            rs.getObject("actor_user_id", UUID::class.java),
            rs.getString("status"),
            rs.getObject("amount")?.let { (it as Number).toInt() },
            rs.getBigDecimal("recovery_credits"),
            rs.getObject("stop_renewal")?.let { rs.getBoolean("stop_renewal") },
            rs.getObject("received_at", OffsetDateTime::class.java).toInstant(),
            rs.getObject("retained_until", OffsetDateTime::class.java).toInstant(),
            rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
            rs.getObject("updated_at", OffsetDateTime::class.java).toInstant(),
            EncryptedContent(
                rs.getBytes("payload_encrypted"),
                rs.getString("encryption_scheme"),
                rs.getInt("key_version"),
            ),
        )

    private companion object {
        const val MAX_PAGE_SIZE = 100
        const val TRANSACTION_RETENTION_YEARS = 5L
        const val COMPLAINT_RETENTION_YEARS = 3L
        const val LIVE_ENVIRONMENT = "toss_live"
        val CHARGE_KINDS = setOf("charge", "renewal")

        fun supplyId(orderId: UUID): UUID = UUID.nameUUIDFromBytes("supply:$orderId".toByteArray())

        fun consentId(sessionId: UUID): UUID = UUID.nameUUIDFromBytes("consent:$sessionId".toByteArray())

        const val CURRENT_CONSENT_VERSION = "start-monthly-v1"
        const val LEGAL_SNAPSHOT_VERSION = "2026-10-06"

        fun refundId(
            orderId: UUID,
            refunded: Int,
        ): UUID = UUID.nameUUIDFromBytes("refund:$orderId:$refunded".toByteArray())
    }
}
