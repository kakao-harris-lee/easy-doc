package kr.easydoc.api

import kr.easydoc.api.config.TypedValueSlotInterceptor
import kr.easydoc.api.support.ContractQueryParameter
import kr.easydoc.api.support.ContractSpec
import kr.easydoc.api.support.ContractValueSlot
import kr.easydoc.api.support.ServedOperations
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.core.env.Environment
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * 값 자리 불변식의 전용 강제자 — 「성공 응답은 요청이 지정한 값을 반영한다. 반영할 것이
 * 없으면 성공하지 못한다.」
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["easydoc.auth.jwt-secret=$DOCUMENT_REACH_TEST_SECRET"],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ValueSlotInvariantReachTest {
    @LocalServerPort
    private var port: Int = 0

    /** 실제로 매핑된 표면. 손 목록을 두지 않는 이유는 [ServedOperations] KDoc 에 있다. */
    @Autowired
    private lateinit var handlerMapping: RequestMappingHandlerMapping

    @Autowired
    private lateinit var environment: Environment

    private val json = ObjectMapper()

    @Test
    @DisplayName("긍정 — 성공 응답은 요청이 **지정한 값을 반영한다**")
    fun `지정한 값이 응답에 반영된다`() {
        val token = newAccount()
        createDocument(token)
        val second = createWorkspace(token, "둘째 공간")
        createDocument(token, workspaceId = second)

        ContractSpec.queryParameters(DOCUMENTS_PATH, GET).forEach { parameter ->
            if (parameter.name in ContractSpec.schemaRequired(LIST_SCHEMA)) {
                assertEchoed(token, parameter)
            } else {
                assertFiltered(token, parameter, second)
            }
        }
    }

    /** 응답에 그 이름의 필드가 있는 파라미터는 되돌려주는 값으로 반영을 잰다. */
    private fun assertEchoed(
        token: String,
        parameter: ContractQueryParameter,
    ) {
        val sent = ContractSpec.inputLimitRange(limitNodeOf(parameter.name)).min
        val body = bodyOf(list(token, "${parameter.name}=$sent"))

        assertThat(body[parameter.name])
            .withFailMessage(
                "%s=%d 를 보냈는데 응답이 %s 를 되돌려준다 — 지정한 값이 반영되지 않았다",
                parameter.name,
                sent,
                body[parameter.name],
            ).isEqualTo(sent)

        assertThat(bodyOf(list(token, "${parameter.name}=%2B$sent"))[parameter.name]).isEqualTo(sent)

        assertThat((body[ITEMS_PROPERTY] as List<*>).size).isLessThanOrEqualTo(body[LIMIT_PROPERTY] as Int)
    }

    /** 메아리 필드가 없는 파라미터는 효과로 잰다. `workspace_id` 의 효과는 「그 작업 공간의 문서만」이다. */
    private fun assertFiltered(
        token: String,
        parameter: ContractQueryParameter,
        workspaceId: String,
    ) {
        val filtered = itemsOf(list(token, "${parameter.name}=$workspaceId"))
        val all = itemsOf(list(token, null))

        assertThat(all.size)
            .withFailMessage("전체 목록이 2건 미만이라 필터 효과를 구별할 수 없다 — 이 케이스의 전제가 깨졌다")
            .isGreaterThan(filtered.size)
        assertThat(filtered).hasSize(1)
    }

    @Test
    @DisplayName("부정(쿼리) — 매핑된 **모든** 오퍼레이션에서, 선언 타입으로 해석되지 않는 값 자리는 성공 응답을 만들지 못한다")
    fun `해석되지 않는 쿼리 값 자리는 성공하지 못한다`() {
        val token = newAccount()
        createDocument(token)
        val live = liveSlots().filter { it.slot.location == QUERY_LOCATION }
        assertThat(live)
            .withFailMessage("매핑된 오퍼레이션에 쿼리 값 자리가 하나도 없다 — 이 케이스는 아무것도 재지 않는다")
            .isNotEmpty()

        val slips = mutableListOf<String>()
        live.forEach { (slot, method) ->
            val parameter = queryParameterOf(slot)
            uninterpretableSamples(parameter).forEach { (label, encoded) ->
                val response = sendToSlot(token, slot, method, "${slot.name}=$encoded")
                if (response.statusCode() in SUCCESS_RANGE) {
                    slips += "${slot.label} $label → ${response.statusCode()} ${response.body().take(120)}"
                    return@forEach
                }
                assertDeclaredStatus(response, slot.path, method, slot.name, label)
                assertValidationArray(response, QUERY_LOCATION, slot.name)
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

    @Test
    @DisplayName("부정(경로 변수) — 매핑된 **모든** 오퍼레이션에서 공백뿐인 경로 조각이 흡수되지 않고, 계약이 선언한 상태로 거절된다")
    fun `해석되지 않는 경로 값 자리는 성공하지 못한다`() {
        val token = newAccount()
        val live = liveSlots().filter { it.slot.location == PATH_LOCATION }
        assertThat(live)
            .withFailMessage("매핑된 오퍼레이션에 경로 값 자리가 하나도 없다 — 이 케이스는 아무것도 재지 않는다")
            .isNotEmpty()

        live.forEach { (slot, method) ->
            val response = sendToSlot(token, slot, method, rawQuery = null)

            assertThat(response.statusCode() in SUCCESS_RANGE)
                .withFailMessage("%s 의 공백 경로 조각이 성공 응답을 받았다 — 흡수됐다", slot.label)
                .isFalse()
            assertDeclaredStatus(response, slot.path, method, slot.name, "공백뿐")
            assertValidationArray(response, PATH_LOCATION, slot.name)
        }
    }

    /**
     * 분모 정확 분할 — 계약이 선언한 값 자리는 「매핑됨(위 두 케이스가 잰다)」과
     * 「아직 매핑 안 됨」 둘 중 정확히 하나다.
     */
    @Test
    @DisplayName("분모 — 계약의 값 자리 전수가 「매핑됨」과 「미구현」으로 정확히 갈리고, 매핑된 쪽이 비어 있지 않다")
    fun `계약의 값 자리 전수가 분류된다`() {
        val declared = ContractSpec.valueSlots()
        assertThat(declared)
            .withFailMessage("계약에서 값 자리를 하나도 읽지 못했다 — 이 분모는 아무것도 재지 않는다")
            .isNotEmpty()

        val live = liveSlots()
        val liveSlotSet = live.map { it.slot }.toSet()
        val unmapped = declared.filterNot { it in liveSlotSet }

        assertThat(liveSlotSet)
            .withFailMessage("매핑된 값 자리가 0건이다 — 두 부정 케이스가 표본 0건으로 통과한다")
            .isNotEmpty()

        assertThat(liveSlotSet.size + unmapped.size)
            .withFailMessage(
                "값 자리 %d 개 중 매핑됨 %d + 미구현 %d 로 갈리지 않는다 — 분류가 겹치거나 빠졌다",
                declared.size,
                liveSlotSet.size,
                unmapped.size,
            ).isEqualTo(declared.size)

        println("[값 자리 분모] 매핑됨 ${liveSlotSet.size} · 미구현 ${unmapped.size}: ${unmapped.map { it.label }}")
    }

    /**
     * 인증이 이 가드보다 먼저다. **인증이 걸린 오퍼레이션만** 잰다 — 계약이
     * `security: []` 로 연 오퍼레이션(예: `oauthStart`)은 인증 자체가 없으니 이 순서
     * 주장이 성립하지 않는다(재는 것이 없어 공허하게 통과하는 것도 아니다 — 애초에
     * 대상이 아니다).
     */
    @Test
    @DisplayName("순서 — 토큰 없는 **공백 쿼리 값 자리**는 422 가 아니라 401 이다 (X-A3)")
    fun `인증이 공백 쿼리 값 자리 거절보다 먼저다`() {
        val slots = liveSlots().filter { it.slot.location == QUERY_LOCATION && requiresAuth(it) }
        assertThat(slots).withFailMessage("매핑된 쿼리 값 자리가 없다 — 이 케이스는 아무것도 재지 않는다").isNotEmpty()

        slots.forEach { (slot, method) ->
            val response =
                sendToSlot(null, slot, method, "${slot.name}=$BLANK_SEGMENT")

            assertUnauthorized(response, slot)
        }
    }

    /** 위 쿼리 케이스와 같은 범위 제한(인증이 걸린 오퍼레이션만) — 그 KDoc 참고. */
    @Test
    @DisplayName("순서 — 토큰 없는 **공백 경로 조각**은 422 가 아니라 401 이다 (X-A3)")
    fun `인증이 공백 경로 값 자리 거절보다 먼저다`() {
        val slots = liveSlots().filter { it.slot.location == PATH_LOCATION && requiresAuth(it) }
        assertThat(slots).withFailMessage("매핑된 경로 값 자리가 없다 — 이 케이스는 아무것도 재지 않는다").isNotEmpty()

        slots.forEach { (slot, method) ->
            val response = sendToSlot(null, slot, method, rawQuery = null)

            assertUnauthorized(response, slot)
        }
    }

    private fun assertUnauthorized(
        response: HttpResponse<String>,
        slot: ContractValueSlot,
    ) {
        assertThat(response.statusCode())
            .withFailMessage(
                "%s: 토큰 없는 공백 값 자리가 %d 다 — 값 자리 가드가 인증보다 먼저 돌았다(X-A3 위반). " +
                    "토큰 없이 파라미터 형태를 탐색할 수 있다",
                slot.label,
                response.statusCode(),
            ).isEqualTo(UNAUTHORIZED)
        assertThat(bodyOf(response)[DETAIL])
            .withFailMessage("%s: 401 의 detail 이 문자열이 아니다 — 검증 실패 배열이 새어 나왔다", slot.label)
            .isInstanceOf(String::class.java)
    }

    /** 계약 값 자리 하나와, 그것이 실제로 걸리는 매핑된 메서드 하나. */
    private data class LiveSlot(
        val slot: ContractValueSlot,
        val method: String,
    )

    /** `security: []` 로 연 오퍼레이션(예: `oauthStart`)은 인증 자체가 없다. */
    private fun requiresAuth(liveSlot: LiveSlot): Boolean =
        ContractSpec.security(liveSlot.slot.path, liveSlot.method).isNotEmpty()

    /** 계약 × 실제 매핑. 경로 수준 선언은 그 경로의 매핑된 메서드마다 하나씩 펼친다. */
    private fun liveSlots(): List<LiveSlot> {
        val served = ServedOperations.of(handlerMapping, environment)
        return ContractSpec.valueSlots().flatMap { slot ->
            val contractMethods =
                ContractSpec
                    .operations()
                    .filter { it.first == slot.path }
                    .map { it.second }
                    .toSet()
            val candidates = slot.method?.let { setOf(it) } ?: contractMethods
            candidates
                .filter { method -> (slot.path to method) in served && method in contractMethods }
                .map { LiveSlot(slot, it) }
        }
    }

    /** 그 쿼리 값 자리의 계약 선언. 표본 생성기가 선언 타입을 읽는 데 쓴다. */
    private fun queryParameterOf(slot: ContractValueSlot): ContractQueryParameter =
        ContractQueryParameter(
            name = slot.name,
            location = slot.location,
            required = false,
            schema = slot.schema,
        )

    /**
     * 그 값 자리를 겨눈 요청 하나. 경로 값 자리는 공백 조각으로, 쿼리 값 자리는
     * [rawQuery] 를 그대로 실어 보낸다.
     */
    private fun sendToSlot(
        token: String?,
        slot: ContractValueSlot,
        method: String,
        rawQuery: String?,
    ): HttpResponse<String> {
        var path = slot.path
        if (slot.location == PATH_LOCATION) {
            path = path.replace("{${slot.name}}", BLANK_SEGMENT)
        }
        // 쿼리 값 자리를 겨눌 때 같은 경로의 `{conversion_id}` 같은 나머지를 채운다.
        // 더미 UUID 면 형식 오류가 자원 404 보다 먼저다.
        path = PATH_VARIABLE.replace(path) { UNUSED_PATH_UUID }
        require(!path.contains("{")) {
            "${slot.label}: 경로에 채우지 못한 변수가 남았다($path) — 그 자리를 채울 자원 fixture 가 필요하다. " +
                "이 케이스를 건너뛰지 않는다."
        }
        val url = if (rawQuery == null) path else "$path?$rawQuery"
        val request = jsonRequest(url, token)
        val body = requestBodyFor(slot.path, method)
        val publisher =
            if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)
        return send(request.method(method.uppercase(), publisher))
    }

    /** 그 오퍼레이션이 요구하는 최소 유효 본문. 본문을 선언하지 않으면 `null`. */
    private fun requestBodyFor(
        path: String,
        method: String,
    ): String? {
        val schema = ContractSpec.requestBodySchemaName(path, method) ?: return null
        return MINIMAL_BODIES[schema]
            ?: error("$method $path 의 요청 본문 스키마 $schema 의 최소 유효 본문이 이 케이스에 없다 — MINIMAL_BODIES 에 더해라")
    }

    /** 그 파라미터의 선언 타입으로 해석되지 않는 표본들 — 동치류로 덮는다. */
    private fun uninterpretableSamples(parameter: ContractQueryParameter): List<Pair<String, String>> {
        val common = listOf("빈 자리" to "", "공백뿐" to BLANK_SEGMENT)
        return when (declaredKindOf(parameter)) {
            INTEGER_KIND -> {
                common + listOf("정수 문법 아님" to "abc", "표현 범위 초과" to "99999999999999999999")
            }

            UUID_KIND -> {
                common + listOf("UUID 문법 아님" to "abc")
            }

            STRING_KIND -> {
                val outsider = enumOutsider(parameter)
                common + if (outsider == null) emptyList() else listOf("enum 밖" to outsider)
            }

            else -> {
                error("계약이 ${parameter.name} 에 선언한 타입을 이 표본 생성기가 모른다: ${parameter.schema}")
            }
        }
    }

    /** 계약 enum 에 없는 값 하나. 목록이 없으면 이 자리는 공백·빈 값만 잰다. */
    private fun enumOutsider(parameter: ContractQueryParameter): String? {
        val declared = (parameter.schema["enum"] as? List<*>)?.map { it.toString() }?.toSet() ?: return null
        require(declared.isNotEmpty()) { "${parameter.name} 의 enum 이 비었다 — 밖을 고를 분모가 없다" }
        return listOf("pdf", "hwp", "DOCX", "__not_in_enum__").firstOrNull { it !in declared }
    }

    /** 계약 파라미터 스키마의 선언 타입. `anyOf` 는 널이 아닌 갈래를 읽는다. */
    private fun declaredKindOf(parameter: ContractQueryParameter): String {
        val direct =
            parameter.schema["format"]?.toString()
                ?: parameter.schema["type"]?.toString()?.takeIf { it != NULL_TYPE }
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

    private fun assertDeclaredStatus(
        response: HttpResponse<String>,
        path: String,
        method: String,
        parameterName: String,
        label: String,
    ) {
        assertThat(ContractSpec.responseStatuses(path, method))
            .withFailMessage(
                "%s %s 의 %s(%s) 거절 상태 %d 를 계약이 선언하지 않는다 — 계약 밖 상태 코드가 나갔다",
                method,
                path,
                parameterName,
                label,
                response.statusCode(),
            ).contains(response.statusCode().toString())
    }

    /** 거절 본문이 스키마 층 모양(배열)이고 그 자리를 지목하는가. */
    private fun assertValidationArray(
        response: HttpResponse<String>,
        location: String,
        parameterName: String,
    ) {
        val detail = bodyOf(response)[DETAIL]
        assertThat(detail)
            .withFailMessage("%s 의 거절 detail 이 배열이 아니다 — 타입 해석 실패는 스키마 층이다: %s", parameterName, detail)
            .isInstanceOf(List::class.java)
        val items = (detail as List<*>).map { it as Map<*, *> }
        val declaredKeys = ContractSpec.schemaRequired(VALIDATION_ITEM_SCHEMA)
        assertThat(items).isNotEmpty()
        items.forEach { item ->
            assertThat(item.keys.map { it.toString() }.toSet()).isEqualTo(declaredKeys)
        }
        assertThat(items.map { it[LOC_KEY] })
            .withFailMessage("거절 항목이 %s 를 지목하지 않는다: %s", parameterName, items)
            .contains(listOf(location, parameterName))
    }

    private fun newAccount(): String {
        val email = "valueslot${counter++}@example.test"
        val credentials = json.writeValueAsString(mapOf("email" to email, "password" to VALID_PASSWORD))
        send(jsonRequest("/auth/signup", null).POST(HttpRequest.BodyPublishers.ofString(credentials)))
        // 이메일 인증 게이트는 `POST /documents` 앞이다 — 이 파일은 그 게이트를 재지 않으므로
        // 실물 인증 흐름 대신 저장소를 직접 인증 완료로 만든다.
        database.execute("UPDATE users SET email_verified_at = now() WHERE email = '$email'")
        val login = send(jsonRequest("/auth/login", null).POST(HttpRequest.BodyPublishers.ofString(credentials)))
        return bodyOf(login).required("access_token").toString()
    }

    private fun createWorkspace(
        token: String,
        name: String,
    ): String {
        val body = json.writeValueAsString(mapOf("name" to name))
        val response = send(jsonRequest("/workspaces", token).POST(HttpRequest.BodyPublishers.ofString(body)))
        return bodyOf(response).required("id").toString()
    }

    private fun createDocument(
        token: String,
        workspaceId: String? = null,
    ) {
        val payload =
            json.writeValueAsString(
                buildMap {
                    put("text", "안내문 본문입니다")
                    if (workspaceId != null) put("workspace_id", workspaceId)
                },
            )
        send(jsonRequest(DOCUMENTS_PATH, token).POST(HttpRequest.BodyPublishers.ofString(payload)))
    }

    /** 목록을 부른다. [rawQuery] 는 이미 인코딩된 조각을 그대로 싣는다. */
    private fun list(
        token: String,
        rawQuery: String?,
    ): HttpResponse<String> {
        val path = if (rawQuery == null) DOCUMENTS_PATH else "$DOCUMENTS_PATH?$rawQuery"
        return send(jsonRequest(path, token).GET())
    }

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

    private fun bodyOf(response: HttpResponse<String>): Map<*, *> = json.readValue(response.body(), Map::class.java)

    private fun itemsOf(response: HttpResponse<String>): List<*> {
        assertThat(response.statusCode()).isEqualTo(ContractSpec.successStatus(DOCUMENTS_PATH, GET))
        return bodyOf(response)[ITEMS_PROPERTY] as List<*>
    }

    private fun Map<*, *>.required(key: String): Any = this[key] ?: error("응답에 $key 가 없다")

    companion object {
        private const val DOCUMENTS_PATH = "/documents"
        private const val GET = "get"

        /** 오퍼레이션이 요청 본문을 요구할 때 쓰는 최소 유효 본문 — 스키마 이름별. */
        private val MINIMAL_BODIES =
            mapOf(
                "WorkspaceNameRequest" to """{"name":"가"}""",
                "ConversionReviewRequest" to """{"edited_text":"가"}""",
                // 계약 required 셋만 담는다 — `comment` 는 선택이라 최소 본문에 없다.
                "ConversionFeedbackRequest" to
                    """{"publish_intent":"as_is","quality_score":3,"minutes_spent":1}""",
                // 이 표본은 값 자리(경로 `provider`) 해석 여부만 잰다 — 허용 목록·실제
                // google 설정과 무관하다(그 판정은 `SocialLoginServiceTest`·`OAuthContractTest`).
                "OAuthStartRequest" to """{"redirect_uri":"http://localhost:5173/auth/google/callback"}""",
                "OAuthCallbackRequest" to
                    """{"code":"probe-code","state":"probe-state",""" +
                    """"redirect_uri":"http://localhost:5173/auth/google/callback"}""",
            )

        private const val LIST_SCHEMA = "DocumentListResponse"
        private const val VALIDATION_ITEM_SCHEMA = "ValidationErrorItem"

        private const val ITEMS_PROPERTY = "items"
        private const val LIMIT_PROPERTY = "limit"
        private const val DETAIL = "detail"
        private const val LOC_KEY = "loc"

        /** 계약 `ValidationFailed` 항목의 `loc` 첫 칸 둘. */
        private const val QUERY_LOCATION = "query"
        private const val PATH_LOCATION = "path"

        /** OpenAPI 타입·형식 어휘. 표본 생성기가 선언 타입을 가르는 데 쓴다. */
        private const val INTEGER_KIND = "integer"
        private const val UUID_KIND = "uuid"
        private const val STRING_KIND = "string"
        private const val NULL_TYPE = "null"
        private const val UNKNOWN_KIND = "?"

        private val PATH_VARIABLE = Regex("\\{[^}]+\\}")
        private const val UNUSED_PATH_UUID = "00000000-0000-0000-0000-000000000000"

        /** 값 자리는 있고 어떤 타입으로도 해석되지 않는 조각. 인코딩된 공백이다. */
        private const val BLANK_SEGMENT = "%20"
        private const val VALID_PASSWORD = "correct horse battery"

        private const val UNAUTHORIZED = 401

        private val SUCCESS_RANGE = 200..299

        private var counter = 0

        /** 이 테스트만 쓰는 DB. 목록 개수 단언이 다른 테스트의 행에 흔들리지 않게 한다. */
        val database: DatabaseHandle by lazy { PostgresTestSupport.createEmptyDatabase("value_slot") }

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.jdbcUrl }
            registry.add("spring.datasource.username") { database.username }
            registry.add("spring.datasource.password") { database.password }
        }
    }
}
