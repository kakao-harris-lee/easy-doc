package kr.easydoc.api

import kr.easydoc.api.config.PrivateResponseHeadersConfig
import kr.easydoc.api.support.AuthSliceBeans
import kr.easydoc.api.support.ContractSpec
import kr.easydoc.api.support.FakeGoogleSocialLoginProvider
import kr.easydoc.api.support.InMemoryUserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets

/**
 * `/auth/oauth/{provider}/start` · `/auth/oauth/{provider}/callback` 의 계약 —
 * backlog §1.4 P0-1. 실제 Google 을 부르지 않는다 —
 * `AuthSliceBeans.FakeGoogleSocialLoginProvider` 가 `code` 문자열
 * (`sub|email|verified`, 특수값 `reject`·`unreachable`)로 시나리오를 흉내 낸다.
 *
 * "제공자 미설정" 422 는 여기서 재지 않는다 — 이 슬라이스는 항상 google 이 등록된
 * 배선이다. 그 경로는 `AuthEndpointReachTest`(실물 설정, 기본값에 키가 없다)가 진다.
 */
@WebMvcTest
@Import(PrivateResponseHeadersConfig::class, AuthSliceBeans::class)
class OAuthContractTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var fakeProvider: FakeGoogleSocialLoginProvider

    @Autowired
    private lateinit var users: InMemoryUserRepository

    private val json = ObjectMapper()

    @Test
    @DisplayName(
        "BadGateway(502) 는 oauthCallback·oauthLinkCallback·reconvertUnit 세 오퍼레이션만 선언한다 " +
            "(리뷰 후속 조치 LOW — x-retired-responses[0].reinstated_by 의 좁은 범위를 계약 테스트로 고정한다)",
    )
    fun `502 선언 범위가 정확히 셋이다`() {
        val declaring =
            ContractSpec.operations().filter { (path, method) ->
                BAD_GATEWAY.toString() in ContractSpec.responseStatuses(path, method)
            }

        assertThat(declaring.map { it.first }.toSet())
            .withFailMessage(
                "502 를 선언한 오퍼레이션이 %s 다 — 정본(x-retired-responses[0].reinstated_by)이 예고한 " +
                    "셋(oauthCallback·oauthLinkCallback·reconvertUnit)과 달라졌다. 새 오퍼레이션이 늘었다면 그 정본 항목도 함께 갱신하라.",
                declaring.map { (path, method) -> "${method.uppercase()} $path" },
            ).isEqualTo(setOf(CALLBACK_PATH, LINK_CALLBACK_PATH, RECONVERT_UNIT_PATH))

        declaring.forEach { (path, method) ->
            val ref = ContractSpec.map("paths", path, method, "responses", BAD_GATEWAY.toString())["\$ref"]
            assertThat(ref)
                .withFailMessage("%s %s 의 502 가 BadGateway 컴포넌트를 참조하지 않는다: %s", method, path, ref)
                .isEqualTo("#/components/responses/BadGateway")
        }
    }

    @Test
    @DisplayName("start 성공 — 계약의 성공 상태 · 사적 헤더 · 본문 키 집합이 정확히 required")
    fun `start 응답이 계약과 같다`() {
        val response = start(REDIRECT_URI)

        assertThat(response.status).isEqualTo(ContractSpec.successStatus(START_PATH, POST))
        assertPrivateHeaders(response)
        assertThat(bodyKeys(response)).isEqualTo(ContractSpec.schemaRequired("OAuthStartResponse"))

        val body = body(response)
        assertThat(body["authorization_url"] as String).contains("state=").contains("nonce=")
        assertThat((body["state"] as String)).isNotBlank()
    }

    @Test
    @DisplayName("지원하지 않는 provider(foo, 아는 provider 가 아니다) 는 422 배열이다 — 경로 값 자리 해석 실패는 스키마 층이다")
    fun `지원하지 않는 provider 는 422 다`() {
        val response = start(REDIRECT_URI, provider = "foo")

        assertDeclaredStatus(response, UNPROCESSABLE_CONTENT, START_PATH, POST)
        val detail = body(response)["detail"]
        assertThat(detail).isInstanceOf(List::class.java)
        val items = (detail as List<*>).map { it as Map<*, *> }
        assertThat(items.map { it["loc"] }).contains(listOf("path", "provider"))
    }

    // ------------------------------------------------------------------ 카카오(계약 2.13.0) — 4개 오퍼레이션 전부 허용

    @Test
    @DisplayName("카카오 start 도 200 이다 — google 과 같은 enum 안 값")
    fun `카카오 start 는 200 이다`() {
        val response = start(KAKAO_REDIRECT_URI, provider = "kakao")

        assertThat(response.status).isEqualTo(ContractSpec.successStatus(START_PATH, POST))
        val body = body(response)
        assertThat(body["authorization_url"] as String).contains("state=").contains("nonce=")
    }

    @Test
    @DisplayName("카카오 callback 도 로그인/가입에 쓸 수 있다")
    fun `카카오 callback 은 토큰을 발급한다`() {
        val state = startState(provider = "kakao", redirectUri = KAKAO_REDIRECT_URI)

        val response =
            callback(
                code = "kakao-sub-new-1|kakao-new@example.test|true",
                state = state,
                provider = "kakao",
                redirectUri = KAKAO_REDIRECT_URI,
            )

        assertThat(response.status).isEqualTo(ContractSpec.successStatus(CALLBACK_PATH, POST))
        assertThat(body(response)["token_type"]).isEqualTo("bearer")
    }

    @Test
    @DisplayName("카카오도 link/start·link/callback 을 통해 기존 계정에 연결된다")
    fun `카카오 연결 흐름도 동작한다`() {
        val email = uniqueEmail()
        signupAndVerify(email)
        val bearer = login(email)

        val linkState =
            body(
                postAuthorized(
                    "/auth/oauth/kakao/link/start",
                    bearer,
                    json.writeValueAsString(mapOf("redirect_uri" to KAKAO_REDIRECT_URI)),
                ),
            )["state"] as String

        val linkResponse =
            postAuthorized(
                "/auth/oauth/kakao/link/callback",
                bearer,
                json.writeValueAsString(
                    mapOf(
                        "code" to "kakao-link-sub|kakao-linked@example.test|true",
                        "state" to linkState,
                        "redirect_uri" to KAKAO_REDIRECT_URI,
                    ),
                ),
            )

        assertThat(linkResponse.status).isEqualTo(NO_CONTENT)
        val me = body(getAuthorized("/auth/me", bearer))
        assertThat((me["identities"] as List<*>).map { (it as Map<*, *>)["provider"] }).containsExactly("kakao")
    }

    // ------------------------------------------------------------------ 네이버(계약 2.15.0) — OIDC 없음, 이메일 항상 미검증

    @Test
    @DisplayName("네이버 start 도 200 이다 — google 과 같은 enum 안 값")
    fun `네이버 start 는 200 이다`() {
        val response = start(NAVER_REDIRECT_URI, provider = "naver")

        assertThat(response.status).isEqualTo(ContractSpec.successStatus(START_PATH, POST))
        val body = body(response)
        assertThat(body["authorization_url"] as String).contains("state=")
    }

    @Test
    @DisplayName(
        "네이버 신원의 최초 가입(callback)은 이메일이 있으면 200 이다 — 네이버는 email_verified " +
            "개념이 없어 이메일이 있어도 항상 미검증으로 낸다, 그래도 계정은 만들고 이메일 인증 " +
            "절차로 이어진다(readMe.email_verified=false, 2026-09-05 결정)",
    )
    fun `네이버 최초 가입은 미검증 계정으로 200 이다`() {
        val state = startState(provider = "naver", redirectUri = NAVER_REDIRECT_URI)

        val response =
            callback(
                code = "naver-sub-new-1|naver-new@example.test",
                state = state,
                provider = "naver",
                redirectUri = NAVER_REDIRECT_URI,
            )

        assertThat(response.status).isEqualTo(ContractSpec.successStatus(CALLBACK_PATH, POST))
        val bearer = body(response)["access_token"] as String
        val me = body(getAuthorized("/auth/me", bearer))
        assertThat(me["email_verified"]).isEqualTo(false)
    }

    @Test
    @DisplayName("네이버 신원에 이메일 자체가 없으면 여전히 422 다 — 네이버 전용 문구")
    fun `네이버 이메일 없으면 네이버 전용 문구로 422 다`() {
        val response =
            callback(
                code = "naver-sub-no-email|",
                state = startState(provider = "naver", redirectUri = NAVER_REDIRECT_URI),
                provider = "naver",
                redirectUri = NAVER_REDIRECT_URI,
            )

        assertDeclaredStatus(response, UNPROCESSABLE_CONTENT, CALLBACK_PATH, POST)
        assertThat(detailText(response))
            .isEqualTo("네이버 계정에 이메일이 없어 가입할 수 없습니다. 이메일로 가입하거나 다른 방법을 이용해 주세요")
    }

    @Test
    @DisplayName("네이버도 link/start·link/callback 을 통해 기존 계정에 연결된다 — 연결은 이메일 검증을 요구하지 않는다")
    fun `네이버 연결 흐름도 동작한다`() {
        val email = uniqueEmail()
        signupAndVerify(email)
        val bearer = login(email)

        val linkState =
            body(
                postAuthorized(
                    "/auth/oauth/naver/link/start",
                    bearer,
                    json.writeValueAsString(mapOf("redirect_uri" to NAVER_REDIRECT_URI)),
                ),
            )["state"] as String

        val linkResponse =
            postAuthorized(
                "/auth/oauth/naver/link/callback",
                bearer,
                json.writeValueAsString(
                    mapOf(
                        "code" to "naver-link-sub|naver-linked@example.test|true",
                        "state" to linkState,
                        "redirect_uri" to NAVER_REDIRECT_URI,
                    ),
                ),
            )

        assertThat(linkResponse.status).isEqualTo(NO_CONTENT)
        val me = body(getAuthorized("/auth/me", bearer))
        assertThat((me["identities"] as List<*>).map { (it as Map<*, *>)["provider"] }).containsExactly("naver")
    }

    @Test
    @DisplayName("연결된 네이버 신원으로 다시 콜백을 받으면 이메일 규칙과 무관하게 로그인이다")
    fun `연결된 네이버 신원은 로그인이다`() {
        val email = uniqueEmail()
        val userId = signupAndVerify(email)
        val bearer = login(email)
        val linkState =
            body(
                postAuthorized(
                    "/auth/oauth/naver/link/start",
                    bearer,
                    json.writeValueAsString(mapOf("redirect_uri" to NAVER_REDIRECT_URI)),
                ),
            )["state"] as String
        postAuthorized(
            "/auth/oauth/naver/link/callback",
            bearer,
            json.writeValueAsString(
                mapOf(
                    "code" to "naver-relogin-sub|naver-relogin@example.test|true",
                    "state" to linkState,
                    "redirect_uri" to NAVER_REDIRECT_URI,
                ),
            ),
        )

        val loginResponse =
            callback(
                code = "naver-relogin-sub|ignored@example.test|true",
                state = startState(provider = "naver", redirectUri = NAVER_REDIRECT_URI),
                provider = "naver",
                redirectUri = NAVER_REDIRECT_URI,
            )

        assertThat(userIdOf(loginResponse)).isEqualTo(userId)
    }

    @Test
    @DisplayName("허용 목록 밖 redirect_uri 는 422 다")
    fun `허용 목록 밖 redirect_uri 는 422 다`() {
        val response = start("https://evil.example.test/callback")

        assertDeclaredStatus(response, UNPROCESSABLE_CONTENT, START_PATH, POST)
        assertThat(detailText(response)).isEqualTo("허용되지 않은 redirect_uri 입니다")
    }

    @Test
    @DisplayName("start 의 빈 redirect_uri 는 422 배열이다 — minLength:1, 스키마 층")
    fun `start 의 빈 redirect_uri 는 422 배열이다`() {
        val response = start("")

        assertDeclaredStatus(response, UNPROCESSABLE_CONTENT, START_PATH, POST)
        assertBodyValidationArray(response, "redirect_uri")
    }

    @Test
    @DisplayName("새 신원 콜백 성공 — 계약의 성공 상태 · TokenResponse 키 집합")
    fun `콜백 성공 응답이 계약과 같다`() {
        val state = startState()

        val response = callback(code = "sub-new-1|new@example.test|true", state = state)

        assertThat(response.status).isEqualTo(ContractSpec.successStatus(CALLBACK_PATH, POST))
        assertPrivateHeaders(response)
        assertThat(bodyKeys(response)).isEqualTo(ContractSpec.schemaRequired("TokenResponse"))
        assertThat(body(response)["token_type"]).isEqualTo("bearer")
    }

    @Test
    @DisplayName("이미 연결된 신원은 새 계정을 만들지 않고 같은 사용자로 로그인한다")
    fun `기존 신원은 같은 사용자로 로그인한다`() {
        val firstToken = userIdOf(callback(code = "sub-repeat|repeat@example.test|true", state = startState()))
        val secondToken = userIdOf(callback(code = "sub-repeat|ignored@example.test|true", state = startState()))

        assertThat(secondToken).isEqualTo(firstToken)
    }

    @Test
    @DisplayName("같은 검증된 이메일의 계정이 이미 있으면 409 다 — 자동 연결하지 않는다")
    fun `이메일이 겹치면 409 다`() {
        callback(code = "sub-first|shared@example.test|true", state = startState())

        val response = callback(code = "sub-second|shared@example.test|true", state = startState())

        assertDeclaredStatus(response, CONFLICT, CALLBACK_PATH, POST)
        assertThat(detailText(response))
            .isEqualTo("이미 같은 이메일로 가입된 계정이 있습니다. 이메일로 로그인한 뒤 연결해 주세요.")
    }

    @Test
    @DisplayName("이메일이 없으면 422 다")
    fun `이메일 없으면 422 다`() {
        val response = callback(code = "sub-no-email||true", state = startState())

        assertDeclaredStatus(response, UNPROCESSABLE_CONTENT, CALLBACK_PATH, POST)
        assertThat(detailText(response)).isEqualTo("이메일 정보를 확인할 수 없습니다")
    }

    @Test
    @DisplayName("이메일이 검증되지 않았으면 422 다")
    fun `이메일 미검증은 422 다`() {
        val response = callback(code = "sub-unverified|unverified@example.test|false", state = startState())

        assertDeclaredStatus(response, UNPROCESSABLE_CONTENT, CALLBACK_PATH, POST)
        assertThat(detailText(response)).isEqualTo("이메일 정보를 확인할 수 없습니다")
    }

    @Test
    @DisplayName("제공자가 코드를 거절하면 401 이다 — WWW-Authenticate 헤더 포함")
    fun `코드 거절은 401 이다`() {
        val response = callback(code = "reject", state = startState())

        assertDeclaredStatus(response, UNAUTHORIZED, CALLBACK_PATH, POST)
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer")
        assertThat(detailText(response)).isEqualTo("이메일 또는 비밀번호가 올바르지 않습니다")
    }

    @Test
    @DisplayName("제공자에 닿지 못하면 502 다")
    fun `제공자 불통은 502 다`() {
        val response = callback(code = "unreachable", state = startState())

        assertDeclaredStatus(response, BAD_GATEWAY, CALLBACK_PATH, POST)
        assertThat(detailText(response)).isEqualTo("구글에 연결하지 못했습니다")
    }

    @Test
    @DisplayName("발급하지 않은 state 는 400 이다")
    fun `없는 state 는 400 이다`() {
        val response = callback(code = "sub-x|x@example.test|true", state = "never-issued")

        assertDeclaredStatus(response, BAD_REQUEST, CALLBACK_PATH, POST)
        assertThat(detailText(response)).isEqualTo("요청이 만료되었거나 이미 사용되었습니다")
    }

    @Test
    @DisplayName("state 는 한 번만 쓸 수 있다")
    fun `state 재사용은 400 이다`() {
        val state = startState()
        callback(code = "sub-once|once@example.test|true", state = state)

        val response = callback(code = "sub-once|once@example.test|true", state = state)

        assertDeclaredStatus(response, BAD_REQUEST, CALLBACK_PATH, POST)
    }

    @Test
    @DisplayName("redirect_uri 가 start 때와 다르면 400 이다")
    fun `redirect_uri 불일치는 400 이다`() {
        val state = startState()

        val response =
            postJson(
                "/auth/oauth/google/callback",
                json.writeValueAsString(
                    mapOf(
                        "code" to "sub-y|y@example.test|true",
                        "state" to state,
                        "redirect_uri" to "https://different.example.test/callback",
                    ),
                ),
            )

        assertDeclaredStatus(response, BAD_REQUEST, CALLBACK_PATH, POST)
    }

    @Test
    @DisplayName("빈 code 는 422 배열이다 — 제공자를 왕복하지 않고 끊긴다")
    fun `빈 code 는 422 배열이고 제공자를 부르지 않는다`() {
        val state = startState()
        val callsBefore = fakeProvider.exchangeCallCount

        val response = callback(code = "", state = state)

        assertDeclaredStatus(response, UNPROCESSABLE_CONTENT, CALLBACK_PATH, POST)
        assertBodyValidationArray(response, "code")
        assertThat(fakeProvider.exchangeCallCount)
            .withFailMessage("빈 code 검증 실패인데 제공자 exchange 가 불렸다 — 스키마 층에서 끊기지 않았다")
            .isEqualTo(callsBefore)
    }

    @Test
    @DisplayName("빈 state 는 422 배열이다")
    fun `빈 state 는 422 배열이다`() {
        val response = callback(code = "sub-z|z@example.test|true", state = "")

        assertDeclaredStatus(response, UNPROCESSABLE_CONTENT, CALLBACK_PATH, POST)
        assertBodyValidationArray(response, "state")
    }

    @Test
    @DisplayName("콜백의 빈 redirect_uri 는 422 배열이다")
    fun `콜백의 빈 redirect_uri 는 422 배열이다`() {
        val response = callback(code = "sub-w|w@example.test|true", state = startState(), redirectUri = "")

        assertDeclaredStatus(response, UNPROCESSABLE_CONTENT, CALLBACK_PATH, POST)
        assertBodyValidationArray(response, "redirect_uri")
    }

    // ------------------------------------------------------------------ 명시적 연결(link/start, link/callback)

    @Test
    @DisplayName("연결 오퍼레이션 둘 다 인증 없이는 401이다")
    fun `연결 오퍼레이션은 인증이 필요하다`() {
        val startResponse =
            postJson("/auth/oauth/google/link/start", json.writeValueAsString(mapOf("redirect_uri" to REDIRECT_URI)))
        assertDeclaredStatus(startResponse, UNAUTHORIZED, LINK_START_PATH, POST)

        val callbackResponse =
            postJson(
                "/auth/oauth/google/link/callback",
                json.writeValueAsString(mapOf("code" to "any", "state" to "any", "redirect_uri" to REDIRECT_URI)),
            )
        assertDeclaredStatus(callbackResponse, UNAUTHORIZED, LINK_CALLBACK_PATH, POST)
    }

    @Test
    @DisplayName("비밀번호 계정에 신원을 연결하면 readMe.identities 에 나타나고, 이후 같은 신원 로그인이 같은 사용자로 온다")
    fun `연결 뒤 같은 신원 로그인은 같은 계정이다`() {
        val email = uniqueEmail()
        val userId = signupAndVerify(email)
        val bearer = login(email)

        val linkState = linkStartState(bearer)
        val linkResponse = linkCallback(bearer, code = "google-link-sub|linked@example.test|true", state = linkState)
        assertThat(linkResponse.status).isEqualTo(NO_CONTENT)

        val me = body(getAuthorized("/auth/me", bearer))
        assertThat((me["identities"] as List<*>).map { (it as Map<*, *>)["provider"] }).containsExactly("google")

        val loginResponse = callback(code = "google-link-sub|ignored@example.test|true", state = startState())
        assertThat(userIdOf(loginResponse)).isEqualTo(userId)
    }

    @Test
    @DisplayName("같은 신원을 같은 계정에 다시 연결해도 멱등이다 — 두 번째도 204다")
    fun `재연결은 멱등이다`() {
        val email = uniqueEmail()
        signupAndVerify(email)
        val bearer = login(email)

        val firstResponse =
            linkCallback(bearer, code = "google-idempotent-sub|first@example.test|true", state = linkStartState(bearer))
        assertThat(firstResponse.status).isEqualTo(NO_CONTENT)

        val secondResponse =
            linkCallback(
                bearer,
                code = "google-idempotent-sub|second@example.test|true",
                state = linkStartState(bearer),
            )
        assertThat(secondResponse.status).isEqualTo(NO_CONTENT)
    }

    @Test
    @DisplayName("다른 계정이 이미 쓰는 신원을 연결하려 하면 409다")
    fun `다른 계정의 신원을 연결하려 하면 409다`() {
        val ownerEmail = uniqueEmail()
        signupAndVerify(ownerEmail)
        val ownerBearer = login(ownerEmail)
        linkCallback(
            ownerBearer,
            code = "google-taken-sub|taken@example.test|true",
            state = linkStartState(ownerBearer),
        )

        val otherEmail = uniqueEmail()
        signupAndVerify(otherEmail)
        val otherBearer = login(otherEmail)

        val response =
            linkCallback(
                otherBearer,
                code = "google-taken-sub|taken@example.test|true",
                state = linkStartState(otherBearer),
            )

        assertDeclaredStatus(response, CONFLICT, LINK_CALLBACK_PATH, POST)
        assertThat(detailText(response)).isEqualTo("이 구글 계정은 이미 다른 계정에 연결되어 있습니다")
    }

    @Test
    @DisplayName("한 계정에 같은 제공자의 두 번째 신원을 연결하려 하면 409다")
    fun `같은 제공자의 두 번째 신원은 409다`() {
        val email = uniqueEmail()
        signupAndVerify(email)
        val bearer = login(email)
        linkCallback(bearer, code = "google-first-sub|first@example.test|true", state = linkStartState(bearer))

        val response =
            linkCallback(bearer, code = "google-second-sub|second@example.test|true", state = linkStartState(bearer))

        assertDeclaredStatus(response, CONFLICT, LINK_CALLBACK_PATH, POST)
        assertThat(detailText(response)).isEqualTo("이미 다른 구글 계정이 이 계정에 연결되어 있습니다")
    }

    @Test
    @DisplayName("로그인 state 를 연결 콜백에 쓰면 400이다")
    fun `로그인 state 는 연결 콜백에서 거절된다`() {
        val email = uniqueEmail()
        signupAndVerify(email)
        val bearer = login(email)
        val loginState = startState()

        val response = linkCallback(bearer, code = "any|any@example.test|true", state = loginState)

        assertDeclaredStatus(response, BAD_REQUEST, LINK_CALLBACK_PATH, POST)
        assertThat(detailText(response)).isEqualTo("요청이 만료되었거나 이미 사용되었습니다")
    }

    @Test
    @DisplayName("연결 state 를 로그인 콜백에 쓰면 400이다")
    fun `연결 state 는 로그인 콜백에서 거절된다`() {
        val email = uniqueEmail()
        signupAndVerify(email)
        val bearer = login(email)
        val linkState = linkStartState(bearer)

        val response = callback(code = "any|any@example.test|true", state = linkState)

        assertDeclaredStatus(response, BAD_REQUEST, CALLBACK_PATH, POST)
    }

    @Test
    @DisplayName("다른 사용자에게 발급된 연결 state 는 400이다")
    fun `다른 사용자의 연결 state 는 거절된다`() {
        val issuerEmail = uniqueEmail()
        signupAndVerify(issuerEmail)
        val issuerBearer = login(issuerEmail)
        val impostorEmail = uniqueEmail()
        signupAndVerify(impostorEmail)
        val impostorBearer = login(impostorEmail)
        val state = linkStartState(issuerBearer)

        val response = linkCallback(impostorBearer, code = "any|any@example.test|true", state = state)

        assertDeclaredStatus(response, BAD_REQUEST, LINK_CALLBACK_PATH, POST)
    }

    @Test
    @DisplayName("제공자가 연결 코드를 거절하면 401이다")
    fun `연결 코드 거절은 401이다`() {
        val email = uniqueEmail()
        signupAndVerify(email)
        val bearer = login(email)

        val response = linkCallback(bearer, code = "reject", state = linkStartState(bearer))

        assertDeclaredStatus(response, UNAUTHORIZED, LINK_CALLBACK_PATH, POST)
        assertThat(detailText(response)).isEqualTo("이메일 또는 비밀번호가 올바르지 않습니다")
    }

    @Test
    @DisplayName("연결 콜백에서도 제공자 불통은 502다")
    fun `연결 콜백의 제공자 불통은 502다`() {
        val email = uniqueEmail()
        signupAndVerify(email)
        val bearer = login(email)

        val response = linkCallback(bearer, code = "unreachable", state = linkStartState(bearer))

        assertDeclaredStatus(response, BAD_GATEWAY, LINK_CALLBACK_PATH, POST)
    }

    // ------------------------------------------------------------------ 헬퍼

    private fun start(
        redirectUri: String,
        provider: String = "google",
    ): MockHttpServletResponse =
        postJson(
            "/auth/oauth/$provider/start",
            json.writeValueAsString(mapOf("redirect_uri" to redirectUri)),
        )

    private fun startState(
        provider: String = "google",
        redirectUri: String = REDIRECT_URI,
    ): String = body(start(redirectUri, provider))["state"] as String

    private fun callback(
        code: String,
        state: String,
        redirectUri: String = REDIRECT_URI,
        provider: String = "google",
    ): MockHttpServletResponse =
        postJson(
            "/auth/oauth/$provider/callback",
            json.writeValueAsString(mapOf("code" to code, "state" to state, "redirect_uri" to redirectUri)),
        )

    /** `StubAccessTokens` 가 토큰을 `stub-token:<uuid>` 로 발급한다 — 접두사를 떼면 사용자 id 다. */
    private fun userIdOf(response: MockHttpServletResponse): String =
        (body(response)["access_token"] as String).removePrefix("stub-token:")

    private fun postJson(
        path: String,
        payload: String,
    ): MockHttpServletResponse =
        mockMvc
            .post(path) {
                contentType = MediaType.APPLICATION_JSON
                content = payload
            }.andReturn()
            .response

    private fun postAuthorized(
        path: String,
        bearer: String,
        payload: String,
    ): MockHttpServletResponse =
        mockMvc
            .post(path) {
                contentType = MediaType.APPLICATION_JSON
                header(HttpHeaders.AUTHORIZATION, "Bearer $bearer")
                content = payload
            }.andReturn()
            .response

    private fun getAuthorized(
        path: String,
        bearer: String,
    ): MockHttpServletResponse =
        mockMvc
            .get(path) {
                header(HttpHeaders.AUTHORIZATION, "Bearer $bearer")
            }.andReturn()
            .response

    private fun credentials(email: String): String =
        json.writeValueAsString(mapOf("email" to email, "password" to PASSWORD))

    /** 가입하고 `InMemoryUserRepository` 로 곧장 이메일 인증까지 마친다. 사용자 id 를 돌려준다. */
    private fun signupAndVerify(email: String): String {
        val response = postJson("/auth/signup", credentials(email))
        users.verifyEmailFor(email)
        return body(response)["id"] as String
    }

    /** 로그인해 Bearer 토큰(접두사 포함, `Authorization` 헤더에 그대로 쓸 값)을 얻는다. */
    private fun login(email: String): String =
        body(postJson("/auth/login", credentials(email)))["access_token"] as String

    private fun linkStart(
        bearer: String,
        redirectUri: String = REDIRECT_URI,
    ): MockHttpServletResponse =
        postAuthorized(
            "/auth/oauth/google/link/start",
            bearer,
            json.writeValueAsString(mapOf("redirect_uri" to redirectUri)),
        )

    private fun linkStartState(bearer: String): String = body(linkStart(bearer))["state"] as String

    private fun linkCallback(
        bearer: String,
        code: String,
        state: String,
        redirectUri: String = REDIRECT_URI,
    ): MockHttpServletResponse =
        postAuthorized(
            "/auth/oauth/google/link/callback",
            bearer,
            json.writeValueAsString(mapOf("code" to code, "state" to state, "redirect_uri" to redirectUri)),
        )

    private fun uniqueEmail(): String = "oauth-link${counter++}@example.test"

    private fun body(response: MockHttpServletResponse): Map<*, *> =
        json.readValue(response.getContentAsString(StandardCharsets.UTF_8), Map::class.java)

    private fun bodyKeys(response: MockHttpServletResponse): Set<String> =
        body(response).keys.map { it.toString() }.toSet()

    private fun detailText(response: MockHttpServletResponse): String =
        body(response)["detail"] as? String ?: error("detail 이 문자열이 아니다: ${body(response)}")

    private fun assertDeclaredStatus(
        response: MockHttpServletResponse,
        status: Int,
        path: String,
        method: String,
    ) {
        assertThat(response.status).isEqualTo(status)
        assertThat(ContractSpec.responseStatuses(path, method))
            .withFailMessage("계약이 %s %s 에 %d 를 선언하지 않는다", method, path, status)
            .contains(status.toString())
    }

    /** 값·부착 개수만 잰다 — `AuthContractTest.assertPrivateHeaders` 와 같은 방식. */
    private fun assertPrivateHeaders(response: MockHttpServletResponse) {
        val expected = ContractSpec.globalHeaderValues()
        expected.forEach { (header, value) ->
            assertThat(response.getHeaders(header)).containsExactly(value)
        }
    }

    /** Bean Validation(스키마 층) 실패의 모양 — `[{loc, msg, type}]`, `loc` 이 그 필드를 지목한다. */
    private fun assertBodyValidationArray(
        response: MockHttpServletResponse,
        fieldName: String,
    ) {
        val detail = body(response)["detail"]
        assertThat(detail).isInstanceOf(List::class.java)
        val items = (detail as List<*>).map { it as Map<*, *> }
        assertThat(items.map { it["loc"] })
            .withFailMessage("거절 항목이 %s 를 지목하지 않는다: %s", fieldName, items)
            .contains(listOf("body", fieldName))
    }

    private companion object {
        const val START_PATH = "/auth/oauth/{provider}/start"
        const val CALLBACK_PATH = "/auth/oauth/{provider}/callback"
        const val LINK_START_PATH = "/auth/oauth/{provider}/link/start"
        const val LINK_CALLBACK_PATH = "/auth/oauth/{provider}/link/callback"
        const val RECONVERT_UNIT_PATH = "/conversions/{conversion_id}/units/{source_unit_index}/reconvert"
        const val POST = "post"
        const val UNPROCESSABLE_CONTENT = 422
        const val CONFLICT = 409
        const val UNAUTHORIZED = 401
        const val BAD_GATEWAY = 502
        const val BAD_REQUEST = 400
        const val NO_CONTENT = 204
        const val REDIRECT_URI = "http://localhost:5173/auth/google/callback"
        const val KAKAO_REDIRECT_URI = "http://localhost:5173/auth/kakao/callback"
        const val NAVER_REDIRECT_URI = "http://localhost:5173/auth/naver/callback"
        const val PASSWORD = "correct horse battery"

        var counter = 0
    }
}
