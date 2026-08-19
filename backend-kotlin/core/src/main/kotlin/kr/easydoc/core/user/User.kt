package kr.easydoc.core.user

import kr.easydoc.core.privacy.CONTENT_MASK
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
) {
    /**
     * **이메일을 찍지 않는다.**
     *
     * 이 KDoc 의 첫 절이 비밀번호 해시를 담지 않는 근거로 *"담아 두면 `toString()`·직렬화·
     * 로그 어디에서든 새는 경로가 생긴다"* 를 들면서, 정작 **이메일 자신에는 같은 규율이
     * 적용되지 않은 비대칭**이 있었다(게이트 23 privacy-gate 3a — `/auth/me` 는 요청마다
     * 이 객체를 힙에 올린다). `Workspace.name` 이 같은 이유로 먼저 고쳐졌고 이메일만
     * 남아 있었으므로, 비대칭은 사라진 것이 아니라 **옮겨간** 상태였다.
     *
     * 이메일은 개인정보다(`CLAUDE.md` 보안 규칙 — 로깅은 문서 ID·길이·처리 상태까지).
     * 마스킹 범주가 2종으로 좁아진 것은 **LLM 전송 경계**의 결정이고 로그 경계와 무관하다.
     *
     * 오늘 이 경로에 로거가 0개라 **도달은 0**이다. 그래도 지금 막는 이유는 `Workspace`
     * 와 같다 — 막는 비용이 한 줄인데 새는 순간은 로깅이 처음 들어오는 커밋이다.
     * **직렬화는 가리지 않는다** — 계약 `UserResponse.email` 은 required 다.
     */
    override fun toString(): String = "User(id=$id, email=$CONTENT_MASK, createdAt=$createdAt)"
}

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
