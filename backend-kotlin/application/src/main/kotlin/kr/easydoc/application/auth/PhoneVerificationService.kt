package kr.easydoc.application.auth

import kr.easydoc.application.credit.CreditAccountService
import kr.easydoc.core.credit.CreditReason
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.EmailNotVerifiedException
import kr.easydoc.core.exceptions.InvalidCredentialsException
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.exceptions.InvalidVerificationCodeException
import kr.easydoc.core.exceptions.StorageException
import kr.easydoc.core.security.HmacSha256
import kr.easydoc.core.security.Secret
import java.time.Duration
import java.util.UUID

/** SENS 등 실제 SMS 발송기는 이 좁은 포트만 구현한다. */
fun interface PhoneVerificationSmsSender {
    fun send(
        phoneNumber: String,
        code: String,
        validMinutes: Long,
    )
}

/** [PhoneVerificationSmsSender] 로 보낸 인증 코드 하나 — e2e 진단 전용. */
data class SentPhoneVerification(
    val code: String,
    val validMinutes: Long,
) {
    /** 코드가 로그로 새지 않게 한다 — `LatestMailResponse`·`ConfirmEmailVerificationRequest` 와 같은 규약. */
    override fun toString(): String = "SentPhoneVerification(codeLength=${code.length}, validMinutes=$validMinutes)"
}

/**
 * 발송한 인증 코드를 다시 읽는 포트 — **e2e 진단 전용**이다. [kr.easydoc.application.mail.MailInbox]
 * 와 같은 이유로 얇게 둔다: 실제 SENS 어댑터는 보낸 문자를 되읽을 방법이 없으므로
 * `FakeSmsSender`(infrastructure) 만 구현하고, `api` 모듈은 `infrastructure` 를
 * `runtimeOnly` 로만 의존해 그 구체 타입을 컴파일 시점에 보지 못하므로 이 포트를
 * `application` 에 둔다.
 */
interface PhoneVerificationSmsOutbox {
    /** 그 번호로 보낸 가장 최근 인증 코드. 없으면 `null`. */
    fun latestTo(phoneNumber: String): SentPhoneVerification?
}

/** 번호 지문을 선점해 무료 체험 중복 지급을 원자적으로 막는다. */
fun interface PhoneTrialGrantLedger {
    fun claim(fingerprint: String): Boolean
}

/** 국내 010 번호만 받으며 평문을 로그에 노출하지 않는다. */
class DomesticMobileNumber private constructor(val digits: String) {
    override fun toString(): String = "DomesticMobileNumber([REDACTED])"

    companion object {
        fun of(raw: String): DomesticMobileNumber {
            val normalized = raw.filterNot { it == '-' || it.isWhitespace() }
            if (!normalized.matches(Regex("010[0-9]{8}"))) {
                throw InvalidInputException("국내 010 휴대폰 번호를 입력해 주세요")
            }
            return DomesticMobileNumber(normalized)
        }
    }
}

/** 단순 해시로 열거 공격을 허용하지 않도록 번호에 서버 비밀값을 섞는다. */
class PhoneFingerprintHasher(private val pepper: Secret) {
    fun hash(number: DomesticMobileNumber): String = HmacSha256.hex(pepper, number.digits)
}

/** 휴대폰 인증, 최초 번호당 체험 크레딧 지급, 결제 자격 생성 유스케이스. */
@Suppress("LongParameterList")
class PhoneVerificationService(
    private val users: UserRepository,
    private val workspaces: WorkspaceRepository,
    private val codes: PhoneVerificationCodeStore,
    private val sms: PhoneVerificationSmsSender,
    private val credits: CreditAccountService,
    private val grants: PhoneTrialGrantLedger,
    private val hasher: PhoneFingerprintHasher,
    private val transaction: TransactionRunner,
    private val codeTtl: Duration,
    private val resendCooldown: Duration,
    private val maxAttempts: Int,
    private val trialCredits: Int,
) {
    fun request(
        userId: UUID,
        rawPhoneNumber: String,
    ) {
        val number = DomesticMobileNumber.of(rawPhoneNumber)
        val fingerprint = hasher.hash(number)
        val issued =
            transaction.inTransaction {
                val user = users.lockForUpdate(userId) ?: throw InvalidCredentialsException(ACCOUNT_GONE_MESSAGE)
                requireEligibleForRequest(user)
                codes.issuePhoneVerification(userId, codeTtl, resendCooldown).also { issued ->
                    users.setPendingPhoneFingerprint(userId, fingerprint, issued.id)
                }
            }
        try {
            sms.send(number.digits, issued.code, codeTtl.toMinutes())
        } catch (
            @Suppress("TooGenericExceptionCaught") failure: RuntimeException,
        ) {
            transaction.inTransaction {
                if (codes.revokePhoneVerification(userId, issued.id)) {
                    users.clearPendingPhoneFingerprint(userId, issued.id)
                }
            }
            throw failure
        }
    }

    @Suppress("ThrowsCount")
    fun confirm(
        userId: UUID,
        code: String,
    ): PhoneVerificationResult {
        val result =
            transaction.inTransaction {
                val user = users.lockForUpdate(userId) ?: throw InvalidCredentialsException(ACCOUNT_GONE_MESSAGE)
                if (user.phoneVerifiedAt != null) throw ConflictException(ALREADY_VERIFIED_MESSAGE)
                val fingerprint = user.pendingPhoneFingerprint
                if (fingerprint == null || !codes.attempt(userId, code, maxAttempts)) {
                    return@inTransaction null
                }
                if (!users.markPhoneVerified(userId)) throw InvalidCredentialsException(ACCOUNT_GONE_MESSAGE)

                val granted = trialCredits > 0 && grants.claim(fingerprint)
                if (granted) {
                    val workspaceId =
                        workspaces.findDefaultId(userId)
                            ?: throw StorageException(STORAGE_FAILURE_MESSAGE)
                    credits.grantFreeTrial(
                        workspaceId = workspaceId,
                        ownerUserId = userId,
                        credits = trialCredits,
                        reason = CreditReason.SIGNUP,
                        note = PHONE_TRIAL_NOTE,
                    )
                }
                PhoneVerificationResult(grantedCredits = if (granted) trialCredits else 0)
            }
        return result ?: throw InvalidVerificationCodeException(INVALID_CODE_MESSAGE)
    }

    private fun requireEligibleForRequest(user: kr.easydoc.core.user.User) {
        if (user.emailVerifiedAt == null) throw EmailNotVerifiedException("이메일 인증을 먼저 완료해 주세요")
        if (user.phoneVerifiedAt != null) throw ConflictException(ALREADY_VERIFIED_MESSAGE)
    }

    private companion object {
        const val ACCOUNT_GONE_MESSAGE = "이메일 또는 비밀번호가 올바르지 않습니다"
        const val ALREADY_VERIFIED_MESSAGE = "이미 인증된 휴대폰 번호가 있습니다"
        const val INVALID_CODE_MESSAGE = "인증 코드가 올바르지 않거나 만료되었습니다"
        const val STORAGE_FAILURE_MESSAGE = "요청을 처리하지 못했습니다"
        const val PHONE_TRIAL_NOTE = "phone_verification_trial"
    }
}

data class PhoneVerificationResult(val grantedCredits: Int)
