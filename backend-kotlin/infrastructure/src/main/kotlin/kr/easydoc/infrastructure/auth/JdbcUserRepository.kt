package kr.easydoc.infrastructure.auth

import kr.easydoc.application.auth.UserRepository
import kr.easydoc.core.exceptions.EmailAlreadyRegisteredException
import kr.easydoc.core.exceptions.StorageException
import kr.easydoc.core.user.PasswordHash
import kr.easydoc.core.user.StoredUser
import kr.easydoc.core.user.User
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

/**
 * `users` 테이블 접근. 스키마는 `V1__python_schema_baseline.sql` 이 정한다.
 *
 * ## JPA 가 아니라 [JdbcClient] 인 이유
 *
 * 스키마·컬럼·제약 이름을 코드가 아니라 마이그레이션이 정한다(계획 §4.2). JPA 는 엔티티가
 * 스키마를 주도하려 하고, 소유권 조건(`WHERE ... AND user_id = ?`)이 SQL 에 눈에 보이는
 * 편이 안전하다(kotlin-spring-conventions §3).
 *
 * ## 원본 DB 예외를 올리지 않는다
 *
 * PostgreSQL 은 제약 위반의 `DETAIL` 에 **실패한 행 전체**를 담는다 — 유일성 위반이면
 * 이메일이, CHECK 위반이면 비밀번호 해시까지 그 문자열에 실린다. 그것을 그대로 감싸
 * 올리면 로그와 응답 어디로든 새므로, 여기서 **도메인 예외로 갈아 끼우고 원인 체인을
 * 끊는다**(`app/repositories/users.py` 가 같은 이유로 하던 일이다).
 */
class JdbcUserRepository(private val jdbc: JdbcClient) : UserRepository {
    override fun findByEmail(email: String): StoredUser? =
        jdbc
            .sql("SELECT id, email, password_hash, created_at FROM users WHERE email = :email")
            .param("email", email)
            .query { rs, _ -> toStoredUser(rs) }
            .optional()
            .orElse(null)

    override fun findById(id: UUID): User? =
        jdbc
            .sql("SELECT id, email, created_at FROM users WHERE id = :id")
            .param("id", id)
            .query { rs, _ -> toUser(rs) }
            .optional()
            .orElse(null)

    /**
     * 새 사용자를 만든다.
     *
     * `created_at` 은 DB `DEFAULT now()` 가 채우고 `RETURNING` 으로 되읽는다 — 앱 시계와
     * DB 시계가 갈리는 자리를 만들지 않는다.
     *
     * **선조회로 중복을 판정하지 않는다.** 조회와 삽입 사이에 다른 요청이 끼어들면 두 번째
     * 삽입이 인덱스에서 터지는데, 그때 나오는 것이 이 예외다. 유일성을 지키는 것은
     * `ix_users_email` 이고 여기서는 그 결과를 번역하기만 한다.
     */
    override fun create(
        email: String,
        passwordHash: PasswordHash,
    ): User {
        val id = UUID.randomUUID()
        return try {
            jdbc
                .sql(
                    """
                    INSERT INTO users (id, email, password_hash)
                    VALUES (:id, :email, :passwordHash)
                    RETURNING id, email, created_at
                    """.trimIndent(),
                ).param("id", id)
                .param("email", email)
                .param("passwordHash", passwordHash.reveal())
                .query { rs, _ -> toUser(rs) }
                .single()
        } catch (_: DuplicateKeyException) {
            // 원인을 잇지 않는다 — DETAIL 에 이메일이 들어 있다.
            throw EmailAlreadyRegisteredException(DUPLICATE_EMAIL_MESSAGE)
        } catch (_: DataIntegrityViolationException) {
            // 유일성 말고 남은 제약은 `ck_users_email_lowercase` 뿐이고, 정규화를 거친 값이
            // 여기까지 왔다면 그것은 사용자 잘못이 아니라 **코드 버그**다 → 500.
            throw StorageException(STORAGE_FAILURE_MESSAGE)
        }
    }

    override fun updatePasswordHash(
        userId: UUID,
        passwordHash: PasswordHash,
    ) {
        jdbc
            .sql("UPDATE users SET password_hash = :passwordHash WHERE id = :id")
            .param("passwordHash", passwordHash.reveal())
            .param("id", userId)
            .update()
    }

    private fun toUser(rs: ResultSet): User =
        User(
            id = rs.getObject("id", UUID::class.java),
            email = rs.getString("email"),
            // timestamptz 를 OffsetDateTime 으로 받는다. `getTimestamp` 는 JVM 기본 시간대를
            // 끼워 넣어 서버 시간대에 따라 값이 달라진다.
            createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
        )

    private fun toStoredUser(rs: ResultSet): StoredUser =
        StoredUser(toUser(rs), PasswordHash(rs.getString("password_hash")))

    private companion object {
        /** 계약 `components/responses/Conflict` 의 `duplicate_email` 예시와 같은 값. */
        const val DUPLICATE_EMAIL_MESSAGE = "이미 가입된 이메일입니다"

        /** 저장소가 만든 고정 문자열. 계약 `InternalError` 의 `storage` 갈래다. */
        const val STORAGE_FAILURE_MESSAGE = "요청을 처리하지 못했습니다"
    }
}
