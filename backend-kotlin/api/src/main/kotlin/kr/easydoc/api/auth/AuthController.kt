package kr.easydoc.api.auth

import kr.easydoc.application.auth.AuthService
import kr.easydoc.application.auth.EmailVerificationService
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** `/auth/signup` · `/auth/login` · `/auth/me` · `/auth/email-verification/{request,confirm}`. */
@RestController
@RequestMapping("/auth")
class AuthController(
    private val authService: AuthService,
    private val emailVerification: EmailVerificationService,
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

    /** 토큰이 가리키는 사용자. */
    @GetMapping("/me")
    fun me(user: AuthenticatedUser): ResponseEntity<UserResponse> =
        private(HttpStatus.OK).body(UserResponse.of(authService.readUser(user.id)))

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
