package kr.easydoc.infrastructure.auth

import kr.easydoc.application.auth.PhoneTrialGrantLedger
import org.springframework.jdbc.core.simple.JdbcClient

class JdbcPhoneTrialGrantLedger(private val jdbc: JdbcClient) : PhoneTrialGrantLedger {
    override fun claim(fingerprint: String): Boolean =
        jdbc
            .sql(
                """
                INSERT INTO phone_trial_grant_records (phone_fingerprint)
                VALUES (:fingerprint)
                ON CONFLICT (phone_fingerprint) DO NOTHING
                """.trimIndent(),
            ).param("fingerprint", fingerprint)
            .update() > 0
}
