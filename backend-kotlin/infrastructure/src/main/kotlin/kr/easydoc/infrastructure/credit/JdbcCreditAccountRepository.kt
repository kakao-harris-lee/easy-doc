package kr.easydoc.infrastructure.credit

import kr.easydoc.application.credit.CreditAccountRepository
import kr.easydoc.application.credit.CreditAccountRow
import kr.easydoc.application.credit.CreditConsistencyViolation
import kr.easydoc.application.credit.CreditTransactionView
import kr.easydoc.application.credit.ReservationResult
import kr.easydoc.core.credit.CreditReason
import kr.easydoc.core.credit.CreditTransactionKind
import kr.easydoc.core.credit.Credits
import kr.easydoc.core.exceptions.NotFoundException
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

/** `workspace_credit_accounts`·`credit_transactions` 접근. 스키마는 `V15__credit_accounts.sql`. */
class JdbcCreditAccountRepository(private val jdbc: JdbcClient) : CreditAccountRepository {
    override fun ensureAccount(workspaceId: UUID) {
        jdbc
            .sql(
                """
                INSERT INTO workspace_credit_accounts (workspace_id, balance, reserved)
                VALUES (:workspaceId, 0, 0)
                ON CONFLICT (workspace_id) DO NOTHING
                """.trimIndent(),
            ).param("workspaceId", workspaceId)
            .update()
    }

    /**
     * 예약 UPDATE — [enforced] 가 거짓이면 잔액 조건을 아예 걷어낸다(`:enforced = false OR …`,
     * `JdbcConversionRepository.RESERVE_RECONVERSION_CALLS_SQL` 과 같은 형태). 소유 술어가
     * 이 문장 자신에 걸린다(`EXISTS` 서브쿼리) — `OwnershipPredicateGuardTest` 관행과 같다.
     *
     * **[ensureAccount] 를 먼저 부른다**(리뷰 HIGH-2) — 롤링 배포 창에서 계정 행이 아직
     * 없는 워크스페이스가 집행이 꺼진 상태에서도 402(`Insufficient`)로 잘못 거절되는 것을
     * 막는다. 집행 스위치가 꺼져 있다는 것은 "잔액 검사를 하지 않는다"는 뜻이지 "행이
     * 없으면 거절한다"는 뜻이 아니다. [ensureAccount] 는 멱등이라(`ON CONFLICT DO
     * NOTHING`) 이미 있는 계정에는 아무 영향이 없다.
     */
    override fun reserve(
        ownerId: UUID,
        workspaceId: UUID,
        documentId: UUID,
        amount: Credits,
        enforced: Boolean,
    ): ReservationResult {
        ensureAccount(workspaceId)

        val reserved =
            jdbc
                .sql(RESERVE_SQL)
                .param("workspaceId", workspaceId)
                .param("ownerId", ownerId)
                .param("amount", amount.amount)
                .param("enforced", enforced)
                .query { rs, _ -> rs.getInt("balance") to rs.getInt("reserved") }
                .optional()

        if (reserved.isEmpty) {
            // 가용량은 **음수일 수 있다**(집행이 꺼진 이전 요청들이 잔액을 이미 음수로
            // 만들어 둔 상태) — `coerceAtLeast(0)` 로 바닥을 씌우면 402 헤더
            // `X-Credit-Balance` 가 거짓 낙관값을 낸다(리뷰 지적).
            val available =
                jdbc
                    .sql(AVAILABLE_SQL)
                    .param("workspaceId", workspaceId)
                    .param("ownerId", ownerId)
                    .query { rs, _ -> rs.getInt("balance") - rs.getInt("reserved") }
                    .optional()
                    .orElse(0)
            return ReservationResult.Insufficient(available)
        }

        val (balance, reservedTotal) = reserved.get()
        insertTransaction(
            workspaceId = workspaceId,
            ownerId = ownerId,
            documentId = documentId,
            kind = CreditTransactionKind.RESERVE,
            balanceDelta = 0,
            reservedDelta = amount.amount,
            reason = CreditReason.CONVERSION,
            note = null,
            actorUserId = null,
        )
        return ReservationResult.Reserved(balance, reservedTotal)
    }

    override fun consume(
        workspaceId: UUID,
        ownerId: UUID,
        documentId: UUID,
        conversionId: UUID,
        amount: Credits,
    ) {
        jdbc
            .sql(
                """
                UPDATE workspace_credit_accounts
                SET balance = balance - :amount, reserved = reserved - :amount, updated_at = now()
                WHERE workspace_id = :workspaceId
                """.trimIndent(),
            ).param("workspaceId", workspaceId)
            .param("amount", amount.amount)
            .update()
        insertTransaction(
            workspaceId,
            ownerId,
            documentId,
            CreditTransactionKind.CONSUME,
            balanceDelta = -amount.amount,
            reservedDelta = -amount.amount,
            CreditReason.CONVERSION,
            note = null,
            actorUserId = null,
        )
    }

    override fun release(
        workspaceId: UUID,
        ownerId: UUID,
        documentId: UUID,
        conversionId: UUID,
        amount: Credits,
    ) {
        jdbc
            .sql(
                """
                UPDATE workspace_credit_accounts
                SET reserved = reserved - :amount, updated_at = now()
                WHERE workspace_id = :workspaceId
                """.trimIndent(),
            ).param("workspaceId", workspaceId)
            .param("amount", amount.amount)
            .update()
        insertTransaction(
            workspaceId,
            ownerId,
            documentId,
            CreditTransactionKind.RELEASE,
            balanceDelta = 0,
            reservedDelta = -amount.amount,
            CreditReason.CONVERSION,
            note = null,
            actorUserId = null,
        )
    }

    /**
     * 계정 행이 없으면 [NotFoundException] — `.optional()`로 0행을 스프링 데이터 접근
     * 예외(`IncorrectResultSizeDataAccessException`)가 아니라 도메인 예외로 옮긴다(리뷰
     * 지적). 이 갈래는 운영 프로필(`credit-grant`)이 잘못된 워크스페이스 식별자를 받거나,
     * 그 사이 워크스페이스가 지워진 경우에만 닿는다.
     */
    override fun grant(
        workspaceId: UUID,
        ownerUserId: UUID,
        credits: Int,
        reason: CreditReason,
        note: String?,
        actorUserId: UUID?,
    ): Int {
        val balance =
            jdbc
                .sql(
                    """
                    UPDATE workspace_credit_accounts
                    SET balance = balance + :credits, updated_at = now()
                    WHERE workspace_id = :workspaceId
                    RETURNING balance
                    """.trimIndent(),
                ).param("workspaceId", workspaceId)
                .param("credits", credits)
                .query { rs, _ -> rs.getInt("balance") }
                .optional()
                .orElseThrow { NotFoundException(WORKSPACE_NOT_FOUND_MESSAGE) }
        val kind = if (credits >= 0) CreditTransactionKind.GRANT else CreditTransactionKind.ADJUST
        insertTransaction(
            workspaceId = workspaceId,
            ownerId = ownerUserId,
            documentId = null,
            kind = kind,
            balanceDelta = credits,
            reservedDelta = 0,
            reason = reason,
            note = note,
            actorUserId = actorUserId,
        )
        return balance
    }

    override fun read(
        ownerId: UUID,
        workspaceId: UUID,
    ): CreditAccountRow? {
        val account =
            jdbc
                .sql(
                    """
                    SELECT a.workspace_id, a.balance, a.reserved
                    FROM workspace_credit_accounts a
                    JOIN workspaces w ON w.id = a.workspace_id
                    WHERE a.workspace_id = :workspaceId AND w.user_id = :ownerId
                    """.trimIndent(),
                ).param("workspaceId", workspaceId)
                .param("ownerId", ownerId)
                .query { rs, _ -> Pair(rs.getInt("balance"), rs.getInt("reserved")) }
                .optional()
                .orElse(null) ?: return null

        val transactions =
            jdbc
                .sql(
                    """
                    SELECT id, kind, balance_delta, reserved_delta, reason, note, document_id, created_at
                    FROM credit_transactions
                    WHERE workspace_id = :workspaceId
                    ORDER BY created_at DESC, id DESC
                    LIMIT :limit
                    """.trimIndent(),
                ).param("workspaceId", workspaceId)
                .param("limit", TRANSACTION_HISTORY_LIMIT)
                .query { rs, _ -> toTransactionView(rs) }
                .list()

        return CreditAccountRow(workspaceId, account.first, account.second, transactions)
    }

    /** 두 불변식을 함께 확인한다 — `sum(balance_delta) = balance`, `sum(reserved_delta) = reserved`. */
    override fun consistencyViolations(): List<CreditConsistencyViolation> =
        jdbc
            .sql(
                """
                SELECT a.workspace_id, a.balance, a.reserved,
                       coalesce(sum(t.balance_delta), 0)::integer AS balance_sum,
                       coalesce(sum(t.reserved_delta), 0)::integer AS reserved_sum
                FROM workspace_credit_accounts a
                LEFT JOIN credit_transactions t ON t.workspace_id = a.workspace_id
                GROUP BY a.workspace_id, a.balance, a.reserved
                HAVING coalesce(sum(t.balance_delta), 0) <> a.balance
                    OR coalesce(sum(t.reserved_delta), 0) <> a.reserved
                """.trimIndent(),
            ).query { rs, _ ->
                CreditConsistencyViolation(
                    workspaceId = rs.getObject("workspace_id", UUID::class.java),
                    balance = rs.getInt("balance"),
                    reserved = rs.getInt("reserved"),
                    balanceSum = rs.getInt("balance_sum"),
                    reservedSum = rs.getInt("reserved_sum"),
                )
            }.list()

    @Suppress("LongParameterList")
    private fun insertTransaction(
        workspaceId: UUID,
        ownerId: UUID,
        documentId: UUID?,
        kind: CreditTransactionKind,
        balanceDelta: Int,
        reservedDelta: Int,
        reason: CreditReason,
        note: String?,
        actorUserId: UUID?,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO credit_transactions
                    (id, workspace_id, owner_user_id, document_id, kind, balance_delta, reserved_delta, reason, note,
                     actor_user_id)
                VALUES (:id, :workspaceId, :ownerId, :documentId, :kind, :balanceDelta, :reservedDelta, :reason, :note,
                        :actorUserId)
                """.trimIndent(),
            ).param("id", UUID.randomUUID())
            .param("workspaceId", workspaceId)
            .param("ownerId", ownerId)
            .param("documentId", documentId)
            .param("kind", kind.wireName)
            .param("balanceDelta", balanceDelta)
            .param("reservedDelta", reservedDelta)
            .param("reason", reason.wireName)
            .param("note", note)
            .param("actorUserId", actorUserId)
            .update()
    }

    /**
     * `workspaces` 만 읽는다 — 문서·변환에 닿지 않으므로 `OwnershipPredicateGuardTest`
     * (문서·변환 SQL 전수)의 대상이 아니다. 소유 술어가 없는 이유: 이 조회 자체가 "누가
     * 소유자인가"를 알아내는 것이 목적이라, 알아내기 전에는 걸 수 있는 소유 술어가 없다
     * (운영 CLI, `credit-grant` 프로필 하나만 부른다).
     */
    override fun ownerOf(workspaceId: UUID): UUID? =
        jdbc
            .sql("SELECT user_id FROM workspaces WHERE id = :workspaceId")
            .param("workspaceId", workspaceId)
            .query { rs, _ -> rs.getObject("user_id", UUID::class.java) }
            .optional()
            .orElse(null)

    private fun toTransactionView(rs: ResultSet): CreditTransactionView =
        CreditTransactionView(
            id = rs.getObject("id", UUID::class.java),
            kind = CreditTransactionKind.ofWireName(rs.getString("kind")),
            balanceDelta = rs.getInt("balance_delta"),
            reservedDelta = rs.getInt("reserved_delta"),
            reason = CreditReason.ofWireName(rs.getString("reason")),
            note = rs.getString("note"),
            documentId = rs.getObject("document_id", UUID::class.java),
            createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
        )

    private companion object {
        /** 계약 `WorkspaceCreditsResponse.transactions` — 최근 50건. */
        const val TRANSACTION_HISTORY_LIMIT = 50

        /** `WorkspaceService`·`UsageQueryService` 와 같은 존재 은닉 문구. */
        const val WORKSPACE_NOT_FOUND_MESSAGE = "작업 공간을 찾을 수 없습니다"

        /**
         * 예약 UPDATE(계획 §2 결정 4). `JdbcConversionRepository.RESERVE_RECONVERSION_CALLS_SQL`
         * 이 선례다 — 소유 술어(`EXISTS`)가 이 문장 자신에 걸린다.
         */
        val RESERVE_SQL =
            """
            UPDATE workspace_credit_accounts
            SET reserved = reserved + :amount, updated_at = now()
            WHERE workspace_id = :workspaceId
              AND EXISTS (SELECT 1 FROM workspaces WHERE id = :workspaceId AND user_id = :ownerId)
              AND (:enforced = false OR balance - reserved >= :amount)
            RETURNING balance, reserved
            """.trimIndent()

        /** 예약 실패 뒤 잔여 가용량을 계산하려고 다시 읽는 질의. 예약과 같은 소유 술어다. */
        val AVAILABLE_SQL =
            """
            SELECT a.balance, a.reserved
            FROM workspace_credit_accounts a
            WHERE a.workspace_id = :workspaceId
              AND EXISTS (SELECT 1 FROM workspaces WHERE id = :workspaceId AND user_id = :ownerId)
            """.trimIndent()
    }
}
