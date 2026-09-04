package kr.easydoc.api

import kr.easydoc.api.support.ContractSpec
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * `easydoc.oauth.google.client-id`/`client-secret` 이 비어 있는 **실물** 구성 —
 * `AuthSliceBeans` 의 가짜 google provider 를 쓰지 않는 실제 `@ConfigurationProperties`
 * 바인딩과 `AuthConfiguration.socialLoginProviders` 조립을 잰다. backlog §1.4 P0-1.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["easydoc.auth.jwt-secret=$UNCONFIGURED_TEST_SECRET"],
)
class OAuthProviderUnconfiguredReachTest {
    @LocalServerPort
    private var port: Int = 0

    private val json = ObjectMapper()

    @Test
    @DisplayName("키 없는 배포는 뜬다 — 기동을 막지 않는다")
    fun `기동은 막지 않는다`() {
        assertThat(get("/health").statusCode()).isEqualTo(200)
    }

    @Test
    @DisplayName("google start 가 422 다 — 제공자 미설정, JWT 문제(503)로 감추지 않는다")
    fun `google 미설정은 422 다`() {
        val response =
            post(
                "/auth/oauth/google/start",
                """{"redirect_uri":"http://localhost:5173/auth/google/callback"}""",
            )

        assertThat(response.statusCode()).isEqualTo(422)
        assertThat(ContractSpec.responseStatuses("/auth/oauth/{provider}/start", "post")).contains("422")
        val detail = json.readValue(response.body(), Map::class.java)["detail"]
        assertThat(detail).isEqualTo("구글 로그인이 설정되지 않았습니다")
    }

    @Test
    @DisplayName("google callback 도 422 다 — 같은 사유")
    fun `google callback 미설정도 422 다`() {
        val response =
            post(
                "/auth/oauth/google/callback",
                """{"code":"any","state":"any","redirect_uri":"http://localhost:5173/auth/google/callback"}""",
            )

        assertThat(response.statusCode()).isEqualTo(422)
        val detail = json.readValue(response.body(), Map::class.java)["detail"]
        assertThat(detail).isEqualTo("구글 로그인이 설정되지 않았습니다")
    }

    @Test
    @DisplayName("지원하지 않는 provider 는 실물 구성에서도 422 배열이다 — 경로 값 해석은 스키마 층이다")
    fun `지원하지 않는 provider 는 422 다`() {
        val response =
            post(
                "/auth/oauth/kakao/start",
                """{"redirect_uri":"http://localhost:5173/auth/google/callback"}""",
            )

        assertThat(response.statusCode()).isEqualTo(422)
        val detail = json.readValue(response.body(), Map::class.java)["detail"]
        assertThat(detail).isInstanceOf(List::class.java)
    }

    private fun post(
        path: String,
        body: String,
    ): HttpResponse<String> =
        send(
            HttpRequest
                .newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8)),
        )

    private fun get(path: String): HttpResponse<String> = send(HttpRequest.newBuilder(uri(path)).GET())

    private fun send(builder: HttpRequest.Builder): HttpResponse<String> =
        HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))

    private fun uri(path: String): URI = URI.create("http://localhost:$port$path")

    companion object {
        /** 이 테스트만 쓰는 DB. */
        val database: DatabaseHandle by lazy { PostgresTestSupport.createEmptyDatabase("oauth_unconfigured") }

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.jdbcUrl }
            registry.add("spring.datasource.username") { database.username }
            registry.add("spring.datasource.password") { database.password }
        }
    }
}

/** 이 테스트가 쓰는 서명 키. `AuthEndpointReachTest` 와 값을 공유하지 않는다 — 독립 클래스다. */
const val UNCONFIGURED_TEST_SECRET: String = "unconfigured-test-signing-key-0123456789"
