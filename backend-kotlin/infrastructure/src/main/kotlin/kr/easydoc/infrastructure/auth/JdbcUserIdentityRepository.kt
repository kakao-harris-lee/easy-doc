package kr.easydoc.infrastructure.auth

import kr.easydoc.application.auth.SocialLoginProviderId
import kr.easydoc.application.auth.UserIdentity
import kr.easydoc.application.auth.UserIdentityRepository
import kr.easydoc.core.exceptions.ConflictException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.ResultSet
import java.util.UUID

/** `user_identities` 테이블 접근. 스키마는 `V5__user_identities.sql` 이 정한다. */
class JdbcUserIdentityRepository(private val jdbc: JdbcClient) : UserIdentityRepository {
    override fun findByProviderIdentity(
        provider: SocialLoginProviderId,
        providerUserId: String,
    ): UserIdentity? =
        jdbc
            .sql(
                """
                SELECT id, user_id, provider, provider_user_id FROM user_identities
                WHERE provider = :provider AND provider_user_id = :providerUserId
                """.trimIndent(),
            ).param("provider", provider.wireValue)
            .param("providerUserId", providerUserId)
            .query { rs, _ -> toIdentity(rs) }
            .optional()
            .orElse(null)

    override fun link(
        userId: UUID,
        provider: SocialLoginProviderId,
        providerUserId: String,
        email: String?,
        emailVerified: Boolean,
    ): UserIdentity {
        val id = UUID.randomUUID()
        return try {
            jdbc
                .sql(
                    """
                    INSERT INTO user_identities (id, user_id, provider, provider_user_id, email, email_verified)
                    VALUES (:id, :userId, :provider, :providerUserId, :email, :emailVerified)
                    RETURNING id, user_id, provider, provider_user_id
                    """.trimIndent(),
                ).param("id", id)
                .param("userId", userId)
                .param("provider", provider.wireValue)
                .param("providerUserId", providerUserId)
                .param("email", email)
                .param("emailVerified", emailVerified)
                .query { rs, _ -> toIdentity(rs) }
                .single()
        } catch (_: DuplicateKeyException) {
            // `SocialLoginService` 는 같은 트랜잭션 안에서 `findByProviderIdentity` 를
            // 먼저 확인하므로 여기까지 오는 것은 경쟁(같은 신원이 동시에 콜백을 두 번
            // 완료)뿐이다. 원인을 잇지 않는다 — DETAIL 에 provider_user_id 가 담긴다.
            throw ConflictException(ALREADY_LINKED_MESSAGE)
        }
    }

    private fun toIdentity(rs: ResultSet): UserIdentity =
        UserIdentity(
            id = rs.getObject("id", UUID::class.java),
            userId = rs.getObject("user_id", UUID::class.java),
            provider = providerOf(rs.getString("provider")),
            providerUserId = rs.getString("provider_user_id"),
        )

    private fun providerOf(wireValue: String): SocialLoginProviderId =
        requireNotNull(SocialLoginProviderId.entries.firstOrNull { it.wireValue == wireValue }) {
            "알 수 없는 provider 값이 user_identities 에 저장돼 있다: $wireValue"
        }

    private companion object {
        const val ALREADY_LINKED_MESSAGE = "이미 연결된 계정입니다"
    }
}
