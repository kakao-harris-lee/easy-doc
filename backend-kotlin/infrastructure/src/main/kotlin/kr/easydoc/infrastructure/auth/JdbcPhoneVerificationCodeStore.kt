package kr.easydoc.infrastructure.auth

import kr.easydoc.application.auth.PhoneVerificationCodeStore
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock

class JdbcPhoneVerificationCodeStore(
    jdbc: JdbcClient,
    clock: Clock,
) : JdbcOneTimeCodeStore(jdbc, clock, "phone_verification_codes"),
    PhoneVerificationCodeStore
