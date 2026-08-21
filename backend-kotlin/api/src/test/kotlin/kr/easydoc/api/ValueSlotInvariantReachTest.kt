package kr.easydoc.api

import kr.easydoc.api.support.ContractQueryParameter
import kr.easydoc.api.support.ContractSpec
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

/**
 * **값 자리 불변식의 전용 강제자** — 「성공 응답은 요청이 지정한 값을 반영한다. 반영할 것이
 * 없으면 성공하지 못한다.」
 *
 * ## 왜 전용 클래스인가 (R-7)
 *
 * 이 불변식은 처음에 `DocumentListReachTest`·`WorkspaceEndpointReachTest` 안의 **메서드
 * 몇 개**로 살았고, 그 두 클래스가 바닥 목록(`FLOOR_TEST_CLASSES`)에 「이 불변식의 유일한
 * 강제자」로 등재됐다. **핀의 알갱이가 보호 대상의 알갱이보다 굵었다** — 바닥은 클래스
 * **이름**을 지키는데 보호 대상은 **메서드**였다.
 *
 * 실측했다(2026-08-21): 그 메서드들과 전용 보조만 지우고 서식을 정리한 뒤
 * `ktlintCheck detekt build moduleBoundaryCheck parityHarness --continue --rerun-tasks` 가
 * **exit 0 BUILD SUCCESSFUL**, 핀 게이트가 **112 passed** 였다. 클래스도, 선언 개수도,
 * 트리 스캔도, 바닥 목록도 전부 초록인 채 **`TypedValueSlotInterceptor` 를 지워도 아무도
 * 모르는 상태**로 돌아간다.
 *
 * 그래서 불변식을 자기 클래스로 뽑았다. 이제 **클래스 알갱이 == 속성 알갱이**이고, 이미
 * 있는 바닥 기제가 실제로 선언된 것을 지킨다. 클래스 이름이 그 속성의 이름인 것도 이득이다.
 *
 * ## 세 케이스가 한 클래스에 있는 이유
 *
 * 셋이 **한 속성**의 세 관측면이다 — 쿼리 파라미터의 긍정·부정, 그리고 경로 변수의 부정.
 * 속성 하나에 클래스 하나가 R-7 의 요점이므로 가르지 않는다. 한 컨텍스트로 `/documents` 와
 * `/workspaces` 를 모두 부를 수 있어 스프링 컨텍스트도 하나만 늘어난다.
 *
 * ## 관측 지점은 **컨테이너**다
 *
 * 흡수가 인자 해석기·타입 변환기에서 일어나므로 실제 요청이 그 층을 지나야 한다. 슬라이스로
 * 재면 앞단 장치가 만든 응답을 보지 못한다(C4-R1 도달 표).
 *
 * ## 케이스를 계약에서 유도한다
 *
 * 파라미터 목록·선언 타입·경계·응답 스키마를 전부 [ContractSpec] 이 읽는다. 그래서 계약이
 * 파라미터를 더하면 이 불변식이 **자동으로** 그것을 덮고, 표본을 만들 수 없는 타입이면
 * `error()` 로 끊긴다(표본 0건으로 조용히 통과하지 않는다).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["easydoc.auth.jwt-secret=$DOCUMENT_REACH_TEST_SECRET"],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ValueSlotInvariantReachTest {
    @LocalServerPort
    private var port: Int = 0

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

    /**
     * 응답에 그 이름의 필드가 있는 파라미터는 **되돌려주는 값**으로 반영을 잰다.
     *
     * 「어느 파라미터가 메아리를 갖는가」를 코드에 적지 않고 응답 스키마에서 읽는다.
     */
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
        // 부호가 붙은 형태도 **해석되는** 입력이다 — 같은 값으로 반영돼야 한다.
        assertThat(bodyOf(list(token, "${parameter.name}=%2B$sent"))[parameter.name]).isEqualTo(sent)
        // 되돌려준 값이 **실제로 쓰였는지**도 본다 — 메아리만 맞추고 무시하는 구현을 배제한다.
        assertThat((body[ITEMS_PROPERTY] as List<*>).size).isLessThanOrEqualTo(body[LIMIT_PROPERTY] as Int)
    }

    /** 메아리 필드가 없는 파라미터는 **효과**로 잰다. `workspace_id` 의 효과는 「그 작업 공간의 문서만」이다. */
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
    @DisplayName("부정(쿼리) — **값 자리가 있으나 선언 타입으로 해석되지 않는 입력**은 성공 응답을 만들지 못한다")
    fun `해석되지 않는 쿼리 값 자리는 성공하지 못한다`() {
        val token = newAccount()
        createDocument(token)
        val declared = ContractSpec.queryParameters(DOCUMENTS_PATH, GET)
        assertThat(declared)
            .withFailMessage("계약이 이 오퍼레이션에 쿼리 파라미터를 하나도 선언하지 않았다 — 이 케이스는 아무것도 재지 않는다")
            .isNotEmpty()

        val slips = mutableListOf<String>()
        declared.forEach { parameter ->
            uninterpretableSamples(parameter).forEach { (label, encoded) ->
                val response = list(token, "${parameter.name}=$encoded")
                if (response.statusCode() in SUCCESS_RANGE) {
                    slips += "${parameter.name} $label → ${response.statusCode()} ${response.body().take(120)}"
                    return@forEach
                }
                assertDeclaredStatus(response, DOCUMENTS_PATH, GET, parameter.name, label)
                assertValidationArray(response, QUERY_LOCATION, parameter.name)
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
    @DisplayName("부정(경로 변수) — 공백뿐인 경로 조각도 흡수되지 않고, 계약이 선언한 상태로 거절된다")
    fun `해석되지 않는 경로 값 자리는 성공하지 못한다`() {
        // 실측(고치기 전): `%20` 이 UUID 변환에서 널이 되고, 경로 변수는 널일 수 없어
        // `MissingPathVariableException` → **400** 이 나갔다. 계약은 `'400'` 을 어느
        // 오퍼레이션에도 선언하지 않는다 — 계약 밖 상태 코드였다. 쿼리 파라미터 흡수와
        // **같은 뿌리**이므로 같은 불변식이 덮는다.
        val token = newAccount()
        val parameter = ContractSpec.pathParameters(WORKSPACE_ITEM_PATH).single { it.location == PATH_LOCATION }
        val path = WORKSPACE_ITEM_PATH.replace("{${parameter.name}}", BLANK_SEGMENT)

        PATH_VARIABLE_METHODS.forEach { method ->
            val request = jsonRequest(path, token)
            val response =
                if (method == PATCH) {
                    send(request.method(method.uppercase(), HttpRequest.BodyPublishers.ofString(NAME_BODY)))
                } else {
                    send(request.method(method.uppercase(), HttpRequest.BodyPublishers.noBody()))
                }

            assertThat(response.statusCode() in SUCCESS_RANGE)
                .withFailMessage("%s %s 의 공백 경로 조각이 성공 응답을 받았다 — 흡수됐다", method, path)
                .isFalse()
            assertDeclaredStatus(response, WORKSPACE_ITEM_PATH, method, parameter.name, "공백뿐")
            assertValidationArray(response, PATH_LOCATION, parameter.name)
        }
    }

    // ================================================================ 표본

    /**
     * 그 파라미터의 **선언 타입으로 해석되지 않는** 표본들 — 동치류로 덮는다.
     *
     * 열거하는 것은 값이 아니라 **동치류**다: 빈 자리 · 공백뿐 · 그 타입의 문법이 아님 ·
     * (정수면) 표현 범위 초과. 넷이 「값 자리가 있으나 그 타입으로 해석되지 않는다」는 종류를
     * 덮는다 — 자리가 비었거나(앞 둘), 문법이 아니거나(셋째), 문법이지만 담기지 않는다(넷째).
     *
     * **부호가 붙은 형태(`+5`)는 여기 없다.** 해석되는 입력이므로 긍정 케이스가 「반영된다」로
     * 잰다 — 동치류를 값 목록으로 다루면 이 구별이 사라진다.
     *
     * 선언 타입은 계약에서 읽고, 모르는 타입이면 **끊는다**.
     */
    private fun uninterpretableSamples(parameter: ContractQueryParameter): List<Pair<String, String>> {
        val common = listOf("빈 자리" to "", "공백뿐" to BLANK_SEGMENT)
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

    /**
     * 그 파라미터의 경계가 사는 `x-input-limits` 노드 이름. 계약이 `list_` 접두로 둔다.
     *
     * 이름 규칙이 암묵 계약이라는 사실은 개선 백로그 B-18 이다 — 계약이 규칙을 바꾸면
     * 접근자가 `error()` 로 끊긴다(조용하지 않지만 손이 필요하다).
     */
    private fun limitNodeOf(parameterName: String): String = "list_$parameterName"

    // ================================================================ 단언 도구

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

    /** 거절 본문이 스키마 층 모양(**배열**)이고 그 자리를 지목하는가. */
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

    // ================================================================ 요청 조립

    private fun newAccount(): String {
        val email = "valueslot${counter++}@example.test"
        val credentials = json.writeValueAsString(mapOf("email" to email, "password" to VALID_PASSWORD))
        send(jsonRequest("/auth/signup", null).POST(HttpRequest.BodyPublishers.ofString(credentials)))
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

    /**
     * 목록을 부른다. [rawQuery] 는 **이미 인코딩된 조각**을 그대로 싣는다.
     *
     * 다시 인코딩하지 않는 이유: R-6 표본은 빈 값·공백처럼 **인코딩 자체가 재는 대상**이다.
     */
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
        private const val WORKSPACE_ITEM_PATH = "/workspaces/{workspace_id}"
        private const val GET = "get"
        private const val PATCH = "patch"

        /** 경로 변수가 값 자리를 갖는 오퍼레이션들. 계약이 그 경로에 선언한 쓰기 메서드 둘이다. */
        private val PATH_VARIABLE_METHODS = listOf("patch", "delete")

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
        private const val NULL_TYPE = "null"
        private const val UNKNOWN_KIND = "?"

        /** **값 자리는 있고 어떤 타입으로도 해석되지 않는** 조각. 인코딩된 공백이다. */
        private const val BLANK_SEGMENT = "%20"
        private const val NAME_BODY = """{"name":"가"}"""
        private const val VALID_PASSWORD = "correct horse battery"

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
