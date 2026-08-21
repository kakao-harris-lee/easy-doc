package kr.easydoc.api

import kr.easydoc.api.support.ContractSpec
import kr.easydoc.api.support.OwnershipConcealment
import kr.easydoc.core.crypto.EncryptionScheme
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
import kotlin.random.Random

/** `/workspaces` 의 실측 계약 — 명세 §5 의 C-R·C-I 계층. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["easydoc.auth.jwt-secret=$WORKSPACE_REACH_TEST_SECRET"],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Suppress("LargeClass")
class WorkspaceEndpointReachTest {
    @LocalServerPort
    private var port: Int = 0

    private val json = ObjectMapper()

    @Test
    @DisplayName("WX-1 가입 직후 목록이 정확히 1건이고 그 이름이 계약이 지정한 값이다")
    fun `가입이 함께 만드는 작업 공간이 목록에 있다`() {
        val token = newAccount()

        val items = itemsOf(get(COLLECTION_PATH, token))

        assertThat(items).hasSize(1)

        assertThat(items.single()[NAME_PROPERTY]).isEqualTo(ContractSpec.defaultWorkspaceName())
    }

    @Test
    @DisplayName("WL-3 목록이 만든 순서로 나온다 — 첫 항목이 가장 먼저 만든 것이다")
    fun `목록이 만든 순서다`() {
        val token = newAccount()
        val firstName = nameOf(itemsOf(get(COLLECTION_PATH, token)).single())
        val second = uniqueName()
        val third = uniqueName()
        create(token, second)
        create(token, third)

        assertThat(itemsOf(get(COLLECTION_PATH, token)).map(::nameOf))
            .containsExactly(firstName, second, third)
    }

    @Test
    @DisplayName("WL-4 타인의 작업 공간이 목록에 0건이다")
    fun `목록이 소유자 범위로 한정된다`() {
        val mine = newAccount()
        val other = newAccount()
        val otherId = idOf(create(other, uniqueName()))

        assertThat(itemsOf(get(COLLECTION_PATH, mine)).map { it["id"].toString() }).doesNotContain(otherId)
    }

    @Test
    @DisplayName("WL-5 document_count 가 실제 문서 수에서 유도된다 — 상수 0 구현이면 여기서 깨진다")
    fun `문서 수가 공간마다 다르다`() {
        val token = newAccount()
        val userId = subjectOf(token)
        val withDocuments = idOf(create(token, uniqueName()))
        val empty = idOf(create(token, uniqueName()))
        insertDocument(userId, withDocuments)
        insertDocument(userId, withDocuments)

        val counts = itemsOf(get(COLLECTION_PATH, token)).associate { it["id"].toString() to it["document_count"] }

        assertThat(counts[withDocuments]).isEqualTo(2)
        assertThat(counts[empty]).isEqualTo(0)
    }

    @Test
    @DisplayName("WL-6 토큰 없이 목록 → 401 · WWW-Authenticate · 본문 키가 정확히 ErrorResponse.required · JSON")
    fun `토큰 없는 목록은 401 이다`() {
        val response = get(COLLECTION_PATH, token = null)

        assertDeclaredStatus(response, UNAUTHORIZED, COLLECTION_PATH, GET)
        assertThat(response.headers().firstValue(WWW_AUTHENTICATE))
            .contains(ContractSpec.headerConst(WWW_AUTHENTICATE_COMPONENT))

        assertThat(bodyOf(response).keys.map { it.toString() }.toSet())
            .isEqualTo(ContractSpec.schemaRequired(ERROR_SCHEMA))
        assertJsonContentType(response)
    }

    @Test
    @DisplayName("WC-2 같은 사용자가 같은 이름으로 두 번 → 409 · detail 문자열 · 사적 헤더 · JSON")
    fun `같은 이름은 409 다`() {
        val token = newAccount()
        val name = uniqueName()
        create(token, name)

        val response = create(token, name)

        assertDeclaredStatus(response, CONFLICT, COLLECTION_PATH, POST)

        assertThat(bodyOf(response)["detail"]).isInstanceOf(String::class.java)
        assertPrivateHeaders(response)
        assertJsonContentType(response)
    }

    @Test
    @DisplayName("WC-3 다른 사용자가 같은 이름으로 → 통과 (유일성은 사용자 범위 안에서만이다)")
    fun `이름 유일성은 사용자 범위다`() {
        val name = uniqueName()
        create(newAccount(), name)

        assertThat(create(newAccount(), name).statusCode())
            .isEqualTo(ContractSpec.successStatus(COLLECTION_PATH, POST))
    }

    @Test
    @DisplayName("WC-12 토큰 없음 + 빈 이름 본문 → 401 (422 가 아니다) — X-A3")
    fun `인증이 본문 검증보다 먼저다`() {
        val response = send(jsonRequest(COLLECTION_PATH, null).POST(bodyPublisher(nameBody("   "))))

        assertDeclaredStatus(response, UNAUTHORIZED, COLLECTION_PATH, POST)
    }

    @Test
    @DisplayName("WR-1 이름 변경 성공 — 계약의 성공 상태 · 사적 헤더 2종(개수까지) · 키가 정확히 required · 값이 바뀐다")
    fun `이름 변경 응답이 계약과 같다`() {
        val token = newAccount()
        val id = idOf(create(token, uniqueName()))
        val renamed = uniqueName()

        val response = patch(token, id, renamed)

        assertThat(response.statusCode()).isEqualTo(ContractSpec.successStatus(ITEM_PATH, PATCH))
        assertPrivateHeaders(response)
        assertThat(bodyOf(response).keys.map { it.toString() }.toSet())
            .isEqualTo(ContractSpec.schemaRequired(SINGLE_SCHEMA))
        assertThat(bodyOf(response)[NAME_PROPERTY]).isEqualTo(renamed)
    }

    @Test
    @DisplayName("WR-3 타인 소유 작업 공간 → 404 이고 **403 이 아니다** (X-B1)")
    fun `타인 자원은 404 이고 403 이 아니다`() {
        val other = newAccount()
        val id = idOf(create(other, uniqueName()))

        val response = patch(newAccount(), id, uniqueName())

        assertThat(response.statusCode()).isNotEqualTo(FORBIDDEN)
        assertDeclaredStatus(response, NOT_FOUND, ITEM_PATH, PATCH)
        assertThat(bodyOf(response)["detail"])
            .isEqualTo(ContractSpec.pathExampleDetail(ITEM_PATH, PATCH, NOT_FOUND, NOT_FOUND_EXAMPLE))
    }

    /** 성질 P1 — 응답 구별 불가. 판정은 [OwnershipConcealment] 한 벌이 진다. */
    @Test
    @DisplayName("WR-4 없는 자원과 타인 자원의 응답이 상태·본문 **원시 바이트**·헤더 이름 집합까지 같다 (X-B2)")
    fun `없는 자원과 타인 자원이 구분되지 않는다`() {
        val mine = newAccount()
        val othersId = idOf(create(newAccount(), uniqueName()))
        val name = uniqueName()

        val absent = patchBytes(mine, UUID.randomUUID().toString(), name)
        val others = patchBytes(mine, othersId, name)

        OwnershipConcealment.assertIndistinguishable("PATCH $ITEM_PATH", absent, others)
    }

    /** 소유권 은닉의 셋째 축 — 응답 시간. */
    @Test
    @DisplayName("소유권 404 의 응답 시간이 「없음」과 「타인 것」 사이에서 갈리지 않는다")
    fun `소유권 404 의 응답 시간이 갈리지 않는다`() {
        val mine = newAccount()
        val othersId = idOf(create(newAccount(), uniqueName()))

        val (absent, others) = interleavedNotFoundMedians(mine, othersId)
        val ratio = maxOf(absent, others) / minOf(absent, others).coerceAtLeast(MIN_MEASURABLE_MILLIS)

        println("소유권 404 응답 시간 중앙값: 없음=${absent}ms 타인=${others}ms 비=$ratio")
        assertThat(ratio).isLessThan(MAX_TIMING_RATIO)
    }

    @Test
    @DisplayName("WR-5 내 다른 작업 공간과 같은 이름으로 변경 → 409 · detail 문자열")
    fun `이름 변경 충돌은 409 다`() {
        val token = newAccount()
        val taken = uniqueName()
        create(token, taken)
        val id = idOf(create(token, uniqueName()))

        val response = patch(token, id, taken)

        assertDeclaredStatus(response, CONFLICT, ITEM_PATH, PATCH)
        assertThat(bodyOf(response)["detail"]).isInstanceOf(String::class.java)
    }

    @Test
    @DisplayName("WR-8 위조 토큰 + UUID 가 아닌 경로 변수 → 401 (422 가 아니다) — X-A3")
    fun `인증이 경로 변수 변환보다 먼저다`() {
        val forged = jsonRequest(itemPath(NOT_A_UUID), FORGED_TOKEN)

        val response = send(forged.method(PATCH.uppercase(), bodyPublisher(nameBody("가"))))

        assertDeclaredStatus(response, UNAUTHORIZED, ITEM_PATH, PATCH)
    }

    @Test
    @DisplayName("WR-9 토큰 없음 → 401 · WWW-Authenticate · 본문 키가 정확히 ErrorResponse.required")
    fun `토큰 없는 이름 변경은 401 이다`() {
        val response = patch(token = null, workspaceId = UUID.randomUUID().toString(), name = "가")

        assertDeclaredStatus(response, UNAUTHORIZED, ITEM_PATH, PATCH)
        assertThat(response.headers().firstValue(WWW_AUTHENTICATE))
            .contains(ContractSpec.headerConst(WWW_AUTHENTICATE_COMPONENT))
        assertThat(bodyOf(response).keys.map { it.toString() }.toSet())
            .isEqualTo(ContractSpec.schemaRequired(ERROR_SCHEMA))
    }

    @Test
    @DisplayName("WD-1 빈 작업 공간 삭제 → 204 · 본문 길이 0 · 사적 헤더 있음 (X-D2)")
    fun `삭제가 204 이고 본문이 없다`() {
        val token = newAccount()
        val id = idOf(create(token, uniqueName()))

        val response = delete(token, id)

        assertThat(response.statusCode()).isEqualTo(ContractSpec.successStatus(ITEM_PATH, DELETE))
        assertThat(response.body()).isEmpty()

        assertPrivateHeaders(response)
    }

    /** WD-9 — 거절 두 갈래가 동시에 해당하는 상태. */
    @Test
    @DisplayName("WD-9 마지막 하나 + 문서 있음이 겹치면 계약이 정한 갈래를 낸다 (D-2)")
    fun `거절 두 갈래가 겹치면 계약이 정한 순서를 따른다`() {
        val token = newAccount()

        val onlyWorkspace = itemsOf(get(COLLECTION_PATH, token)).single()["id"].toString()
        insertDocument(subjectOf(token), onlyWorkspace)

        val response = delete(token, onlyWorkspace)

        assertDeclaredStatus(response, CONFLICT, ITEM_PATH, DELETE)
        val expected = ContractSpec.deletionRefusalPrecedenceExample()
        assertThat(bodyOf(response)["detail"])
            .withFailMessage(
                "겹치는 상태의 거절 문구가 계약 D-2 조항이 가리키는 갈래(%s)와 다르다: %s",
                expected,
                response.body(),
            ).isEqualTo(ContractSpec.pathExampleDetail(ITEM_PATH, DELETE, CONFLICT, expected))

        assertThat(ContractSpec.pathExampleDetail(ITEM_PATH, DELETE, CONFLICT, LAST_ONE_EXAMPLE))
            .isNotEqualTo(ContractSpec.pathExampleDetail(ITEM_PATH, DELETE, CONFLICT, HAS_DOCUMENTS_EXAMPLE))
        assertThat(itemsOf(get(COLLECTION_PATH, token))).hasSize(1)
    }

    @Test
    @DisplayName("WD-2·WD-3 타인 자원 삭제 → 404(403 아님)이고 없는 자원과 응답이 같다")
    fun `타인 자원 삭제가 없는 자원과 구분되지 않는다`() {
        val mine = newAccount()
        val othersId = idOf(create(newAccount(), uniqueName()))

        val others = deleteBytes(mine, othersId)
        val absent = deleteBytes(mine, UUID.randomUUID().toString())

        assertThat(others.statusCode()).isNotEqualTo(FORBIDDEN)
        assertDeclaredStatus(others, NOT_FOUND, ITEM_PATH, DELETE)
        OwnershipConcealment.assertIndistinguishable("DELETE $ITEM_PATH", absent, others)
    }

    @Test
    @DisplayName("WD-4 문서가 남은 작업 공간 → 409 문서 잔존 갈래 · 삭제되지 않는다")
    fun `문서가 남으면 지워지지 않는다`() {
        val token = newAccount()
        val id = idOf(create(token, uniqueName()))
        insertDocument(subjectOf(token), id)

        val response = delete(token, id)

        assertDeclaredStatus(response, CONFLICT, ITEM_PATH, DELETE)
        assertThat(bodyOf(response)["detail"])
            .isEqualTo(ContractSpec.pathExampleDetail(ITEM_PATH, DELETE, CONFLICT, HAS_DOCUMENTS_EXAMPLE))
        assertThat(itemsOf(get(COLLECTION_PATH, token)).map { it["id"].toString() }).contains(id)
    }

    @Test
    @DisplayName("WD-5 마지막 남은 작업 공간 → 409 마지막 하나 갈래 · WD-4 와 다른 문구")
    fun `마지막 하나는 지워지지 않는다`() {
        val token = newAccount()
        val id = itemsOf(get(COLLECTION_PATH, token)).single()["id"].toString()

        val response = delete(token, id)

        assertDeclaredStatus(response, CONFLICT, ITEM_PATH, DELETE)
        val lastOne = ContractSpec.pathExampleDetail(ITEM_PATH, DELETE, CONFLICT, LAST_ONE_EXAMPLE)
        assertThat(bodyOf(response)["detail"]).isEqualTo(lastOne)

        val hasDocuments = ContractSpec.pathExampleDetail(ITEM_PATH, DELETE, CONFLICT, HAS_DOCUMENTS_EXAMPLE)
        assertThat(lastOne).isNotEqualTo(hasDocuments)
    }

    @Test
    @DisplayName("WD-7 토큰 없는 삭제 → 401 · WWW-Authenticate")
    fun `토큰 없는 삭제는 401 이다`() {
        val response = delete(token = null, workspaceId = UUID.randomUUID().toString())

        assertDeclaredStatus(response, UNAUTHORIZED, ITEM_PATH, DELETE)
        assertThat(response.headers().firstValue(WWW_AUTHENTICATE))
            .contains(ContractSpec.headerConst(WWW_AUTHENTICATE_COMPONENT))
    }

    @Test
    @DisplayName("WD-8 삭제 직후 같은 UUID 로 재요청 → 404 (멱등 204 가 아니다)")
    fun `이미 지운 자원은 404 다`() {
        val token = newAccount()
        val id = idOf(create(token, uniqueName()))
        delete(token, id)

        assertDeclaredStatus(delete(token, id), NOT_FOUND, ITEM_PATH, DELETE)
    }

    private fun interleavedNotFoundMedians(
        token: String,
        othersId: String,
    ): Pair<Double, Double> {
        val samples = mutableMapOf(ABSENT to mutableListOf<Double>(), OTHERS to mutableListOf())
        val order =
            (List(TIMING_SAMPLES + 1) { ABSENT } + List(TIMING_SAMPLES + 1) { OTHERS })
                .shuffled(Random(TIMING_SEED))
        val warmed = mutableSetOf<String>()

        order.forEach { path ->
            val target = if (path == ABSENT) UUID.randomUUID().toString() else othersId
            val elapsed = measureMillis { patch(token, target, uniqueName()) }

            if (warmed.add(path)) return@forEach
            samples.getValue(path) += elapsed
        }
        return median(samples.getValue(ABSENT)) to median(samples.getValue(OTHERS))
    }

    private fun measureMillis(block: () -> Unit): Double {
        val started = System.nanoTime()
        block()
        return (System.nanoTime() - started) / NANOS_PER_MILLI
    }

    private fun median(values: List<Double>): Double = values.sorted()[values.size / 2]

    /** 가입하고 로그인해 토큰을 받는다. 가입은 기본 작업 공간을 함께 만든다. */
    private fun newAccount(): String {
        val email = "workspace${counter++}@example.test"
        val credentials = json.writeValueAsString(mapOf("email" to email, "password" to VALID_PASSWORD))
        send(jsonRequest("/auth/signup", null).POST(bodyPublisher(credentials)))
        val login = send(jsonRequest("/auth/login", null).POST(bodyPublisher(credentials)))
        return bodyOf(login).required("access_token").toString()
    }

    private fun create(
        token: String,
        name: String,
    ): HttpResponse<String> = send(jsonRequest(COLLECTION_PATH, token).POST(bodyPublisher(nameBody(name))))

    private fun patch(
        token: String?,
        workspaceId: String,
        name: String,
    ): HttpResponse<String> =
        send(jsonRequest(itemPath(workspaceId), token).method(PATCH.uppercase(), bodyPublisher(nameBody(name))))

    private fun delete(
        token: String?,
        workspaceId: String,
    ): HttpResponse<String> = send(jsonRequest(itemPath(workspaceId), token).DELETE())

    /** P1 이 쓰는 바이트 팔 둘. 다른 케이스는 본문을 JSON 으로 읽어야 해서 문자열 팔을 쓴다. */
    private fun patchBytes(
        token: String?,
        workspaceId: String,
        name: String,
    ): HttpResponse<ByteArray> =
        sendBytes(jsonRequest(itemPath(workspaceId), token).method(PATCH.uppercase(), bodyPublisher(nameBody(name))))

    private fun deleteBytes(
        token: String?,
        workspaceId: String,
    ): HttpResponse<ByteArray> = sendBytes(jsonRequest(itemPath(workspaceId), token).DELETE())

    private fun sendBytes(builder: HttpRequest.Builder): HttpResponse<ByteArray> =
        HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())

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
                .newBuilder(
                    URI.create("http://localhost:$port$path"),
                ).header("Content-Type", "application/json")
        token?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    private fun bodyPublisher(payload: String): HttpRequest.BodyPublisher =
        HttpRequest.BodyPublishers.ofString(payload, Charsets.UTF_8)

    private fun send(builder: HttpRequest.Builder): HttpResponse<String> =
        HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))

    private fun nameBody(name: String): String = json.writeValueAsString(mapOf(NAME_PROPERTY to name))

    /** P-21 — 경로 변수 이름을 계약에서 읽어 URL 을 조립한다. */
    private fun itemPath(workspaceId: String): String {
        val parameter = ContractSpec.pathParameters(ITEM_PATH).single { it.location == "path" }
        return ITEM_PATH.replace("{${parameter.name}}", workspaceId)
    }

    /** 토큰의 `sub`. DB 에 문서를 심을 때 소유자가 필요하다. */
    private fun subjectOf(token: String): String =
        kr.easydoc.api.support.TestJwt
            .payload(token)["sub"]
            .toString()

    /** 문서 한 건을 직접 심는다. */
    private fun insertDocument(
        userId: String,
        workspaceId: String,
    ) {
        database.execute(
            """
            INSERT INTO documents
                (id, user_id, title, source_format, source_text_encrypted, char_count, workspace_id,
                 encryption_scheme, key_version)
            VALUES ('${UUID.randomUUID()}', '$userId', 'fixture', 'docx', '\x00'::bytea, 1, '$workspaceId',
                    '${EncryptionScheme.AES_256_GCM_V1}', 1)
            """.trimIndent(),
        )
    }

    private fun assertPrivateHeaders(response: HttpResponse<String>) {
        ContractSpec.globalHeaderValues().forEach { (header, value) ->
            assertThat(response.headers().allValues(header))
                .withFailMessage("%s 가 %s 로 나갔다 — 값 또는 부착 개수가 계약과 다르다", header, response.headers().allValues(header))
                .containsExactly(value)
        }
    }

    private fun assertDeclaredStatus(
        response: HttpResponse<*>,
        status: Int,
        path: String,
        method: String,
    ) {
        assertThat(response.statusCode()).withFailMessage("%s %s 가 %d 이 아니다", method, path, status).isEqualTo(status)
        assertThat(ContractSpec.responseStatuses(path, method))
            .withFailMessage("계약이 %s %s 에 %d 를 선언하지 않는다", method, path, status)
            .contains(status.toString())
    }

    private fun assertJsonContentType(response: HttpResponse<String>) {
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
            .withFailMessage("오류 응답의 Content-Type 이 JSON 이 아니다")
            .contains("application/json")
    }

    private fun bodyOf(response: HttpResponse<String>): Map<*, *> = json.readValue(response.body(), Map::class.java)

    private fun itemsOf(response: HttpResponse<String>): List<Map<*, *>> =
        (bodyOf(response).required("items") as List<*>).map { it as Map<*, *> }

    private fun idOf(response: HttpResponse<String>): String = bodyOf(response).required("id").toString()

    private fun nameOf(item: Map<*, *>): String = item.required(NAME_PROPERTY).toString()

    private fun uniqueName(): String = "공간${counter++}"

    companion object {
        private const val COLLECTION_PATH = "/workspaces"
        private const val ITEM_PATH = "/workspaces/{workspace_id}"
        private const val GET = "get"
        private const val POST = "post"
        private const val PATCH = "patch"
        private const val DELETE = "delete"

        private const val UNAUTHORIZED = 401
        private const val FORBIDDEN = 403
        private const val NOT_FOUND = 404
        private const val CONFLICT = 409

        private const val SINGLE_SCHEMA = "WorkspaceResponse"
        private const val ERROR_SCHEMA = "ErrorResponse"
        private const val NAME_PROPERTY = "name"

        private const val WWW_AUTHENTICATE = "WWW-Authenticate"
        private const val WWW_AUTHENTICATE_COMPONENT = "WWWAuthenticateBearer"

        /** 계약이 경로 인라인 예시에 붙인 이름들. 값이 아니라 이름이라 여기 적는다. */
        private const val NOT_FOUND_EXAMPLE = "not_found"
        private const val HAS_DOCUMENTS_EXAMPLE = "has_documents"
        private const val LAST_ONE_EXAMPLE = "last_one"

        private const val NOT_A_UUID = "not-a-uuid"

        private const val FORGED_TOKEN = "forged.token.value"
        private const val VALID_PASSWORD = "correct horse battery"

        /** 응답마다 값이 달라지는 헤더 — 집합 비교에서 뺀다(길이·날짜는 존재 자체가 갈리지 않는다). */

        private const val ABSENT = "absent"
        private const val OTHERS = "others"

        /** 경로당 표본 수. 홀수라 중앙값이 표본 하나로 정해진다. */
        private const val TIMING_SAMPLES = 21

        /** 두 경로를 섞는 순서. 고정 시드라 실패가 재현된다. */
        private const val TIMING_SEED = 20260819L

        /** 명세는 KDoc 에 있다 — 2.0 에서 좁힌 값이고 auth 의 로그인 게이트와 같다. */
        private const val MAX_TIMING_RATIO = 1.5

        /** 0 으로 나누지 않기 위한 바닥. 이보다 짧은 응답은 측정 분해능 밖이다. */
        private const val MIN_MEASURABLE_MILLIS = 0.05

        private const val NANOS_PER_MILLI = 1_000_000.0

        private var counter = 0

        /** 이 테스트만 쓰는 DB. 다른 기동 테스트의 행과 섞이지 않게 따로 만든다. */
        val database: DatabaseHandle by lazy { PostgresTestSupport.createEmptyDatabase("workspace_reach") }

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.jdbcUrl }
            registry.add("spring.datasource.username") { database.username }
            registry.add("spring.datasource.password") { database.password }
        }
    }
}

/** 이 테스트가 쓰는 서명 키. 계약 `x-auth.min_secret_bytes` 이상이어야 한다. */
const val WORKSPACE_REACH_TEST_SECRET: String = "workspace-test-signing-key-0123456789-abc"

/**
 * 스타 프로젝션 맵에서 필수 키를 꺼낸다. 없으면 끊는다 — `null` 을 흘려보내면
 * 「키가 없다」가 「값이 null 이다」로 둔갑해 단언이 무엇을 재는지 흐려진다.
 */
private fun Map<*, *>.required(key: String): Any = this[key] ?: error("응답에 $key 가 없다")
