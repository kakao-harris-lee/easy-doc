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

    /**
     * 새 사용자를 만든다.
     *
     * 같은 이메일이 이미 있으면 `EmailAlreadyRegisteredException`.
     * **선조회로 판정하지 않는다** — 조회와 삽입 사이에 다른 요청이 끼어들 수 있고,
     * 유일성을 지키는 것은 결국 `ix_users_email` 인덱스다.
     */
    fun create(
        email: String,
        passwordHash: PasswordHash,
    ): User

    /** 재해시 결과를 반영한다. 로그인 **성공 뒤에만** 불린다. */
    fun updatePasswordHash(
        userId: UUID,
        passwordHash: PasswordHash,
    )
}

/**
 * 작업 공간 저장소.
 *
 * **소유자 조건이 인터페이스에 박혀 있다.** 모든 메서드가 `ownerId` 를 받고 구현은 그것을
 * `WHERE` 절에 넣는다 — "먼저 조회하고 나서 소유자를 비교한다"는 형태를 아예 만들 수
 * 없게 하려는 것이다. 그 형태는 비교를 잊으면 조용히 남의 자원을 내주고, 잊지 않아도
 * 존재 여부가 응답 시간으로 새는 경로를 연다(계약 소유권 은닉 조항).
 */
interface WorkspaceRepository {
    /** 가입 트랜잭션 안에서 기본 작업 공간을 만든다. */
    fun createDefault(userId: UUID): UUID

    /**
     * 소유한 작업 공간을 **만든 순서로** 돌려준다 — 계약이 *"첫 번째 항목이 기본 작업
     * 공간이다(가장 먼저 만든 것). 이 순서가 계약이다"* 로 못박았다.
     */
    fun listOwned(ownerId: UUID): List<WorkspaceListing>

    /**
     * 새 작업 공간을 만든다. 같은 사용자 안에서 이름이 겹치면 `ConflictException`.
     *
     * **선조회로 중복을 판정하지 않는다** — 조회와 삽입 사이에 다른 요청이 끼어들 수 있고,
     * 유일성을 지키는 것은 결국 `uq_workspaces_user_id_name` 이다.
     */
    fun create(
        ownerId: UUID,
        name: String,
    ): Workspace

    /**
     * 이름을 바꾼다. **내 것이 아니거나 없으면 `null`** — 둘을 구분하지 않는다.
     *
     * 구분하면 호출자가 그 차이를 응답에 실을 수 있게 되고, 계약이 숨기라고 한 것이
     * 정확히 그 차이다. 이름이 겹치면 `ConflictException` 이다 — 소유 조건이 `WHERE` 에
     * 있으므로 남의 자원에서는 유일성 위반이 애초에 일어나지 않고 `null` 이 나간다.
     */
    fun rename(
        ownerId: UUID,
        workspaceId: UUID,
        name: String,
    ): Workspace?

    /**
     * 삭제 판정에 필요한 상태를 읽고 **그 사용자의 작업 공간 행을 잠근다**.
     *
     * 잠그는 이유는 「마지막 하나」 판정이 행 하나가 아니라 **집합**에 걸리기 때문이다.
     * 대상 행만 잠그면 같은 사용자의 다른 작업 공간을 지우는 요청과 동시에 돌아 둘 다
     * "아직 둘 있다"를 보고 통과하고, 결과가 0 개가 된다. 없거나 내 것이 아니면 `null`.
     *
     * 호출자가 트랜잭션 안에서 불러야 한다 — 밖에서 부르면 잠금이 즉시 풀려 아무것도
     * 지키지 못한다.
     */
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

/**
 * 삭제를 거절해야 하는지 판정할 재료. 계약 `DELETE /workspaces/{workspace_id}` 의 409 두 갈래다.
 *
 * @property ownedWorkspaceCount 그 사용자의 작업 공간 수. 1 이면 대상이 마지막 하나다.
 * @property documentCount 대상 작업 공간에 남은 문서 수.
 */
data class WorkspaceDeletionState(
    val ownedWorkspaceCount: Int,
    val documentCount: Int,
)

/**
 * 비밀번호 해시 계산·검증·재해시 판정.
 *
 * 요구 정본은 `migration-safety-gate` I-8 이다. 특히 [needsRehash] 는 Spring Security
 * `Argon2PasswordEncoder.upgradeEncoding()` 을 **쓰면 안 되는** 자리다 — 그쪽은
 * memory·iterations 의 "미만"만 보므로 파라미터를 낮춘 경우와 `parallelism` 만 바뀐
 * 경우를 놓친다(Phase 0 실측, 필수조치 A).
 */
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

    /**
     * 계정이 없을 때 [verify] 에 먹일 **더미 PHC**.
     *
     * 계약 `x-auth.failure_uniformity` 가 *"사용자가 없을 때도 더미 해시로 같은 검증 비용을
     * 치러 응답 시간으로도 새지 않게 한다"* 고 정한다. 이 값이 없으면 로그인 응답 시간이
     * 계정 존재 여부를 알려 준다 — 실측 42배(privacy-gate B-1).
     *
     * 구현이 지켜야 하는 것 둘.
     * ⑴ **현행 정책 파라미터로 만든다.** 파라미터가 다르면 계산 비용이 달라져 격차가 그대로
     *    남는다. 그래서 상수 문자열을 돌려주면 안 된다.
     * ⑵ **해시의 원문을 아무도 알 수 없어야 한다.** 알려진 값(코드 안의 `const` 등)을
     *    해시하면 그 값으로 [verify] 가 `true` 가 된다 — 종전 구현이 그랬고 privacy-gate
     *    M-1 이 실측했다. 기동 시 난수를 해시하면 이 조건이 선다.
     *
     * **재해시에 닿지 않는 근거는 [verify] 의 결과가 아니라 제어 흐름이다.** 계정 부재
     * 경로는 검증 결과를 버리고 무조건 실패를 던지며, 재해시는 검증 성공 뒤에만 불린다
     * (I-8 검증 2 — 실패한 로그인에서 재해시 금지). ⑵ 가 필요한 이유는 두 분기를 하나로
     * 합치는 형태의 변경에서 그 성질이 실제로 요구되기 때문이다.
     */
    fun dummyHash(): PasswordHash
}

/**
 * 액세스 토큰 발급·검증.
 *
 * 요구 정본은 계약 `x-auth` 와 `migration-safety-gate` I-9 다.
 * 만료 판정에 허용 오차를 두지 않는다(clock skew 0).
 */
interface AccessTokens {
    /**
     * 서명 설정이 갖춰졌는지 본다. 아니면 `ConfigurationException`(→ 503).
     *
     * **가입이 이것을 먼저 부른다.** 계약이 *"JWT 비밀키 미설정 또는 32바이트 미만 →
     * 인증 API 전체"* 를 503 으로 정했고(`components/responses/ServiceUnavailable`),
     * 가입만 성공시키면 **로그인할 수 없는 계정**이 만들어진다. 값비싼 해시 계산 전에
     * 끊는 효과도 있다.
     */
    fun ensureConfigured()

    fun issue(userId: UUID): IssuedAccessToken

    /**
     * 토큰을 검증하고 `sub` 를 돌려준다.
     *
     * 실패는 **원인을 구분하지 않고** `InvalidCredentialsException` 이다 — 만료·위조·
     * 클레임 누락·용도 불일치를 가르면 유효한 토큰 형식을 탐색하는 단서가 된다
     * (계약 `x-auth.failure_uniformity`). 서명 키 설정 문제만 `ConfigurationException`
     * 으로 갈라 나간다(운영 문제이지 호출자 잘못이 아니다 → 503).
     */
    fun verify(token: String): UUID
}

/**
 * 발급된 액세스 토큰.
 *
 * `data class` 가 아니다 — 자동 `toString()` 이 토큰 전문을 찍는다. 응답 본문 자체가
 * 토큰이라 값은 꺼내 쓸 수 있어야 하지만, **로그로 새는 경로는 막는다.**
 */
class IssuedAccessToken(
    val token: String,
    val expiresInSeconds: Long,
) {
    override fun toString(): String = "IssuedAccessToken(expiresInSeconds=$expiresInSeconds)"
}

/**
 * 트랜잭션 경계.
 *
 * `application` 은 Spring 을 모르므로 `@Transactional` 을 쓸 수 없다. 경계를 유스케이스
 * 계층이 여는 규율(kotlin-spring-conventions §6.2)을 지키려면 이 포트가 필요하다 —
 * 구현은 `infrastructure` 의 `TransactionTemplate` 이다.
 *
 * **외부 호출(LLM·문서 파싱)을 이 블록 안에서 하지 않는다.** 커넥션을 붙잡은 채 수 초를
 * 기다리면 풀이 마른다.
 */
interface TransactionRunner {
    fun <T> inTransaction(block: () -> T): T
}
