package kr.easydoc.api.auth

import jakarta.validation.Valid
import kr.easydoc.api.config.privateResponse
import kr.easydoc.application.auth.SocialLoginProviderId
import kr.easydoc.application.auth.SocialLoginService
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** `/auth/oauth` 소셜 로그인과 명시적 계정 연결 엔드포인트. */
@RestController
@RequestMapping("/auth/oauth")
class OAuthController(private val socialLogin: SocialLoginService) {
    /** 제공자 인가 URL을 만든다. 인증 불필요(계약 `security: []`). */
    @PostMapping("/{provider}/start", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun start(
        @PathVariable provider: SocialLoginProviderId,
        @Valid @RequestBody request: OAuthStartRequest,
    ): ResponseEntity<OAuthStartResponse> {
        val started = socialLogin.start(provider, request.redirectUri)
        return private(HttpStatus.OK).body(OAuthStartResponse(started.authorizationUrl, started.state))
    }

    /** 인가 코드를 액세스 토큰으로 바꾼다. 응답 모양은 `login` 성공 응답과 같다. */
    @PostMapping("/{provider}/callback", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun callback(
        @PathVariable provider: SocialLoginProviderId,
        @Valid @RequestBody request: OAuthCallbackRequest,
    ): ResponseEntity<TokenResponse> {
        val issued = socialLogin.callback(provider, request.code, request.state, request.redirectUri)
        return private(HttpStatus.OK).body(
            TokenResponse(
                accessToken = issued.token,
                tokenType = BEARER_TOKEN_TYPE,
                expiresIn = issued.expiresInSeconds,
            ),
        )
    }

    /** 인증된 사용자의 계정에 연결할 소셜 인가 URL을 만든다. */
    @PostMapping("/{provider}/link/start", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun linkStart(
        user: AuthenticatedUser,
        @PathVariable provider: SocialLoginProviderId,
        @Valid @RequestBody request: OAuthStartRequest,
    ): ResponseEntity<OAuthStartResponse> {
        val started = socialLogin.linkStart(user.id, provider, request.redirectUri)
        return private(HttpStatus.OK).body(OAuthStartResponse(started.authorizationUrl, started.state))
    }

    /**
     * 인가 코드를 검증하고 소셜 신원을 [user] 계정에 잇는다. 새 토큰을 발급하지 않는다 —
     * 호출자는 이미 인증돼 있다(계약 `oauthLinkCallback` 204).
     */
    @PostMapping("/{provider}/link/callback", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun linkCallback(
        user: AuthenticatedUser,
        @PathVariable provider: SocialLoginProviderId,
        @Valid @RequestBody request: OAuthCallbackRequest,
    ): ResponseEntity<Void> {
        socialLogin.linkCallback(user.id, provider, request.code, request.state, request.redirectUri)
        return private(HttpStatus.NO_CONTENT).build()
    }

    /** [user] 계정에서 [provider] 신원 연결을 끊는다. 연결이 없으면 404, 마지막 로그인 수단이면 409다. */
    @DeleteMapping("/{provider}/link")
    fun unlink(
        user: AuthenticatedUser,
        @PathVariable provider: SocialLoginProviderId,
    ): ResponseEntity<Void> {
        socialLogin.unlink(user.id, provider)
        return private(HttpStatus.NO_CONTENT).build()
    }

    /** 전역 필터와 별개로 컨트롤러 응답에도 보안 헤더를 싣는다. */
    private fun private(status: HttpStatus): ResponseEntity.BodyBuilder =
        ResponseEntity
            .status(status)
            .contentType(MediaType.APPLICATION_JSON)
            .privateResponse()

    private companion object {
        const val BEARER_TOKEN_TYPE = "bearer"
    }
}
