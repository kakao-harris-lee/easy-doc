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
 *
 * 함수 수 상한을 억제한다 — 리뷰 팔로업(상수 시간 비교, 경쟁 방지 가드)이
 * `hashOf`/`matches`/`digestOf`/`toActiveCodeRow`/`consume` 처럼 작은 사유별 함수로
 * 쪼갠 결과이지 책임이 늘어난 것이 아니다. 합치면 오히려 각 함수가 지는 사유가 흐려진다.
 */
@Suppress("TooManyFunctions")
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
        val matched = active != null && matches(code, active.salt, active.codeHash)
        if (matched) {
            consume(checkNotNull(active).id, now)
        }
        return matched
    }

    /**
     * 활성(미소비·미만료·시도 미소진) 코드 하나의 시도 횟수를 올리고 그 행의 해시 재료를
     * 돌려준다. 조건에 걸리는 행이 없으면 아무것도 갱신하지 않고 `null` 이다.
     *
     * **가드 조건을 바깥 `WHERE` 에도 되풀이한다** — 안쪽 서브쿼리는 `id` 하나만 골라 잠금
     * 전에 고정하므로, 동시에 들어온 두 번째 시도가 잠금을 기다리는 동안 첫 번째가
     * `attempts` 를 상한까지 올려도 그 `id` 자체는 여전히 일치해 두 번째 `UPDATE` 도
     * 통과해 버린다(상한을 넘겨 증가). 바깥 `WHERE` 에 같은 조건을 두면 PostgreSQL 이 잠금을
     * 얻은 뒤 그 행의 **최신 커밋값**으로 조건을 다시 평가한다(`EvalPlanQual`) — 상한에
     * 이미 닿은 행은 그 시점에 걸러진다.
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
                AND consumed_at IS NULL
                AND expires_at > :now
                AND attempts < :maxAttempts
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
    ): String = Base64.getEncoder().encodeToString(digestOf(code, salt))

    /**
     * 저장된 해시와 **상수 시간**으로 비교한다 — `String.equals`/`==` 는 첫 불일치 바이트에서
     * 바로 멈추는 단락 평가라 응답 시간이 일치한 접두 길이를 흘린다(비교 대상이 salt 를 아는
     * DB 공격자가 아니라 이 서버 자신의 판정이라도, 코드가 100만 가지뿐이라 그 신호가 실전
     * 값어치를 갖는다). `MessageDigest.isEqual` 은 배열 길이가 같으면 항상 전체를 훑는다.
     */
    private fun matches(
        code: String,
        salt: String,
        storedHash: String,
    ): Boolean = MessageDigest.isEqual(digestOf(code, salt), Base64.getDecoder().decode(storedHash))

    private fun digestOf(
        code: String,
        salt: String,
    ): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt.toByteArray(Charsets.UTF_8))
        digest.update(code.toByteArray(Charsets.UTF_8))
        return digest.digest()
    }

    private companion object {
        const val CODE_SPACE = 1_000_000
        const val SALT_BYTES = 16

        /** 계약 `POST /auth/email-verification/request` 429 예시. */
        const val RATE_LIMITED_MESSAGE = "잠시 후 다시 시도해주세요"
    }
}
