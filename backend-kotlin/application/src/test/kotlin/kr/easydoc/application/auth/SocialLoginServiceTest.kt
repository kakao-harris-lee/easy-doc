package kr.easydoc.application.auth

import kr.easydoc.application.credit.CreditAccountRepository
import kr.easydoc.application.credit.CreditAccountService
import kr.easydoc.application.credit.NoopCreditAccountRepository
import kr.easydoc.core.credit.CreditReason
import kr.easydoc.core.credit.Credits
import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.EmailAlreadyRegisteredException
import kr.easydoc.core.exceptions.ExternalServiceUnavailableException
import kr.easydoc.core.exceptions.InvalidCredentialsException
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.exceptions.InvalidOAuthStateException
import kr.easydoc.core.exceptions.NotFoundException
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
@Suppress("LargeClass")
class SocialLoginServiceTest {
    @Test
    @DisplayName("새 신원은 크레딧 계정도 같은 트랜잭션에서 만든다")
    fun `새 신원은 크레딧 계정을 만든다`() {
        val world = SocialWorld()
        world.provider.nextIdentity = SocialIdentity("google-sub-credit", "credit@example.test", emailVerified = true)

        val start = world.service.start(SocialLoginProviderId.GOOGLE, REDIRECT_URI)
        world.service.callback(SocialLoginProviderId.GOOGLE, "auth-code", start.state, REDIRECT_URI)

        assertThat(world.creditRepository.ensuredFor).hasSize(1)
        assertThat(world.creditRepository.grantCalls).isEmpty()
    }

    @Test
    @DisplayName("가입 부여가 설정되면 소셜 신규 가입도 grant 거래를 만든다")
    fun `소셜 가입 부여가 설정되면 grant 한다`() {
        val world = SocialWorld(signupGrant = 50)
        world.provider.nextIdentity = SocialIdentity("google-sub-grant", "grant@example.test", emailVerified = true)

        val start = world.service.start(SocialLoginProviderId.GOOGLE, REDIRECT_URI)
        world.service.callback(SocialLoginProviderId.GOOGLE, "auth-code", start.state, REDIRECT_URI)

        assertThat(world.creditRepository.grantCalls).hasSize(1)
        val (workspaceId, credits, reason) = world.creditRepository.grantCalls.single()
        assertThat(workspaceId).isEqualTo(world.creditRepository.ensuredFor.single())
        assertThat(credits).isEqualTo(50)
        assertThat(reason).isEqualTo(CreditReason.SIGNUP)
    }

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
        val existingUser = User(UUID.randomUUID(), "linked@example.test", Instant.EPOCH, hasPassword = true)
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
            StoredUser(
                User(UUID.randomUUID(), "taken@example.test", Instant.EPOCH, hasPassword = true),
                PasswordHash("hashed:x"),
            )
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
    @DisplayName(
        "동시 최초 콜백이 자기 자신과 경쟁하면(신원 유일 제약 통과 뒤 users.email 에서 걸림) " +
            "롤백 뒤 재조회로 이긴 쪽 사용자로 로그인 처리한다 — 새 계정·작업 공간을 또 만들지 않는다",
    )
    fun `자기 자신과의 경쟁은 승자로 로그인 처리한다`() {
        val winnerUserId = UUID.randomUUID()
        val winnerIdentity =
            UserIdentity(UUID.randomUUID(), winnerUserId, SocialLoginProviderId.GOOGLE, "google-race-sub")
        val identities = SelfRacedIdentityRepository(winnerAfterRace = winnerIdentity)
        val users = AlwaysDuplicateEmailUserRepository(SocialLoginService.EMAIL_ALREADY_LINKED_MESSAGE)
        val workspaces = RecordingSocialWorkspaceRepository()
        val emailVerification = SocialRecordingEmailVerification(SocialRecordingTransactionRunner())
        val world =
            RacedSocialWorld(
                users = users,
                identities = identities,
                workspaces = workspaces,
                emailVerification = emailVerification,
                providerUserId = "google-race-sub",
                email = "raced@example.test",
            )

        val start = world.service.start(SocialLoginProviderId.GOOGLE, REDIRECT_URI)
        val token = world.service.callback(SocialLoginProviderId.GOOGLE, "auth-code", start.state, REDIRECT_URI)

        assertThat(token.token).isEqualTo("token:$winnerUserId")
        assertThat(workspaces.createdFor)
            .withFailMessage("경쟁에서 진 쪽은 새 작업 공간을 만들면 안 된다 — 이긴 쪽이 이미 만들었다")
            .isEmpty()
        assertThat(emailVerification.issuedFor)
            .withFailMessage("경쟁에서 진 쪽은 이메일 인증 코드를 또 발급하면 안 된다")
            .isEmpty()
        assertThat(identities.findByProviderIdentityCallCount)
            .withFailMessage("첫 조회(트랜잭션 전) + 재조회(롤백 뒤) 두 번이어야 한다")
            .isEqualTo(2)
    }

    @Test
    @DisplayName(
        "롤백 뒤 재조회해도 신원이 여전히 없으면 진짜 다른 사람의 이메일과 겹친 것이다 — " +
            "같은 예외를 그대로 409로 올린다",
    )
    fun `진짜 중복 이메일은 재조회해도 그대로 409다`() {
        val identities = SelfRacedIdentityRepository(winnerAfterRace = null)
        // 실물 `JdbcUserRepository.createWithoutPassword` 가 `users.email` 유일 인덱스 위반에서
        // 던지는 것은 `EMAIL_ALREADY_LINKED_MESSAGE`(사전 검사 문구)가 아니라
        // `JdbcUserRepository.DUPLICATE_EMAIL_MESSAGE`("이미 가입된 이메일입니다")다 — 이 대역이
        // 그 실제 DB 계층 문구를 그대로 흉내 내야 "재조회로도 못 찾으면 재조회 전 사전 검사와
        // 같은 문구로 409를 낸다"는 요구(PR #54 리뷰 후속 B)를 이 테스트가 실제로 잰다.
        val users = AlwaysDuplicateEmailUserRepository(SIMULATED_DB_DUPLICATE_EMAIL_MESSAGE)
        val workspaces = RecordingSocialWorkspaceRepository()
        val emailVerification = SocialRecordingEmailVerification(SocialRecordingTransactionRunner())
        val world =
            RacedSocialWorld(
                users = users,
                identities = identities,
                workspaces = workspaces,
                emailVerification = emailVerification,
                providerUserId = "google-race-sub-2",
                email = "genuinely-taken@example.test",
            )

        val start = world.service.start(SocialLoginProviderId.GOOGLE, REDIRECT_URI)

        assertThatThrownBy {
            world.service.callback(SocialLoginProviderId.GOOGLE, "auth-code", start.state, REDIRECT_URI)
        }.isInstanceOf(EmailAlreadyRegisteredException::class.java)
            .withFailMessage(
                "재조회로도 못 찾은 진짜 중복은 사전 검사와 같은 EMAIL_ALREADY_LINKED_MESSAGE 여야 하는데, " +
                    "DB 계층이 던진 원래 예외를 그대로 올리면 문구가 갈린다",
            ).hasMessage(SocialLoginService.EMAIL_ALREADY_LINKED_MESSAGE)
        assertThat(workspaces.createdFor).isEmpty()
        assertThat(emailVerification.issuedFor).isEmpty()
        assertThat(identities.findByProviderIdentityCallCount)
            .withFailMessage("재조회까지 두 번 봤어야 진짜 중복으로 판정한다")
            .isEqualTo(2)
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

    // ------------------------------------------------------------------ linkCallback 자기 경쟁(PR #54 리뷰 후속 A)

    @Test
    @DisplayName(
        "같은 사용자·같은 신원의 연결 콜백 둘이 동시에 오면(사전 검사 통과 뒤 DB 유일성 제약에서 " +
            "경쟁) 재조회로 이미 이 사용자에게 연결됐음을 확인하고 멱등 성공 처리한다 — " +
            "이메일이 일치하면 인증 완료 표시도 그대로 한다",
    )
    fun `연결 콜백 자기 경쟁은 멱등 성공이다`() {
        val users = RecordingSocialUserRepository()
        val user = users.seedPasswordAccount("self-link-race@example.test", emailVerified = false)
        val winnerIdentity =
            UserIdentity(UUID.randomUUID(), user.id, SocialLoginProviderId.GOOGLE, "google-link-race-1")
        val identities = RacingLinkIdentityRepository(winnerAfterRace = winnerIdentity)
        val world =
            RacedSocialWorld(
                users = users,
                identities = identities,
                workspaces = RecordingSocialWorkspaceRepository(),
                emailVerification = { error("linkCallback 은 이메일 인증 코드를 발급하지 않는다") },
                providerUserId = "google-link-race-1",
                email = "Self-Link-Race@Example.Test",
            )
        val state = world.service.linkStart(user.id, SocialLoginProviderId.GOOGLE, REDIRECT_URI).state

        world.service.linkCallback(user.id, SocialLoginProviderId.GOOGLE, "auth-code", state, REDIRECT_URI)

        assertThat(identities.linkCalled).isTrue()
        assertThat(identities.findByProviderIdentityCallCount)
            .withFailMessage("사전 검사 + 경쟁 후 재조회 두 번이어야 한다")
            .isEqualTo(2)
        assertThat(
            users.saved
                .getValue("self-link-race@example.test")
                .user.emailVerifiedAt,
        ).withFailMessage("자기 경쟁으로 복구된 연결도 이메일이 일치하면 인증 완료로 표시해야 한다")
            .isNotNull()
    }

    @Test
    @DisplayName(
        "연결 콜백이 다른 사용자와 경쟁해 DB 유일성 제약에 걸리면(재조회 결과가 다른 사용자) " +
            "409 — 그 신원이 이미 다른 사용자에게 연결됐다는 문구다",
    )
    fun `연결 콜백이 다른 사용자와 경쟁하면 409다`() {
        val users = RecordingSocialUserRepository()
        val user = users.seedPasswordAccount("other-user-link-race@example.test")
        val otherUserId = UUID.randomUUID()
        val winnerIdentity =
            UserIdentity(UUID.randomUUID(), otherUserId, SocialLoginProviderId.GOOGLE, "google-link-race-2")
        val identities = RacingLinkIdentityRepository(winnerAfterRace = winnerIdentity)
        val world =
            RacedSocialWorld(
                users = users,
                identities = identities,
                workspaces = RecordingSocialWorkspaceRepository(),
                emailVerification = { error("linkCallback 은 이메일 인증 코드를 발급하지 않는다") },
                providerUserId = "google-link-race-2",
                email = "other-user-link-race-social@example.test",
            )
        val state = world.service.linkStart(user.id, SocialLoginProviderId.GOOGLE, REDIRECT_URI).state

        assertThatThrownBy {
            world.service.linkCallback(user.id, SocialLoginProviderId.GOOGLE, "auth-code", state, REDIRECT_URI)
        }.isInstanceOf(ConflictException::class.java)
            .hasMessage(SocialLoginService.identityAlreadyLinkedToOtherUserMessage(SocialLoginProviderId.GOOGLE))
    }

    @Test
    @DisplayName(
        "연결 콜백이 같은 사용자의 다른 신원과 경쟁해 DB 유일성 제약(V9)에 걸리면(재조회 결과가 " +
            "이 사용자의 다른 신원) 409 — 제공자당 하나라는 문구다",
    )
    fun `연결 콜백이 같은 사용자의 다른 신원과 경쟁하면 409다`() {
        val users = RecordingSocialUserRepository()
        val user = users.seedPasswordAccount("multi-identity-link-race@example.test")
        val ownerIdentity =
            UserIdentity(UUID.randomUUID(), user.id, SocialLoginProviderId.GOOGLE, "google-link-race-3-existing")
        val identities = RacingLinkIdentityRepository(winnerAfterRace = null, ownerAfterRace = ownerIdentity)
        val world =
            RacedSocialWorld(
                users = users,
                identities = identities,
                workspaces = RecordingSocialWorkspaceRepository(),
                emailVerification = { error("linkCallback 은 이메일 인증 코드를 발급하지 않는다") },
                providerUserId = "google-link-race-3",
                email = "multi-identity-link-race-social@example.test",
            )
        val state = world.service.linkStart(user.id, SocialLoginProviderId.GOOGLE, REDIRECT_URI).state

        assertThatThrownBy {
            world.service.linkCallback(user.id, SocialLoginProviderId.GOOGLE, "auth-code", state, REDIRECT_URI)
        }.isInstanceOf(ConflictException::class.java)
            .hasMessage(SocialLoginService.providerAlreadyLinkedMessage(SocialLoginProviderId.GOOGLE))
    }

    @Test
    @DisplayName(
        "재조회 두 곳(신원·사용자+제공자) 모두 아무것도 못 찾으면 자기 경쟁이 아니다 — " +
            "DB 가 던진 원래 409 를 그대로 올린다",
    )
    fun `연결 콜백이 재조회로도 설명되지 않으면 원래 예외를 그대로 올린다`() {
        val users = RecordingSocialUserRepository()
        val user = users.seedPasswordAccount("unexplained-link-race@example.test")
        val identities =
            RacingLinkIdentityRepository(
                winnerAfterRace = null,
                ownerAfterRace = null,
                linkConflictMessage = "unique constraint violation detail",
            )
        val world =
            RacedSocialWorld(
                users = users,
                identities = identities,
                workspaces = RecordingSocialWorkspaceRepository(),
                emailVerification = { error("linkCallback 은 이메일 인증 코드를 발급하지 않는다") },
                providerUserId = "google-link-race-4",
                email = "unexplained-link-race-social@example.test",
            )
        val state = world.service.linkStart(user.id, SocialLoginProviderId.GOOGLE, REDIRECT_URI).state

        assertThatThrownBy {
            world.service.linkCallback(user.id, SocialLoginProviderId.GOOGLE, "auth-code", state, REDIRECT_URI)
        }.isInstanceOf(ConflictException::class.java)
            .hasMessage("unique constraint violation detail")
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
            StoredUser(
                User(UUID.randomUUID(), "naver-taken@example.test", Instant.EPOCH, hasPassword = true),
                PasswordHash("hashed:x"),
            )
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
        val existingUser = User(UUID.randomUUID(), "naver-linked@example.test", Instant.EPOCH, hasPassword = true)
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

    // ------------------------------------------------------------------ 연결 해제(unlink, backlog §1.4 다음 조각)

    @Test
    @DisplayName("연결이 없으면 404 — 존재를 숨긴다")
    fun `연결 없는 제공자를 해제하면 404다`() {
        val world = SocialWorld()
        val user = world.users.seedPasswordAccount("no-link@example.test")

        assertThatThrownBy { world.service.unlink(user.id, SocialLoginProviderId.GOOGLE) }
            .isInstanceOf(NotFoundException::class.java)
            .hasMessage(SocialLoginService.IDENTITY_NOT_FOUND_MESSAGE)
    }

    @Test
    @DisplayName("비밀번호 계정의 유일한 신원도 해제할 수 있다 — 비밀번호가 남은 로그인 수단이다")
    fun `비밀번호 계정은 마지막 신원도 해제된다`() {
        val world = SocialWorld()
        val user = world.users.seedPasswordAccount("has-password@example.test")
        world.identities.seed(user.id, SocialLoginProviderId.GOOGLE, "google-sub-unlink-1")

        world.service.unlink(user.id, SocialLoginProviderId.GOOGLE)

        assertThat(world.identities.findAllByUser(user.id)).isEmpty()
    }

    @Test
    @DisplayName("비밀번호가 없고 신원이 여럿이면 하나를 해제해도 다른 신원이 남는다 — 409가 아니다")
    fun `비밀번호 없이도 신원이 여럿이면 해제된다`() {
        val world = SocialWorld()
        val user = world.users.createWithoutPassword("multi-identity@example.test", emailVerified = true)
        world.identities.seed(user.id, SocialLoginProviderId.GOOGLE, "google-sub-unlink-2")
        world.identities.seed(user.id, SocialLoginProviderId.KAKAO, "kakao-sub-unlink-2")

        world.service.unlink(user.id, SocialLoginProviderId.GOOGLE)

        assertThat(world.identities.findAllByUser(user.id).map { it.provider })
            .containsExactly(SocialLoginProviderId.KAKAO)
    }

    @Test
    @DisplayName("비밀번호가 없고 신원이 이것뿐이면 409 — 마지막 로그인 수단이다")
    fun `비밀번호 없는 마지막 신원은 409다`() {
        val world = SocialWorld()
        val user = world.users.createWithoutPassword("last-identity@example.test", emailVerified = true)
        world.identities.seed(user.id, SocialLoginProviderId.GOOGLE, "google-sub-unlink-3")

        assertThatThrownBy { world.service.unlink(user.id, SocialLoginProviderId.GOOGLE) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessage(SocialLoginService.LAST_LOGIN_METHOD_MESSAGE)
        assertThat(world.identities.findAllByUser(user.id)).hasSize(1)
    }

    @Test
    @DisplayName("반복 해제 요청은 404다 — 첫 해제 뒤에는 연결이 없다")
    fun `이미 해제된 신원을 다시 해제하면 404다`() {
        val world = SocialWorld()
        val user = world.users.seedPasswordAccount("repeat-unlink@example.test")
        world.identities.seed(user.id, SocialLoginProviderId.GOOGLE, "google-sub-unlink-4")
        world.service.unlink(user.id, SocialLoginProviderId.GOOGLE)

        assertThatThrownBy { world.service.unlink(user.id, SocialLoginProviderId.GOOGLE) }
            .isInstanceOf(NotFoundException::class.java)
            .hasMessage(SocialLoginService.IDENTITY_NOT_FOUND_MESSAGE)
    }

    private companion object {
        const val REDIRECT_URI = "http://localhost:5173/auth/google/callback"
        const val KAKAO_REDIRECT_URI = "http://localhost:5173/auth/kakao/callback"
        const val NAVER_REDIRECT_URI = "http://localhost:5173/auth/naver/callback"

        /**
         * `JdbcUserRepository.DUPLICATE_EMAIL_MESSAGE` 를 그대로 옮긴 값이다(다른 모듈이라
         * 상수를 직접 참조할 수 없다) — `users.email` 유일 인덱스 위반이 실제로 던지는 문구.
         * `SocialLoginService.EMAIL_ALREADY_LINKED_MESSAGE`(사전 검사 문구)와 **의도적으로 다르다**.
         */
        const val SIMULATED_DB_DUPLICATE_EMAIL_MESSAGE = "이미 가입된 이메일입니다"
    }
}

/** 유스케이스 하나를 돌리는 데 필요한 최소 세계. */
private class SocialWorld(
    tokensConfigured: Boolean = true,
    googleConfigured: Boolean = true,
    kakaoConfigured: Boolean = false,
    naverConfigured: Boolean = false,
    signupGrant: Int = 0,
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
    val creditRepository = RecordingSocialCreditAccountRepository()
    val credits = CreditAccountService(creditRepository, enforced = false, signupGrant = signupGrant)
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
            credits = credits,
        )
}

/**
 * 크레딧 계정 호출을 기록하는 대역 — 소셜 가입 경로도 `AuthService` 와 같은 규약을 진다.
 * `ensureAccount`·`grant` 만 재정의하고 나머지는 [NoopCreditAccountRepository] 에
 * 위임한다(리뷰 MEDIUM-11).
 */
private class RecordingSocialCreditAccountRepository : CreditAccountRepository by NoopCreditAccountRepository {
    val ensuredFor: MutableList<UUID> = mutableListOf()
    val grantCalls: MutableList<Triple<UUID, Int, CreditReason>> = mutableListOf()

    override fun ensureAccount(workspaceId: UUID) {
        ensuredFor += workspaceId
    }

    override fun grant(
        workspaceId: UUID,
        ownerUserId: UUID,
        credits: Int,
        reason: CreditReason,
        note: String?,
        actorUserId: UUID?,
    ): Int {
        grantCalls += Triple(workspaceId, credits, reason)
        return credits
    }
}

/**
 * `callback` 자기 경쟁 테스트 전용 세계 — [SocialWorld] 와 배선은 같지만, 재조회 결과와
 * 예외 발생 시점을 테스트가 직접 통제해야 해서 사용자·신원 저장소를 밖에서 주입받는다.
 */
private class RacedSocialWorld(
    users: UserRepository,
    identities: UserIdentityRepository,
    workspaces: WorkspaceRepository,
    emailVerification: PostSignupEmailVerification,
    providerUserId: String,
    email: String,
) {
    val states: OAuthStateStore = InMemoryOAuthStateStore()
    val provider =
        FakeSocialLoginProvider("http://localhost:5173/auth/google/callback").apply {
            nextIdentity = SocialIdentity(providerUserId, email, emailVerified = true)
        }
    val service =
        SocialLoginService(
            providers = mapOf(SocialLoginProviderId.GOOGLE to provider),
            states = states,
            repositories = SocialLoginRepositories(users, identities, workspaces),
            accessTokens = RecordingSocialAccessTokens(configured = true),
            transaction = SocialRecordingTransactionRunner(),
            stateTtl = Duration.ofMinutes(10),
            emailVerification = emailVerification,
            credits = CreditAccountService(RecordingSocialCreditAccountRepository(), enforced = false),
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

    override fun lockForUpdate(id: UUID): User? = findById(id)

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
        val user = User(UUID.randomUUID(), email, Instant.EPOCH, verifiedAt, hasPassword = false)
        val stored = StoredUser(user, passwordHash = null)
        saved[email] = stored
        return stored.user
    }

    override fun updatePasswordHash(
        userId: UUID,
        passwordHash: PasswordHash,
    ) = error("소셜 로그인 유스케이스는 비밀번호를 재해시하지 않는다")

    /** `linkCallback` 의 부수 효과(검증된 이메일이 일치하면 인증 완료로 표시)를 재는 자리에서 쓴다. */
    override fun markEmailVerified(userId: UUID): Boolean {
        val existing = saved.values.firstOrNull { it.user.id == userId } ?: return false
        val eligible = existing.user.emailVerifiedAt == null
        if (eligible) {
            val replaced = StoredUser(existing.user.copy(emailVerifiedAt = Instant.EPOCH), existing.passwordHash)
            saved[existing.user.email] = replaced
        }
        return eligible
    }

    /** 비밀번호 계정을 직접 심는다 — `linkCallback` 이 "이미 로그인한 계정"을 전제하는 시나리오용. */
    fun seedPasswordAccount(
        email: String,
        emailVerified: Boolean = true,
    ): User {
        val verifiedAt = if (emailVerified) Instant.EPOCH else null
        val user = User(UUID.randomUUID(), email, Instant.EPOCH, verifiedAt, hasPassword = true)
        val stored = StoredUser(user, PasswordHash("hashed:x"))
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

/**
 * 콜백 자기 경쟁 테스트 전용 — 트랜잭션 안 `createWithoutPassword` 가 항상
 * [EmailAlreadyRegisteredException] 을 던지게 해 `users.email` 유일 인덱스 위반을 흉내 낸다.
 * [findByEmail] 은 `null` 을 돌려준다 — 사전 검사([SocialLoginService.requireEmailNotAlreadyLinked])가
 * 통과해 흐름이 트랜잭션까지 들어오는 경쟁 창을 재현해야 하기 때문이다.
 */
private class AlwaysDuplicateEmailUserRepository(private val message: String) : UserRepository {
    override fun findByEmail(email: String): StoredUser? = null

    override fun findById(id: UUID): User? = error("이 테스트는 callback 만 부른다")

    override fun exists(id: UUID): Boolean = error("이 테스트는 callback 만 부른다")

    override fun lockForUpdate(id: UUID): User? = error("이 테스트는 callback 만 부른다")

    override fun create(
        email: String,
        passwordHash: PasswordHash,
    ): User = error("소셜 로그인 유스케이스는 비밀번호가 있는 create 를 부르지 않는다")

    override fun createWithoutPassword(
        email: String,
        emailVerified: Boolean,
    ): User = throw EmailAlreadyRegisteredException(message)

    override fun updatePasswordHash(
        userId: UUID,
        passwordHash: PasswordHash,
    ) = error("이 테스트는 callback 만 부른다")

    override fun markEmailVerified(userId: UUID): Boolean = error("이 테스트는 callback 만 부른다")
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

    override fun deleteByUserAndProvider(
        userId: UUID,
        provider: SocialLoginProviderId,
    ): Boolean {
        val key = byProvider.entries.firstOrNull { it.value.userId == userId && it.value.provider == provider }?.key
        if (key == null) return false
        byProvider.remove(key)
        return true
    }
}

/**
 * 콜백 자기 경쟁 테스트 전용 — 트랜잭션 **전** 첫 `findByProviderIdentity` 는 "아직 없음"을
 * 돌려주고(둘 다 이 사전 검사를 통과하는 경쟁 창), 트랜잭션이 롤백된 뒤의 재조회는
 * [winnerAfterRace] 를 돌려준다: 자기 경쟁이면 상대가 이미 커밋해 남긴 신원, 진짜 다른
 * 사람의 이메일과 겹친 경우면 `null`(여전히 아무도 이 신원을 연결하지 않았다).
 */
private class SelfRacedIdentityRepository(private val winnerAfterRace: UserIdentity?) : UserIdentityRepository {
    var findByProviderIdentityCallCount = 0
        private set

    override fun findByProviderIdentity(
        provider: SocialLoginProviderId,
        providerUserId: String,
    ): UserIdentity? {
        findByProviderIdentityCallCount++
        return if (findByProviderIdentityCallCount == 1) null else winnerAfterRace
    }

    override fun findByUserAndProvider(
        userId: UUID,
        provider: SocialLoginProviderId,
    ): UserIdentity? = error("이 테스트는 callback 만 부른다")

    override fun findAllByUser(userId: UUID): List<UserIdentity> = error("이 테스트는 callback 만 부른다")

    override fun link(
        userId: UUID,
        provider: SocialLoginProviderId,
        providerUserId: String,
        email: String?,
        emailVerified: Boolean,
    ): UserIdentity = error("경쟁에서 진 쪽은 신원을 연결하면 안 된다 — 이겼다면 상대가 이미 연결했다")

    override fun deleteByUserAndProvider(
        userId: UUID,
        provider: SocialLoginProviderId,
    ): Boolean = error("이 테스트는 callback 만 부른다")
}

/**
 * `linkCallback` 자기 경쟁 테스트 전용(PR #54 리뷰 후속 A) — 사전 검사 두 곳
 * (`findByProviderIdentity`·`findByUserAndProvider`)은 첫 호출에서 "아직 없음"을 돌려줘 흐름이
 * `link()` 까지 들어오게 하고, `link()` 는 항상 [ConflictException] 을 던져 DB 유일성 제약
 * 위반을 흉내 낸다. 그 뒤 `SocialLoginService` 가 부르는 재조회는 두 번째 호출부터
 * [winnerAfterRace]·[ownerAfterRace] 를 돌려준다 — 갈래 넷을 이 하나의 대역으로 다 흉내 낸다:
 * 자기 경쟁([winnerAfterRace] 가 호출자 소유), 다른 사용자 경쟁([winnerAfterRace] 가 다른
 * 사용자 소유), 같은 사용자의 다른 신원 경쟁([winnerAfterRace] 는 `null`, [ownerAfterRace] 는
 * 존재), 설명되지 않는 경쟁(둘 다 `null` — 원래 예외를 그대로 올려야 한다).
 */
private class RacingLinkIdentityRepository(
    private val winnerAfterRace: UserIdentity?,
    private val ownerAfterRace: UserIdentity? = null,
    private val linkConflictMessage: String = "simulated-unique-violation",
) : UserIdentityRepository {
    var findByProviderIdentityCallCount = 0
        private set
    var findByUserAndProviderCallCount = 0
        private set
    var linkCalled = false
        private set

    override fun findByProviderIdentity(
        provider: SocialLoginProviderId,
        providerUserId: String,
    ): UserIdentity? {
        findByProviderIdentityCallCount++
        return if (findByProviderIdentityCallCount == 1) null else winnerAfterRace
    }

    override fun findByUserAndProvider(
        userId: UUID,
        provider: SocialLoginProviderId,
    ): UserIdentity? {
        findByUserAndProviderCallCount++
        return if (findByUserAndProviderCallCount == 1) null else ownerAfterRace
    }

    override fun findAllByUser(userId: UUID): List<UserIdentity> = error("이 테스트는 linkCallback 만 부른다")

    override fun link(
        userId: UUID,
        provider: SocialLoginProviderId,
        providerUserId: String,
        email: String?,
        emailVerified: Boolean,
    ): UserIdentity {
        linkCalled = true
        throw ConflictException(linkConflictMessage)
    }

    override fun deleteByUserAndProvider(
        userId: UUID,
        provider: SocialLoginProviderId,
    ): Boolean = error("이 테스트는 linkCallback 만 부른다")
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
