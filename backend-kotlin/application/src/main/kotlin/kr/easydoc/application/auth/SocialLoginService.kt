package kr.easydoc.application.auth

import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.EmailAlreadyRegisteredException
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.exceptions.InvalidOAuthStateException
import java.time.Duration
import java.util.UUID

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
 * 소셜 로그인(Authorization Code 흐름) 유스케이스 — 시작 · 콜백 · (인증된 계정에) 명시적 연결.
 *
 * `AuthService` 와 나란히 두는 별도 클래스다. 이메일/비밀번호 로그인과 겹치는 것은
 * "액세스 토큰을 발급한다"뿐이고, 나머지(제공자 왕복·state 검증·계정 연결 규칙)는
 * 이 클래스만의 책임이라 한 서비스에 계속 붙이면 god service가 된다(CLAUDE.md 설계 규칙).
 * `linkStart`/`linkCallback`(backlog §1.4 명시적 연결)이 같은 이유로 여기 산다 — 상태
 * 없는 신원 왕복은 로그인이든 연결이든 한 곳이 진다.
 *
 * `TooManyFunctions` 를 억제한다 — 늘어난 것은 "로그인"과 "연결" 두 흐름이 각자 시작·
 * 콜백·state 소비를 갖기 때문이지 책임이 여럿으로 갈라진 것이 아니다(`GoogleSocialLoginProvider`
 * 와 같은 근거 — 실패 갈래마다 이름 붙은 작은 함수가 늘었을 뿐이다).
 */
@Suppress("TooManyFunctions")
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
     *     않는다 — 명시적 연결은 [linkStart]/[linkCallback], 로그인한 뒤 계정 설정에서
     *     잇는 별도 흐름이다).
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
        val nonce = consumeLoginState(providerId, state, redirectUri)
        val identity = provider.exchange(code, redirectUri, nonce)

        val existing = repositories.identities.findByProviderIdentity(providerId, identity.providerUserId)
        if (existing != null) {
            return accessTokens.issue(existing.userId)
        }

        val normalizedEmail = requireVerifiedEmail(identity)
        requireEmailNotAlreadyLinked(normalizedEmail)

        return transaction.inTransaction {
            // `requireVerifiedEmail` 이 이미 `identity.emailVerified` 를 참으로 확정했다 —
            // 여기서는 그 사실을 값으로 전달할 뿐이다.
            val user = repositories.users.createWithoutPassword(normalizedEmail, emailVerified = identity.emailVerified)
            repositories.workspaces.createDefault(user.id)
            repositories.identities.link(user.id, providerId, identity.providerUserId, normalizedEmail, true)
            accessTokens.issue(user.id)
        }
    }

    /**
     * 이미 로그인한 사용자([userId], Bearer 토큰의 사용자)가 그 계정에 소셜 신원을 잇는
     * 흐름의 시작 — backlog §1.4 명시적 연결(P0-1의 다음 조각). [start] 와 갈리는 것은
     * state 발급뿐이다(`states.issue` 에 [userId] 를 싣는다) — 허용 목록·제공자 설정
     * 판정은 같은 규칙을 그대로 쓴다.
     */
    fun linkStart(
        userId: UUID,
        providerId: SocialLoginProviderId,
        redirectUri: String,
    ): OAuthStart {
        val provider = providerOf(providerId)
        requireAllowedRedirectUri(provider, redirectUri)

        val challenge = states.issue(providerId, redirectUri, stateTtl, userId)
        val url = provider.authorizationUrl(challenge.state, challenge.nonce, redirectUri)
        return OAuthStart(url, challenge.state)
    }

    /**
     * 인가 코드를 [userId] 계정에 있는 소셜 신원으로 잇는다. 이메일 검증
     * 여부([SocialIdentity.emailVerified])나 계정 이메일과의 일치는 요구하지 않는다 —
     * 호출자가 이미 인증된 상태고, 코드 교환 자체가 신원 소유를 증명한다. 검증된 이메일이
     * 계정 이메일과 같고 계정이 아직 미인증이면 부수 효과로 `email_verified_at`을 채운다.
     *
     * 갈래:
     *  1. 같은 신원이 이미 **이 사용자**에 연결돼 있다 → 멱등, 아무것도 하지 않는다(204).
     *  2. 같은 신원이 **다른 사용자**에 연결돼 있다 → 409.
     *  3. 이 사용자가 이 제공자에 **다른** 신원을 이미 연결했다 → 409(제공자당 하나).
     *  4. 완전히 새로운 연결 → 신원을 만든다.
     */
    fun linkCallback(
        userId: UUID,
        providerId: SocialLoginProviderId,
        code: String,
        state: String,
        redirectUri: String,
    ) {
        val provider = providerOf(providerId)
        val nonce = consumeLinkState(providerId, state, redirectUri, userId)
        val identity = provider.exchange(code, redirectUri, nonce)

        val existing = repositories.identities.findByProviderIdentity(providerId, identity.providerUserId)
        if (existing != null) {
            if (existing.userId == userId) {
                return
            }
            throw ConflictException(identityAlreadyLinkedToOtherUserMessage(providerId))
        }
        if (repositories.identities.findByUserAndProvider(userId, providerId) != null) {
            throw ConflictException(providerAlreadyLinkedMessage(providerId))
        }

        val normalizedEmail = identity.email?.let(::normalizeEmail)
        repositories.identities.link(
            userId,
            providerId,
            identity.providerUserId,
            normalizedEmail,
            identity.emailVerified,
        )
        markEmailVerifiedIfMatching(userId, normalizedEmail, identity.emailVerified)
    }

    /** `readMe.identities` — 이 사용자가 연결한 제공자 목록. */
    fun identitiesOf(userId: UUID): List<SocialLoginProviderId> =
        repositories.identities.findAllByUser(userId).map { it.provider }

    /**
     * 연결 부수 효과: 제공자가 준 이메일이 **검증됐고** 계정 이메일과 같은데 계정이 아직
     * 미인증이면 그 자리에서 인증 완료로 표시한다(위임 지침의 nice-to-have). 대소문자·
     * 좌우 공백 차이는 [normalizeEmail] 로 흡수한다 — `users.email` 도 항상 정규화된
     * 값이다(`CredentialRules`).
     */
    private fun markEmailVerifiedIfMatching(
        userId: UUID,
        normalizedIdentityEmail: String?,
        identityEmailVerified: Boolean,
    ) {
        if (!identityEmailVerified || normalizedIdentityEmail == null) {
            return
        }
        val account = repositories.users.findById(userId) ?: return
        if (account.emailVerifiedAt == null && normalizeEmail(account.email) == normalizedIdentityEmail) {
            repositories.users.markEmailVerified(userId)
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

    /** 로그인 콜백 전용 — 연결 state(`boundUserId != null`)가 오면 같은 400 으로 거절한다. */
    private fun consumeLoginState(
        providerId: SocialLoginProviderId,
        state: String,
        redirectUri: String,
    ): String {
        val consumed =
            states.consume(providerId, state, redirectUri) ?: throw InvalidOAuthStateException(INVALID_STATE_MESSAGE)
        if (consumed.boundUserId != null) {
            throw InvalidOAuthStateException(INVALID_STATE_MESSAGE)
        }
        return consumed.nonce
    }

    /**
     * 연결 콜백 전용 — 로그인 state(`boundUserId == null`)이거나 **다른** 사용자에게
     * 발급된 state 면 같은 400 으로 거절한다(사유를 구분하지 않는다 — `x-social-login.state`
     * 와 같은 원칙).
     */
    private fun consumeLinkState(
        providerId: SocialLoginProviderId,
        state: String,
        redirectUri: String,
        callerUserId: UUID,
    ): String {
        val consumed =
            states.consume(providerId, state, redirectUri) ?: throw InvalidOAuthStateException(INVALID_STATE_MESSAGE)
        if (consumed.boundUserId != callerUserId) {
            throw InvalidOAuthStateException(INVALID_STATE_MESSAGE)
        }
        return consumed.nonce
    }

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

        /** 계약 `oauthLinkCallback` `409` 예시 — 그 신원이 이미 **다른** 사용자에 연결돼 있다. */
        fun identityAlreadyLinkedToOtherUserMessage(providerId: SocialLoginProviderId): String =
            when (providerId) {
                SocialLoginProviderId.GOOGLE -> "이 구글 계정은 이미 다른 계정에 연결되어 있습니다"
            }

        /** 계약 `oauthLinkCallback` `409` 예시 — 이 사용자가 이 제공자에 이미 다른 신원을 연결했다. */
        fun providerAlreadyLinkedMessage(providerId: SocialLoginProviderId): String =
            when (providerId) {
                SocialLoginProviderId.GOOGLE -> "이미 다른 구글 계정이 이 계정에 연결되어 있습니다"
            }
    }
}
