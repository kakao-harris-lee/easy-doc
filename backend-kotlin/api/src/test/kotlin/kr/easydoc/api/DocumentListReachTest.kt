package kr.easydoc.api

import kr.easydoc.api.support.ContractQueryParameter
import kr.easydoc.api.support.ContractSpec
import kr.easydoc.api.support.MultipartBody
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

    // ================================================================ R-6 — 값 자리 불변식

    @Test
    @DisplayName("R-6 불변식(긍정) — 성공 응답은 요청이 **지정한 값을 반영한다**")
    fun `지정한 값이 응답에 반영된다`() {
        val token = newAccount()
        createFromText(token, textBody("기본 공간 문서"))
        val second = createWorkspace(token, "둘째 공간")
        createFromText(token, textBody("둘째 공간 문서", workspaceId = second))

        ContractSpec.queryParameters(DOCUMENTS_PATH, GET).forEach { parameter ->
            if (parameter.name in ContractSpec.schemaRequired(LIST_SCHEMA)) {
                // **응답에 그 이름의 필드가 있는 파라미터**는 되돌려주는 값으로 반영을 잰다.
                // 「어느 파라미터가 메아리를 갖는가」를 코드에 적지 않고 응답 스키마에서 읽는다.
                val sent = ContractSpec.inputLimitRange(limitNodeOf(parameter.name)).min
                val body = bodyOf(list(token, raw = "${parameter.name}=$sent"))
                assertThat(body[parameter.name])
                    .withFailMessage(
                        "%s=%d 를 보냈는데 응답이 %s 를 되돌려준다 — 지정한 값이 반영되지 않았다",
                        parameter.name,
                        sent,
                        body[parameter.name],
                    ).isEqualTo(sent)
                // 부호가 붙은 형태도 **해석되는** 입력이다 — 같은 값으로 반영돼야 한다.
                assertThat(bodyOf(list(token, raw = "${parameter.name}=%2B$sent"))[parameter.name]).isEqualTo(sent)
                // 되돌려준 값이 **실제로 쓰였는지**도 본다 — 메아리만 맞추고 무시하는 구현을
                // 배제한다. 실린 항목 수는 되돌려준 페이지 크기를 넘을 수 없다.
                assertThat((body[ITEMS_PROPERTY] as List<*>).size).isLessThanOrEqualTo(body[LIMIT_PROPERTY] as Int)
            } else {
                // 메아리 필드가 없는 파라미터는 **효과**로 잰다. `workspace_id` 가 그것이고,
                // 효과는 「그 작업 공간의 문서만」이다.
                val filtered = itemsOf(list(token, raw = "${parameter.name}=$second"))
                val all = itemsOf(list(token))
                assertThat(all.size)
                    .withFailMessage("전체 목록이 2건 미만이라 필터 효과를 구별할 수 없다 — 이 케이스의 전제가 깨졌다")
                    .isGreaterThan(filtered.size)
                assertThat(filtered).hasSize(1)
            }
        }
    }

    @Test
    @DisplayName("R-6 불변식(부정) — **값 자리가 있으나 선언 타입으로 해석되지 않는 입력**은 성공 응답을 만들지 못한다")
    fun `해석되지 않는 값 자리는 성공하지 못한다`() {
        val token = newAccount()
        createFromText(token, textBody("문서"))
        val declared = ContractSpec.queryParameters(DOCUMENTS_PATH, GET)
        assertThat(declared)
            .withFailMessage("계약이 이 오퍼레이션에 쿼리 파라미터를 하나도 선언하지 않았다 — 이 케이스는 아무것도 재지 않는다")
            .isNotEmpty()

        val slips = mutableListOf<String>()
        declared.forEach { parameter ->
            uninterpretableSamples(parameter).forEach { (label, encoded) ->
                val response = list(token, raw = "${parameter.name}=$encoded")
                if (response.statusCode() in SUCCESS_RANGE) {
                    slips += "${parameter.name} $label → ${response.statusCode()} ${response.body().take(120)}"
                    return@forEach
                }
                // 거절이면 **계약이 선언한 상태**여야 하고 모양은 스키마 층(배열)이어야 한다.
                assertThat(ContractSpec.responseStatuses(DOCUMENTS_PATH, GET))
                    .withFailMessage(
                        "%s %s 의 거절 상태 %d 를 계약이 선언하지 않는다",
                        parameter.name,
                        label,
                        response.statusCode(),
                    ).contains(response.statusCode().toString())
                assertValidationArrayFor(response, parameter.name)
            }
        }

        assertThat(slips)
            .withFailMessage(
                "값 자리가 있으나 선언 타입으로 해석되지 않는 입력이 **성공 응답**을 받았다 — " +
                    "프레임워크가 그것을 기본값·미지정으로 흡수했다는 뜻이고, 계약이 스키마 층 판정을 " +
                    "요구한 자리에서 그 층을 우회한 것이다.\n%s",
                slips.joinToString("\n") { "  - $it" },
            ).isEmpty()
    }

    /**
     * 그 파라미터의 **선언 타입으로 해석되지 않는** 표본들 — 동치류로 덮는다.
     *
     * 열거하는 것은 값이 아니라 **동치류**다: 빈 자리 · 공백뿐 · 그 타입의 문법이 아님 ·
     * (정수면) 표현 범위 초과. 이 넷이 「값 자리가 있으나 그 타입으로 해석되지 않는다」는
     * 종류를 덮는다 — 자리가 비었거나(앞 둘), 문법이 아니거나(셋째), 문법이지만 담기지
     * 않는다(넷째).
     *
     * **부호가 붙은 형태(`+5`)는 여기 없다.** 그것은 해석되는 입력이므로 긍정 케이스가
     * 「반영된다」로 잰다 — 동치류를 값 목록으로 다루면 이 구별이 사라진다.
     *
     * 선언 타입은 계약에서 읽는다. 모르는 타입이면 **끊는다** — 조용히 표본 0건이 되면
     * 그 파라미터는 검사받지 않는다.
     */
    private fun uninterpretableSamples(parameter: ContractQueryParameter): List<Pair<String, String>> {
        val common = listOf("빈 자리" to "", "공백뿐" to "%20")
        return when (declaredKindOf(parameter)) {
            INTEGER_KIND -> common + listOf("정수 문법 아님" to "abc", "표현 범위 초과" to "99999999999999999999")
            UUID_KIND -> common + listOf("UUID 문법 아님" to "abc")
            else -> error("계약이 ${parameter.name} 에 선언한 타입을 이 표본 생성기가 모른다: ${parameter.schema}")
        }
    }

    /** 계약 파라미터 스키마의 선언 타입. `anyOf` 는 널이 아닌 갈래를 읽는다. */
    private fun declaredKindOf(parameter: ContractQueryParameter): String {
        val direct = parameter.schema["type"]?.toString()?.takeIf { it != NULL_TYPE }
        val branch =
            (parameter.schema["anyOf"] as? List<*>)
                ?.filterIsInstance<Map<*, *>>()
                ?.firstOrNull { it["type"]?.toString() != NULL_TYPE }
        return direct
            ?: branch?.get("format")?.toString()
            ?: branch?.get("type")?.toString()
            ?: UNKNOWN_KIND
    }

    /** 그 파라미터의 경계가 사는 `x-input-limits` 노드 이름. 계약이 `list_` 접두로 둔다. */
    private fun limitNodeOf(parameterName: String): String = "list_$parameterName"

    private fun assertValidationArrayFor(
        response: HttpResponse<String>,
        parameterName: String,
    ) {
        val detail = bodyOf(response)[DETAIL]
        assertThat(detail)
            .withFailMessage("%s 의 거절 detail 이 배열이 아니다 — 스키마 층 실패는 배열이다: %s", parameterName, detail)
            .isInstanceOf(List::class.java)
        val items = (detail as List<*>).map { it as Map<*, *> }
        assertThat(items).isNotEmpty()
        val declaredKeys = ContractSpec.schemaRequired(VALIDATION_ITEM_SCHEMA)
        items.forEach { item ->
            assertThat(item.keys.map { it.toString() }.toSet()).isEqualTo(declaredKeys)
        }
        assertThat(items.map { it[LOC_KEY] })
            .withFailMessage("거절 항목이 %s 를 지목하지 않는다: %s", parameterName, items)
            .contains(listOf(QUERY_LOCATION, parameterName))
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

    @Test
    @DisplayName("DL-9 없는 작업 공간과 **남의** 작업 공간의 응답 바이트가 같다 (X-B2)")
    fun `없는 것과 남의 것이 구분되지 않는다`() {
        val mine = newAccount()
        val theirs = newAccount()

        val missing = list(mine, workspaceId = UUID.randomUUID().toString())
        val someoneElse = list(mine, workspaceId = defaultWorkspaceId(theirs))

        assertThat(missing.statusCode()).isEqualTo(someoneElse.statusCode())
        assertThat(missing.body()).isEqualTo(someoneElse.body())
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

    private fun createWorkspace(
        token: String,
        name: String,
    ): String {
        val body = json.writeValueAsString(mapOf("name" to name))
        val response = send(post(token, JSON_MEDIA_TYPE, body.toByteArray(Charsets.UTF_8), "/workspaces"))
        return bodyOf(response).required("id").toString()
    }

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

        private const val LIMIT_PROPERTY = "limit"
        private const val VALIDATION_ITEM_SCHEMA = "ValidationErrorItem"
        private const val LOC_KEY = "loc"

        /** 계약 `ValidationFailed.examples.query_range` 의 `loc` 첫 칸. */
        private const val QUERY_LOCATION = "query"

        /** OpenAPI 타입·형식 어휘. 표본 생성기가 선언 타입을 가르는 데 쓴다. */
        private const val INTEGER_KIND = "integer"
        private const val UUID_KIND = "uuid"
        private const val NULL_TYPE = "null"
        private const val UNKNOWN_KIND = "?"

        private val SUCCESS_RANGE = 200..299

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
