package kr.easydoc.infrastructure.auth

import kr.easydoc.application.account.AccountDeletionRepository
import kr.easydoc.application.account.LockedAccount
import kr.easydoc.core.user.PasswordHash
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.ResultSet
import java.util.UUID

/**
 * 회원 탈퇴 저장소 — 계획 `docs/plans/2026-09-09-account-deletion.md` §3. 스키마는
 * `V1__initial_schema.sql`(`users`)·`V2__conversion_feedback.sql`·`V16__invoice_requests.sql`.
 */
class JdbcAccountDeletionRepository(private val jdbc: JdbcClient) : AccountDeletionRepository {
    /** `SELECT … FOR UPDATE` — `JdbcUserRepository.lockForUpdate`와 같은 규약. */
    override fun lockForDeletion(userId: UUID): LockedAccount? =
        jdbc
            .sql("SELECT is_admin, password_hash FROM users WHERE id = :userId FOR UPDATE")
            .param("userId", userId)
            .query { rs, _ -> toLockedAccount(rs) }
            .optional()
            .orElse(null)

    override fun pendingInvoiceRequestIds(userId: UUID): List<UUID> =
        jdbc
            .sql("SELECT id FROM invoice_requests WHERE owner_user_id = :userId AND status = 'requested'")
            .param("userId", userId)
            .query { rs, _ -> rs.getObject("id", UUID::class.java) }
            .list()

    /**
     * `conversion_feedback`(V2)은 FK가 없어 `users` 삭제의 CASCADE가 닿지 않는다 —
     * [AccountDeletionRepository.deleteConversionFeedback] KDoc대로 [deleteUser]보다
     * **먼저** 불러야 한다(그 뒤에는 이 서브쿼리의 조인 대상이 이미 사라진다). 소유 술어
     * (`d.user_id = :userId`)는 서브쿼리 안에 있다 — `OwnershipPredicateGuardTest`가
     * 이 문장을 감시 테이블(`conversion_feedback`·`conversions`·`documents`) 접근으로
     * 인구조사한다.
     */
    override fun deleteConversionFeedback(userId: UUID) {
        jdbc
            .sql(
                """
                DELETE FROM conversion_feedback
                WHERE conversion_id IN (
                    SELECT c.id FROM conversions c
                    JOIN documents d ON d.id = c.document_id
                    WHERE d.user_id = :userId
                )
                """.trimIndent(),
            ).param("userId", userId)
            .update()
    }

    /**
     * 문서를 사용자보다 **먼저** 지운다. `users` 한 행만 지우면 CASCADE 가 `workspaces` 를
     * `documents` 보다 먼저 훑고(V1 의 FK 선언 순서), 그 뒤 문서가 지워질 때 V29 의 BEFORE
     * DELETE trigger(`settle_document_jobs_before_delete`)가 활성 행동 안내
     * 작업의 예약을 해제하며 `credit_transactions` 에 해제 거래 한 행을 적는다 — 그 행의
     * `workspace_id`·`owner_user_id` 가 **같은 문장에서 이미 사라진 뒤**라 FK 위반
     * (`fk_credit_transactions_workspace_id_workspaces`)으로 탈퇴 전체가 실패했다.
     * 명시 삭제가 그 trigger 를 작업 공간·사용자가 아직 살아 있는 동안 돌리고, 그렇게 적힌
     * 해제 거래는 이어지는 사용자 삭제의 CASCADE 로 함께 사라진다(`owner_user_id` CASCADE).
     * 작업 행 자체는 설계대로 남는다 — 두 FK 가 SET NULL 이라 소유 연결만 끊긴다.
     *
     * 두 문장은 [kr.easydoc.application.account.DeleteAccountService] 의 **한 트랜잭션**
     * 안에서 이 순서로 실행된다. 나머지 대부분(`workspaces`·`conversions`·…)은 사용자
     * 삭제의 CASCADE 가 지운다.
     */
    override fun deleteUser(userId: UUID) {
        kr.easydoc.infrastructure.subscription.BillingDeletionGuard
            .check(jdbc, userId)
        jdbc
            .sql("DELETE FROM documents WHERE user_id = :userId")
            .param("userId", userId)
            .update()
        jdbc
            .sql("DELETE FROM users WHERE id = :userId")
            .param("userId", userId)
            .update()
    }

    private fun toLockedAccount(rs: ResultSet): LockedAccount =
        LockedAccount(
            isAdmin = rs.getBoolean("is_admin"),
            passwordHash = rs.getString("password_hash")?.let(::PasswordHash),
        )
}
