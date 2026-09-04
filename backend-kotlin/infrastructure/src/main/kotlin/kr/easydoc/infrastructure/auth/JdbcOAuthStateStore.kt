package kr.easydoc.infrastructure.auth

import kr.easydoc.application.auth.ConsumedOAuthState
import kr.easydoc.application.auth.OAuthChallenge
import kr.easydoc.application.auth.OAuthStateStore
import kr.easydoc.application.auth.SocialLoginProviderId
import org.springframework.jdbc.core.simple.JdbcClient
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.UUID

/**
 * `oauth_states` 테이블 접근. 여러 API 인스턴스가 같은 상태를 봐야 하므로(무상태 배포)
 * 인메모리가 아니라 여기 둔다 — 스키마는 `V6__user_identities.sql`.
 */
class JdbcOAuthStateStore(
    private val jdbc: JdbcClient,
    private val clock: Clock,
) : OAuthStateStore {
    private val random = SecureRandom()

    override fun issue(
        provider: SocialLoginProviderId,
        redirectUri: String,
        ttl: Duration,
        userId: UUID?,
    ): OAuthChallenge {
        val state = randomToken()
        val nonce = randomToken()
        val now = clock.instant()
        jdbc
            .sql(
                """
                INSERT INTO oauth_states (id, provider, state, nonce, redirect_uri, expires_at, user_id)
                VALUES (:id, :provider, :state, :nonce, :redirectUri, :expiresAt, :userId)
                """.trimIndent(),
            ).param("id", UUID.randomUUID())
            .param("provider", provider.wireValue)
            .param("state", state)
            .param("nonce", nonce)
            .param("redirectUri", redirectUri)
            .param("expiresAt", (now + ttl).let { java.sql.Timestamp.from(it) })
            .param("userId", userId)
            .update()
        return OAuthChallenge(state, nonce)
    }

    /**
     * 단발 소비 — 하나의 `UPDATE ... WHERE consumed_at IS NULL ... RETURNING` 이 검사와
     * 기록을 원자적으로 한다. 두 요청이 같은 `state` 로 동시에 오더라도 이 문장 자체가
     * 행 잠금을 잡으므로 **최대 하나만** `nonce` 를 받는다 — 애플리케이션 레벨 검사 뒤
     * 별도 UPDATE 를 하면 그 사이에 경쟁이 끼어들 여지가 있었을 것이다.
     */
    override fun consume(
        provider: SocialLoginProviderId,
        state: String,
        redirectUri: String,
    ): ConsumedOAuthState? =
        jdbc
            .sql(
                """
                UPDATE oauth_states
                SET consumed_at = :now
                WHERE provider = :provider
                  AND state = :state
                  AND redirect_uri = :redirectUri
                  AND consumed_at IS NULL
                  AND expires_at > :now
                RETURNING nonce, user_id
                """.trimIndent(),
            ).param("now", java.sql.Timestamp.from(clock.instant()))
            .param("provider", provider.wireValue)
            .param("state", state)
            .param("redirectUri", redirectUri)
            .query { rs, _ -> ConsumedOAuthState(rs.getString("nonce"), rs.getObject("user_id", UUID::class.java)) }
            .optional()
            .orElse(null)

    /** 128비트 이상의 무작위 값을 URL-safe base64 로 인코딩한다. */
    private fun randomToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private companion object {
        /** 32바이트 = 256비트 — 계약이 요구하는 128비트 하한을 넉넉히 넘는다. */
        const val TOKEN_BYTES = 32
    }
}
