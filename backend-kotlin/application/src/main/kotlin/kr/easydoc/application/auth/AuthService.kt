package kr.easydoc.application.auth

import kr.easydoc.core.exceptions.InvalidCredentialsException
import kr.easydoc.core.user.StoredUser
import kr.easydoc.core.user.User
import kr.easydoc.core.workspace.DEFAULT_WORKSPACE_NAME
import org.slf4j.LoggerFactory
import java.util.UUID

/** 인증 유스케이스 — 가입 · 로그인 · 내 정보. */
class AuthService(
    private val users: UserRepository,
    private val workspaces: WorkspaceRepository,
    private val passwords: PasswordHasher,
    private val accessTokens: AccessTokens,
    private val transaction: TransactionRunner,
) {
    private val log = LoggerFactory.getLogger(AuthService::class.java)

    /** 계정과 **기본 작업 공간**을 같은 트랜잭션에서 만든다. */
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

    /** 자격증명을 확인하고 액세스 토큰을 발급한다. */
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

    /** 토큰이 가리키는 사용자를 읽는다. */
    fun readUser(userId: UUID): User =
        users.findById(userId)
            ?: throw InvalidCredentialsException(INVALID_CREDENTIALS_MESSAGE)

    /** 액세스 토큰을 검증하고 사용자 식별자를 돌려준다. 실패는 [InvalidCredentialsException]. */
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

    /** 계정이 없는 경로가 치르는 해시 비용. */
    private fun verifyAgainstDummy(rawPassword: String) {
        passwords.verify(rawPassword, passwords.dummyHash())
    }

    /** 저장된 해시의 파라미터가 현행 정책과 다르면 올린다 — **best-effort**. */
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

        /** 토큰 검증이 실패한 갈래가 비용을 맞추려고 조회하는 식별자. */
        val ABSENT_USER_PROBE_ID: UUID = UUID(0L, 0L)
    }
}
