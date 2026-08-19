package kr.easydoc.application.auth

import kr.easydoc.core.exceptions.InvalidCredentialsException
import kr.easydoc.core.user.StoredUser
import kr.easydoc.core.user.User
import kr.easydoc.core.workspace.DEFAULT_WORKSPACE_NAME
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * 인증 유스케이스 — 가입 · 로그인 · 내 정보.
 *
 * 요구 정본: 계약 `contracts/easy-doc-v1.yaml` 의 `/auth` 세 경로·`x-auth`,
 * 그리고 `migration-safety-gate` I-8(Argon2)·I-9(JWT).
 *
 * **Python `app/services/auth.py` 를 옮긴 것이 아니다.** 인증·비밀번호는 명세에서 새로
 * 만든다는 결정(`CLAUDE.md` — "명세가 있는 것을 확인하겠다고 Python 코드 읽기" 금지)에
 * 따라 위 두 정본만 보고 썼다.
 *
 * ## 실패를 구분하지 않는다
 *
 * 이메일 부재·비밀번호 불일치·계정 삭제가 전부 같은 401, 같은 문구다
 * (계약 `x-auth.failure_uniformity`). 구분하면 "이 이메일은 가입돼 있다"가 새어 나가
 * 계정 열거 공격의 단서가 된다.
 *
 * ## 로그에 이메일을 남기지 않는다
 *
 * 로깅은 사용자 **ID** 와 상태까지다(`CLAUDE.md` 보안 규칙 — 이메일도 개인정보다).
 * 예외 객체를 그대로 로거에 넘기지 않는다 — 메시지에 무엇이 실릴지 이 지점에서 알 수 없다.
 */
class AuthService(
    private val users: UserRepository,
    private val workspaces: WorkspaceRepository,
    private val passwords: PasswordHasher,
    private val accessTokens: AccessTokens,
    private val transaction: TransactionRunner,
) {
    private val log = LoggerFactory.getLogger(AuthService::class.java)

    /**
     * 계정과 **기본 작업 공간**을 같은 트랜잭션에서 만든다.
     *
     * 나눠 커밋하면 계정만 있고 작업 공간이 없는 사용자가 생겨 첫 업로드가 갈 곳을 잃는다
     * (계약 `paths./auth/signup`).
     *
     * **해시 계산은 트랜잭션 밖에서 한다.** Argon2 한 번이 64MiB 를 수십 밀리초 동안
     * 붙들고 있는데, 그 시간만큼 DB 커넥션을 쥐고 있을 이유가 없다.
     */
    fun signup(
        email: String,
        password: String,
    ): User {
        // 인증이 배선되지 않았으면 여기서 끊는다 — 로그인할 수 없는 계정을 만들지 않는다.
        // 값비싼 Argon2 계산 전이기도 하다.
        accessTokens.ensureConfigured()

        val normalizedEmail = normalizeEmail(email)
        requireValidEmail(normalizedEmail)
        requireValidPassword(password)

        val passwordHash = passwords.hash(password)

        return transaction.inTransaction {
            val created = users.create(normalizedEmail, passwordHash)
            workspaces.createDefault(created.id)
            created
        }
    }

    /**
     * 자격증명을 확인하고 액세스 토큰을 발급한다.
     *
     * **가입 입력 규칙을 다시 적용하지 않는다.** 로그인에서 길이·형식을 422 로 거절하면
     * "이 이메일은 규칙을 통과하는 형태다" 같은 정보가 401 과 다른 코드로 새고, 규칙을
     * 조인 뒤에 가입한 기존 계정이 로그인하지 못한다. 계약도 `/auth/login` 의 422 를
     * **필드 누락**으로만 적었다.
     */
    fun login(
        email: String,
        password: String,
    ): IssuedAccessToken {
        // 자격증명을 확인하기 전에 끊는다. 설정 문제를 401 로 감추면 배포 사고가
        // "사용자가 비밀번호를 틀렸다"로 둔갑하고, 해시 계산도 헛되이 돈다.
        accessTokens.ensureConfigured()

        val stored = users.findByEmail(normalizeEmail(email))
        if (stored == null) {
            // 계정이 없어도 **같은 검증 비용**을 치른다 — 계약 `x-auth.failure_uniformity` 3행.
            // 여기서 바로 던지면 응답 시간이 계정 존재 여부를 알려 준다(실측 42배).
            verifyAgainstDummy(password)
            throw InvalidCredentialsException(INVALID_CREDENTIALS_MESSAGE)
        }

        if (!passwords.verify(password, stored.passwordHash)) {
            throw InvalidCredentialsException(INVALID_CREDENTIALS_MESSAGE)
        }

        // 재해시는 **성공한 뒤에만** 한다. 실패한 로그인에서 재해시하면 오프라인 공격자에게
        // 계산 자원을 태워 준다(I-8 검증 2).
        rehashIfOutdated(stored, password)

        return accessTokens.issue(stored.user.id)
    }

    /**
     * 토큰이 가리키는 사용자를 읽는다.
     *
     * 토큰은 유효한데 계정이 지워진 경우도 **같은 401** 이다 — 삭제 여부가 새면 안 된다
     * (계약 `x-auth.failure_uniformity`, 검증 X-A2).
     *
     * [authenticate] 가 이미 존재를 확인하므로 여기 `null` 갈래는 **오늘 도달하지 않는다**.
     * 그래도 남겨 둔다 — 이 함수는 인터셉터 밖에서도 불릴 수 있고, 존재 확인이 인증
     * 경계에 있다는 사실에 기대어 여기서 `!!` 를 쓰면 두 자리의 결합이 타입에서 사라진다.
     */
    fun readUser(userId: UUID): User =
        users.findById(userId)
            ?: throw InvalidCredentialsException(INVALID_CREDENTIALS_MESSAGE)

    /**
     * 액세스 토큰을 검증하고 사용자 식별자를 돌려준다. 실패는 [InvalidCredentialsException].
     *
     * ## 계정이 지워졌으면 여기서 끊는다
     *
     * 계약 `x-auth.failure_uniformity`(`:299-302`)가 **계정 삭제**를 이메일 부재·비밀번호
     * 불일치·토큰 만료·위조와 **같은 줄에서** 401 사유로 열거한다. 로그인 시점의 삭제는
     * 「이메일 부재」가 이미 덮으므로, 「계정 삭제」를 따로 세운 것은 **토큰이 아직 유효한
     * 요청 시점**을 가리킬 때만 뜻이 있다 — 즉 이 자리다.
     *
     * 서명 검증만 하고 넘기면 폐기된 자격증명이 승인되고, 그 뒤 경로마다 결과가 갈린다:
     * 실측으로 `GET /workspaces` 200 빈 목록 · `POST` 500(사용자 FK 위반) ·
     * `PATCH`·`DELETE` 404 · `GET /auth/me` 401. **네 갈래가 곧 삭제 여부를 알려 주는
     * 상태 코드 채널**이다.
     *
     * ## 확인을 여기 **한 곳**에만 둔다
     *
     * 컨트롤러마다 확인하면 새 엔드포인트가 빠뜨린다 — 그 형태로 이미 한 번 샜다.
     * 인터셉터가 부르는 이 함수가 보호된 모든 요청이 지나는 유일한 목이다
     * (`AuthenticatedEndpoints.PROTECTED_PATH_PATTERNS` ↔ 계약 `security` 대조가 그
     * 「유일함」을 지킨다).
     *
     * ## 결과를 캐시하지 않는다
     *
     * 캐시하면 삭제가 캐시 수명만큼 늦게 반영돼 「탈퇴했는데 아직 쓸 수 있다」가 그대로
     * 남는다. 매 요청 조회 비용은 그 대가로 받아들인 것이다(질의 하나, [UserRepository.exists]).
     *
     * ## 토큰이 든 세 갈래가 **같은 양의 일**을 한다
     *
     * 계약 `x-auth.failure_uniformity` 는 *"토큰 만료·위조·계정 삭제를 모두 같은 401 과 같은
     * 메시지"* 로 묶고 바로 다음 문장에서 *"응답 시간으로도 새지 않게 한다"* 를 적는다.
     * 바이트 축은 같은 예외 하나로 성립하지만 시간 축은 아니었다 — 서명이 깨진 토큰은
     * [AccessTokens.verify] 에서 끊겨 DB 를 아예 타지 않고, 서명이 유효한 삭제 계정 토큰만
     * `exists` 왕복을 돌았다. privacy-gate 실측(게이트 23 기록 ①, 표본 각 101, 교차 순서):
     * 삭제 계정 p50 **1.067ms** / 위조 0.539 / 만료 0.547 — **비 1.95~1.98**.
     * 저장소가 같은 성질에 쓰는 문턱은 1.5 다(로그인 B-1 · 소유권 X-3ⓑ).
     *
     * 그래서 실패 갈래도 **같은 왕복 한 번**을 치른다. 로그인 경로가 계정이 없을 때
     * [verifyAgainstDummy] 로 해시 비용을 치르는 것과 **같은 형태**이고, 값도 같은 성격이다 —
     * 결과를 쓰지 않으며 안전성은 제어 흐름이 진다.
     *
     * **헤더가 아예 없는 요청은 이 균일화의 대상이 아니다.** 그 갈래는 인터셉터가 여기 닿기
     * 전에 끊고, 계약이 **다른 문구**(`no_header`)를 주므로 바이트 축에서 이미 구분된다 —
     * 시간을 맞춰도 얻을 것이 없고, 맞추려면 인증 헤더 없는 트래픽 전부에 DB 부하를 얹게 된다.
     * 균일화의 대상은 계약이 한 줄에 묶은 **토큰이 든 세 갈래**다.
     *
     * **예외를 삼키지 않는다.** DB 가 죽으면 유효 토큰 경로와 실패 갈래가 **똑같이** 500 이
     * 된다. 여기서 조회 실패를 삼켜 401 로 바꾸면 그 대칭이 깨져 「DB 장애 중에는 위조만
     * 401」이라는 새 채널이 생긴다.
     */
    fun authenticate(token: String): UUID {
        val userId =
            try {
                accessTokens.verify(token)
            } catch (failure: InvalidCredentialsException) {
                // 만료·위조·클레임 누락도 삭제 계정과 같은 DB 왕복 하나를 돈다.
                // 결과는 쓰지 않는다 — 이 갈래는 무조건 실패로 끝난다.
                users.exists(ABSENT_USER_PROBE_ID)
                throw failure
            }
        // 사유를 가르지 않는다 — 위조 토큰과 삭제 계정이 같은 예외·같은 문구로 나간다.
        if (!users.exists(userId)) {
            throw InvalidCredentialsException(INVALID_CREDENTIALS_MESSAGE)
        }
        return userId
    }

    /**
     * 계정이 없는 경로가 치르는 해시 비용.
     *
     * **결과를 쓰지 않는다.** 호출자가 무조건 실패를 던지므로 `true` 가 나와도 인증이
     * 되지 않는다 — 이 경로의 안전성은 검증 결과가 아니라 **제어 흐름**이 진다.
     * **재해시를 걸지 않는 것**도 같은 이유로 성립한다: [rehashIfOutdated] 는 검증 성공
     * 뒤에만 불리고 이 경로는 그 앞에서 끊긴다.
     *
     * 더미가 어떤 비밀번호와도 일치하지 않는다는 성질은 [PasswordHasher.dummyHash] 가
     * 따로 요구한다(원문이 난수라 성립한다). 여기서 그 성질에 기대지 않는다 — 기대면
     * 두 분기를 합치는 변경이 조용히 안전해 보인다.
     */
    private fun verifyAgainstDummy(rawPassword: String) {
        passwords.verify(rawPassword, passwords.dummyHash())
    }

    /**
     * 저장된 해시의 파라미터가 현행 정책과 다르면 올린다 — **best-effort**.
     *
     * 실패해도 로그인을 막지 않는다(I-8 검증 3). 여기서 예외가 새면 파라미터를 올린 날
     * 전 사용자가 로그인하지 못한다.
     */
    private fun rehashIfOutdated(
        stored: StoredUser,
        rawPassword: String,
    ) {
        try {
            if (!passwords.needsRehash(stored.passwordHash)) {
                return
            }
            users.updatePasswordHash(stored.user.id, passwords.hash(rawPassword))
            log.info("비밀번호 해시를 현행 파라미터로 올렸다: userId={}", stored.user.id)
        } catch (
            @Suppress("TooGenericExceptionCaught") failure: RuntimeException,
        ) {
            // 예외 객체를 넘기지 않는다 — 메시지·원인 체인에 무엇이 실릴지 알 수 없다.
            // 남기는 것은 사용자 ID 와 예외 **타입 이름**뿐이다.
            log.warn(
                "비밀번호 재해시에 실패했다(로그인은 계속한다): userId={} 예외={}",
                stored.user.id,
                failure::class.java.simpleName,
            )
        }
    }

    private companion object {
        /**
         * 인증 실패 문구. 계약 `components/responses/Unauthorized` 의 `invalid_token` 예시와
         * 같은 값이며, 로그인 실패·토큰 무효·계정 삭제가 **전부 이 하나**를 쓴다.
         */
        const val INVALID_CREDENTIALS_MESSAGE = "이메일 또는 비밀번호가 올바르지 않습니다"

        /**
         * 토큰 검증이 실패한 갈래가 비용을 맞추려고 조회하는 식별자.
         *
         * **절대 존재하지 않는 값**이어야 한다 — 계정 식별자는 `UUID.randomUUID()`(버전 4)로
         * 만들어지므로 nil UUID 는 나올 수 없다. 검증에 실패한 토큰의 `sub` 를 대신 쓰지
         * 않는 이유는 둘이다: 서명이 확인되지 않은 입력을 파싱해 질의 인자로 쓰게 되고,
         * 「그 식별자가 있는가」를 공격자가 고른 값으로 물어보는 통로가 열린다.
         */
        val ABSENT_USER_PROBE_ID: UUID = UUID(0L, 0L)
    }
}
