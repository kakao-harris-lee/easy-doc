package kr.easydoc.infrastructure.auth.naver

import kr.easydoc.application.auth.SocialIdentity
import kr.easydoc.application.auth.SocialLoginProvider
import kr.easydoc.core.exceptions.ExternalServiceUnavailableException
import kr.easydoc.core.exceptions.InvalidCredentialsException
import kr.easydoc.core.security.Secret
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URLEncoder
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * 네이버 OAuth 2.0 Authorization Code 어댑터 — `SocialLoginProvider` 의 네이버 구현
 * (backlog §1.4, 권고 순서 "구글 → 카카오 → 네이버"의 마지막 제공자, 계약 2.15.0).
 *
 * **경로가 하나뿐이다 — OIDC 가 없다.** [KakaoSocialLoginProvider][kr.easydoc.infrastructure.auth.kakao.KakaoSocialLoginProvider]
 * 와 달리 인가 코드 교환 응답에 ID 토큰이 없다(네이버는 OIDC 를 지원하지 않는다 —
 * `x-social-login.providers.x-note`). 그래서 이 어댑터는 항상 액세스 토큰으로
 * `GET https://openapi.naver.com/v1/nid/me` 를 불러 `response.id`·`response.email` 을
 * 읽는다. JWKS 서명 검증이 필요 없어 [kr.easydoc.infrastructure.auth.oidc.OidcJwksVerifier]
 * 를 쓰지 않는다.
 *
 * **`nonce` 를 인가 URL 에 싣지 않고 검증도 하지 않는다.** 네이버 프로토콜이 그 파라미터를
 * 지원하지 않는다(2차 출처 조사, backlog §1.4). [SocialLoginService][kr.easydoc.application.auth.SocialLoginService]
 * 는 다른 제공자와 같은 방식으로 `nonce` 를 발급·저장하지만(state 저장소 스키마와 호출
 * 규약을 제공자마다 가르지 않기 위해서다) 이 어댑터는 그 값을 받기만 하고 쓰지 않는다 —
 * ID 토큰이 없으니 리플레이 방지 대조 자체가 성립하지 않는다.
 *
 * **이메일은 있어도 항상 미검증이다(2026-09-05 결정).** 네이버 응답에는 이메일 검증
 * 여부를 나타내는 필드 자체가 없다(`email_verified` 개념이 없다, 2차 출처 조사 —
 * 공식 문서 `developers.naver.com` 은 이 조사 환경의 WebFetch 에서 접근이 차단됐다).
 * 검증되지 않은 값을 검증됨으로 잘못 표시하는 쪽보다 안전한 쪽을 택해
 * [SocialIdentity.emailVerified] 를 항상 `false` 로 낸다 — 그 결과 기존
 * `SocialLoginService.requireVerifiedEmail` 규칙이 그대로 적용돼, 네이버 신원 단독으로는
 * 새 계정을 만들 수 없고(이메일이 있어도 422 `email_required`) 이미 인증된 계정에
 * 명시적으로 연결하거나(`linkCallback`, 이메일 검증을 요구하지 않는다) 이미 연결된
 * 신원으로 로그인하는 경로만 연다.
 *
 * 엔드포인트 URL 을 설정으로 열지 않는 이유는 `GoogleSocialLoginProvider`·
 * `KakaoSocialLoginProvider` 와 같다.
 *
 * KDoc의 엔드포인트 URL·응답 모양·이메일 검증 부재는 **2차 출처로만 확인됐다** —
 * 공식 문서가 이 조사 환경에서 접근 차단됐다(backlog §1.4).
 *
 * **`TooManyFunctions` 억제 이유**: 토큰 교환·userinfo 경로마다 이름 붙은 작은 함수가
 * 11개 상한을 넘긴다 — `KakaoSocialLoginProvider` 와 같은 "억제는 이 클래스 하나" 원칙
 * (그 클래스 KDoc의 근거를 그대로 옮긴다).
 */
@Suppress("TooManyFunctions")
class NaverSocialLoginProvider(private val settings: NaverOAuthSettings) : SocialLoginProvider {
    private val log = LoggerFactory.getLogger(NaverSocialLoginProvider::class.java)
    private val json = JsonMapper.builder().build()

    private val client: RestClient =
        RestClient
            .builder()
            .requestFactory(
                JdkClientHttpRequestFactory(
                    HttpClient.newBuilder().connectTimeout(settings.connectTimeout).build(),
                ).apply { setReadTimeout(settings.readTimeout) },
            ).build()

    override fun supportsRedirectUri(redirectUri: String): Boolean = redirectUri in settings.redirectUriAllowlist

    /**
     * `nonce` 는 매개변수로 받지만 쓰지 않는다 — 네이버는 그 파라미터를 지원하지 않는다
     * (클래스 KDoc). 다른 제공자와 시그니처를 맞추기 위해 받는다.
     */
    override fun authorizationUrl(
        state: String,
        nonce: String,
        redirectUri: String,
    ): String {
        val query =
            listOf(
                "response_type" to "code",
                "client_id" to settings.clientId,
                "redirect_uri" to redirectUri,
                "state" to state,
            ).joinToString("&") { (key, value) -> "$key=${urlEncode(value)}" }
        return "${settings.authorizationEndpoint}?$query"
    }

    /** `nonce` 는 받기만 하고 쓰지 않는다(클래스 KDoc). */
    override fun exchange(
        code: String,
        redirectUri: String,
        nonce: String,
    ): SocialIdentity = identityFromUserInfo(accessTokenFrom(tokenResponseNode(code, redirectUri)))

    // ------------------------------------------------------------------ ① 토큰 교환

    private fun tokenResponseNode(
        code: String,
        redirectUri: String,
    ): JsonNode = parseJson(tokenResponseBody(code, redirectUri))

    private fun tokenResponseBody(
        code: String,
        redirectUri: String,
    ): String =
        try {
            rawTokenBody(code, redirectUri)
        } catch (_: HttpClientErrorException) {
            // 네이버는 잘못된/만료된/재사용된 코드에 400 을 낸다 — Google·Kakao 와 같은 갈래.
            throw InvalidCredentialsException(CODE_REJECTED_MESSAGE)
        } catch (exc: RestClientException) {
            throw unreachable(exc::class.java.simpleName)
        }

    private fun rawTokenBody(
        code: String,
        redirectUri: String,
    ): String {
        val form =
            LinkedMultiValueMap<String, String>().apply {
                add("grant_type", "authorization_code")
                add("client_id", settings.clientId)
                add("client_secret", settings.clientSecret.reveal())
                add("redirect_uri", redirectUri)
                add("code", code)
            }
        return client
            .post()
            .uri(settings.tokenEndpoint)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(ByteArray::class.java)
            ?.toString(StandardCharsets.UTF_8)
            ?: throw unreachable("응답 본문 없음")
    }

    private fun parseJson(rawJson: String): JsonNode =
        try {
            json.readTree(rawJson)
        } catch (exc: JacksonException) {
            throw unreachable("응답 형식 오류: ${exc::class.java.simpleName}")
        }

    private fun accessTokenFrom(node: JsonNode): String =
        node.path("access_token").stringValue("").ifEmpty { throw unreachable("access_token 없음") }

    // ------------------------------------------------------------------ ② userinfo 경로(유일한 경로)

    /**
     * `GET https://openapi.naver.com/v1/nid/me` 를 불러 `response.id`·`response.email` 을
     * 읽는다. 이메일 필드 자체가 없으면(제공 정보 미동의·비공개) `email` 은 `null` 이다.
     * `emailVerified` 는 항상 `false` 다(클래스 KDoc).
     */
    private fun identityFromUserInfo(accessToken: String): SocialIdentity {
        val node = parseJson(userInfoResponseBody(accessToken))
        val response = node.path("response")
        val id = response.path("id").stringValue("").ifEmpty { throw unreachable("response.id 없음") }
        val email = response.path("email").stringValue("").ifEmpty { null }
        return SocialIdentity(
            providerUserId = id,
            email = email,
            emailVerified = false,
        )
    }

    private fun userInfoResponseBody(accessToken: String): String =
        try {
            client
                .get()
                .uri(settings.userInfoEndpoint)
                .header("Authorization", "Bearer $accessToken")
                .retrieve()
                .body(ByteArray::class.java)
                ?.toString(StandardCharsets.UTF_8)
                ?: throw unreachable("응답 본문 없음")
        } catch (_: HttpClientErrorException) {
            throw InvalidCredentialsException(CODE_REJECTED_MESSAGE)
        } catch (exc: RestClientException) {
            throw unreachable(exc::class.java.simpleName)
        }

    /** `GoogleSocialLoginProvider.unreachable` 과 같은 계약 — 그 함수 KDoc. */
    private fun unreachable(detail: String): ExternalServiceUnavailableException {
        log.warn("네이버 소셜 로그인 제공자에 닿지 못했다: {}", detail)
        return ExternalServiceUnavailableException(PROVIDER_UNREACHABLE_MESSAGE)
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private companion object {
        /** 계약 401 예시와 같은 값(재사용 — `x-auth` 401 두 갈래 불변식을 지킨다). */
        const val CODE_REJECTED_MESSAGE = "이메일 또는 비밀번호가 올바르지 않습니다"

        /** 계약 `components/responses/BadGateway` 의 `provider_unreachable` 예시와 같은 값. */
        const val PROVIDER_UNREACHABLE_MESSAGE = "네이버에 연결하지 못했습니다"
    }
}

/** [NaverSocialLoginProvider] 설정. 엔드포인트 기본값은 네이버 프로토콜 불변식 — 테스트만 바꾼다. */
data class NaverOAuthSettings(
    val clientId: String,
    val clientSecret: Secret,
    val redirectUriAllowlist: Set<String>,
    val authorizationEndpoint: String = NAVER_AUTHORIZATION_ENDPOINT,
    val tokenEndpoint: String = NAVER_TOKEN_ENDPOINT,
    val userInfoEndpoint: String = NAVER_USERINFO_ENDPOINT,
    val connectTimeout: Duration = NAVER_CONNECT_TIMEOUT,
    val readTimeout: Duration = NAVER_READ_TIMEOUT,
)

/**
 * 네이버 개발자센터 확인값 — 2차 출처로만 확인됐다(공식 문서 `developers.naver.com` 이
 * 이 조사 환경의 WebFetch 에서 접근 차단됨, backlog §1.4). 2026-09-05.
 */
const val NAVER_AUTHORIZATION_ENDPOINT: String = "https://nid.naver.com/oauth2.0/authorize"
const val NAVER_TOKEN_ENDPOINT: String = "https://nid.naver.com/oauth2.0/token"
const val NAVER_USERINFO_ENDPOINT: String = "https://openapi.naver.com/v1/nid/me"

val NAVER_CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
val NAVER_READ_TIMEOUT: Duration = Duration.ofSeconds(15)
