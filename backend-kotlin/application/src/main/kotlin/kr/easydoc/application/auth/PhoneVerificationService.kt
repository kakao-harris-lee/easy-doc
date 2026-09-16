package kr.easydoc.application.auth

import kr.easydoc.application.credit.CreditAccountService
import kr.easydoc.core.credit.CreditReason
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.EmailNotVerifiedException
import kr.easydoc.core.exceptions.InvalidCredentialsException
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.exceptions.InvalidVerificationCodeException
import kr.easydoc.core.exceptions.StorageException
import kr.easydoc.core.security.Secret
import java.time.Duration
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** SENS 등 실제 SMS 발송기는 이 좁은 포트만 구현한다. */
fun interface PhoneVerificationSmsSender {
    fun send(
        phoneNumber: String,
        code: String,
        validMinutes: Long,
    )
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
    fun hash(number: DomesticMobileNumber): String {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(pepper.reveal().toByteArray(Charsets.UTF_8), ALGORITHM))
        return mac.doFinal(number.digits.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val ALGORITHM = "HmacSHA256"
    }
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
        val code =
            transaction.inTransaction {
                val user = users.lockForUpdate(userId) ?: throw InvalidCredentialsException(ACCOUNT_GONE_MESSAGE)
                requireEligibleForRequest(user)
                codes.issue(userId, codeTtl, resendCooldown).also {
                    users.setPendingPhoneFingerprint(userId, fingerprint)
                }
            }
        try {
            sms.send(number.digits, code, codeTtl.toMinutes())
        } catch (
            @Suppress("TooGenericExceptionCaught") failure: RuntimeException,
        ) {
            transaction.inTransaction {
                codes.revoke(userId)
                users.clearPendingPhoneFingerprint(userId, fingerprint)
            }
            throw failure
        }
    }

    @Suppress("ThrowsCount")
    fun confirm(
        userId: UUID,
        code: String,
    ): PhoneVerificationResult =
        transaction.inTransaction {
            val user = users.lockForUpdate(userId) ?: throw InvalidCredentialsException(ACCOUNT_GONE_MESSAGE)
            if (user.phoneVerifiedAt != null) throw ConflictException(ALREADY_VERIFIED_MESSAGE)
            val fingerprint = user.pendingPhoneFingerprint
            if (fingerprint == null || !codes.attempt(userId, code, maxAttempts)) {
                throw InvalidVerificationCodeException(INVALID_CODE_MESSAGE)
            }
            if (!users.markPhoneVerified(userId)) throw InvalidCredentialsException(ACCOUNT_GONE_MESSAGE)

            val granted = trialCredits > 0 && grants.claim(fingerprint)
            if (granted) {
                val workspaceId =
                    workspaces
                        .listOwned(userId)
                        .firstOrNull()
                        ?.workspace
                        ?.id
                        ?: throw StorageException(STORAGE_FAILURE_MESSAGE)
                credits.grant(
                    workspaceId = workspaceId,
                    ownerUserId = userId,
                    credits = trialCredits,
                    reason = CreditReason.SIGNUP,
                    note = PHONE_TRIAL_NOTE,
                )
            }
            PhoneVerificationResult(grantedCredits = if (granted) trialCredits else 0)
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
