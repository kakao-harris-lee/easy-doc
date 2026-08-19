package kr.easydoc.api.auth

import kr.easydoc.application.auth.AuthService
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * `/auth/signup` · `/auth/login` · `/auth/me`.
 *
 * ## 라우터에 비즈니스 판단이 없다
 *
 * 정규화·길이·형식 판정, 중복 이메일 처리, 재해시, 토큰 발급은 전부
 * [AuthService] 가 한다(`CLAUDE.md` 아키텍처 규칙 3). 여기서 하는 일은 HTTP 표현으로
 * 바꾸는 것뿐이다.
 *
 * ## 캐시 금지 헤더를 `ResponseEntity` 에 직접 싣는다
 *
 * 전역 필터·밸브가 모든 응답에 같은 헤더를 붙이지만(계약 `x-global-response-headers`),
 * 계약이 고위험 10곳을 **하한선**으로 남겼고 이 셋이 그중 셋이다 — 이메일이 실리는 응답
 * 둘과 응답 본문 자체가 Bearer 토큰인 것 하나. 전역 장치가 빠지거나 체인 순서가
 * 어긋났을 때 **여기서 먼저 깨져야 한다**(리더 판정 부수 결정 1).
 *
 * 두 층이 같은 헤더를 쓰므로 **`add` 가 아니라 `set` 이어야 한다**. `ResponseEntity` 의
 * `header(...)` 는 값을 덧붙이지만, 전역 필터가 `set` 을 쓰므로 최종 응답에는 하나만
 * 남는다 — 그 사실을 계약 테스트가 **개수까지** 단언한다.
 */
@RestController
@RequestMapping("/auth")
class AuthController(private val authService: AuthService) {
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

    /**
     * 토큰이 가리키는 사용자.
     *
     * [AuthenticatedUser] 는 [AuthenticationInterceptor] 가 검증한 결과다. 토큰은
     * 유효한데 계정이 지워졌으면 **인증 경계**([AuthService.authenticate])가 이미 401 로
     * 끊었으므로 여기까지 오지 않는다. [AuthService.readUser] 의 같은 401 갈래는 그대로
     * 두지만, 그 갈래가 이 경로를 지키는 유일한 장치였을 때가 X-1 의 자리였다 — 나머지
     * 보호 경로들이 그 확인을 지나지 않았다.
     */
    @GetMapping("/me")
    fun me(user: AuthenticatedUser): ResponseEntity<UserResponse> =
        private(HttpStatus.OK).body(UserResponse.of(authService.readUser(user.id)))

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
