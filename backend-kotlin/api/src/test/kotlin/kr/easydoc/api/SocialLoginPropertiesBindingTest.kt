package kr.easydoc.api

import kr.easydoc.infrastructure.auth.GoogleOAuthProperties
import kr.easydoc.infrastructure.auth.KakaoOAuthProperties
import kr.easydoc.infrastructure.auth.NaverOAuthProperties
import kr.easydoc.infrastructure.auth.OAuthProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 소셜 로그인 공급자 설정 — 비밀값과 redirect_uri 기본값. */
class SocialLoginPropertiesBindingTest {
    @Test
    @DisplayName("소셜 로그인 설정이 기본값과 다른 값을 싣는다 — backlog §1.4 P0-1")
    fun `소셜 로그인 설정이 기본값과 다른 값을 싣는다`() {
        val oauth =
            bindProperties(
                "easydoc.oauth",
                OAuthProperties::class.java,
                mapOf("easydoc.oauth.state-ttl-minutes" to "3"),
            )
        assertThat(oauth.stateTtlMinutes).isEqualTo(3)

        val google =
            bindProperties(
                "easydoc.oauth.google",
                GoogleOAuthProperties::class.java,
                mapOf(
                    "easydoc.oauth.google.client-id" to "test-client-id",
                    "easydoc.oauth.google.client-secret" to BINDING_SECRET_VALUE,
                    "easydoc.oauth.google.redirect-uris[0]" to "https://example.test/auth/google/callback",
                    "easydoc.oauth.google.timeout-ms" to "5000",
                ),
            )
        assertThat(google.clientId).isEqualTo("test-client-id")
        assertThat(google.clientSecret.reveal()).isEqualTo(BINDING_SECRET_VALUE)
        assertThat(google.redirectUris).containsExactly("https://example.test/auth/google/callback")
        assertThat(google.timeoutMs).isEqualTo(5000L)
    }

    @Test
    @DisplayName(
        "redirect-uris 를 설정하지 않으면 로그인·연결 콜백 기본값 둘 다 실린다 " +
            "(리뷰 후속 조치 — link/callback 을 빠뜨리면 oauthLinkStart 가 기본 구성에서 422 다)",
    )
    fun `구글 redirect_uri 기본값이 로그인과 연결 콜백을 모두 담는다`() {
        val google =
            bindProperties(
                "easydoc.oauth.google",
                GoogleOAuthProperties::class.java,
                mapOf(
                    "easydoc.oauth.google.client-id" to "test-client-id",
                    "easydoc.oauth.google.client-secret" to BINDING_SECRET_VALUE,
                ),
            )
        assertThat(google.redirectUris)
            .containsExactly(
                GoogleOAuthProperties.DEFAULT_LOGIN_REDIRECT_URI,
                GoogleOAuthProperties.DEFAULT_LINK_REDIRECT_URI,
            )
    }

    @Test
    @DisplayName("카카오 소셜 로그인 설정이 기본값과 다른 값을 싣는다 — backlog §1.4, 계약 2.13.0")
    fun `카카오 설정이 기본값과 다른 값을 싣는다`() {
        val kakao =
            bindProperties(
                "easydoc.oauth.kakao",
                KakaoOAuthProperties::class.java,
                mapOf(
                    "easydoc.oauth.kakao.client-id" to "test-kakao-client-id",
                    "easydoc.oauth.kakao.client-secret" to BINDING_SECRET_VALUE,
                    "easydoc.oauth.kakao.redirect-uris[0]" to "https://example.test/auth/kakao/callback",
                    "easydoc.oauth.kakao.timeout-ms" to "6000",
                ),
            )
        assertThat(kakao.clientId).isEqualTo("test-kakao-client-id")
        assertThat(kakao.clientSecret.reveal()).isEqualTo(BINDING_SECRET_VALUE)
        assertThat(kakao.redirectUris).containsExactly("https://example.test/auth/kakao/callback")
        assertThat(kakao.timeoutMs).isEqualTo(6000L)
    }

    @Test
    @DisplayName("카카오 redirect-uris 를 설정하지 않으면 로그인·연결 콜백 기본값 둘 다 실린다 — 구글과 같은 방침")
    fun `카카오 redirect_uri 기본값이 로그인과 연결 콜백을 모두 담는다`() {
        val kakao =
            bindProperties(
                "easydoc.oauth.kakao",
                KakaoOAuthProperties::class.java,
                mapOf(
                    "easydoc.oauth.kakao.client-id" to "test-kakao-client-id",
                    "easydoc.oauth.kakao.client-secret" to BINDING_SECRET_VALUE,
                ),
            )
        assertThat(kakao.redirectUris)
            .containsExactly(
                KakaoOAuthProperties.DEFAULT_LOGIN_REDIRECT_URI,
                KakaoOAuthProperties.DEFAULT_LINK_REDIRECT_URI,
            )
    }

    @Test
    @DisplayName("네이버 소셜 로그인 설정이 기본값과 다른 값을 싣는다 — backlog §1.4, 계약 2.15.0")
    fun `네이버 설정이 기본값과 다른 값을 싣는다`() {
        val naver =
            bindProperties(
                "easydoc.oauth.naver",
                NaverOAuthProperties::class.java,
                mapOf(
                    "easydoc.oauth.naver.client-id" to "test-naver-client-id",
                    "easydoc.oauth.naver.client-secret" to BINDING_SECRET_VALUE,
                    "easydoc.oauth.naver.redirect-uris[0]" to "https://example.test/auth/naver/callback",
                    "easydoc.oauth.naver.timeout-ms" to "7000",
                ),
            )
        assertThat(naver.clientId).isEqualTo("test-naver-client-id")
        assertThat(naver.clientSecret.reveal()).isEqualTo(BINDING_SECRET_VALUE)
        assertThat(naver.redirectUris).containsExactly("https://example.test/auth/naver/callback")
        assertThat(naver.timeoutMs).isEqualTo(7000L)
    }

    @Test
    @DisplayName("네이버 redirect-uris 를 설정하지 않으면 로그인·연결 콜백 기본값 둘 다 실린다 — 구글·카카오와 같은 방침")
    fun `네이버 redirect_uri 기본값이 로그인과 연결 콜백을 모두 담는다`() {
        val naver =
            bindProperties(
                "easydoc.oauth.naver",
                NaverOAuthProperties::class.java,
                mapOf(
                    "easydoc.oauth.naver.client-id" to "test-naver-client-id",
                    "easydoc.oauth.naver.client-secret" to BINDING_SECRET_VALUE,
                ),
            )
        assertThat(naver.redirectUris)
            .containsExactly(
                NaverOAuthProperties.DEFAULT_LOGIN_REDIRECT_URI,
                NaverOAuthProperties.DEFAULT_LINK_REDIRECT_URI,
            )
    }
}
