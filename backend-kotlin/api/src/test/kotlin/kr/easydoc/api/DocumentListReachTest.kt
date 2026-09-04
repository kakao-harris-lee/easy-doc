package kr.easydoc.api

import kr.easydoc.api.support.ContractSpec
import kr.easydoc.api.support.MultipartBody
import kr.easydoc.api.support.OwnershipConcealment
import kr.easydoc.api.support.UploadFixtures
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Optional
import java.util.UUID
import javax.net.ssl.SSLSession

/** `GET /documents` 의 실측 계약 — 명세 §5 의 C-R·C-I 계층. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["easydoc.auth.jwt-secret=$DOCUMENT_REACH_TEST_SECRET"],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentListReachTest {
    @LocalServerPort
    private var port: Int = 0

    private val json = ObjectMapper()

    @Test
    @DisplayName("DL-2 항목마다 키 집합이 정확히 DocumentListItem.required — **완료 전 항목에서도 하나도 생략되지 않는다** (X-E2)")
    fun `목록 항목의 키가 계약 required 와 정확히 같다`() {
        val token = newAccount()
        createFromText(token, textBody("첫 번째 안내문"))
        createFromText(token, textBody("두 번째 안내문"))

        val items = itemsOf(list(token))

        assertThat(items).hasSizeGreaterThanOrEqualTo(2)
        val declared = ContractSpec.schemaRequired(LIST_ITEM_SCHEMA)
        items.forEach { item ->
            assertThat(item.keys.map { it.toString() }.toSet())
                .withFailMessage("목록 항목의 키 집합이 계약 %s 와 다르다. 실제: %s", LIST_ITEM_SCHEMA, item.keys)
                .isEqualTo(declared)
        }
    }

    @Test
    @DisplayName("DL-3 source_format 값이 계약 enum 집합 안이다 — 집합을 계약에서 읽어 대조한다 (X-E4 인접)")
    fun `원본 형식이 계약 enum 안이다`() {
        val token = newAccount()
        createFromText(token, textBody("붙여넣기 본문"))
        upload(token, MultipartBody().file(FILE_PART, "안내문.docx", UploadFixtures.sampleDocx()))

        // 1.6.0 에서 이 속성이 `SourceFormat` 컴포넌트 `$ref` 가 됐다 — 값 집합은 그대로다.
        val declared = ContractSpec.schemaPropertyEnumResolved(LIST_ITEM_SCHEMA, SOURCE_FORMAT_PROPERTY).toSet()
        val observed = itemsOf(list(token)).map { it[SOURCE_FORMAT_PROPERTY].toString() }.toSet()

        assertThat(declared).isNotEmpty()

        assertThat(observed).hasSizeGreaterThanOrEqualTo(2)
        assertThat(declared).containsAll(observed)
    }

    @Test
    @DisplayName("목록이 최신순이다 — 계약 items 설명이 그렇게 적었다")
    fun `목록이 최신순이다`() {
        val token = newAccount()
        createFromText(token, textBody("먼저 올린 문서"))
        createFromText(token, textBody("나중에 올린 문서"))

        val createdAt = itemsOf(list(token)).map { it[CREATED_AT_PROPERTY].toString() }

        assertThat(createdAt).isSortedAccordingTo(Comparator.reverseOrder())
    }

    @Test
    @DisplayName("DL-4 타인 문서가 함께 있어도 응답에 타인 소유 항목이 **0건**이다")
    fun `남의 문서가 목록에 실리지 않는다`() {
        val mine = newAccount()
        val theirs = newAccount()
        createFromText(mine, textBody("내 안내문"))
        createFromText(theirs, textBody("남의 안내문"))

        val myIds = itemsOf(list(mine)).map { it[ID_PROPERTY].toString() }.toSet()
        val theirIds = itemsOf(list(theirs)).map { it[ID_PROPERTY].toString() }.toSet()

        assertThat(myIds).hasSize(1)
        assertThat(theirIds).hasSize(1)

        assertThat(myIds).doesNotContainAnyElementsOf(theirIds)
    }

    @Test
    @DisplayName("DL-8 다음 쪽이 있으면 참, 없으면 거짓이고 **총 개수 필드는 없다**")
    fun `다음 쪽 유무가 갈린다`() {
        val token = newAccount()
        repeat(DOCUMENTS_FOR_PAGING) { createFromText(token, textBody("문서 $it")) }

        val firstPage = bodyOf(list(token, limit = 1))
        val wholeSet = bodyOf(list(token, limit = DOCUMENTS_FOR_PAGING + 1))

        assertThat(firstPage[HAS_MORE_PROPERTY]).isEqualTo(true)
        assertThat(wholeSet[HAS_MORE_PROPERTY]).isEqualTo(false)

        assertThat((firstPage[ITEMS_PROPERTY] as List<*>)).hasSize(1)

        assertThat(wholeSet.keys.map { it.toString() }.toSet()).isEqualTo(ContractSpec.schemaRequired(LIST_SCHEMA))
    }

    @Test
    @DisplayName("시작점을 넘겨 가며 읽으면 같은 문서가 두 번 보이지도, 빠지지도 않는다")
    fun `페이지 경계에서 중복과 누락이 없다`() {
        val token = newAccount()
        repeat(DOCUMENTS_FOR_PAGING) { createFromText(token, textBody("문서 $it")) }

        val paged =
            (0 until DOCUMENTS_FOR_PAGING).flatMap { offset ->
                itemsOf(list(token, limit = 1, offset = offset)).map { it[ID_PROPERTY].toString() }
            }

        assertThat(paged).hasSize(DOCUMENTS_FOR_PAGING)
        assertThat(paged).doesNotHaveDuplicates()
        assertThat(paged).containsExactlyInAnyOrderElementsOf(
            itemsOf(list(token, limit = DOCUMENTS_FOR_PAGING)).map { it[ID_PROPERTY].toString() },
        )
    }

    @Test
    @DisplayName("DL-9 남의 작업 공간 식별자로 걸러 조회하면 **404** 다 — 빈 목록이 아니다 (X-B1)")
    fun `남의 작업 공간 필터는 404 다`() {
        val mine = newAccount()
        val theirs = newAccount()
        createFromText(theirs, textBody("남의 안내문"))

        val response = list(mine, workspaceId = defaultWorkspaceId(theirs))

        assertDeclaredStatus(response, NOT_FOUND)
        assertThat(bodyOf(response)[DETAIL]).isInstanceOf(String::class.java)

        assertThat(bodyOf(response).keys.map { it.toString() }).doesNotContain(ITEMS_PROPERTY)
    }

    /** 성질 P1 — 응답 구별 불가. 판정은 [OwnershipConcealment] 한 벌이 진다. */
    @Test
    @DisplayName("DL-9 없는 작업 공간과 **남의** 작업 공간의 응답이 상태·본문 **원시 바이트**·헤더 이름 집합까지 같다 (X-B2)")
    fun `없는 것과 남의 것이 구분되지 않는다`() {
        val mine = newAccount()
        val theirs = newAccount()

        val missing = listBytes(mine, UUID.randomUUID().toString())
        val someoneElse = listBytes(mine, defaultWorkspaceId(theirs))

        OwnershipConcealment.assertIndistinguishable("GET $DOCUMENTS_PATH?$WORKSPACE_ID_PARAM=", missing, someoneElse)
    }

    /** 공유 판정이 세 축 모두에서 빨개질 수 있다 — [OwnershipConcealment] 의 대조 프로브. */
    @Test
    @DisplayName("공유 판정이 상태·바이트·헤더 **세 축 모두에서** 구별한다 (대조 프로브)")
    fun `공유 판정이 세 축 모두에서 구별한다`() {
        val base =
            OwnershipConcealment.Observation(
                status = NOT_FOUND,
                body = PROBE_BODY.toByteArray(Charsets.UTF_8),
                headerNames = setOf("content-type"),
            )
        val differing =
            mapOf(
                "상태" to OwnershipConcealment.Observation(UNAUTHORIZED, base.body, base.headerNames),
                "바이트" to
                    OwnershipConcealment.Observation(
                        base.status,
                        PROBE_OTHER_BODY.toByteArray(Charsets.UTF_8),
                        base.headerNames,
                    ),
                "헤더" to
                    OwnershipConcealment.Observation(
                        base.status,
                        base.body,
                        base.headerNames + "x-probe",
                    ),
            )

        differing.forEach { (axis, other) ->
            assertThatThrownBy { OwnershipConcealment.assertIndistinguishable("대조 프로브", base, other) }
                .withFailMessage("공유 판정이 %s 축의 차이를 구별하지 못한다 — 그 축의 초록은 아무 뜻이 없다", axis)
                .isInstanceOf(AssertionError::class.java)
        }
    }

    /**
     * 위 프로브는 `Observation` 을 직접 만든다. 실사용 여섯 자리는 `observe(response)` 를
     * 지나므로, 그 어댑터가 뭉개지면 **두 팔이 대칭으로** 뭉개져 여섯 자리가 조용해진다.
     */
    @Test
    @DisplayName("**`observe` 어댑터를 지나서도** 세 축이 구별된다 — 실사용 여섯 자리가 쓰는 갈래의 대조 프로브")
    fun `어댑터를 지나는 판정도 세 축을 구별한다`() {
        val base = probeResponse(NOT_FOUND, PROBE_BODY, mapOf(CONTENT_TYPE_HEADER to JSON_MEDIA_TYPE))

        val differing =
            mapOf(
                "상태" to probeResponse(UNAUTHORIZED, PROBE_BODY, mapOf(CONTENT_TYPE_HEADER to JSON_MEDIA_TYPE)),
                "바이트" to probeResponse(NOT_FOUND, PROBE_OTHER_BODY, mapOf(CONTENT_TYPE_HEADER to JSON_MEDIA_TYPE)),
                "헤더" to
                    probeResponse(
                        NOT_FOUND,
                        PROBE_BODY,
                        mapOf(CONTENT_TYPE_HEADER to JSON_MEDIA_TYPE, PROBE_HEADER to PROBE_HEADER_VALUE),
                    ),
            )

        differing.forEach { (axis, other) ->
            assertThatThrownBy { OwnershipConcealment.assertIndistinguishable("어댑터 프로브", base, other) }
                .withFailMessage(
                    "`observe` 를 지나는 갈래가 %s 축의 차이를 구별하지 못한다 — 실사용 여섯 자리가 " +
                        "그 축을 재지 않고 있다는 뜻이다",
                    axis,
                ).isInstanceOf(AssertionError::class.java)
        }
    }

    /** 면제가 커지면 헤더 축의 분모가 깎인다. 상한 자신은 git 이력 라쳇이 진다. */
    @Test
    @DisplayName("은폐 목록(`VARIABLE_HEADERS`)이 상한 안이다 — 면제를 늘리는 편집이 조용히 통과하지 않는다")
    fun `은폐 목록이 상한 안이다`() {
        assertThat(OwnershipConcealment.VARIABLE_HEADERS)
            .withFailMessage(
                "헤더 이름 면제가 %d 개다 — 상한 %d 을 넘었다. 항목을 더하면 그 헤더로 「없는 것」과 " +
                    "「남의 것」이 갈려도 초록이 된다. 실제 목록: %s",
                OwnershipConcealment.VARIABLE_HEADERS.size,
                OwnershipConcealment.MAX_VARIABLE_HEADERS,
                OwnershipConcealment.VARIABLE_HEADERS,
            ).hasSizeLessThanOrEqualTo(OwnershipConcealment.MAX_VARIABLE_HEADERS)
    }

    private fun probeResponse(
        status: Int,
        body: String,
        headers: Map<String, String>,
    ): HttpResponse<ByteArray> = ProbeResponse(status, body.toByteArray(Charsets.UTF_8), headers)

    private class ProbeResponse(
        private val status: Int,
        private val bytes: ByteArray,
        headers: Map<String, String>,
    ) : HttpResponse<ByteArray> {
        private val wrapped: HttpHeaders =
            HttpHeaders.of(headers.mapValues { (_, value) -> listOf(value) }) { _, _ -> true }

        override fun statusCode(): Int = status

        override fun request(): HttpRequest = HttpRequest.newBuilder(PROBE_URI).build()

        override fun previousResponse(): Optional<HttpResponse<ByteArray>> = Optional.empty()

        override fun headers(): HttpHeaders = wrapped

        override fun body(): ByteArray = bytes

        override fun sslSession(): Optional<SSLSession> = Optional.empty()

        override fun uri(): URI = PROBE_URI

        override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1

        private companion object {
            val PROBE_URI: URI = URI.create("http://ownership-probe.invalid/")
        }
    }

    @Test
    @DisplayName("DL-10 Authorization 이 없으면 401 이고 WWW-Authenticate 가 붙는다 (X-A1)")
    fun `토큰이 없으면 401 이다`() {
        val response = send(get(null, DOCUMENTS_PATH))

        assertDeclaredStatus(response, UNAUTHORIZED)
        assertThat(response.headers().firstValue(WWW_AUTHENTICATE))
            .withFailMessage("401 에 WWW-Authenticate 가 없다 — 클라이언트가 재인증 방식을 알 수 없다")
            .hasValue(ContractSpec.headerConst(WWW_AUTHENTICATE_COMPONENT))
    }

    @Test
    @DisplayName("DL-11 위조 토큰 + **범위 밖 페이지 파라미터** → 401 이다(422 가 아니다) — 계약 산문이 지목한 실측 케이스 (X-A3)")
    fun `인증이 쿼리 파라미터 검증보다 먼저다`() {
        val range = ContractSpec.inputLimitRange(LIST_LIMIT_KEY)
        val above = range.aboveMax ?: error("계약이 $LIST_LIMIT_KEY 에 상한을 두지 않았다 — 이 케이스를 세울 수 없다")

        val response =
            send(
                get(FORGED_TOKEN, "$DOCUMENTS_PATH?$LIST_LIMIT_PARAM=$above&$LIST_OFFSET_PARAM=${range.belowMin}"),
            )

        assertDeclaredStatus(response, UNAUTHORIZED)
        assertThat(bodyOf(response)[DETAIL])
            .withFailMessage("위조 토큰 응답의 detail 이 문자열이 아니다 — 검증 실패 배열이 새어 나왔다")
            .isInstanceOf(String::class.java)
    }

    private fun newAccount(): String {
        val email = "documentlist${counter++}@example.test"
        val credentials = json.writeValueAsString(mapOf("email" to email, "password" to VALID_PASSWORD))
        send(post(null, JSON_MEDIA_TYPE, credentials.toByteArray(Charsets.UTF_8), "/auth/signup"))
        // 이메일 인증 게이트는 `POST /documents` 앞이다 — 이 파일은 그 게이트를 재지 않으므로
        // 실물 인증 흐름 대신 저장소를 직접 인증 완료로 만든다.
        database.execute("UPDATE users SET email_verified_at = now() WHERE email = '$email'")
        val login = send(post(null, JSON_MEDIA_TYPE, credentials.toByteArray(Charsets.UTF_8), "/auth/login"))
        return bodyOf(login).required("access_token").toString()
    }

    private fun createFromText(
        token: String,
        body: String,
    ): HttpResponse<String> = send(post(token, JSON_MEDIA_TYPE, body.toByteArray(Charsets.UTF_8), DOCUMENTS_PATH))

    private fun upload(
        token: String,
        body: MultipartBody,
    ): HttpResponse<String> = send(post(token, body.contentType(), body.build(), DOCUMENTS_PATH))

    private fun textBody(
        text: String,
        workspaceId: String? = null,
    ): String =
        json.writeValueAsString(
            buildMap {
                put("text", text)
                if (workspaceId != null) put("workspace_id", workspaceId)
            },
        )

    private fun list(
        token: String,
        limit: Int? = null,
        offset: Int? = null,
        workspaceId: String? = null,
        raw: String? = null,
    ): HttpResponse<String> {
        val query =
            buildList {
                if (limit != null) add("$LIST_LIMIT_PARAM=$limit")
                if (offset != null) add("$LIST_OFFSET_PARAM=$offset")
                if (workspaceId != null) add("$WORKSPACE_ID_PARAM=$workspaceId")

                if (raw != null) add(raw)
            }.joinToString("&")
        return send(get(token, if (query.isEmpty()) DOCUMENTS_PATH else "$DOCUMENTS_PATH?$query"))
    }

    private fun get(
        token: String?,
        path: String,
    ): HttpRequest.Builder {
        val builder = HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).GET()
        token?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    private fun post(
        token: String?,
        contentType: String,
        body: ByteArray,
        path: String,
    ): HttpRequest.Builder {
        val builder =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
        token?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    private fun send(builder: HttpRequest.Builder): HttpResponse<String> =
        HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))

    /** 같은 요청을 바이트로 받는다 — 디코딩을 지나지 않는 팔이다. */
    private fun listBytes(
        token: String,
        workspaceId: String,
    ): HttpResponse<ByteArray> =
        HttpClient.newHttpClient().send(
            get(token, "$DOCUMENTS_PATH?$WORKSPACE_ID_PARAM=$workspaceId").build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )

    private fun defaultWorkspaceId(token: String): String =
        database
            .queryFirstColumn(
                "SELECT id FROM workspaces WHERE user_id = '${subjectOf(token)}' ORDER BY created_at LIMIT 1",
            ).single()

    private fun subjectOf(token: String): String =
        kr.easydoc.api.support.TestJwt
            .payload(token)["sub"]
            .toString()

    private fun assertDeclaredStatus(
        response: HttpResponse<String>,
        status: Int,
    ) {
        assertThat(response.statusCode()).isEqualTo(status)
        assertThat(ContractSpec.responseStatuses(DOCUMENTS_PATH, GET))
            .withFailMessage("계약이 GET %s 에 %d 를 선언하지 않는다", DOCUMENTS_PATH, status)
            .contains(status.toString())
    }

    private fun bodyOf(response: HttpResponse<String>): Map<*, *> = json.readValue(response.body(), Map::class.java)

    private fun itemsOf(response: HttpResponse<String>): List<Map<*, *>> {
        assertThat(response.statusCode()).isEqualTo(ContractSpec.successStatus(DOCUMENTS_PATH, GET))
        return (bodyOf(response)[ITEMS_PROPERTY] as List<*>).map { it as Map<*, *> }
    }

    private fun Map<*, *>.required(key: String): Any = this[key] ?: error("응답에 $key 가 없다")

    companion object {
        private const val DOCUMENTS_PATH = "/documents"
        private const val GET = "get"
        private const val DETAIL = "detail"

        private const val UNAUTHORIZED = 401
        private const val NOT_FOUND = 404

        private const val LIST_SCHEMA = "DocumentListResponse"
        private const val LIST_ITEM_SCHEMA = "DocumentListItem"

        private const val ID_PROPERTY = "id"
        private const val ITEMS_PROPERTY = "items"
        private const val HAS_MORE_PROPERTY = "has_more"
        private const val CREATED_AT_PROPERTY = "created_at"
        private const val SOURCE_FORMAT_PROPERTY = "source_format"

        /** 계약 `x-input-limits` 의 노드 이름. */
        private const val LIST_LIMIT_KEY = "list_limit"

        /** 계약 `paths./documents.get.parameters` 의 이름 셋. */
        private const val LIST_LIMIT_PARAM = "limit"
        private const val LIST_OFFSET_PARAM = "offset"
        private const val WORKSPACE_ID_PARAM = "workspace_id"

        private const val FILE_PART = "file"
        private const val JSON_MEDIA_TYPE = "application/json"

        private const val WWW_AUTHENTICATE = "WWW-Authenticate"
        private const val WWW_AUTHENTICATE_COMPONENT = "WWWAuthenticateBearer"

        /** 대조 프로브의 합성 본문 둘. 길이가 달라 바이트 축이 반드시 갈린다. */
        private const val PROBE_BODY = "{\"detail\":\"가\"}"
        private const val PROBE_OTHER_BODY = "{\"detail\":\"가나\"}"

        private const val CONTENT_TYPE_HEADER = "Content-Type"
        private const val PROBE_HEADER = "X-Ownership-Probe"
        private const val PROBE_HEADER_VALUE = "1"

        private const val FORGED_TOKEN = "forged.token.value"
        private const val VALID_PASSWORD = "correct horse battery"

        /** 페이지 경계를 여러 번 넘기려면 셋 이상이어야 한다. 값 자체는 계약과 무관하다. */
        private const val DOCUMENTS_FOR_PAGING = 3

        private var counter = 0

        /** 이 테스트만 쓰는 DB. 다른 테스트의 행과 섞이면 목록 개수 단언이 무너진다. */
        val database: DatabaseHandle by lazy { PostgresTestSupport.createEmptyDatabase("document_list") }

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.jdbcUrl }
            registry.add("spring.datasource.username") { database.username }
            registry.add("spring.datasource.password") { database.password }
        }
    }
}
