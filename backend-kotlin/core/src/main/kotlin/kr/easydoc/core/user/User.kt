package kr.easydoc.core.user

import kr.easydoc.core.privacy.CONTENT_MASK
import java.time.Instant
import java.util.UUID

/** 사용자 도메인 타입. */
data class User(
    val id: UUID,
    val email: String,
    val createdAt: Instant,
) {
    /** **이메일을 찍지 않는다.** */
    override fun toString(): String = "User(id=$id, email=$CONTENT_MASK, createdAt=$createdAt)"
}

/**
 * 저장소에서 읽어 온 사용자 — 검증에 쓸 비밀번호 해시를 함께 든다.
 *
 * **[passwordHash] 는 `null` 일 수 있다.** 소셜 로그인으로만 가입한 사용자는 비밀번호가
 * 없다(`users.password_hash` 가 nullable — `V5__user_identities.sql`). 비밀번호 로그인
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
