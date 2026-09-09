package kr.easydoc.api.auth

import kr.easydoc.application.account.DeleteAccountService
import kr.easydoc.application.auth.AuthService
import kr.easydoc.application.auth.EmailVerificationService
import kr.easydoc.application.auth.PasswordResetService
import kr.easydoc.application.auth.PasswordService
import kr.easydoc.application.auth.SocialLoginService
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * `/auth/signup` · `/auth/login` · `/auth/me` · `/auth/email-verification/{request,confirm}` ·
 * `/auth/password` · `/auth/password-reset/{request,confirm}` · `/auth/me/deletion`.
 *
 * 조립 지점(생성자)의 매개변수 수는 이 컨트롤러가 다루는 유스케이스의 수다 — `AuthConfiguration`
 * 과 같은 근거로 늘어난다.
 */
@RestController
@RequestMapping("/auth")
@Suppress("LongParameterList")
class AuthController(
    private val authService: AuthService,
    private val emailVerification: EmailVerificationService,
    private val socialLogin: SocialLoginService,
    private val passwordService: PasswordService,
    private val passwordResetService: PasswordResetService,
    private val deleteAccountService: DeleteAccountService,
) {
    /** 계정과 기본 작업 공간을 만든다. **201** 이다 — 자원이 실제로 생겼다. */
    @PostMapping("/signup", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun signup(
        @RequestBody request: SignupRequest,
    ): ResponseEntity<UserResponse> {
        val user = authService.signup(request.email, request.password)
        return private(HttpStatus.CREATED).body(UserResponse.of(user))
    }

    /** 자격증명을 확인하고 액세스 토큰을 발급한다. */
    @PostMapping("/login", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun login(
        @RequestBody request: LoginRequest,
    ): ResponseEntity<TokenResponse> {
        val issued = authService.login(request.email, request.password)
        return private(HttpStatus.OK).body(
            TokenResponse(
                accessToken = issued.token,
                tokenType = BEARER_TOKEN_TYPE,
                expiresIn = issued.expiresInSeconds,
            ),
        )
    }

    /** 토큰이 가리키는 사용자. `identities` 는 연결된 소셜 신원 목록이다(2.10.0, backlog §1.4). */
    @GetMapping("/me")
    fun me(user: AuthenticatedUser): ResponseEntity<UserResponse> =
        private(HttpStatus.OK).body(
            UserResponse.of(authService.readUser(user.id), socialLogin.identitiesOf(user.id)),
        )

    /** 인증 코드를 (재)발급하고 메일로 보낸다. 이미 인증됐으면 409, 쿨다운 안이면 429. */
    @PostMapping("/email-verification/request")
    fun requestEmailVerification(user: AuthenticatedUser): ResponseEntity<Void> {
        emailVerification.requestVerification(user.id)
        return ResponseEntity.noContent().build()
    }

    /** 발급된 코드를 확인한다. */
    @PostMapping("/email-verification/confirm", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun confirmEmailVerification(
        user: AuthenticatedUser,
        @RequestBody request: ConfirmEmailVerificationRequest,
    ): ResponseEntity<Void> {
        emailVerification.confirm(user.id, request.code)
        return ResponseEntity.noContent().build()
    }

    /**
     * 비밀번호 없는(소셜 전용) 계정에 비밀번호를 만든다. 이미 있으면 409(`PasswordService`가
     * 재설정 이용을 안내한다).
     */
    @PostMapping("/password", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun setPassword(
        user: AuthenticatedUser,
        @RequestBody request: SetPasswordRequest,
    ): ResponseEntity<Void> {
        passwordService.set(user.id, request.newPassword)
        return ResponseEntity.noContent().build()
    }

    /**
     * 비밀번호 재설정 코드를 이메일로 보낸다. **이메일 존재 여부·쿨다운과 무관하게 항상
     * 202다** — `PasswordResetService.request`가 두 신호 모두 삼킨다(존재 은닉).
     */
    @PostMapping("/password-reset/request", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun requestPasswordReset(
        @RequestBody request: PasswordResetRequest,
    ): ResponseEntity<Void> {
        passwordResetService.request(request.email)
        return ResponseEntity.status(HttpStatus.ACCEPTED).build()
    }

    /**
     * 재설정 코드를 확인하고 비밀번호를 바꾼 뒤 로그인과 같은 토큰을 발급한다. 코드가
     * 오답·만료·시도 소진이거나 이메일이 존재하지 않으면 401 하나로 묶는다(사유 은닉).
     */
    @PostMapping("/password-reset/confirm", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun confirmPasswordReset(
        @RequestBody request: PasswordResetConfirmRequest,
    ): ResponseEntity<TokenResponse> {
        val issued = passwordResetService.confirm(request.email, request.code, request.newPassword)
        return private(HttpStatus.OK).body(
            TokenResponse(
                accessToken = issued.token,
                tokenType = BEARER_TOKEN_TYPE,
                expiresIn = issued.expiresInSeconds,
            ),
        )
    }

    /**
     * 계정을 즉시 파기한다 — 유예·복구 기간이 없다(계획
     * `docs/plans/2026-09-09-account-deletion.md` §2 결정 3). 재확인 두 겹(비밀번호·확인
     * 문구)이 틀리면 422, 관리자 계정이면 409다. 성공하면 이 토큰을 포함한 모든 인증
     * 수단이 같은 트랜잭션에서 사라지므로 이후 요청은 자연히 401이다 — 별도 로그아웃
     * 처리가 필요 없다.
     */
    @PostMapping("/me/deletion", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun deleteAccount(
        user: AuthenticatedUser,
        @RequestBody request: DeleteAccountRequest,
    ): ResponseEntity<Void> {
        deleteAccountService.deleteAccount(user.id, request.password, request.confirmation)
        return ResponseEntity.noContent().build()
    }

    /** 고위험 응답에 붙는 하한선 헤더. 값의 정본은 계약 `components/headers` 의 각 컴포넌트다. */
    private fun private(status: HttpStatus): ResponseEntity.BodyBuilder =
        ResponseEntity
            .status(status)
            .contentType(MediaType.APPLICATION_JSON)
            .header(CACHE_CONTROL, NO_STORE)
            .header(X_CONTENT_TYPE_OPTIONS, NOSNIFF)

    private companion object {
        /** 계약 `TokenResponse.properties.token_type.const`. */
        const val BEARER_TOKEN_TYPE = "bearer"

        const val CACHE_CONTROL = "Cache-Control"
        const val NO_STORE = "no-store"
        const val X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options"
        const val NOSNIFF = "nosniff"
    }
}
