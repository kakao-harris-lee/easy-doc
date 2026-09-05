package kr.easydoc.application.auth

import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.EmailAlreadyRegisteredException
import kr.easydoc.core.exceptions.ExternalServiceUnavailableException
import kr.easydoc.core.exceptions.InvalidCredentialsException
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.exceptions.InvalidOAuthStateException
import kr.easydoc.core.user.PasswordHash
import kr.easydoc.core.user.StoredUser
import kr.easydoc.core.user.User
import kr.easydoc.core.workspace.Workspace
import kr.easydoc.core.workspace.WorkspaceListing
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** 소셜 로그인 유스케이스의 분기를 잰다 — Spring 도 DB 도 실제 Google 도 없이. */
class SocialLoginServiceTest {
    @Test
    @DisplayName("새 신원은 계정과 기본 작업 공간을 같은 트랜잭션에서 만든다")
    fun `새 신원이 계정을 만든다`() {
        val world = SocialWorld()
        world.provider.nextIdentity = SocialIdentity("google-sub-1", "New@Example.Test", emailVerified = true)

        val start = world.service.start(SocialLoginProviderId.GOOGLE, REDIRECT_URI)
        val token = world.service.callback(SocialLoginProviderId.GOOGLE, "auth-code", start.state, REDIRECT_URI)

        assertThat(token.token).isNotBlank()
        assertThat(world.workspaces.createdFor).hasSize(1)
        assertThat(
            world.identities.linked
                .single()
                .providerUserId,
        ).isEqualTo("google-sub-1")
        assertThat(world.users.saved.keys).containsExactly("new@example.test")
        // 제공자가 이미 검증한 이메일이다 — 우리 쪽 이메일 인증 코드가 또 필요하지 않다
        // (backlog §1.4 P0-3, `UserRepository.createWithoutPassword` KDoc).
        assertThat(
            world.users.saved
                .getValue("new@example.test")
                .user.emailVerifiedAt,
        ).withFailMessage("구글 최초 가입 계정이 생성 시점에 인증 완료로 표시되지 않았다")
            .isNotNull()
        // 이미 검증된 이메일이라 이메일 인증 코드를 또 발급하지 않는다 — 네이버(미검증)만
        // 예외로 발급한다(클래스 KDoc, `callback` KDoc).
        assertThat(world.emailVerification.issuedFor).isEmpty()
    }

    @Test
    @DisplayName("이미 연결된 신원은 새 계정을 만들지 않고 로그인한다")
    fun `기존 신원은 로그인이다`() {
        val world = SocialWorld()
        val existingUser = User(UUID.randomUUID(), "linked@example.test", Instant.EPOCH)
        world.identities.seed(existingUser.id, SocialLoginProviderId.GOOGLE, "google-sub-2")
        world.provider.nextIdentity = SocialIdentity("google-sub-2", "linked@example.test", emailVerified = true)

        val start = world.service.start(SocialLoginProviderId.GOOGLE, REDIRECT_URI)
        val token = world.service.callback(SocialLoginProviderId.GOOGLE, "auth-code", start.state, REDIRECT_URI)

        assertThat(token.token).isEqualTo("token:${existingUser.id}")
        assertThat(world.workspaces.createdFor).isEmpty()
        assertThat(world.identities.linked).isEmpty()
    }

    @Test
    @DisplayName("같은 검증된 이메일의 계정이 이미 있으면 409 — 자동 연결하지 않는다")
    fun `이메일이 겹치면 409다`() {
        val world = SocialWorld()
        world.users.saved["taken@example.test"] =
            StoredUser(User(UUID.randomUUID(), "taken@example.test", Instant.EPOCH), PasswordHash("hashed:x"))
        world.provider.nextIdentity = SocialIdentity("google-sub-3", "taken@example.test", emailVerified = true)

        val start = world.service.start(SocialLoginProviderId.GOOGLE, REDIRECT_URI)

        assertThatThrownBy {
            world.service.callback(
                SocialLoginProviderId.GOOGLE,
                "auth-code",
                start.state,
                REDIRECT_URI,
            )
        }.isInstanceOf(EmailAlreadyRegisteredException::class.java)
            .hasMessage(SocialLoginService.EMAIL_ALREADY_LINKED_MESSAGE)
        assertThat(world.identities.linked).isEmpty()
    }

    @Test
    @DisplayName("이메일이 없으면 422")
    fun `이메일 없으면 422다`() {
        val world = SocialWorld()
        world.provider.nextIdentity = SocialIdentity("google-sub-4", email = null, emailVerified = false)

        val start = world.service.start(SocialLoginProviderId.GOOGLE, REDIRECT_URI)

        assertThatThrownBy {
            world.service.callback(
                SocialLoginProviderId.GOOGLE,
                "auth-code",
                start.state,
                REDIRECT_URI,
            )
        }.isInstanceOf(InvalidInputException::class.java)
            .hasMessage(SocialLoginService.EMAIL_REQUIRED_MESSAGE)
    }

    @Test
    @DisplayName("이메일이 검증되지 않았으면 422 — 값이 있어도 마찬가지다")
    fun `이메일 미검증도 422다`() {
        val world = SocialWorld()
        world.provider.nextIdentity = SocialIdentity("google-sub-5", "unverified@example.test", emailVerified = false)

        val start = world.service.start(SocialLoginProviderId.GOOGLE, REDIRECT_URI)

        assertThatThrownBy {
            world.service.callback(
                SocialLoginProviderId.GOOGLE,
                "auth-code",
                start.state,
                REDIRECT_URI,
            )
        }.isInstanceOf(InvalidInputException::class.java)
            .hasMessage(SocialLoginService.EMAIL_REQUIRED_MESSAGE)
    }

    @Test
    @DisplayName("state 가 없거나 만료·재사용이면 400 — 사유를 구분하지 않는다")
    fun `무효한 state 는 400이다`() {
        val world = SocialWorld()

        assertThatThrownBy {
            world.service.callback(
                SocialLoginProviderId.GOOGLE,
                "auth-code",
                "never-issued",
                REDIRECT_URI,
            )
        }.isInstanceOf(InvalidOAuthStateException::class.java)
            .hasMessage(SocialLoginService.INVALID_STATE_MESSAGE)
    }

    @Test
    @DisplayName("state 는 한 번만 쓸 수 있다")
    fun `state 는 단발이다`() {
        val world = SocialWorld()
        world.provider.nextIdentity = SocialIdentity("google-sub-6", "once@example.test", emailVerified = true)
        val start = world.service.start(SocialLoginProviderId.GOOGLE, REDIRECT_URI)

        world.service.callback(SocialLoginProviderId.GOOGLE, "auth-code", start.state, REDIRECT_URI)

        assertThatThrownBy {
            world.service.callback(
                SocialLoginProviderId.GOOGLE,
                "auth-code",
                start.state,
                REDIRECT_URI,
            )
        }.isInstanceOf(InvalidOAuthStateException::class.java)
    }

    @Test
    @DisplayName("redirect_uri 가 발급 시점과 다르면 400 — state 소비가 그 자리에서 막힌다")
    fun `redirect_uri 불일치는 400이다`() {
        val world = SocialWorld()
        val start = world.service.start(SocialLoginProviderId.GOOGLE, REDIRECT_URI)

        assertThatThrownBy {
            world.service.callback(
                SocialLoginProviderId.GOOGLE,
                "auth-code",
                start.state,
                "https://other.example.test/callback",
            )
        }.isInstanceOf(InvalidOAuthStateException::class.java)
    }

    @Test
    @DisplayName("제공자가 코드를 거절하면 401 — 로그인 실패와 같은 문구다")
    fun `코드 거절은 401이다`() {
        val world = SocialWorld()
        world.provider.exchangeFailure = InvalidCredentialsException("이메일 또는 비밀번호가 올바르지 않습니다")
        val start = world.service.start(SocialLoginProviderId.GOOGLE, REDIRECT_URI)

        assertThatThrownBy {
            world.service.callback(
                SocialLoginProviderId.GOOGLE,
                "auth-code",
                start.state,
                REDIRECT_URI,
            )
        }.isInstanceOf(InvalidCredentialsException::class.java)
    }

    @Test
    @DisplayName("제공자에 닿지 못하면 502다")
    fun `제공자 불통은 502다`() {
        val world = SocialWorld()
        world.provider.exchangeFailure = ExternalServiceUnavailableException("요청을 처리하지 못했습니다")
        val start = world.service.start(SocialLoginProviderId.GOOGLE, REDIRECT_URI)

        assertThatThrownBy {
            world.service.callback(
                SocialLoginProviderId.GOOGLE,
                "auth-code",
                start.state,
                REDIRECT_URI,
            )
        }.isInstanceOf(ExternalServiceUnavailableException::class.java)
    }

    // 「지원하지 않는 provider」 케이스는 이 서비스 밖이다 — `SocialLoginProviderId` 가
    // `Converter` 로만 만들어지므로(이 파일이 그 타입을 직접 쓴다), 이 테스트가 부를 수
    // 있는 provider 인자는 애초에 컴파일 시점에 google 하나뿐이다. 그 경계는
    // `kr.easydoc.api.auth.SocialLoginProviderIdConverter` 와 `ValueSlotInvariantReachTest`
    // (스키마 층 422 배열)가 잰다.

    @Test
    @DisplayName("키가 설정되지 않은 제공자는 422 — 구글 전용 문구다")
    fun `설정되지 않은 제공자는 422다`() {
        val world = SocialWorld(googleConfigured = false)

        assertThatThrownBy { world.service.start(SocialLoginProviderId.GOOGLE, REDIRECT_URI) }
            .isInstanceOf(InvalidInputException::class.java)
            .hasMessage("구글 로그인이 설정되지 않았습니다")
    }

    @Test
    @DisplayName("허용 목록 밖 redirect_uri 는 422다")
    fun `허용 목록 밖 redirect_uri 는 422다`() {
        val world = SocialWorld()

        assertThatThrownBy { world.service.start(SocialLoginProviderId.GOOGLE, "https://evil.example.test/callback") }
            .isInstanceOf(InvalidInputException::class.java)
            .hasMessage(SocialLoginService.REDIRECT_URI_NOT_ALLOWED_MESSAGE)
    }

    @Test
    @DisplayName("인증이 배선되지 않으면 콜백이 제공자를 부르기 전에 끊긴다")
    fun `설정 미비는 제공자 호출 전에 끊는다`() {
        val world = SocialWorld(tokensConfigured = false)

        assertThatThrownBy {
            world.service.callback(
                SocialLoginProviderId.GOOGLE,
                "auth-code",
                "any-state",
                REDIRECT_URI,
            )
        }.isInstanceOf(ConfigurationException::class.java)

        assertThat(world.provider.exchangeCallCount).isZero()
    }

    // ------------------------------------------------------------------ 명시적 연결(linkStart/linkCallback)

    @Test
    @DisplayName("연결 성공 — 로그인한 계정에 새 구글 신원이 연결된다")
    fun `연결이 성공한다`() {
        val world = SocialWorld()
        val user = world.users.seedPasswordAccount("owner@example.test")
        world.provider.nextIdentity = SocialIdentity("google-link-1", "owner-google@example.test", emailVerified = true)

        val start = world.service.linkStart(user.id, SocialLoginProviderId.GOOGLE, REDIRECT_URI)
        world.service.linkCallback(user.id, SocialLoginProviderId.GOOGLE, "auth-code", start.state, REDIRECT_URI)

        val linked = world.identities.linked.single()
        assertThat(linked.userId).isEqualTo(user.id)
        assertThat(linked.providerUserId).isEqualTo("google-link-1")
    }

    @Test
    @DisplayName("같은 신원을 같은 사용자에 다시 연결하면 멱등이다 — 새 신원을 만들지 않는다")
    fun `같은 사용자의 재연결은 멱등이다`() {
        val world = SocialWorld()
        val user = world.users.seedPasswordAccount("owner2@example.test")
        world.provider.nextIdentity =
            SocialIdentity("google-link-2", "owner2-google@example.test", emailVerified = true)
        val firstState = world.service.linkStart(user.id, SocialLoginProviderId.GOOGLE, REDIRECT_URI).state
        world.service.linkCallback(user.id, SocialLoginProviderId.GOOGLE, "auth-code", firstState, REDIRECT_URI)

        val secondState = world.service.linkStart(user.id, SocialLoginProviderId.GOOGLE, REDIRECT_URI).state
        world.service.linkCallback(user.id, SocialLoginProviderId.GOOGLE, "auth-code", secondState, REDIRECT_URI)

        assertThat(world.identities.linked).hasSize(1)
    }

    @Test
    @DisplayName("다른 사용자가 이미 쓰는 신원을 연결하려 하면 409다")
    fun `다른 사용자의 신원을 연결하려 하면 409다`() {
        val world = SocialWorld()
        val owner = world.users.seedPasswordAccount("first-owner@example.test")
        val other = world.users.seedPasswordAccount("second-owner@example.test")
        world.provider.nextIdentity =
            SocialIdentity("google-link-3", "shared-identity@example.test", emailVerified = true)
        val ownerState = world.service.linkStart(owner.id, SocialLoginProviderId.GOOGLE, REDIRECT_URI).state
        world.service.linkCallback(owner.id, SocialLoginProviderId.GOOGLE, "auth-code", ownerState, REDIRECT_URI)

        val otherState = world.service.linkStart(other.id, SocialLoginProviderId.GOOGLE, REDIRECT_URI).state

        assertThatThrownBy {
            world.service.linkCallback(other.id, SocialLoginProviderId.GOOGLE, "auth-code", otherState, REDIRECT_URI)
        }.isInstanceOf(ConflictException::class.java)
            .hasMessage(SocialLoginService.identityAlreadyLinkedToOtherUserMessage(SocialLoginProviderId.GOOGLE))
    }

    @Test
    @DisplayName("한 계정에 같은 제공자의 두 번째 신원을 연결하려 하면 409다")
    fun `같은 제공자의 두 번째 신원은 409다`() {
        val world = SocialWorld()
        val user = world.users.seedPasswordAccount("two-identities@example.test")
        world.provider.nextIdentity = SocialIdentity("google-link-4a", "first@example.test", emailVerified = true)
        val firstState = world.service.linkStart(user.id, SocialLoginProviderId.GOOGLE, REDIRECT_URI).state
        world.service.linkCallback(user.id, SocialLoginProviderId.GOOGLE, "auth-code", firstState, REDIRECT_URI)

        world.provider.nextIdentity = SocialIdentity("google-link-4b", "second@example.test", emailVerified = true)
        val secondState = world.service.linkStart(user.id, SocialLoginProviderId.GOOGLE, REDIRECT_URI).state

        assertThatThrownBy {
            world.service.linkCallback(user.id, SocialLoginProviderId.GOOGLE, "auth-code", secondState, REDIRECT_URI)
        }.isInstanceOf(ConflictException::class.java)
            .hasMessage(SocialLoginService.providerAlreadyLinkedMessage(SocialLoginProviderId.GOOGLE))
    }

    @Test
    @DisplayName("로그인 state 를 연결 콜백에 쓰면 400이다")
    fun `로그인 state 는 연결 콜백에서 거절된다`() {
        val world = SocialWorld()
        val user = world.users.seedPasswordAccount("login-state-on-link@example.test")
        val loginState = world.service.start(SocialLoginProviderId.GOOGLE, REDIRECT_URI).state

        assertThatThrownBy {
            world.service.linkCallback(user.id, SocialLoginProviderId.GOOGLE, "auth-code", loginState, REDIRECT_URI)
        }.isInstanceOf(InvalidOAuthStateException::class.java)
            .hasMessage(SocialLoginService.INVALID_STATE_MESSAGE)
    }

    @Test
    @DisplayName("연결 state 를 로그인 콜백에 쓰면 400이다")
    fun `연결 state 는 로그인 콜백에서 거절된다`() {
        val world = SocialWorld()
        val user = world.users.seedPasswordAccount("link-state-on-login@example.test")
        val linkState = world.service.linkStart(user.id, SocialLoginProviderId.GOOGLE, REDIRECT_URI).state

        assertThatThrownBy {
            world.service.callback(SocialLoginProviderId.GOOGLE, "auth-code", linkState, REDIRECT_URI)
        }.isInstanceOf(InvalidOAuthStateException::class.java)
            .hasMessage(SocialLoginService.INVALID_STATE_MESSAGE)
    }

    @Test
    @DisplayName("다른 사용자에게 발급된 연결 state 는 400이다")
    fun `다른 사용자의 연결 state 는 거절된다`() {
        val world = SocialWorld()
        val issuer = world.users.seedPasswordAccount("issuer@example.test")
        val impostor = world.users.seedPasswordAccount("impostor@example.test")
        val state = world.service.linkStart(issuer.id, SocialLoginProviderId.GOOGLE, REDIRECT_URI).state

        assertThatThrownBy {
            world.service.linkCallback(impostor.id, SocialLoginProviderId.GOOGLE, "auth-code", state, REDIRECT_URI)
        }.isInstanceOf(InvalidOAuthStateException::class.java)
            .hasMessage(SocialLoginService.INVALID_STATE_MESSAGE)
    }

    @Test
    @DisplayName("검증된 이메일이 계정 이메일과 같으면 미인증 계정을 인증 완료로 표시한다")
    fun `일치하는 검증된 이메일은 계정을 인증 완료로 표시한다`() {
        val world = SocialWorld()
        val user = world.users.seedPasswordAccount("verify-me@example.test", emailVerified = false)
        world.provider.nextIdentity = SocialIdentity("google-link-5", "Verify-Me@Example.Test", emailVerified = true)
        val state = world.service.linkStart(user.id, SocialLoginProviderId.GOOGLE, REDIRECT_URI).state

        world.service.linkCallback(user.id, SocialLoginProviderId.GOOGLE, "auth-code", state, REDIRECT_URI)

        assertThat(
            world.users.saved
                .getValue("verify-me@example.test")
                .user.emailVerifiedAt,
        ).isNotNull()
    }

    @Test
    @DisplayName("이메일이 다르면 부수 효과로 인증 완료 표시를 하지 않는다")
    fun `이메일이 다르면 인증 완료로 표시하지 않는다`() {
        val world = SocialWorld()
        val user = world.users.seedPasswordAccount("account-email@example.test", emailVerified = false)
        world.provider.nextIdentity = SocialIdentity("google-link-6", "different@example.test", emailVerified = true)
        val state = world.service.linkStart(user.id, SocialLoginProviderId.GOOGLE, REDIRECT_URI).state

        world.service.linkCallback(user.id, SocialLoginProviderId.GOOGLE, "auth-code", state, REDIRECT_URI)

        assertThat(
            world.users.saved
                .getValue("account-email@example.test")
                .user.emailVerifiedAt,
        ).isNull()
    }

    // ------------------------------------------------------------------ 카카오(계약 2.13.0)

    @Test
    @DisplayName("카카오 신원도 이메일이 없으면 422 — google 과 같은 규칙(x-social-login.account_linking)")
    fun `카카오 이메일 없으면 422다`() {
        val world = SocialWorld(kakaoConfigured = true)
        world.kakaoProvider.nextIdentity = SocialIdentity("kakao-sub-1", email = null, emailVerified = false)

        val start = world.service.start(SocialLoginProviderId.KAKAO, KAKAO_REDIRECT_URI)

        assertThatThrownBy {
            world.service.callback(
                SocialLoginProviderId.KAKAO,
                "auth-code",
                start.state,
                KAKAO_REDIRECT_URI,
            )
        }.isInstanceOf(InvalidInputException::class.java)
            .hasMessage(SocialLoginService.EMAIL_REQUIRED_MESSAGE)
    }

    @Test
    @DisplayName("카카오 최초 가입은 검증된 이메일이라 이메일 인증 코드를 또 발급하지 않는다")
    fun `카카오 최초 가입은 인증 코드를 발급하지 않는다`() {
        val world = SocialWorld(kakaoConfigured = true)
        world.kakaoProvider.nextIdentity =
            SocialIdentity("kakao-sub-new-1", "kakao-new@example.test", emailVerified = true)

        val start = world.service.start(SocialLoginProviderId.KAKAO, KAKAO_REDIRECT_URI)
        val token = world.service.callback(SocialLoginProviderId.KAKAO, "auth-code", start.state, KAKAO_REDIRECT_URI)

        assertThat(token.token).isNotBlank()
        assertThat(
            world.users.saved
                .getValue("kakao-new@example.test")
                .user.emailVerifiedAt,
        ).isNotNull()
        assertThat(world.emailVerification.issuedFor).isEmpty()
    }

    @Test
    @DisplayName("카카오 연결 흐름도 동작한다 — 로그인한 계정에 카카오 신원이 연결된다")
    fun `카카오 연결이 성공한다`() {
        val world = SocialWorld(kakaoConfigured = true)
        val user = world.users.seedPasswordAccount("kakao-owner@example.test")
        world.kakaoProvider.nextIdentity =
            SocialIdentity("kakao-link-1", "kakao-owner-social@example.test", emailVerified = true)

        val start = world.service.linkStart(user.id, SocialLoginProviderId.KAKAO, KAKAO_REDIRECT_URI)
        world.service.linkCallback(user.id, SocialLoginProviderId.KAKAO, "auth-code", start.state, KAKAO_REDIRECT_URI)

        val linked = world.identities.linked.single()
        assertThat(linked.userId).isEqualTo(user.id)
        assertThat(linked.provider).isEqualTo(SocialLoginProviderId.KAKAO)
        assertThat(linked.providerUserId).isEqualTo("kakao-link-1")
    }

    @Test
    @DisplayName("키가 설정되지 않은 카카오는 422 — 카카오 전용 문구다")
    fun `설정되지 않은 카카오는 422다`() {
        val world = SocialWorld(kakaoConfigured = false)

        assertThatThrownBy { world.service.start(SocialLoginProviderId.KAKAO, KAKAO_REDIRECT_URI) }
            .isInstanceOf(InvalidInputException::class.java)
            .hasMessage("카카오 로그인이 설정되지 않았습니다")
    }

    // ------------------------------------------------------------------ 네이버(계약 2.15.0)

    @Test
    @DisplayName(
        "네이버 최초 가입은 이메일이 있으면 미검증인 채로도 계정을 만든다 — 커밋 뒤 이메일 인증 " +
            "코드를 발급한다(2026-09-05 결정, x-social-login.providers.x-note)",
    )
    fun `네이버 최초 가입은 미검증 계정을 만들고 인증 코드를 발급한다`() {
        val world = SocialWorld(naverConfigured = true)
        world.naverProvider.nextIdentity =
            SocialIdentity("naver-sub-1", "naver-new@example.test", emailVerified = false)

        val start = world.service.start(SocialLoginProviderId.NAVER, NAVER_REDIRECT_URI)
        val token = world.service.callback(SocialLoginProviderId.NAVER, "auth-code", start.state, NAVER_REDIRECT_URI)

        assertThat(token.token).isNotBlank()
        val stored = world.users.saved.getValue("naver-new@example.test")
        assertThat(stored.user.emailVerifiedAt)
            .withFailMessage("네이버 최초 가입 계정은 미검증(email_verified_at == null)이어야 한다")
            .isNull()
        assertThat(
            world.identities.linked
                .single()
                .providerUserId,
        ).isEqualTo("naver-sub-1")
        assertThat(world.identities.lastLinkedEmailVerified)
            .withFailMessage("연결된 신원 행도 emailVerified == false 로 넘어가야 한다")
            .isFalse()
        // 커밋 뒤(트랜잭션 밖, depth == 0)에 best-effort 로 발급한다 — `AuthService.signup` 과 같다.
        assertThat(world.emailVerification.issuedFor).containsExactly(stored.user.id)
        assertThat(world.emailVerification.depthAtIssue).isZero()
    }

    @Test
    @DisplayName("네이버 신원에 이메일 자체가 없으면 여전히 422 — 네이버 전용 문구다")
    fun `네이버 이메일 없으면 네이버 전용 문구로 422다`() {
        val world = SocialWorld(naverConfigured = true)
        world.naverProvider.nextIdentity = SocialIdentity("naver-sub-1b", email = null, emailVerified = false)

        val start = world.service.start(SocialLoginProviderId.NAVER, NAVER_REDIRECT_URI)

        assertThatThrownBy {
            world.service.callback(
                SocialLoginProviderId.NAVER,
                "auth-code",
                start.state,
                NAVER_REDIRECT_URI,
            )
        }.isInstanceOf(InvalidInputException::class.java)
            .hasMessage(SocialLoginService.NAVER_EMAIL_REQUIRED_MESSAGE)
        assertThat(world.identities.linked).isEmpty()
        assertThat(world.emailVerification.issuedFor).isEmpty()
    }

    @Test
    @DisplayName("네이버 신원의 이메일이 형식에 안 맞으면 네이버 전용 문구로 422다 — 계정을 만들지 않는다")
    fun `네이버 이메일이 형식에 안 맞으면 네이버 전용 문구로 422다`() {
        val world = SocialWorld(naverConfigured = true)
        world.naverProvider.nextIdentity =
            SocialIdentity("naver-sub-1d", email = "not-an-email", emailVerified = false)

        val start = world.service.start(SocialLoginProviderId.NAVER, NAVER_REDIRECT_URI)

        assertThatThrownBy {
            world.service.callback(
                SocialLoginProviderId.NAVER,
                "auth-code",
                start.state,
                NAVER_REDIRECT_URI,
            )
        }.isInstanceOf(InvalidInputException::class.java)
            .hasMessage(SocialLoginService.NAVER_EMAIL_REQUIRED_MESSAGE)
        assertThat(world.users.saved).isEmpty()
        assertThat(world.identities.linked).isEmpty()
        assertThat(world.emailVerification.issuedFor).isEmpty()
    }

    @Test
    @DisplayName("네이버 신원의 이메일이 이미 다른 계정에 등록돼 있으면 409 — 자동 연결하지 않는다")
    fun `네이버도 이메일이 겹치면 409다`() {
        val world = SocialWorld(naverConfigured = true)
        world.users.saved["naver-taken@example.test"] =
            StoredUser(User(UUID.randomUUID(), "naver-taken@example.test", Instant.EPOCH), PasswordHash("hashed:x"))
        world.naverProvider.nextIdentity =
            SocialIdentity("naver-sub-1c", "naver-taken@example.test", emailVerified = false)

        val start = world.service.start(SocialLoginProviderId.NAVER, NAVER_REDIRECT_URI)

        assertThatThrownBy {
            world.service.callback(
                SocialLoginProviderId.NAVER,
                "auth-code",
                start.state,
                NAVER_REDIRECT_URI,
            )
        }.isInstanceOf(EmailAlreadyRegisteredException::class.java)
            .hasMessage(SocialLoginService.EMAIL_ALREADY_LINKED_MESSAGE)
        assertThat(world.identities.linked).isEmpty()
        assertThat(world.emailVerification.issuedFor).isEmpty()
    }

    @Test
    @DisplayName("네이버 연결 흐름은 이메일 검증을 요구하지 않는다 — 로그인한 계정에 신원이 연결된다")
    fun `네이버 연결이 성공한다`() {
        val world = SocialWorld(naverConfigured = true)
        val user = world.users.seedPasswordAccount("naver-owner@example.test")
        world.naverProvider.nextIdentity =
            SocialIdentity("naver-link-1", "naver-owner-social@example.test", emailVerified = false)

        val start = world.service.linkStart(user.id, SocialLoginProviderId.NAVER, NAVER_REDIRECT_URI)
        world.service.linkCallback(user.id, SocialLoginProviderId.NAVER, "auth-code", start.state, NAVER_REDIRECT_URI)

        val linked = world.identities.linked.single()
        assertThat(linked.userId).isEqualTo(user.id)
        assertThat(linked.provider).isEqualTo(SocialLoginProviderId.NAVER)
        assertThat(linked.providerUserId).isEqualTo("naver-link-1")
    }

    @Test
    @DisplayName("연결된 네이버 신원으로 다시 콜백을 받으면 이메일 규칙과 무관하게 로그인이다")
    fun `연결된 네이버 신원은 이메일 규칙 없이 로그인한다`() {
        val world = SocialWorld(naverConfigured = true)
        val existingUser = User(UUID.randomUUID(), "naver-linked@example.test", Instant.EPOCH)
        world.identities.seed(existingUser.id, SocialLoginProviderId.NAVER, "naver-sub-2")
        world.naverProvider.nextIdentity = SocialIdentity("naver-sub-2", email = null, emailVerified = false)

        val start = world.service.start(SocialLoginProviderId.NAVER, NAVER_REDIRECT_URI)
        val token = world.service.callback(SocialLoginProviderId.NAVER, "auth-code", start.state, NAVER_REDIRECT_URI)

        assertThat(token.token).isEqualTo("token:${existingUser.id}")
    }

    @Test
    @DisplayName("키가 설정되지 않은 네이버는 422 — 네이버 전용 문구다")
    fun `설정되지 않은 네이버는 422다`() {
        val world = SocialWorld(naverConfigured = false)

        assertThatThrownBy { world.service.start(SocialLoginProviderId.NAVER, NAVER_REDIRECT_URI) }
            .isInstanceOf(InvalidInputException::class.java)
            .hasMessage("네이버 로그인이 설정되지 않았습니다")
    }

    private companion object {
        const val REDIRECT_URI = "http://localhost:5173/auth/google/callback"
        const val KAKAO_REDIRECT_URI = "http://localhost:5173/auth/kakao/callback"
        const val NAVER_REDIRECT_URI = "http://localhost:5173/auth/naver/callback"
    }
}

/** 유스케이스 하나를 돌리는 데 필요한 최소 세계. */
private class SocialWorld(
    tokensConfigured: Boolean = true,
    googleConfigured: Boolean = true,
    kakaoConfigured: Boolean = false,
    naverConfigured: Boolean = false,
) {
    val users = RecordingSocialUserRepository()
    val workspaces = RecordingSocialWorkspaceRepository()
    val identities = RecordingIdentityRepository()
    val states: OAuthStateStore = InMemoryOAuthStateStore()
    val tokens = RecordingSocialAccessTokens(tokensConfigured)
    val provider = FakeSocialLoginProvider("http://localhost:5173/auth/google/callback")
    val kakaoProvider = FakeSocialLoginProvider("http://localhost:5173/auth/kakao/callback")
    val naverProvider = FakeSocialLoginProvider("http://localhost:5173/auth/naver/callback")
    val transaction = SocialRecordingTransactionRunner()
    val emailVerification = SocialRecordingEmailVerification(transaction)
    val service =
        SocialLoginService(
            providers =
                buildMap {
                    if (googleConfigured) put(SocialLoginProviderId.GOOGLE, provider)
                    if (kakaoConfigured) put(SocialLoginProviderId.KAKAO, kakaoProvider)
                    if (naverConfigured) put(SocialLoginProviderId.NAVER, naverProvider)
                },
            states = states,
            repositories = SocialLoginRepositories(users, identities, workspaces),
            accessTokens = tokens,
            transaction = transaction,
            stateTtl = Duration.ofMinutes(10),
            emailVerification = emailVerification,
        )
}

/** 트랜잭션 깊이를 기록한다 — `AuthServiceTest.SocialRecordingTransactionRunner` 와 같은 필요. */
private class SocialRecordingTransactionRunner : TransactionRunner {
    var depth = 0
        private set

    override fun <T> inTransaction(block: () -> T): T {
        depth++
        try {
            return block()
        } finally {
            depth--
        }
    }
}

/**
 * `PostSignupEmailVerification` 대역 — 발급 호출을 기록한다. [depthAtIssue] 는 0이어야
 * 한다: 커밋 **뒤**에 불려야 한다(`AuthServiceTest.SocialRecordingEmailVerification` 과 같은 필요).
 */
private class SocialRecordingEmailVerification(private val transaction: SocialRecordingTransactionRunner) :
    PostSignupEmailVerification {
    val issuedFor: MutableList<UUID> = mutableListOf()
    var depthAtIssue: Int = -1
        private set

    override fun issueAfterSignup(userId: UUID) {
        issuedFor += userId
        depthAtIssue = transaction.depth
    }
}

/** 제공자를 가리지 않는 대역 — 허용 redirect_uri 하나만 다르면 google 이든 kakao 든 같은 계약을 흉내 낸다. */
private class FakeSocialLoginProvider(private val allowedRedirectUri: String) : SocialLoginProvider {
    var nextIdentity: SocialIdentity? = null
    var exchangeFailure: RuntimeException? = null
    var exchangeCallCount = 0
        private set

    override fun supportsRedirectUri(redirectUri: String): Boolean = redirectUri == allowedRedirectUri

    override fun authorizationUrl(
        state: String,
        nonce: String,
        redirectUri: String,
    ): String = "https://accounts.social.test/o/oauth2/auth?state=$state&nonce=$nonce"

    override fun exchange(
        code: String,
        redirectUri: String,
        nonce: String,
    ): SocialIdentity {
        exchangeCallCount++
        exchangeFailure?.let { throw it }
        return nextIdentity ?: error("테스트가 nextIdentity 를 설정하지 않았다")
    }
}

/** state·nonce 를 실제로 단발 소비하는 인메모리 대역 — 실물 `JdbcOAuthStateStore` 와 같은 계약. */
private class InMemoryOAuthStateStore : OAuthStateStore {
    private data class Entry(
        val provider: SocialLoginProviderId,
        val redirectUri: String,
        val nonce: String,
        val userId: java.util.UUID?,
    )

    private val entries = mutableMapOf<String, Entry>()
    private var counter = 0

    override fun issue(
        provider: SocialLoginProviderId,
        redirectUri: String,
        ttl: Duration,
        userId: java.util.UUID?,
    ): OAuthChallenge {
        val state = "state-${++counter}"
        val nonce = "nonce-$counter"
        entries[state] = Entry(provider, redirectUri, nonce, userId)
        return OAuthChallenge(state, nonce)
    }

    override fun consume(
        provider: SocialLoginProviderId,
        state: String,
        redirectUri: String,
    ): ConsumedOAuthState? =
        // 단발 — 일치하든 안 하든 재사용은 막는다(실물의 단일 UPDATE ... WHERE ... RETURNING 과 같은 성질).
        entries
            .remove(state)
            ?.takeIf { it.provider == provider && it.redirectUri == redirectUri }
            ?.let { ConsumedOAuthState(it.nonce, it.userId) }
}

private class RecordingSocialUserRepository : UserRepository {
    val saved: MutableMap<String, StoredUser> = mutableMapOf()

    override fun findByEmail(email: String): StoredUser? = saved[email]

    override fun findById(id: UUID): User? = saved.values.firstOrNull { it.user.id == id }?.user

    override fun exists(id: UUID): Boolean = saved.values.any { it.user.id == id }

    override fun create(
        email: String,
        passwordHash: PasswordHash,
    ): User = error("소셜 로그인 유스케이스는 비밀번호가 있는 create 를 부르지 않는다")

    override fun createWithoutPassword(
        email: String,
        emailVerified: Boolean,
    ): User {
        val verifiedAt = if (emailVerified) Instant.EPOCH else null
        val stored = StoredUser(User(UUID.randomUUID(), email, Instant.EPOCH, verifiedAt), passwordHash = null)
        saved[email] = stored
        return stored.user
    }

    override fun updatePasswordHash(
        userId: UUID,
        passwordHash: PasswordHash,
    ) = error("소셜 로그인 유스케이스는 비밀번호를 재해시하지 않는다")

    /** `linkCallback` 의 부수 효과(검증된 이메일이 일치하면 인증 완료로 표시)를 재는 자리에서 쓴다. */
    override fun markEmailVerified(userId: UUID) {
        val existing = saved.values.firstOrNull { it.user.id == userId } ?: return
        if (existing.user.emailVerifiedAt != null) return
        val replaced = StoredUser(existing.user.copy(emailVerifiedAt = Instant.EPOCH), existing.passwordHash)
        saved[existing.user.email] = replaced
    }

    /** 비밀번호 계정을 직접 심는다 — `linkCallback` 이 "이미 로그인한 계정"을 전제하는 시나리오용. */
    fun seedPasswordAccount(
        email: String,
        emailVerified: Boolean = true,
    ): User {
        val verifiedAt = if (emailVerified) Instant.EPOCH else null
        val stored = StoredUser(User(UUID.randomUUID(), email, Instant.EPOCH, verifiedAt), PasswordHash("hashed:x"))
        saved[email] = stored
        return stored.user
    }
}

private class RecordingSocialWorkspaceRepository : WorkspaceRepository {
    val createdFor: MutableList<UUID> = mutableListOf()

    override fun createDefault(userId: UUID): UUID {
        createdFor += userId
        return UUID.randomUUID()
    }

    override fun listOwned(ownerId: UUID): List<WorkspaceListing> = error(SOCIAL_NOT_SCOPE)

    override fun create(
        ownerId: UUID,
        name: String,
    ): Workspace = error(SOCIAL_NOT_SCOPE)

    override fun rename(
        ownerId: UUID,
        workspaceId: UUID,
        name: String,
    ): Workspace = error(SOCIAL_NOT_SCOPE)

    override fun lockForDeletion(
        ownerId: UUID,
        workspaceId: UUID,
    ): WorkspaceDeletionState = error(SOCIAL_NOT_SCOPE)

    override fun delete(
        ownerId: UUID,
        workspaceId: UUID,
    ): Boolean = error(SOCIAL_NOT_SCOPE)

    private companion object {
        const val SOCIAL_NOT_SCOPE = "소셜 로그인 유스케이스가 부르지 않는 작업 공간 연산이다"
    }
}

private class RecordingIdentityRepository : UserIdentityRepository {
    val linked: MutableList<UserIdentity> = mutableListOf()
    private val byProvider = mutableMapOf<Pair<SocialLoginProviderId, String>, UserIdentity>()

    /**
     * `link()` 가 받은 `emailVerified` 를 그대로 기록한다 — [UserIdentity] 자체엔 그 필드가
     * 없어(응답 최소화, `readMe.identities` KDoc) 인자로 들어온 값을 별도로 남겨야 잰다.
     */
    var lastLinkedEmailVerified: Boolean? = null
        private set

    /** 「이미 연결된 신원」 시나리오를 준비한다. */
    fun seed(
        userId: UUID,
        provider: SocialLoginProviderId,
        providerUserId: String,
    ) {
        byProvider[provider to providerUserId] = UserIdentity(UUID.randomUUID(), userId, provider, providerUserId)
    }

    override fun findByProviderIdentity(
        provider: SocialLoginProviderId,
        providerUserId: String,
    ): UserIdentity? = byProvider[provider to providerUserId]

    override fun findByUserAndProvider(
        userId: UUID,
        provider: SocialLoginProviderId,
    ): UserIdentity? = byProvider.values.firstOrNull { it.userId == userId && it.provider == provider }

    override fun findAllByUser(userId: UUID): List<UserIdentity> = byProvider.values.filter { it.userId == userId }

    override fun link(
        userId: UUID,
        provider: SocialLoginProviderId,
        providerUserId: String,
        email: String?,
        emailVerified: Boolean,
    ): UserIdentity {
        lastLinkedEmailVerified = emailVerified
        val identity = UserIdentity(UUID.randomUUID(), userId, provider, providerUserId)
        byProvider[provider to providerUserId] = identity
        linked += identity
        return identity
    }
}

private class RecordingSocialAccessTokens(private val configured: Boolean) : AccessTokens {
    override fun ensureConfigured() {
        if (!configured) {
            throw ConfigurationException("인증이 설정되지 않았습니다")
        }
    }

    override fun issue(userId: UUID): IssuedAccessToken = IssuedAccessToken("token:$userId", 1)

    override fun verify(token: String): UUID = UUID.fromString(token.removePrefix("token:"))
}
