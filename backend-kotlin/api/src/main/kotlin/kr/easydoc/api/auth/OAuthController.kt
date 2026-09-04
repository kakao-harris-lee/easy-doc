package kr.easydoc.api.auth

import kr.easydoc.application.auth.SocialLoginProviderId
import kr.easydoc.application.auth.SocialLoginService
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * `/auth/oauth/{provider}/start` · `/auth/oauth/{provider}/callback` — 소셜 로그인
 * (backlog §1.4 P0-1). `AuthController` 와 별도 클래스인 이유는 그 클래스 KDoc 이 아니라
 * 여기 있다: 이메일/비밀번호 인증과 겹치는 것은 "액세스 토큰을 발급한다"뿐이고 나머지
 * (제공자 왕복 시작·콜백)는 이 컨트롤러만의 책임이다.
 */
@RestController
@RequestMapping("/auth/oauth")
class OAuthController(private val socialLogin: SocialLoginService) {
    /** 제공자 인가 URL을 만든다. 인증 불필요(계약 `security: []`). */
    @PostMapping("/{provider}/start", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun start(
        @PathVariable provider: SocialLoginProviderId,
        @RequestBody request: OAuthStartRequest,
    ): ResponseEntity<OAuthStartResponse> {
        val started = socialLogin.start(provider, request.redirectUri)
        return private(HttpStatus.OK).body(OAuthStartResponse(started.authorizationUrl, started.state))
    }

    /** 인가 코드를 액세스 토큰으로 바꾼다. 응답 모양은 `login` 성공 응답과 같다. */
    @PostMapping("/{provider}/callback", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun callback(
        @PathVariable provider: SocialLoginProviderId,
        @RequestBody request: OAuthCallbackRequest,
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

    /** `AuthController.private` 와 같은 하한선 헤더. 두 컨트롤러가 같은 상수를 각자 갖는다 —
     * 전역 필터([kr.easydoc.api.config.PrivateResponseHeadersConfig])가 이미 싣지만, 계약
     * 테스트가 컨트롤러 응답 자체에서 재기도 하므로 `AuthController` 와 같은 방식을 따른다. */
    private fun private(status: HttpStatus): ResponseEntity.BodyBuilder =
        ResponseEntity
            .status(status)
            .contentType(MediaType.APPLICATION_JSON)
            .header(CACHE_CONTROL, NO_STORE)
            .header(X_CONTENT_TYPE_OPTIONS, NOSNIFF)

    private companion object {
        const val BEARER_TOKEN_TYPE = "bearer"
        const val CACHE_CONTROL = "Cache-Control"
        const val NO_STORE = "no-store"
        const val X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options"
        const val NOSNIFF = "nosniff"
    }
}
