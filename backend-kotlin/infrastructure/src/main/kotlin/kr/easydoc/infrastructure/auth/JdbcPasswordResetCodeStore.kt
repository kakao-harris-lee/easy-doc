package kr.easydoc.infrastructure.auth

import kr.easydoc.application.auth.PasswordResetCodeStore
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock

/**
 * `password_reset_codes` 테이블 접근. 스키마는 `V11__password_reset_codes.sql`.
 *
 * [JdbcVerificationCodeStore] 와 나란한 어댑터다 — 발급·확인 메커니즘은
 * [JdbcOneTimeCodeStore] 를 공유하고, 이 클래스는 테이블 이름과 [PasswordResetCodeStore]
 * 타입만 고정한다.
 */
class JdbcPasswordResetCodeStore(
    jdbc: JdbcClient,
    clock: Clock,
) : JdbcOneTimeCodeStore(jdbc, clock, TABLE),
    PasswordResetCodeStore {
    private companion object {
        const val TABLE = "password_reset_codes"
    }
}
