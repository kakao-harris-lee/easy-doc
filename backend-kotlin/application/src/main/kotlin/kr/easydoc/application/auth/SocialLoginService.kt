package kr.easydoc.application.auth

import kr.easydoc.core.exceptions.EmailAlreadyRegisteredException
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.exceptions.InvalidOAuthStateException
import java.time.Duration

/** `oauthStart` 응답 — 계약 `OAuthStartResponse`. */
data class OAuthStart(
    val authorizationUrl: String,
    val state: String,
)

/**
 * 소셜 로그인이 함께 쓰는 저장소 세 개를 한 자리로 묶는다 — `DocumentStorage`
 * (`application/document`)가 업로드 저장소 넷을 묶는 것과 같은 이유다: 따로 넘기면
 * `SocialLoginService` 생성자 매개변수가 detekt `LongParameterList` 상한을 넘는다.
 */
class SocialLoginRepositories(
    val users: UserRepository,
    val identities: UserIdentityRepository,
    val workspaces: WorkspaceRepository,
)

/**
 * 소셜 로그인(Authorization Code 흐름) 유스케이스 — 시작 · 콜백.
 *
 * `AuthService` 와 나란히 두는 별도 클래스다. 이메일/비밀번호 로그인과 겹치는 것은
 * "액세스 토큰을 발급한다"뿐이고, 나머지(제공자 왕복·state 검증·계정 연결 규칙)는
 * 이 클래스만의 책임이라 한 서비스에 계속 붙이면 god service가 된다(CLAUDE.md 설계 규칙).
 */
class SocialLoginService(
    private val providers: Map<SocialLoginProviderId, SocialLoginProvider>,
    private val states: OAuthStateStore,
    private val repositories: SocialLoginRepositories,
    private val accessTokens: AccessTokens,
    private val transaction: TransactionRunner,
    private val stateTtl: Duration,
) {
    /**
     * 제공자 인가 URL과 CSRF 방지 `state` 를 만든다.
     *
     * [providerId] 는 이미 해석된 값이다 — "이 문자열이 아는 provider 인가"는 API 경계의
     * `SocialLoginProviderIdConverter` 가 스키마 층에서 먼저 거른다(계약 enum 밖 값은
     * 422 배열, `ValueSlotInvariantReachTest`). 이 서비스가 판정하는 것은 "아는 provider지만
     * 이 배포에 설정됐는가"(→ [providerOf])처럼 **도메인** 규칙뿐이다.
     */
    fun start(
        providerId: SocialLoginProviderId,
        redirectUri: String,
    ): OAuthStart {
        val provider = providerOf(providerId)
        requireAllowedRedirectUri(provider, redirectUri)

        val challenge = states.issue(providerId, redirectUri, stateTtl)
        val url = provider.authorizationUrl(challenge.state, challenge.nonce, redirectUri)
        return OAuthStart(url, challenge.state)
    }

    /**
     * 인가 코드를 액세스 토큰으로 바꾼다.
     *
     * 갈래 셋:
     *  1. 이미 연결된 신원 → 로그인.
     *  2. 신원은 새롭지만 같은 **검증된** 이메일의 계정이 이미 있다 → 409(자동 연결하지
     *     않는다 — 연결 흐름은 다음 작업 단위).
     *  3. 완전히 새로운 사용자 → 계정 + 기본 작업 공간 + 신원 연결을 한 트랜잭션에서 만든다.
     */
    fun callback(
        providerId: SocialLoginProviderId,
        code: String,
        state: String,
        redirectUri: String,
    ): IssuedAccessToken {
        // 자격증명(신원)을 확인하기 전에 끊는다 — `AuthService.login` 과 같은 이유
        // (설정 문제를 다른 실패로 둔갑시키지 않는다, 제공자 왕복도 헛되이 돌지 않는다).
        accessTokens.ensureConfigured()

        val provider = providerOf(providerId)
        val nonce = consumeState(providerId, state, redirectUri)
        val identity = provider.exchange(code, redirectUri, nonce)

        val existing = repositories.identities.findByProviderIdentity(providerId, identity.providerUserId)
        if (existing != null) {
            return accessTokens.issue(existing.userId)
        }

        val normalizedEmail = requireVerifiedEmail(identity)
        requireEmailNotAlreadyLinked(normalizedEmail)

        return transaction.inTransaction {
            val user = repositories.users.createWithoutPassword(normalizedEmail)
            repositories.workspaces.createDefault(user.id)
            repositories.identities.link(user.id, providerId, identity.providerUserId, normalizedEmail, true)
            accessTokens.issue(user.id)
        }
    }

    private fun providerOf(providerId: SocialLoginProviderId): SocialLoginProvider =
        providers[providerId] ?: throw InvalidInputException(providerNotConfiguredMessage(providerId))

    private fun requireAllowedRedirectUri(
        provider: SocialLoginProvider,
        redirectUri: String,
    ) {
        if (!provider.supportsRedirectUri(redirectUri)) {
            throw InvalidInputException(REDIRECT_URI_NOT_ALLOWED_MESSAGE)
        }
    }

    private fun consumeState(
        providerId: SocialLoginProviderId,
        state: String,
        redirectUri: String,
    ): String =
        states.consume(providerId, state, redirectUri) ?: throw InvalidOAuthStateException(INVALID_STATE_MESSAGE)

    /** 이메일이 있고 검증됐는지 본다. 통과하면 정규화된 이메일을 돌려준다. */
    private fun requireVerifiedEmail(identity: SocialIdentity): String {
        val email = identity.email
        if (email.isNullOrBlank() || !identity.emailVerified) {
            throw InvalidInputException(EMAIL_REQUIRED_MESSAGE)
        }
        return normalizeEmail(email)
    }

    /**
     * 트랜잭션 **밖**에서 먼저 본다 — 흔한 갈래(이미 가입된 이메일)를 커넥션 없이 끝낸다.
     * 동시 가입 경쟁은 DB 유일성 제약(`ix_users_email`)이 마지막 방어선이다
     * (`JdbcUserRepository.create*` 가 `DuplicateKeyException` 을 잡아 같은 예외로 옮긴다).
     */
    private fun requireEmailNotAlreadyLinked(normalizedEmail: String) {
        if (repositories.users.findByEmail(normalizedEmail) != null) {
            throw EmailAlreadyRegisteredException(EMAIL_ALREADY_LINKED_MESSAGE)
        }
    }

    companion object {
        /** 계약 `POST /auth/oauth/{provider}/callback` `400` 예시 — 사유를 구분하지 않는다. */
        const val INVALID_STATE_MESSAGE = "요청이 만료되었거나 이미 사용되었습니다"

        /** 계약 `422` 예시 — 제공자가 이메일을 안 주거나 검증되지 않았다. */
        const val EMAIL_REQUIRED_MESSAGE = "이메일 정보를 확인할 수 없습니다"

        /** 계약 `409` 예시 — 같은 검증된 이메일의 계정이 이미 있다(자동 연결 안 함). */
        const val EMAIL_ALREADY_LINKED_MESSAGE =
            "이미 같은 이메일로 가입된 계정이 있습니다. 이메일로 로그인한 뒤 연결해 주세요."

        /** 계약 `422` 예시 — 허용 목록 밖 `redirect_uri`. */
        const val REDIRECT_URI_NOT_ALLOWED_MESSAGE = "허용되지 않은 redirect_uri 입니다"

        /** 키가 설정되지 않아 이 제공자가 등록되지 않았다 — google 전용 문구(사용자 요청). */
        fun providerNotConfiguredMessage(providerId: SocialLoginProviderId): String =
            when (providerId) {
                SocialLoginProviderId.GOOGLE -> "구글 로그인이 설정되지 않았습니다"
            }
    }
}
