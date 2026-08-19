package kr.easydoc.api

import kr.easydoc.api.support.ContractSpec
import kr.easydoc.api.support.TestJwt
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

/**
 * **X-1 — 계정이 지워진 뒤에도 유효한 토큰이 무엇을 할 수 있는가.**
 *
 * 계약 `x-auth.failure_uniformity`(`:299-302`)가 계정 삭제를 이메일 부재·비밀번호 불일치·
 * 토큰 만료·위조와 **같은 줄에서** 401 사유로 열거한다. 그 열거는 `/auth/me` 절이 아니라
 * 최상위 `x-auth` 아래 있고, 로그인 시점의 삭제는 「이메일 부재」가 이미 덮으므로
 * 「계정 삭제」를 따로 세운 것은 **토큰이 아직 유효한 요청 시점**을 가리킨다.
 *
 * ## 고치기 전에 네 갈래가 있었다
 *
 * 서명만 검증하던 시절의 실측(privacy-gate R-3):
 *
 * | 경로 | 그때 | 지금 |
 * |---|---|---|
 * | `GET /auth/me` | 401 | 401 |
 * | `GET /workspaces` | **200 `{"items":[]}`** | 401 |
 * | `POST /workspaces` | **500** (사용자 FK 위반) | 401 |
 * | `PATCH /workspaces/{id}` | **404** | 401 |
 * | `DELETE /workspaces/{id}` | **404** | 401 |
 *
 * 네 갈래가 갈렸다는 것 자체가 **삭제 여부를 알려 주는 상태 코드 채널**이었다. 그래서 이
 * 테스트는 「401 이 나온다」가 아니라 **「다섯이 서로 구분되지 않는다」**를 단언한다 —
 * 다섯이 각각 401 을 내면서 본문이나 헤더가 갈리면 채널은 그대로 살아 있다.
 *
 * ## 위조 토큰과도 구분되지 않아야 한다
 *
 * 삭제 계정의 401 과 위조 토큰의 401 이 바이트로 갈리면, 「이 토큰은 한때 유효했다」가
 * 새어 나간다. 그것도 계약이 같은 줄에서 금지한 구분이다.
 *
 * ## 왜 HTTP 경계인가
 *
 * 확인이 도는 자리는 인터셉터이고, 인터셉터가 **실제로 그 경로에 걸려 있는지**는
 * 유스케이스 단위 테스트가 재지 못한다. 계정 행은 SQL 로 지운다 — 계약에 계정 삭제
 * 엔드포인트가 없어(`/auth` 는 signup·login·me 셋뿐) HTTP 로는 이 상태를 만들 수 없다.
 * `users` 삭제는 `fk_workspaces_user_id_users`(ON DELETE CASCADE)를 타고 작업 공간까지
 * 지우므로, 실제 탈퇴가 남길 상태와 같다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["easydoc.auth.jwt-secret=$DELETED_ACCOUNT_TEST_SECRET"],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeletedAccountTokenReachTest {
    @LocalServerPort
    private var port: Int = 0

    private val json = ObjectMapper()

    @Test
    @DisplayName("계정이 지워진 유효 토큰은 다섯 보호 경로에서 같은 401 로 모인다 (X-1)")
    fun `삭제된 계정의 토큰이 어디서도 통과하지 않는다`() {
        val account = liveAccount()

        // 살아 있는 동안 다섯 경로가 실제로 통하는지 먼저 본다. 이 반대쪽이 없으면
        // 「무엇을 보내도 401 인 구현」과 구분되지 않는다.
        val alive = allProtectedCalls(account).map { it.first to it.second.statusCode() }
        assertThat(alive.map { it.second })
            .withFailMessage("계정이 살아 있는데 보호 경로가 401 을 냈다 — 이 측정의 전제가 깨졌다: %s", alive)
            .doesNotContain(UNAUTHORIZED)

        database.execute("DELETE FROM users WHERE id = '${account.userId}'")

        val responses = allProtectedCalls(account)
        val unauthorizedDetail = ContractSpec.responseExampleDetail(UNAUTHORIZED_COMPONENT, INVALID_TOKEN_EXAMPLE)

        responses.forEach { (label, response) ->
            assertThat(response.statusCode())
                .withFailMessage("%s 가 %d 다 — 삭제 여부가 상태 코드로 새고 있다", label, response.statusCode())
                .isEqualTo(UNAUTHORIZED)
            assertThat(bodyOf(response)["detail"])
                .withFailMessage("%s 의 401 문구가 계약 예시와 다르다: %s", label, response.body())
                .isEqualTo(unauthorizedDetail)
            assertThat(response.headers().firstValue(WWW_AUTHENTICATE))
                .withFailMessage("%s 의 401 에 WWW-Authenticate 가 없다", label)
                .contains(ContractSpec.headerConst(WWW_AUTHENTICATE_COMPONENT))
            assertPrivateHeaders(label, response)
        }

        // 다섯이 **서로** 구분되지 않는다 — 바이트 하나가 갈려도 채널이다.
        assertThat(responses.map { it.second.body() }.distinct())
            .withFailMessage("경로마다 401 본문이 갈린다: %s", responses.map { it.first to it.second.body() })
            .hasSize(1)
        assertThat(responses.map { headerNames(it.second) }.distinct())
            .withFailMessage("경로마다 401 헤더 이름 집합이 갈린다: %s", responses.map { it.first to headerNames(it.second) })
            .hasSize(1)
    }

    @Test
    @DisplayName("삭제 계정의 401 과 위조 토큰의 401 이 바이트로 구분되지 않는다 (X-1)")
    fun `삭제와 위조가 같은 응답이다`() {
        val account = liveAccount()
        database.execute("DELETE FROM users WHERE id = '${account.userId}'")

        val deleted = get(COLLECTION_PATH, account.token)
        val forged = get(COLLECTION_PATH, TestJwt.withBrokenSignature(account.token))

        assertThat(deleted.statusCode()).isEqualTo(forged.statusCode())
        assertThat(deleted.body())
            .withFailMessage("삭제 계정과 위조 토큰의 401 본문이 갈린다 — 「한때 유효했다」가 샌다")
            .isEqualTo(forged.body())
        assertThat(headerNames(deleted)).isEqualTo(headerNames(forged))
    }

    // ================================================================ 호출 조립

    /** 계약이 보호한다고 선언한 다섯 자리를 전부 친다. 반환은 (라벨, 응답). */
    private fun allProtectedCalls(account: LiveAccount): List<Pair<String, HttpResponse<String>>> =
        listOf(
            "GET /auth/me" to get("/auth/me", account.token),
            "GET $COLLECTION_PATH" to get(COLLECTION_PATH, account.token),
            "POST $COLLECTION_PATH" to
                send(jsonRequest(COLLECTION_PATH, account.token).POST(bodyPublisher(nameBody(uniqueName())))),
            "PATCH $ITEM_PATH" to
                send(
                    jsonRequest(itemPath(account.secondWorkspaceId), account.token)
                        .method("PATCH", bodyPublisher(nameBody(uniqueName()))),
                ),
            "DELETE $ITEM_PATH" to send(jsonRequest(itemPath(account.secondWorkspaceId), account.token).DELETE()),
        )

    /**
     * 가입 → 로그인 → 작업 공간 하나 더.
     *
     * 두 번째 작업 공간을 만드는 이유: 살아 있는 동안의 대조에서 DELETE 가 「마지막 하나」
     * 409 로 떨어지면 안 되고, PATCH·DELETE 가 **실재하는 내 자원**을 가리켜야 404 와
     * 401 을 구분해 읽을 수 있다.
     */
    private fun liveAccount(): LiveAccount {
        val email = "deleted-account${counter++}@example.test"
        val credentials = json.writeValueAsString(mapOf("email" to email, "password" to VALID_PASSWORD))
        send(jsonRequest("/auth/signup", null).POST(bodyPublisher(credentials)))
        val login = send(jsonRequest("/auth/login", null).POST(bodyPublisher(credentials)))
        val token = bodyOf(login).required("access_token").toString()
        val second = send(jsonRequest(COLLECTION_PATH, token).POST(bodyPublisher(nameBody(uniqueName()))))
        return LiveAccount(
            token = token,
            userId = TestJwt.payload(token)["sub"].toString(),
            secondWorkspaceId = bodyOf(second).required("id").toString(),
        )
    }

    private data class LiveAccount(
        val token: String,
        val userId: String,
        val secondWorkspaceId: String,
    )

    /** **P-21 — 경로 변수 이름을 계약에서 읽어 URL 을 조립한다.** */
    private fun itemPath(workspaceId: String): String {
        val parameter = ContractSpec.pathParameters(ITEM_PATH).single { it.location == "path" }
        return ITEM_PATH.replace("{${parameter.name}}", workspaceId)
    }

    private fun get(
        path: String,
        token: String?,
    ): HttpResponse<String> = send(jsonRequest(path, token).GET())

    private fun jsonRequest(
        path: String,
        token: String?,
    ): HttpRequest.Builder {
        val builder =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header("Content-Type", "application/json")
        token?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    private fun send(builder: HttpRequest.Builder): HttpResponse<String> =
        HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))

    private fun bodyPublisher(payload: String): HttpRequest.BodyPublisher =
        HttpRequest.BodyPublishers.ofString(payload, Charsets.UTF_8)

    private fun nameBody(name: String): String = json.writeValueAsString(mapOf("name" to name))

    private fun bodyOf(response: HttpResponse<String>): Map<*, *> = json.readValue(response.body(), Map::class.java)

    private fun uniqueName(): String = "삭제계정${counter++}"

    private fun headerNames(response: HttpResponse<String>): Set<String> =
        response
            .headers()
            .map()
            .keys
            .map { it.lowercase() }
            .toSet() - VARIABLE_HEADERS

    private fun assertPrivateHeaders(
        label: String,
        response: HttpResponse<String>,
    ) {
        ContractSpec.globalHeaderValues().forEach { (header, value) ->
            assertThat(response.headers().allValues(header))
                .withFailMessage("%s 의 %s 가 %s 다", label, header, response.headers().allValues(header))
                .containsExactly(value)
        }
    }

    companion object {
        private const val UNAUTHORIZED = 401
        private const val COLLECTION_PATH = "/workspaces"
        private const val ITEM_PATH = "/workspaces/{workspace_id}"
        private const val VALID_PASSWORD = "correct horse battery"
        private const val WWW_AUTHENTICATE = "WWW-Authenticate"
        private const val WWW_AUTHENTICATE_COMPONENT = "WWWAuthenticateBearer"

        /** 계약이 응답 컴포넌트·예시에 붙인 **이름**이다. 값이 아니라 이름이라 여기 적는다. */
        private const val UNAUTHORIZED_COMPONENT = "Unauthorized"
        private const val INVALID_TOKEN_EXAMPLE = "invalid_token"

        /** 응답마다 값이 달라지는 헤더 — 집합 비교에서 뺀다. */
        private val VARIABLE_HEADERS = setOf("date")

        private var counter = 0

        val database: DatabaseHandle by lazy { PostgresTestSupport.createEmptyDatabase("deleted_account_reach") }

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.jdbcUrl }
            registry.add("spring.datasource.username") { database.username }
            registry.add("spring.datasource.password") { database.password }
        }
    }
}

/** 이 테스트만 쓰는 서명 키. 32바이트를 넘겨 HS256 최소 길이를 만족시킨다. */
const val DELETED_ACCOUNT_TEST_SECRET: String = "deleted-account-signing-key-0123456789-abc"

private fun Map<*, *>.required(key: String): Any = this[key] ?: error("응답에 $key 가 없다")
