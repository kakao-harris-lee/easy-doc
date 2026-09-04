package kr.easydoc.core.user

import kr.easydoc.core.privacy.CONTENT_MASK
import java.time.Instant
import java.util.UUID

/**
 * 사용자 도메인 타입.
 *
 * [emailVerifiedAt] 이 `null` 이면 이메일 소유를 아직 확인하지 못한 계정이다 — 이메일/
 * 비밀번호로 갓 가입한 계정의 기본값이다. 소셜 로그인 계정은 제공자가 이미 검증한
 * 이메일만 받으므로 생성 시점에 채워진다([kr.easydoc.application.auth.SocialLoginService]).
 * V7 적용 시점에 존재하던 계정은 소급 인증(grandfather)됐다(`V7__email_verification.sql`).
 */
data class User(
    val id: UUID,
    val email: String,
    val createdAt: Instant,
    val emailVerifiedAt: Instant? = null,
) {
    /** **이메일을 찍지 않는다.** */
    override fun toString(): String =
        "User(id=$id, email=$CONTENT_MASK, createdAt=$createdAt, emailVerified=${emailVerifiedAt != null})"
}

/**
 * 저장소에서 읽어 온 사용자 — 검증에 쓸 비밀번호 해시를 함께 든다.
 *
 * **[passwordHash] 는 `null` 일 수 있다.** 소셜 로그인으로만 가입한 사용자는 비밀번호가
 * 없다(`users.password_hash` 가 nullable — `V6__user_identities.sql`). 비밀번호 로그인
 * 유스케이스([kr.easydoc.application.auth.AuthService.login])는 `null` 을 "이 계정은
 * 비밀번호로 로그인할 수 없다"로 다루고, 계정 존재 여부를 흘리지 않도록 더미 해시 검증과
 * 같은 비용을 치른 뒤 자격증명 실패로 통일한다.
 */
class StoredUser(
    val user: User,
    val passwordHash: PasswordHash?,
) {
    override fun toString(): String = "StoredUser(userId=${user.id})"
}
