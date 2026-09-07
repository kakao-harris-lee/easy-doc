package kr.easydoc.application.admin

import kr.easydoc.core.exceptions.AdminRequiredException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID

/** [AdminGuard] 의 분기 — Spring도 DB도 없이, [FakeAdminAccessRepository] 위에서. */
class AdminGuardTest {
    private val userId = UUID.randomUUID()

    @Test
    @DisplayName("검증된 관리자는 통과한다")
    fun `검증된 관리자는 통과한다`() {
        val repository = FakeAdminAccessRepository(verifiedAdmins = setOf(userId))
        val guard = AdminGuard(repository)

        guard.requireAdmin(userId)
    }

    @Test
    @DisplayName("관리자가 아니면 AdminRequiredException(403 문구)이다")
    fun `관리자가 아니면 거절된다`() {
        val repository = FakeAdminAccessRepository(verifiedAdmins = emptySet())
        val guard = AdminGuard(repository)

        assertThatThrownBy { guard.requireAdmin(userId) }
            .isInstanceOf(AdminRequiredException::class.java)
            .hasMessage(AdminGuard.ADMIN_REQUIRED_MESSAGE)
    }

    @Test
    @DisplayName("매 요청 저장소를 다시 읽는다 — 회수가 다음 요청부터 즉시 반영된다")
    fun `회수는 다음 요청부터 즉시 반영된다`() {
        val repository = FakeAdminAccessRepository(verifiedAdmins = mutableSetOf(userId))
        val guard = AdminGuard(repository)

        guard.requireAdmin(userId)
        repository.revoke(userId)

        assertThatThrownBy { guard.requireAdmin(userId) }.isInstanceOf(AdminRequiredException::class.java)
    }

    private class FakeAdminAccessRepository(verifiedAdmins: Set<UUID>) : AdminAccessRepository {
        private val admins = verifiedAdmins.toMutableSet()

        fun revoke(userId: UUID) {
            admins -= userId
        }

        override fun isVerifiedAdmin(userId: UUID): Boolean = userId in admins

        override fun setIsAdmin(
            userId: UUID,
            isAdmin: Boolean,
        ): Boolean {
            if (isAdmin) admins += userId else admins -= userId
            return true
        }
    }
}
