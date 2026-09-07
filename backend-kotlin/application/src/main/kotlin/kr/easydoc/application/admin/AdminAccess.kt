package kr.easydoc.application.admin

import kr.easydoc.core.exceptions.AdminRequiredException
import java.util.UUID

/**
 * 관리자 판정 포트 — 어드민 최소 계획 `docs/plans/2026-09-07-admin-minimum.md` §2 결정 1·2.
 *
 * [isVerifiedAdmin]은 **매 요청 DB를 다시 읽는다**(토큰에 넣지 않는다) — 회수(`--revoke`)가
 * 다음 요청부터 즉시 반영돼야 하기 때문이다. [setIsAdmin]은 `admin-grant` 운영 프로필
 * ([kr.easydoc.application.admin.AdminGrantService]) 전용이다.
 */
interface AdminAccessRepository {
    /** `users.is_admin AND email_verified_at IS NOT NULL`. */
    fun isVerifiedAdmin(userId: UUID): Boolean

    /** `users.is_admin`을 갱신한다. 대상 계정이 없으면 거짓. */
    fun setIsAdmin(
        userId: UUID,
        isAdmin: Boolean,
    ): Boolean
}

/**
 * 관리자 API 진입점에서 재사용하는 가드(`AdminAccessInterceptor`, `api` 모듈) — 관리자가
 * 아니거나 이메일이 검증되지 않았으면 [AdminRequiredException](→ 403).
 */
class AdminGuard(private val repository: AdminAccessRepository) {
    fun requireAdmin(userId: UUID) {
        if (!repository.isVerifiedAdmin(userId)) {
            throw AdminRequiredException(ADMIN_REQUIRED_MESSAGE)
        }
    }

    companion object {
        /** 계약이 못박은 403 `detail` 문구(어드민 최소 계획 §2 결정 2). */
        const val ADMIN_REQUIRED_MESSAGE = "관리자 권한이 필요합니다"
    }
}
