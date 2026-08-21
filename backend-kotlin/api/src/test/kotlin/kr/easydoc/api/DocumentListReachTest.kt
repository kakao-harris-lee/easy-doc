package kr.easydoc.api

import kr.easydoc.api.support.ContractSpec
import kr.easydoc.api.support.MultipartBody
import kr.easydoc.api.support.OwnershipConcealment
import kr.easydoc.api.support.UploadFixtures
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
 * `GET /documents` 의 **실측** 계약 — 명세 §5 의 C-R·C-I 계층.
 *
 * 여기 있는 것: **DL-2 · DL-3 · DL-4 · DL-8 · DL-9 · DL-10 · DL-11**.
 *
 * **값 자리 불변식은 여기 없다** — `ValueSlotInvariantReachTest` 로 뽑았다(R-7). 그 불변식이
 * 이 클래스의 메서드로 살아 있는 동안은 **바닥 핀의 알갱이가 보호 대상보다 굵었다**: 클래스는
 * 남기고 메서드만 지우면 모든 게이트가 초록이었다(실측). 속성 하나에 클래스 하나가 그 처방이다.
 * (DL-1·DL-5·DL-6·DL-7 은 디스패처만으로 재는 것이라 [DocumentListContractTest] 가 진다.)
 *
 * ## 왜 MockMvc 가 아닌가 (명세 §5-1)
 *
 * **C-R** — DL-10·DL-11 은 **인증 실패 응답**이다. 인증이 입력 검증보다 먼저인지를
 * (X-A3) 목으로 재면 컨테이너·필터 체인이 빠진 배선을 재게 되고, 그런데도 통과한다.
 * 이 저장소는 헤더 쪽에서 이미 그 형태의 거짓 초록을 겪었다(계약 `x-phase3-measurement`).
 *
 * **C-I** — DL-4·DL-9 는 두 사용자와 두 자원이 실제로 있어야 뜻이 있고, DL-2·DL-8 은
 * 저장된 행이 있어야 성립한다.
 *
 * ## 문서를 API 로 만든다
 *
 * `INSERT` 를 직접 쓰지 않는다 — 그러면 목록이 읽는 행의 모양을 테스트가 정하게 되고,
 * 저장 경로가 실제로 쓰는 컬럼 조합과 갈릴 수 있다. `POST /documents` 를 거치면 목록이
 * 읽는 것이 **제품이 쓴 행**이다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["easydoc.auth.jwt-secret=$DOCUMENT_REACH_TEST_SECRET"],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentListReachTest {
    @LocalServerPort
    private var port: Int = 0

    private val json = ObjectMapper()

    // ================================================================ DL-2 · DL-3 — 항목 모양

    @Test
    @DisplayName("DL-2 항목마다 키 집합이 정확히 DocumentListItem.required — **완료 전 항목에서도 하나도 생략되지 않는다** (X-E2)")
    fun `목록 항목의 키가 계약 required 와 정확히 같다`() {
        val token = newAccount()
        createFromText(token, textBody("첫 번째 안내문"))
        createFromText(token, textBody("두 번째 안내문"))

        val items = itemsOf(list(token))

        // 접수 직후라 변환은 전부 `pending` 이다 — 그 상태에서도 `reviewed_at` 처럼
        // 값이 없는 필드의 **키가 나가야** 한다. 값이 널일 때 키를 빼는 직렬화 설정이
        // 켜지면 여기서 깨진다.
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

        val declared =
            ContractSpec
                .strings("components", "schemas", LIST_ITEM_SCHEMA, "properties", SOURCE_FORMAT_PROPERTY, "enum")
                .toSet()
        val observed = itemsOf(list(token)).map { it[SOURCE_FORMAT_PROPERTY].toString() }.toSet()

        assertThat(declared).isNotEmpty()
        // 두 입력 갈래가 **서로 다른 값**을 낸다는 것도 함께 잰다 — 하나만 관측하면
        // 「형식이 늘 같은 상수」인 구현도 통과한다.
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

        // ISO-8601 UTC 문자열은 사전순이 시간순과 같다(`WorkspaceResponse.created_at` 과 같은 형식).
        assertThat(createdAt).isSortedAccordingTo(Comparator.reverseOrder())
    }

    // ================================================================ DL-4 — 소유자 범위

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
        // 교집합 0 이 「범위가 갈린다」의 직접 관측이다. 개수만 재면 둘이 같은 행을 볼 때도 통과한다.
        assertThat(myIds).doesNotContainAnyElementsOf(theirIds)
    }

    // ================================================================ DL-8 — 다음 쪽 유무

    @Test
    @DisplayName("DL-8 다음 쪽이 있으면 참, 없으면 거짓이고 **총 개수 필드는 없다**")
    fun `다음 쪽 유무가 갈린다`() {
        val token = newAccount()
        repeat(DOCUMENTS_FOR_PAGING) { createFromText(token, textBody("문서 $it")) }

        val firstPage = bodyOf(list(token, limit = 1))
        val wholeSet = bodyOf(list(token, limit = DOCUMENTS_FOR_PAGING + 1))

        assertThat(firstPage[HAS_MORE_PROPERTY]).isEqualTo(true)
        assertThat(wholeSet[HAS_MORE_PROPERTY]).isEqualTo(false)
        // 「한 건 더 읽어」 판정하므로 **요청한 개수보다 많이 실리지 않는다**는 것도 함께 잰다.
        assertThat((firstPage[ITEMS_PROPERTY] as List<*>)).hasSize(1)
        // 총 개수 필드가 없다 — DL-1 의 「정확히」가 그 추가를 겸해 잡지만, 이 자리에서도 본다.
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

    // ================================================================ DL-9 — 남의 작업 공간

    @Test
    @DisplayName("DL-9 남의 작업 공간 식별자로 걸러 조회하면 **404** 다 — 빈 목록이 아니다 (X-B1)")
    fun `남의 작업 공간 필터는 404 다`() {
        val mine = newAccount()
        val theirs = newAccount()
        createFromText(theirs, textBody("남의 안내문"))

        val response = list(mine, workspaceId = defaultWorkspaceId(theirs))

        // 빈 목록으로 답하면 「그 작업 공간은 비어 있다」를 알려 주는 셈이고, 그것이 남의
        // 작업 공간의 존재를 확인하는 수단이 된다.
        assertDeclaredStatus(response, NOT_FOUND)
        assertThat(bodyOf(response)[DETAIL]).isInstanceOf(String::class.java)
        // 404 인데 목록 모양이 함께 나가면 「빈 목록이 아니다」가 무의미해진다.
        assertThat(bodyOf(response).keys.map { it.toString() }).doesNotContain(ITEMS_PROPERTY)
    }

    /**
     * **성질 P1 — 응답 구별 불가.** 판정은 [OwnershipConcealment] 한 벌이 진다.
     *
     * 종전 이 케이스는 상태와 **디코딩된 문자열**만 봤고 **헤더 단언이 0건**이었다
     * (`privacy-gate` 회차 2 X1-1). 그래서 남의 작업 공간 팔에만 헤더가 하나 붙는 회귀가
     * 이 자리에서 보이지 않았다 — 존재 여부가 본문이 아니라 헤더로 새는 형태다.
     *
     * **P2(거절 비용의 무상관)는 여기서 재지 않는다.** 목록에서는 그것이 새로 생기는 요구이고
     * (성공 경로의 일이 내용량에 비례한다), 정본 도구는 시간이 아니라 **문장 수**다 —
     * `kr.easydoc.infrastructure.document.JdbcDocumentStoreTest` 의 「거절 경로의 문장 수」가
     * 목록 팔까지 잰다(X1-2). 시간 축을 여기 붙이지 않은 사유는 그 회차가 실측으로 정했다:
     * 같은 변이가 남의 작업 공간 40행에서 비 1.0955 로 **침묵**하고 2,560행에서만 발화한다.
     */
    @Test
    @DisplayName("DL-9 없는 작업 공간과 **남의** 작업 공간의 응답이 상태·본문 **원시 바이트**·헤더 이름 집합까지 같다 (X-B2)")
    fun `없는 것과 남의 것이 구분되지 않는다`() {
        val mine = newAccount()
        val theirs = newAccount()

        val missing = listBytes(mine, UUID.randomUUID().toString())
        val someoneElse = listBytes(mine, defaultWorkspaceId(theirs))

        OwnershipConcealment.assertIndistinguishable("GET $DOCUMENTS_PATH?$WORKSPACE_ID_PARAM=", missing, someoneElse)
    }

    // ================================================================ DL-10 · DL-11 — 인증

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

        // 검증이 먼저 돌면 **토큰 없이 API 표면을 탐색**할 수 있다 — 어느 파라미터가
        // 유효한지가 401 대신 422 로 새어 나온다.
        assertDeclaredStatus(response, UNAUTHORIZED)
        assertThat(bodyOf(response)[DETAIL])
            .withFailMessage("위조 토큰 응답의 detail 이 문자열이 아니다 — 검증 실패 배열이 새어 나왔다")
            .isInstanceOf(String::class.java)
    }

    // ================================================================ 요청 조립

    private fun newAccount(): String {
        val email = "documentlist${counter++}@example.test"
        val credentials = json.writeValueAsString(mapOf("email" to email, "password" to VALID_PASSWORD))
        send(post(null, JSON_MEDIA_TYPE, credentials.toByteArray(Charsets.UTF_8), "/auth/signup"))
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
                // **이미 인코딩된 조각**을 그대로 싣는다. R-6 표본은 빈 값·공백처럼 인코딩
                // 자체가 재는 대상이라, 값을 다시 인코딩하면 그 대상이 바뀐다.
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

    /**
     * 같은 요청을 **바이트로** 받는다 — 디코딩을 지나지 않는 팔이다.
     *
     * 문자열 팔([send])과 함께 두는 이유: 다른 케이스들은 본문을 JSON 으로 읽어야 하고,
     * P1 만 바이트가 필요하다. 두 팔이 **같은 요청 조립**을 쓰도록 [get] 을 공유한다.
     */
    private fun listBytes(
        token: String,
        workspaceId: String,
    ): HttpResponse<ByteArray> =
        HttpClient.newHttpClient().send(
            get(token, "$DOCUMENTS_PATH?$WORKSPACE_ID_PARAM=$workspaceId").build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )

    // ================================================================ DB 확인

    private fun defaultWorkspaceId(token: String): String =
        database
            .queryFirstColumn(
                "SELECT id FROM workspaces WHERE user_id = '${subjectOf(token)}' ORDER BY created_at LIMIT 1",
            ).single()

    private fun subjectOf(token: String): String =
        kr.easydoc.api.support.TestJwt
            .payload(token)["sub"]
            .toString()

    // ================================================================ 단언 도구

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
