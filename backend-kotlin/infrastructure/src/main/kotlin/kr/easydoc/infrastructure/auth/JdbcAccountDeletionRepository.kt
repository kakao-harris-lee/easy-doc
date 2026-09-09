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

    /** CASCADE로 나머지 대부분(`workspaces`·`documents`·`conversions`·… )이 함께 사라진다. */
    override fun deleteUser(userId: UUID) {
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
