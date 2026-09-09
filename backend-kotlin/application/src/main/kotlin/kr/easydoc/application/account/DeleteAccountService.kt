package kr.easydoc.application.account

import kr.easydoc.application.auth.PasswordHasher
import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.mail.EmailAddress
import kr.easydoc.application.mail.MailSender
import kr.easydoc.application.mail.OutboundMail
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.InvalidCredentialsException
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.user.PasswordHash
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * 회원 탈퇴 유스케이스 — `POST /auth/me/deletion`(계획
 * `docs/plans/2026-09-09-account-deletion.md` §2).
 *
 * **즉시 파기다. 유예 기간·복구 기간을 두지 않는다**(계획 §2 결정 3). 재확인 검증 →
 * 세금계산서 처리 대기 조회 → 피드백 삭제 → 사용자 삭제를 **한 트랜잭션**에서 한다 —
 * 검증 실패는 아무것도 지우지 않는다(수용 기준 3·4).
 *
 * 갈래마다 다른 판정(계정 소멸·관리자·재확인 실패)이 서로 독립인 가드라 `ThrowsCount` 를
 * 억제한다 — `PasswordService.set` 과 같은 판단.
 */
@Suppress("ThrowsCount")
class DeleteAccountService(
    private val accounts: AccountDeletionRepository,
    private val passwords: PasswordHasher,
    private val transaction: TransactionRunner,
    private val mail: MailSender,
    private val operatorEmail: String,
) {
    private val log = LoggerFactory.getLogger(DeleteAccountService::class.java)

    /**
     * [rawPassword] 는 비밀번호가 있는 계정(`has_password`)에만 필수다 — 소셜 전용 계정은
     * 확인 문구만으로 탈퇴된다(수용 기준 2). [confirmation] 은 두 경우 모두 정확히
     * [CONFIRMATION_PHRASE] 여야 한다. 관리자 계정은 409(수용 기준 4).
     */
    fun deleteAccount(
        userId: UUID,
        rawPassword: String?,
        confirmation: String,
    ) {
        requireConfirmationPhrase(confirmation)

        val pendingInvoiceRequestIds =
            transaction.inTransaction {
                val locked =
                    accounts.lockForDeletion(userId)
                        ?: throw InvalidCredentialsException(ACCOUNT_GONE_MESSAGE)
                if (locked.isAdmin) {
                    throw ConflictException(ADMIN_CANNOT_DELETE_MESSAGE)
                }
                if (locked.hasPassword) {
                    requireMatchingPassword(rawPassword, locked.passwordHash)
                }

                val ids = accounts.pendingInvoiceRequestIds(userId)
                // 사용자보다 먼저 지운다 — 포트 KDoc.
                accounts.deleteConversionFeedback(userId)
                accounts.deleteUser(userId)
                ids
            }

        // 감사 로그는 user_id 한 줄뿐이다 — 이메일·이름을 남기지 않는다(계획 §2 결정 10).
        log.info("계정을 탈퇴 처리했다: userId={}", userId)
        notifyOperatorIfPending(pendingInvoiceRequestIds)
    }

    private fun requireConfirmationPhrase(confirmation: String) {
        if (confirmation != CONFIRMATION_PHRASE) {
            throw InvalidInputException(CONFIRMATION_MISMATCH_MESSAGE)
        }
    }

    /** [stored] 는 `locked.hasPassword` 가 참일 때만 불리므로 `null` 이 아니다. */
    private fun requireMatchingPassword(
        rawPassword: String?,
        stored: PasswordHash?,
    ) {
        if (rawPassword == null || stored == null || !passwords.verify(rawPassword, stored)) {
            throw InvalidInputException(PASSWORD_MISMATCH_MESSAGE)
        }
    }

    /**
     * 처리 대기 요청이 있으면 운영자에게 알림 메일을 보낸다(계획 §2 결정 6) — **요청 id만**
     * 담는다(이름·이메일 금지). **best-effort** 다 — 발송 실패가 탈퇴를 막지 않는다
     * (`InvoiceRequestService.sendOperatorMail` 과 같은 규약, 탈퇴는 이 시점에 이미 끝났다).
     */
    private fun notifyOperatorIfPending(pendingInvoiceRequestIds: List<UUID>) {
        if (pendingInvoiceRequestIds.isEmpty()) {
            return
        }
        if (operatorEmail.isBlank()) {
            log.warn(
                "easydoc.billing.operator-email 설정이 비어 있어 탈퇴에 따른 세금계산서 취소 알림을 보내지 않는다",
            )
            return
        }
        val subject = "[쉬운 글] 회원 탈퇴로 세금계산서 요청이 취소되었습니다"
        val body = "취소된 요청 id: ${pendingInvoiceRequestIds.joinToString(", ")}"
        try {
            mail.send(OutboundMail(EmailAddress.of(operatorEmail), subject, body))
        } catch (
            @Suppress("TooGenericExceptionCaught") failure: RuntimeException,
        ) {
            // 예외 객체를 넘기지 않는다 — 타입 이름만(`InvoiceRequestService`와 같은 규약).
            log.warn(
                "탈퇴에 따른 세금계산서 취소 알림 메일 발송에 실패했다(탈퇴는 이미 끝났다): 예외={}",
                failure::class.java.simpleName,
            )
        }
    }

    companion object {
        /** 계약 `DeleteAccountRequest.confirmation` — 정본은 이 값 하나다. */
        const val CONFIRMATION_PHRASE = "탈퇴합니다"

        const val CONFIRMATION_MISMATCH_MESSAGE = "확인 문구가 올바르지 않습니다. \"탈퇴합니다\"를 정확히 입력해 주세요."
        const val PASSWORD_MISMATCH_MESSAGE = "비밀번호가 올바르지 않습니다"

        /** 계약 `POST /auth/me/deletion` 409 예시 — 관리자 회수 절차를 안내한다(계획 §2 결정 5). */
        const val ADMIN_CANNOT_DELETE_MESSAGE =
            "관리자 계정은 탈퇴할 수 없습니다. admin-grant --revoke로 관리자 권한을 회수한 뒤 다시 시도하세요."

        /** 인증된 요청인데 계정이 그 사이 지워진 경우 — `AuthService`·`PasswordService`와 같은 문구. */
        private const val ACCOUNT_GONE_MESSAGE = "이메일 또는 비밀번호가 올바르지 않습니다"
    }
}
