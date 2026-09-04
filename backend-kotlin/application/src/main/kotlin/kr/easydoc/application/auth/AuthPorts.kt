package kr.easydoc.application.auth

import kr.easydoc.core.user.PasswordHash
import kr.easydoc.core.user.StoredUser
import kr.easydoc.core.user.User
import kr.easydoc.core.workspace.Workspace
import kr.easydoc.core.workspace.WorkspaceListing
import java.util.UUID

// 유스케이스가 바깥 세계에 요구하는 것들 — **포트 선언**.
//
// `application` 은 `infrastructure` 를 의존하지 않는다(계획 §3.2). 구현은 JDBC·Argon2·
// JWT 를 아는 `infrastructure` 가 제공하고, 이 모듈은 인터페이스만 안다. Python 이
// `app/services` 가 `Protocol` 로 저장소 계약을 선언하고 `app/repositories`
// 가 그것을 만족시키던 구조와 같은 자리다.
//
// **인증 전용 파일이 아니다.** [WorkspaceRepository]·[TransactionRunner] 는 작업 공간
// 유스케이스도 함께 쓴다. 파일이 `auth` 패키지에 남은 것은 auth 단위가 이 자리를
// *"목록·이름 변경·삭제는 다음 작업 단위에서 이 인터페이스에 붙는다"* 로 예고했기
// 때문이고, 그 예고를 따르는 대신 파일 이름이 담는 범위를 여기서 넓혀 적는다 — 선언한
// 범위와 실제 내용이 갈리는 것이 이 저장소가 반복해 고쳐 온 형태다. 패키지 재배치는
// 개선 후보로 남긴다(리뷰를 마친 auth 코드의 import 를 흔들 값어치가 지금은 없다).

/** 사용자 저장소. 이메일은 **정규화된 값**으로만 들어온다([EmailNormalization]). */
interface UserRepository {
    /** 이메일로 찾는다. 없으면 `null` — 로그인 실패와 존재하지 않는 계정을 호출자가 구분하지 않는다. */
    fun findByEmail(email: String): StoredUser?

    /** 식별자로 찾는다. 토큰은 유효한데 계정이 지워진 경우를 위해 `null` 을 돌려준다. */
    fun findById(id: UUID): User?

    /** 그 식별자의 계정이 **아직 있는지**만 본다. */
    fun exists(id: UUID): Boolean

    /** 새 사용자를 만든다. */
    fun create(
        email: String,
        passwordHash: PasswordHash,
    ): User

    /**
     * 비밀번호 없이 새 사용자를 만든다 — 소셜 로그인 최초 가입
     * ([kr.easydoc.application.auth.SocialLoginService]). `password_hash` 는 `null` 로
     * 저장된다(`users.password_hash` nullable, `V5__user_identities.sql`). 이 계정은
     * [PasswordHasher] 를 거치지 않으므로 [create] 와 별도 메서드다 — 매개변수를
     * `PasswordHash?` 로 열면 호출부마다 "언제 null 을 줘도 되는지"를 스스로 판단해야
     * 하고, 이름 있는 메서드 둘이 그 판단을 타입으로 대신한다.
     */
    fun createWithoutPassword(email: String): User

    /** 재해시 결과를 반영한다. 로그인 **성공 뒤에만** 불린다. */
    fun updatePasswordHash(
        userId: UUID,
        passwordHash: PasswordHash,
    )
}

/** 작업 공간 저장소. */
interface WorkspaceRepository {
    /** 가입 트랜잭션 안에서 기본 작업 공간을 만든다. */
    fun createDefault(userId: UUID): UUID

    /**
     * 소유한 작업 공간을 **만든 순서로** 돌려준다 — 계약이 *"첫 번째 항목이 기본 작업
     * 공간이다(가장 먼저 만든 것). 이 순서가 계약이다"* 로 못박았다.
     */
    fun listOwned(ownerId: UUID): List<WorkspaceListing>

    /** 새 작업 공간을 만든다. 같은 사용자 안에서 이름이 겹치면 `ConflictException`. */
    fun create(
        ownerId: UUID,
        name: String,
    ): Workspace

    /** 이름을 바꾼다. **내 것이 아니거나 없으면 `null`** — 둘을 구분하지 않는다. */
    fun rename(
        ownerId: UUID,
        workspaceId: UUID,
        name: String,
    ): Workspace?

    /** 삭제 판정에 필요한 상태를 읽고 **그 사용자의 작업 공간 행을 잠근다**. */
    fun lockForDeletion(
        ownerId: UUID,
        workspaceId: UUID,
    ): WorkspaceDeletionState?

    /** 지운다. 지운 행이 없으면 `false`. */
    fun delete(
        ownerId: UUID,
        workspaceId: UUID,
    ): Boolean
}

/** 삭제를 거절해야 하는지 판정할 재료. 계약 `DELETE /workspaces/{workspace_id}` 의 409 두 갈래다. */
data class WorkspaceDeletionState(
    val ownedWorkspaceCount: Int,
    val documentCount: Int,
)

/** 비밀번호 해시 계산·검증·재해시 판정. */
interface PasswordHasher {
    /** PHC 문자열을 만든다. */
    fun hash(rawPassword: String): PasswordHash

    /** 검증한다. 파라미터는 **저장된 PHC 에서 읽어** 쓴다(하드코딩 금지 — I-8 검증 1). */
    fun verify(
        rawPassword: String,
        stored: PasswordHash,
    ): Boolean

    /**
     * 저장된 해시의 **파라미터 집합 전체**가 현행 정책과 같은지 본다.
     * 변형·버전·메모리·반복·병렬도·salt 길이·hash 길이 중 **하나라도 다르면** `true`.
     */
    fun needsRehash(stored: PasswordHash): Boolean

    /** 계정이 없을 때 [verify] 에 먹일 **더미 PHC**. */
    fun dummyHash(): PasswordHash
}

/** 액세스 토큰 발급·검증. */
interface AccessTokens {
    /** 서명 설정이 갖춰졌는지 본다. 아니면 `ConfigurationException`(→ 503). */
    fun ensureConfigured()

    fun issue(userId: UUID): IssuedAccessToken

    /** 토큰을 검증하고 `sub` 를 돌려준다. */
    fun verify(token: String): UUID
}

/** 발급된 액세스 토큰. */
class IssuedAccessToken(
    val token: String,
    val expiresInSeconds: Long,
) {
    override fun toString(): String = "IssuedAccessToken(expiresInSeconds=$expiresInSeconds)"
}

/** 트랜잭션 경계. */
interface TransactionRunner {
    fun <T> inTransaction(block: () -> T): T
}
