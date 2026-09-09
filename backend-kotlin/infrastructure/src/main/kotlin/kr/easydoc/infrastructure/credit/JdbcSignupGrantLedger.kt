package kr.easydoc.infrastructure.credit

import kr.easydoc.application.credit.SignupGrantLedger
import org.springframework.jdbc.core.simple.JdbcClient

/** `signup_grant_records` 접근(V20). 스키마 KDoc은 `V20__signup_grant_once.sql` 머리주석. */
class JdbcSignupGrantLedger(private val jdbc: JdbcClient) : SignupGrantLedger {
    override fun hasGranted(emailHash: String): Boolean =
        jdbc
            .sql("SELECT 1 FROM signup_grant_records WHERE email_hash = :emailHash")
            .param("emailHash", emailHash)
            .query { rs, _ -> rs.getInt(1) }
            .optional()
            .isPresent

    /**
     * `ON CONFLICT DO NOTHING` — 동시 최초 가입 경쟁에서도 두 번째 기록 시도가 예외로
     * 튀지 않는다([SignupGrantLedger] KDoc, 실제로는 `users.email` 유일 인덱스가 먼저
     * 막아 이 경쟁에 거의 닿지 않는다).
     */
    override fun record(emailHash: String) {
        jdbc
            .sql(
                """
                INSERT INTO signup_grant_records (email_hash)
                VALUES (:emailHash)
                ON CONFLICT (email_hash) DO NOTHING
                """.trimIndent(),
            ).param("emailHash", emailHash)
            .update()
    }
}
