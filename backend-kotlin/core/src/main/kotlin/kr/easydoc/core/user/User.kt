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

/** 저장소에서 읽어 온 사용자 — 검증에 쓸 비밀번호 해시를 함께 든다. */
class StoredUser(
    val user: User,
    val passwordHash: PasswordHash,
) {
    override fun toString(): String = "StoredUser(userId=${user.id})"
}
