package kr.easydoc.infrastructure.auth

import kr.easydoc.application.auth.VerificationCodeStore
import kr.easydoc.core.exceptions.RateLimitedException
import org.springframework.jdbc.core.simple.JdbcClient
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.Locale
import java.util.UUID

/**
 * `email_verification_codes` 테이블 접근. 스키마는 `V7__email_verification.sql`.
 *
 * **해싱은 SHA-256 + 행마다 다른 salt 다** — 그 결정과 근거는 마이그레이션 파일의 머리
 * 주석에 있다(Argon2 를 쓰지 않는 이유: 10분 TTL·5회 시도 제한이 있는 저-엔트로피 일회성
 * 코드에는 계산 비용이 높은 해시가 필요하지 않다).
 */
class JdbcVerificationCodeStore(
    private val jdbc: JdbcClient,
    private val clock: Clock,
) : VerificationCodeStore {
    private val random = SecureRandom()

    /**
     * 쿨다운 확인 → 이전 활성 코드 무효화 → 새 코드 삽입, 세 문장이다. 두 요청이 정확히
     * 동시에 쿨다운 확인을 통과하는 경쟁은 이론상 남지만(첫 문장이 잠그지 않는다), 결과는
     * "코드가 둘 발급됐다"가 아니라 "무효화·삽입이 뒤섞여도 마지막에 삽입된 하나만 활성으로
     * 남는다"이다 — 낮은 위험(6자리 코드, 이미 인증 완료 전 상태)에 견줘 감수한다.
     */
    override fun issue(
        userId: UUID,
        ttl: Duration,
        cooldown: Duration,
    ): String {
        rejectIfWithinCooldown(userId, cooldown)
        voidActiveCode(userId)

        val code = randomCode()
        val salt = randomSalt()
        val now = clock.instant()
        jdbc
            .sql(
                """
                INSERT INTO email_verification_codes (id, user_id, code_hash, salt, expires_at, created_at)
                VALUES (:id, :userId, :codeHash, :salt, :expiresAt, :now)
                """.trimIndent(),
            ).param("id", UUID.randomUUID())
            .param("userId", userId)
            .param("codeHash", hashOf(code, salt))
            .param("salt", salt)
            .param("expiresAt", Timestamp.from(now + ttl))
            .param("now", Timestamp.from(now))
            .update()
        return code
    }

    /**
     * 시도 횟수 증가와 활성·만료·상한 판정을 **한 문장**으로 묶는다 — 조건에 걸리지 않는
     * 행만 `attempts + 1` 이 반영되며, 걸리면 아무 행도 갱신되지 않고(= 증가하지 않고)
     * `false` 다. 반환된 행의 `code_hash`·`salt` 로 이 애플리케이션이 일치를 판정한 뒤,
     * 일치하면 별도 `UPDATE` 로 소비 처리한다.
     */
    override fun attempt(
        userId: UUID,
        code: String,
        maxAttempts: Int,
    ): Boolean {
        val now = Timestamp.from(clock.instant())
        val active = incrementAttemptsOnActiveCode(userId, now, maxAttempts)
        val matched = active != null && hashOf(code, active.salt) == active.codeHash
        if (matched) {
            consume(checkNotNull(active).id, now)
        }
        return matched
    }

    /**
     * 활성(미소비·미만료·시도 미소진) 코드 하나의 시도 횟수를 올리고 그 행의 해시 재료를
     * 돌려준다. 조건에 걸리는 행이 없으면 아무것도 갱신하지 않고 `null` 이다.
     */
    private fun incrementAttemptsOnActiveCode(
        userId: UUID,
        now: Timestamp,
        maxAttempts: Int,
    ): ActiveCodeRow? =
        jdbc
            .sql(
                """
                UPDATE email_verification_codes
                SET attempts = attempts + 1
                WHERE id = (
                    SELECT id FROM email_verification_codes
                    WHERE user_id = :userId
                      AND consumed_at IS NULL
                      AND expires_at > :now
                      AND attempts < :maxAttempts
                    ORDER BY created_at DESC
                    LIMIT 1
                )
                RETURNING id, code_hash, salt
                """.trimIndent(),
            ).param("userId", userId)
            .param("now", now)
            .param("maxAttempts", maxAttempts)
            .query { rs, _ -> toActiveCodeRow(rs) }
            .optional()
            .orElse(null)

    private fun toActiveCodeRow(rs: java.sql.ResultSet): ActiveCodeRow =
        ActiveCodeRow(
            id = rs.getObject("id", UUID::class.java),
            codeHash = rs.getString("code_hash"),
            salt = rs.getString("salt"),
        )

    private fun consume(
        id: UUID,
        now: Timestamp,
    ) {
        jdbc
            .sql("UPDATE email_verification_codes SET consumed_at = :now WHERE id = :id AND consumed_at IS NULL")
            .param("now", now)
            .param("id", id)
            .update()
    }

    /** [attempt] 판정에 필요한 행 재료. */
    private data class ActiveCodeRow(
        val id: UUID,
        val codeHash: String,
        val salt: String,
    )

    private fun rejectIfWithinCooldown(
        userId: UUID,
        cooldown: Duration,
    ) {
        val lastIssuedAt =
            jdbc
                .sql(
                    """
                    SELECT created_at FROM email_verification_codes
                    WHERE user_id = :userId ORDER BY created_at DESC LIMIT 1
                    """.trimIndent(),
                ).param("userId", userId)
                .query { rs, _ -> rs.getObject("created_at", java.time.OffsetDateTime::class.java).toInstant() }
                .optional()
                .orElse(null) ?: return

        val elapsed = Duration.between(lastIssuedAt, clock.instant())
        if (elapsed < cooldown) {
            val remaining = (cooldown - elapsed).seconds.coerceAtLeast(1)
            throw RateLimitedException(RATE_LIMITED_MESSAGE, remaining)
        }
    }

    /** "at most one active code" — 새로 하나를 만들기 전에 남아 있던 활성 행을 전부 죽인다. */
    private fun voidActiveCode(userId: UUID) {
        jdbc
            .sql(
                """
                UPDATE email_verification_codes SET consumed_at = :now
                WHERE user_id = :userId AND consumed_at IS NULL
                """.trimIndent(),
            ).param("now", Timestamp.from(clock.instant()))
            .param("userId", userId)
            .update()
    }

    private fun randomCode(): String = String.format(Locale.ROOT, "%06d", random.nextInt(CODE_SPACE))

    private fun randomSalt(): String {
        val bytes = ByteArray(SALT_BYTES)
        random.nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    private fun hashOf(
        code: String,
        salt: String,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt.toByteArray(Charsets.UTF_8))
        digest.update(code.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(digest.digest())
    }

    private companion object {
        const val CODE_SPACE = 1_000_000
        const val SALT_BYTES = 16

        /** 계약 `POST /auth/email-verification/request` 429 예시. */
        const val RATE_LIMITED_MESSAGE = "잠시 후 다시 시도해주세요"
    }
}
