package kr.easydoc.application.admin

import kr.easydoc.application.auth.UserRepository
import kr.easydoc.application.mail.EmailAddress
import java.util.UUID

/** [AdminGrantService.grant]의 결과 — `admin-grant` 프로필(`AdminGrantRunner`, `api` 모듈)이 가른다. */
sealed interface AdminGrantResult {
    /** 반영됨 — [isAdmin]이 새 플래그 값이다. */
    data class Applied(
        val userId: UUID,
        val isAdmin: Boolean,
    ) : AdminGrantResult

    /** 그 이메일의 계정이 없다. */
    object UserNotFound : AdminGrantResult

    /** 부여(`--revoke` 아님) 대상인데 이메일이 검증되지 않았다. */
    object EmailNotVerified : AdminGrantResult
}

/**
 * `admin-grant --email=<이메일> [--revoke]` 유스케이스(어드민 최소 계획 §2 결정 1) —
 * 검증된 이메일 계정에만 관리자 권한을 부여한다. `--revoke`는 검증 여부와 무관하게 항상
 * 회수할 수 있다(잘못 부여된 미검증 계정도 되돌릴 수 있어야 한다).
 */
class AdminGrantService(
    private val users: UserRepository,
    private val access: AdminAccessRepository,
) {
    /**
     * 세 갈래(UserNotFound·EmailNotVerified·Applied)를 이른 반환으로 가른다 — `require()`로
     * 뭉치면 사유가 뭉개진다. [AdminAccessRepository.setIsAdmin]이 거짓을 돌려주면(그
     * 사이 계정이 지워진 경합) `Applied`를 자칭하지 않고 `UserNotFound`로 내린다 —
     * 독립 리뷰 지적, 반영되지 않은 변경을 성공으로 보고하지 않는다.
     */
    @Suppress("ReturnCount")
    fun grant(
        rawEmail: String,
        revoke: Boolean,
    ): AdminGrantResult {
        val email = EmailAddress.of(rawEmail)
        val stored = users.findByEmail(email.value) ?: return AdminGrantResult.UserNotFound
        val nextFlag = !revoke
        if (nextFlag && stored.user.emailVerifiedAt == null) {
            return AdminGrantResult.EmailNotVerified
        }
        val applied = access.setIsAdmin(stored.user.id, nextFlag)
        if (!applied) return AdminGrantResult.UserNotFound
        return AdminGrantResult.Applied(stored.user.id, nextFlag)
    }
}
