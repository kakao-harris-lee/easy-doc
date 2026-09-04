package kr.easydoc.infrastructure.auth

import kr.easydoc.application.auth.SocialLoginProviderId
import kr.easydoc.application.auth.SocialLoginService
import kr.easydoc.application.auth.UserIdentity
import kr.easydoc.application.auth.UserIdentityRepository
import kr.easydoc.core.exceptions.ConflictException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.ResultSet
import java.util.UUID

/** `user_identities` 테이블 접근. 스키마는 `V6__user_identities.sql` 이 정한다. */
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

    override fun findByUserAndProvider(
        userId: UUID,
        provider: SocialLoginProviderId,
    ): UserIdentity? =
        jdbc
            .sql(
                """
                SELECT id, user_id, provider, provider_user_id FROM user_identities
                WHERE user_id = :userId AND provider = :provider
                """.trimIndent(),
            ).param("userId", userId)
            .param("provider", provider.wireValue)
            .query { rs, _ -> toIdentity(rs) }
            .optional()
            .orElse(null)

    override fun findAllByUser(userId: UUID): List<UserIdentity> =
        jdbc
            .sql(
                """
                SELECT id, user_id, provider, provider_user_id FROM user_identities
                WHERE user_id = :userId
                """.trimIndent(),
            ).param("userId", userId)
            .query { rs, _ -> toIdentity(rs) }
            .list()

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
        } catch (failure: DuplicateKeyException) {
            // 원인을 잇지 않는다 — DETAIL 에 provider_user_id 가 담긴다.
            throw conflictFor(failure, provider)
        }
    }

    /**
     * `user_identities` 에는 유일성 제약이 **둘** 있고(V6·V9), 어느 쪽이 걸렸는지에 따라
     * 사용자에게 보여줄 사유가 다르다. 두 제약 모두 애플리케이션 층이 먼저 확인하지만
     * (`SocialLoginService.linkCallback` 의 `findByProviderIdentity`·`findByUserAndProvider`),
     * 그 확인과 이 `INSERT` 사이에는 커넥션 경계가 없어 동시 요청이 경쟁할 수 있다 — 이
     * `catch` 가 그 경쟁의 **마지막 방어선**이다(리뷰 지적, HIGH: V9 신설 근거).
     *
     * 메시지는 `SocialLoginService`(그 결정 로직이 같은 갈래에서 던지는 문구)와
     * **글자 그대로 같은 값**을 쓴다 — 결정적 경로와 DB 방어선이 서로 다른 문구를
     * 내면 어느 쪽이 정본인지 계약이 갈린다(리뷰 지적, MEDIUM).
     */
    private fun conflictFor(
        failure: DuplicateKeyException,
        provider: SocialLoginProviderId,
    ): ConflictException {
        val detail = failure.mostSpecificCause.message.orEmpty()
        return if (detail.contains(USER_ID_PROVIDER_CONSTRAINT)) {
            // V9 — 이 사용자가 이 제공자에 이미 다른 신원을 연결했다(사용자당 제공자 하나).
            ConflictException(SocialLoginService.providerAlreadyLinkedMessage(provider))
        } else {
            // V6 — 이 (provider, provider_user_id) 신원이 이미(다른 사용자에) 연결돼 있다.
            ConflictException(SocialLoginService.identityAlreadyLinkedToOtherUserMessage(provider))
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
        /** `V9__user_identities_one_per_provider.sql` 이 붙인 이름 — [conflictFor] 가 갈래를 가른다. */
        const val USER_ID_PROVIDER_CONSTRAINT = "uq_user_identities_user_id_provider"
    }
}
