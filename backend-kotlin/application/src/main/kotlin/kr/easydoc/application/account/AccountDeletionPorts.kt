package kr.easydoc.application.account

import kr.easydoc.core.user.PasswordHash
import java.util.UUID

// 회원 탈퇴 유스케이스가 바깥 세계에 요구하는 것들 — **포트 선언**(`AuthPorts.kt`와 같은 규약).
//
// `application` 은 `infrastructure` 를 의존하지 않는다. 구현은 JDBC 를 아는
// `infrastructure` 가 제공한다 — 계획 `docs/plans/2026-09-09-account-deletion.md` §3.

/**
 * `users` 행을 잠근 뒤 탈퇴 판정에 필요한 상태만 읽는다 — [DeleteAccountService.deleteAccount]
 * 전용이다. `UserRepository.lockForUpdate` 가 돌려주는 [kr.easydoc.core.user.User] 에는
 * 비밀번호 해시가 없어(도메인 타입이 해시를 노출하지 않는다) 재확인 검증에 쓸 수 없고,
 * 이 자리만 그 값이 필요하므로 별도 타입으로 둔다.
 */
data class LockedAccount(
    val isAdmin: Boolean,
    val passwordHash: PasswordHash?,
) {
    /** [kr.easydoc.core.user.User.hasPassword] 와 같은 판정 — 해시 존재 여부. */
    val hasPassword: Boolean get() = passwordHash != null
}

/**
 * 회원 탈퇴 저장소 — 계획 §2 결정 4.
 *
 * **[deleteConversionFeedback] 은 반드시 [deleteUser] 보다 먼저 부른다.**
 * `conversion_feedback`(V2)은 FK가 없어 `users` 삭제의 CASCADE 가 닿지 않는다 — 사용자를
 * 먼저 지우면 그 사용자의 변환 id 를 찾을 조인 대상(`conversions`→`documents`)이 이미
 * 사라진 뒤라 그 자유 의견이 봉인된 채로 영원히 남는다. 두 메서드 다 [DeleteAccountService]
 * 가 **같은 트랜잭션 안에서** 이 순서로 부른다.
 */
interface AccountDeletionRepository {
    /**
     * `users` 행을 `SELECT … FOR UPDATE` 로 잠그고 [LockedAccount] 를 읽는다. 계정이 이미
     * 없으면(토큰은 유효한데 동시에 지워진 경우) `null` — `UserRepository.lockForUpdate` 와
     * 같은 규약.
     */
    fun lockForDeletion(userId: UUID): LockedAccount?

    /**
     * 그 사용자가 소유한, 처리 대기(`requested`) 세금계산서 요청 id 목록 — 운영자 알림용
     * (계획 §2 결정 6). 탈퇴 자체를 막지 않는다.
     */
    fun pendingInvoiceRequestIds(userId: UUID): List<UUID>

    /**
     * 그 사용자의 변환에 딸린 `conversion_feedback` 을 지운다 — [deleteUser] 보다 **먼저**
     * 불러야 한다(이 인터페이스 KDoc).
     */
    fun deleteConversionFeedback(userId: UUID)

    /** `users` 행을 지운다 — CASCADE 로 나머지 대부분이 함께 사라진다(계획 §1). */
    fun deleteUser(userId: UUID)
}
