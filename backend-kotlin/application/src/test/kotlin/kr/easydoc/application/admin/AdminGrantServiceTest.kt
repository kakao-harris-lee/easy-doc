package kr.easydoc.application.admin

import kr.easydoc.application.auth.UserRepository
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.user.PasswordHash
import kr.easydoc.core.user.StoredUser
import kr.easydoc.core.user.User
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * `admin-grant --email=<이메일> [--revoke]` 유스케이스(어드민 최소 계획 §2 결정 1) —
 * Spring 도 DB 도 없이, [FakeUserRepository]·[FakeAdminAccessRepository] 위에서 3가지
 * 케이스(부여 성공·미검증 이메일 거절·알 수 없는 이메일)와 회수를 잰다.
 */
class AdminGrantServiceTest {
    @Test
    @DisplayName("검증된 이메일 계정에는 부여된다")
    fun `검증된 이메일은 부여된다`() {
        val users = FakeUserRepository()
        val userId = users.seed("verified@example.test", emailVerified = true)
        val access = FakeAdminAccessRepository()
        val service = AdminGrantService(users, access)

        val result = service.grant("verified@example.test", revoke = false)

        assertThat(result).isEqualTo(AdminGrantResult.Applied(userId, isAdmin = true))
        assertThat(access.isVerifiedAdminFlag(userId)).isTrue()
    }

    @Test
    @DisplayName("이메일이 검증되지 않았으면 EmailNotVerified다 — 부여되지 않는다")
    fun `미검증 이메일은 거절된다`() {
        val users = FakeUserRepository()
        users.seed("unverified@example.test", emailVerified = false)
        val access = FakeAdminAccessRepository()
        val service = AdminGrantService(users, access)

        val result = service.grant("unverified@example.test", revoke = false)

        assertThat(result).isEqualTo(AdminGrantResult.EmailNotVerified)
        assertThat(access.grantedIds).isEmpty()
    }

    @Test
    @DisplayName("알 수 없는 이메일은 UserNotFound다")
    fun `알 수 없는 이메일은 UserNotFound다`() {
        val service = AdminGrantService(FakeUserRepository(), FakeAdminAccessRepository())

        val result = service.grant("nobody@example.test", revoke = false)

        assertThat(result).isEqualTo(AdminGrantResult.UserNotFound)
    }

    @Test
    @DisplayName("--revoke는 이메일 검증 여부와 무관하게 항상 회수한다")
    fun `회수는 검증 여부와 무관하다`() {
        val users = FakeUserRepository()
        val userId = users.seed("mistakenly-verified@example.test", emailVerified = false)
        val access = FakeAdminAccessRepository()
        access.grant(userId)
        val service = AdminGrantService(users, access)

        val result = service.grant("mistakenly-verified@example.test", revoke = true)

        assertThat(result).isEqualTo(AdminGrantResult.Applied(userId, isAdmin = false))
        assertThat(access.isVerifiedAdminFlag(userId)).isFalse()
    }

    private class FakeUserRepository : UserRepository {
        private val byEmail = mutableMapOf<String, StoredUser>()

        fun seed(
            email: String,
            emailVerified: Boolean,
        ): UUID {
            val user =
                User(
                    id = UUID.randomUUID(),
                    email = email,
                    createdAt = Instant.EPOCH,
                    emailVerifiedAt = if (emailVerified) Instant.EPOCH else null,
                    hasPassword = true,
                )
            byEmail[email] = StoredUser(user, PasswordHash("stub"))
            return user.id
        }

        override fun findByEmail(email: String): StoredUser? = byEmail[email]

        override fun findById(id: UUID): User? = byEmail.values.firstOrNull { it.user.id == id }?.user

        override fun exists(id: UUID): Boolean = byEmail.values.any { it.user.id == id }

        override fun lockForUpdate(id: UUID): User? = findById(id)

        override fun create(
            email: String,
            passwordHash: PasswordHash,
        ): User = error("사용하지 않는다")

        override fun createWithoutPassword(
            email: String,
            emailVerified: Boolean,
        ): User = error("사용하지 않는다")

        override fun updatePasswordHash(
            userId: UUID,
            passwordHash: PasswordHash,
        ) = error("사용하지 않는다")

        override fun markEmailVerified(userId: UUID): Boolean = error("사용하지 않는다")
    }

    @Test
    @DisplayName("setIsAdmin이 반영되지 않으면(false) UserNotFound다 — 성공을 자칭하지 않는다")
    fun `setIsAdmin이 실패하면 UserNotFound다`() {
        val users = FakeUserRepository()
        users.seed("race@example.test", emailVerified = true)
        val access = FakeAdminAccessRepository()
        access.failNextSetIsAdmin()
        val service = AdminGrantService(users, access)

        val result = service.grant("race@example.test", revoke = false)

        assertThat(result).isEqualTo(AdminGrantResult.UserNotFound)
    }

    private class FakeAdminAccessRepository : AdminAccessRepository {
        private val admins = mutableSetOf<UUID>()
        private var nextSetIsAdminSucceeds = true

        val grantedIds: Set<UUID> get() = admins.toSet()

        fun grant(userId: UUID) {
            admins += userId
        }

        /** 다음 [setIsAdmin] 호출을 반영 실패(0행 갱신)로 흉내 낸다 — 경합으로 계정이 지워진 경우. */
        fun failNextSetIsAdmin() {
            nextSetIsAdminSucceeds = false
        }

        fun isVerifiedAdminFlag(userId: UUID): Boolean = userId in admins

        override fun isVerifiedAdmin(userId: UUID): Boolean = userId in admins

        override fun setIsAdmin(
            userId: UUID,
            isAdmin: Boolean,
        ): Boolean {
            if (!nextSetIsAdminSucceeds) {
                nextSetIsAdminSucceeds = true
                return false
            }
            if (isAdmin) admins += userId else admins -= userId
            return true
        }
    }
}

/** 잘못된 이메일 형식은 [InvalidInputException] — `EmailAddress.of` 재사용이 그대로 던진다. */
class AdminGrantServiceMalformedEmailTest {
    @Test
    @DisplayName("형식이 잘못된 이메일은 InvalidInputException이다")
    fun `잘못된 이메일 형식은 예외다`() {
        val service = AdminGrantService(NeverCalledUserRepository, NeverCalledAdminAccessRepository)

        assertThatThrownBy { service.grant("not-an-email", revoke = false) }
            .isInstanceOf(InvalidInputException::class.java)
    }

    private object NeverCalledUserRepository : UserRepository {
        override fun findByEmail(email: String): StoredUser? = error("불려서는 안 된다")

        override fun findById(id: UUID): User? = error("불려서는 안 된다")

        override fun exists(id: UUID): Boolean = error("불려서는 안 된다")

        override fun lockForUpdate(id: UUID): User? = error("불려서는 안 된다")

        override fun create(
            email: String,
            passwordHash: PasswordHash,
        ): User = error("불려서는 안 된다")

        override fun createWithoutPassword(
            email: String,
            emailVerified: Boolean,
        ): User = error("불려서는 안 된다")

        override fun updatePasswordHash(
            userId: UUID,
            passwordHash: PasswordHash,
        ) = error("불려서는 안 된다")

        override fun markEmailVerified(userId: UUID): Boolean = error("불려서는 안 된다")
    }

    private object NeverCalledAdminAccessRepository : AdminAccessRepository {
        override fun isVerifiedAdmin(userId: UUID): Boolean = error("불려서는 안 된다")

        override fun setIsAdmin(
            userId: UUID,
            isAdmin: Boolean,
        ): Boolean = error("불려서는 안 된다")
    }
}
