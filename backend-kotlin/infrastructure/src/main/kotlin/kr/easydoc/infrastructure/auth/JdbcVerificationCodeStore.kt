package kr.easydoc.infrastructure.auth

import kr.easydoc.application.auth.VerificationCodeStore
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock

/**
 * `email_verification_codes` 테이블 접근. 스키마는 `V7__email_verification.sql`.
 *
 * 발급·확인 메커니즘 전체는 [JdbcOneTimeCodeStore] 가 진다(SQL·해싱·쿨다운·시도 상한 —
 * 그 클래스 KDoc). 이 클래스는 테이블 이름 하나만 고정하고 [VerificationCodeStore] 타입을
 * 얹는다 — `AuthConfiguration`·`JdbcVerificationCodeStoreTest` 가 그 타입·이 생성자
 * 시그니처를 그대로 쓴다(리팩터로 바뀌지 않는다).
 */
class JdbcVerificationCodeStore(
    jdbc: JdbcClient,
    clock: Clock,
) : JdbcOneTimeCodeStore(jdbc, clock, TABLE),
    VerificationCodeStore {
    private companion object {
        const val TABLE = "email_verification_codes"
    }
}
