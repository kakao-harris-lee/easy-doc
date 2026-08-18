package kr.easydoc.core.user

import java.time.Instant
import java.util.UUID

/**
 * 사용자 도메인 타입.
 *
 * **비밀번호 해시를 담지 않는다.** 계약 `components/schemas/UserResponse` 가
 * *"`password_hash` 는 절대 포함하지 않는다"* 로 못박았고, 담아 두면 `toString()`·
 * 직렬화·로그 어디에서든 새는 경로가 생긴다. 해시가 필요한 자리(로그인 검증)는
 * [StoredUser] 를 따로 받는다 — **필요한 자리에서만 해시가 손에 들어오게** 하는 것이
 * 이 두 타입을 가르는 이유다.
 *
 * `email` 은 **정규화된 값**이다(앞뒤 공백 제거 + 소문자). 정규화 규칙의 정본은
 * `application` 의 `EmailNormalization` 이고, DB `ck_users_email_lowercase` 제약이
 * 서비스를 거치지 않는 경로까지 같은 상태를 강제한다.
 */
data class User(
    val id: UUID,
    val email: String,
    val createdAt: Instant,
)

/**
 * 저장소에서 읽어 온 사용자 — 검증에 쓸 비밀번호 해시를 함께 든다.
 *
 * `data class` 로 두지 않는다. 자동 생성된 `toString()` 이 [passwordHash] 를 찍는데,
 * [PasswordHash] 자신이 마스킹하더라도 "해시를 든 타입은 data class 로 만들지 않는다"는
 * 규율을 타입 하나에서만 지키면 다음에 필드가 하나 늘 때 무너진다.
 */
class StoredUser(
    val user: User,
    val passwordHash: PasswordHash,
) {
    override fun toString(): String = "StoredUser(userId=${user.id})"
}
