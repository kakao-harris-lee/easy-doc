package kr.easydoc.infrastructure.admin

import kr.easydoc.application.admin.AdminAccessRepository
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/** `users.is_admin`·`email_verified_at` 접근. 스키마는 `V17__admin.sql`. */
class JdbcAdminAccessRepository(private val jdbc: JdbcClient) : AdminAccessRepository {
    /** 매 요청 다시 읽는다 — `AdminGuard` KDoc. */
    override fun isVerifiedAdmin(userId: UUID): Boolean =
        jdbc
            .sql("SELECT 1 FROM users WHERE id = :id AND is_admin AND email_verified_at IS NOT NULL")
            .param("id", userId)
            .query { _, _ -> true }
            .optional()
            .orElse(false)

    override fun setIsAdmin(
        userId: UUID,
        isAdmin: Boolean,
    ): Boolean =
        jdbc
            .sql("UPDATE users SET is_admin = :isAdmin WHERE id = :id")
            .param("isAdmin", isAdmin)
            .param("id", userId)
            .update() > 0
}
