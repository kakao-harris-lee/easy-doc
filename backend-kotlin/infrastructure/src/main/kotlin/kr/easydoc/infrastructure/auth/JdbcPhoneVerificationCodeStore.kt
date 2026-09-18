package kr.easydoc.infrastructure.auth

import kr.easydoc.application.auth.IssuedPhoneVerification
import kr.easydoc.application.auth.PhoneVerificationCodeStore
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock
import java.time.Duration
import java.util.UUID

class JdbcPhoneVerificationCodeStore(
    jdbc: JdbcClient,
    clock: Clock,
) : JdbcOneTimeCodeStore(jdbc, clock, "phone_verification_codes"),
    PhoneVerificationCodeStore {
    override fun issuePhoneVerification(
        userId: UUID,
        ttl: Duration,
        cooldown: Duration,
    ): IssuedPhoneVerification = issueWithId(userId, ttl, cooldown).let { IssuedPhoneVerification(it.id, it.code) }

    override fun revokePhoneVerification(
        userId: UUID,
        verificationId: UUID,
    ): Boolean = revokeIssued(userId, verificationId)
}
